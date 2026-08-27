# Architecture

Kept current on purpose: when the model, the persistence or the screen structure changes, this
file changes in the same commit.

## Modules

- `:core-model` — pure Kotlin (`java.time`, kotlinx-serialization), no Android. The domain
  model (`Reminder`, `Trigger`, `Action`, `Status`, `AppSettings`), which circles the place
  watch owes a position (`PlaceGate.kt`) and how they are named (`GeofenceIds`), tag
  normalisation, and — as
  milestones land — the trigger JSON codec, next-fire computation, Home grouping, search and
  validation. Fully unit-tested with JUnit 5.
- `:app` — the Android app: Room + DataStore persistence, the Compose UI, the self-updater.

## Domain model

A `Reminder` is text + tags + a list of `Trigger`s + a set of `Action`s + a `Status`.

A reminder rings by **rules**: a list of `TriggerRule`, each an event plus the conditions that
have to hold when it happens. `RuleMatch` says how the rules combine — ANY (either of them
rings it, the default) or ALL — and a rule's own conditions always all have to hold (ANDed). That shape — an OR of ANDs — expresses any combination somebody can
reasonably mean and, unlike a free-form tree, can be read off a phone screen: *"al llegar a
casa, y sólo si es entre las 18:00 y las 22:00"*.

**ALL** is the other honest reading of a list of events: not "true at the same instant" (that is
what conditions are for) but *the last of them to happen*. So the ones that already have are
remembered — `Reminder.firedRules`, by index — the scheduler wakes at each pending moment in
turn to write it down (`nextWake`), and only the moment that completes the set rings
(`outcomeOfFiring`). Dealing with the firing clears the set and starts the round again.

Conditions (`Condition.kt`) are states, asked "were you true at that moment?", which is what
makes them safe to AND with anything. Today there is one, `time_window` (hours + days, crossing
midnight allowed); a place condition is the obvious next one.

Triggers (`core-model/.../Trigger.kt`), with their frozen JSON discriminators:

| Kind (UI tile)        | Stored as                          | `type`         |
|-----------------------|------------------------------------|----------------|
| Date                  | `AtDateTime(at: LocalDateTime)`    | `at_date_time` |
| Date (no hour chosen) | `DayRandom(date)` — a moment drawn from that day's waking hours | `day_random` |
| Countdown             | `Countdown(minutes, startedAt?)`   | `countdown`    |
| Repeats               | `Repeat(startsOn, every, unit, time?, days, monthly?, ends)` | `repeat` |
| Place                 | `Location(lat, lng, radiusM, ENTER/EXIT, label)` | `location` |
| Random                | `Random(timesPer, DAY/WEEK, from, to, days)` | `random` |
| *(read only)* Date only | `OnDate(date)` — rings at the default time (a setting) | `on_date` |
| *(read only)* Time that repeats | `AtTime(time, days)`     | `at_time`      |

The last two are written by no version of the app any more: a date is one tile now, with an
hour in it or with the day's own hours behind it, and a weekly time is the `WEEK` case of
`Repeat`. Both still decode and still mean exactly what they meant, because the discriminators
are frozen and somebody's phone is full of them; editing one through its tile rewrites it as
whichever of the new shapes it turns out to be.

A `Repeat` is a sequence of *blocks* — days, weeks, months or years, `every` apart, counted
from the block `startsOn` falls in — and each block yields the dates it names (`Repeats.kt`).
Blocks and not occurrences, so "every two weeks on Monday and Thursday" is two rings a
fortnight rather than a series that drifts a week every time it rings twice. Nothing ever
skips a block: a monthly "day 31" rings on the 28th in February, a yearly 29 February rings on
the 28th, and the ordinals stop at *fourth* and *last* — which is what makes the count behind
`RepeatEnd.After` exact, and what stops a reminder from silently missing a month. **A year takes
the same `monthly` rule a month does**, its month coming from `startsOn`: "el primer miércoles
de mayo" is a yearly, and every other way of saying it is arithmetic on a date that moves — the
first Wednesday of May is the 6th in 2026 and the 5th in 2027. With nothing set the rule is the
day `startsOn` falls on, which is what plain year arithmetic gave and keeps 29 February right.

**The hours somebody is up** (`Awake.kt`). A trigger with no `time` rings at a moment drawn
from that day's waking window rather than from the twenty-four hours. `AwakeHours` holds two
pairs — weekday and weekend — and `AppSettings.weekendDay`/`weekendTime` and
`weekendEndDay`/`weekendEndTime` say where the weekend span begins and ends, so the two ends of
a day are asked separately: a Friday gets up for work and goes to bed at the weekend, and a
Sunday keeps the lie-in and loses the late night. `DayShape` is that read-only view of the
settings, threaded through `nextFire`/`nextWake`/`nextFireOfRule`, and a change to it re-arms
everything (`RwilcoApplication`) because it moves real armed moments. The draw itself is
`RandomDraw.inDay`, deterministic by (reminder, day), so the screen and the scheduler agree
without storing it. An explicit `time` ignores all of it.

Wall-clock values are stored without a zone; the zone is applied when the next fire is computed.
A countdown stores the **length**, not the moment: `startedAt` is stamped by `startCountdowns`
where a reminder is written (the editor's save, or straight from a preset) and stripped by
`clearCountdowns` where a preset is, so a shape holds "half an hour" rather than one particular
half hour. Null reads as "from now", which is what the editor shows while one is being written, and `countdownOf` is what keeps a length nobody changed from becoming a *new* timer at the next save.
Reminders written before this hold their countdown as the `AtDateTime` it once produced, which
is what it always was.

`nextFireOfRule` walks a rule's candidate moments until its conditions hold, stopping after 64
so a rule that can never be satisfied ("a las 09:00, y sólo si es entre las 18:00 y las 22:00")
answers *never* instead of looping.

`nextFire(reminder, now, zone, defaultTime)` (`NextFire.kt`) picks, under ANY, the earliest
definite moment (`Scheduled`), else the earliest random draw (`Sometime`, shown as a window),
else a place (`WhenAt`); under ALL it answers with the *last* of the pending ones, or with the
place when one is among them, because then there is no date to give. `nextWake` is the other
question — what the alarm is set for — and is always the earliest pending moment.
**The hero is picked by `nextWake`, not by `nextFire`** (`heroOf`): the soonest thing that *can*
happen rather than the soonest thing with a date on it. On a phone whose reminders are mostly
places almost nothing has a date, and a single appointment five months out was winning the top
of the screen — counting down 138 days — while five others were going to ring that evening. A
place with hours on it has a floor (it cannot ring before its window opens, which is exactly
what the alarm is set for) and says so: *como pronto*. A bare place has no floor and stays in
"cuando ocurra"; a random draw is never lifted out, because a random reminder that announces
its time is not random; and nothing past `HERO_HORIZON` (a week) is lifted at all.
`groupForHome` (`HomeSections.kt`) lifts that hero out and
files the rest under Overdue / Today / Tomorrow / This week (rolling 7 days) / Later / Whenever /
Paused. Random moments come from `RandomDraw.kt`: SplitMix64 seeded by (reminder id, period
index), pinned by golden values in its test.

Dealing with a firing (`statusAfterDismissal`) finishes a reminder unless its `Recurrence` says
otherwise — `None` by default, because "hecho" means finished and a place can always come round
again. `Recurrence.kt` is the whole vocabulary: `ByTrigger` hands the question back to the
triggers (a repeating time, a random window), while `After(amount, unit, from)` and
`MonthlyWeekday(ordinal, day)` work out their own moments. Hours are exact; days, weeks, months
and years land on `AppSettings.dayStart` and never before the span is up.

**The anchor is the question the app used to answer for you** (`RecurrenceFrom`). "Cada semana"
is half a sentence — the other half is which moment the week is counted from — and there are
three answers, asked once in "Vuelve" and nowhere else: the **calendar** (`ByTrigger`: a
`Trigger.Repeat` or a random window works out its own dates and never drifts), the **ringing**
(`RANG`, `lastFiredAt`: answer it late and the rhythm holds, which is what anybody who set
08:00 / 14:00 / 20:00 meant), or **dealing with it** (`DEALT`, `lastDealtAt`: the clock starts
when you do, and six hours between doses is six hours between doses). `DEALT` is the default
and what every install before the field wrote, so old JSON reads as itself. `recurrenceAnchor`
picks the instant and falls back to `lastDealtAt` when there is no firing to count from — a
reminder swiped done on Home never rang, and "cada 6 h desde que suena" must still come back.
The trigger and the recurrence are *not* merged and must not be: a `Trigger.Repeat` is a rule,
so it can be ANDed with a place ("el día 26, y cuando llegue a casa"), it can name two days in
one block, and it can end — none of which a span from an event can say.

The triggers say when it rings the FIRST time and the recurrence when it comes back, so
`recurrenceMoment` takes over once it has been dealt with once — or straight away when there
are no triggers at all, which is what makes "cada 6 h" a whole reminder on its own.
A recurrence's moment is **spent once it has rung**, the same way a rule's is: under `DEALT` the
anchor only moves when somebody deals with the firing, so a reminder that rang and was ignored
would otherwise be handed the same past moment for ever — armed for ever, and an alarm already
in the past arrives at once. Spent, it answers *nothing*, which Home reads as overdue. Under
`RANG` the anchor moves with the firing instead, so an unanswered one comes back on its rhythm
rather than waiting to be acknowledged — which is the whole difference between the two, and the
thing to know before choosing it.
`RecurrencePreset`s (in the settings, four built in unnamed) put the usual answers on buttons.
Room v4 added the boolean this replaced; v5 turns it into a shape (`by_trigger` for whatever
repeated) and rebuilds the table to drop it.

`Validation.kt` decides what blocks a save, which is only the words and a trigger that is
nonsense in itself: a reminder needs **neither a trigger nor an action**. One with neither is a
note kept under its tags, and Home files it under "kept, not timed" rather than calling it
overdue. `Snooze` offers ten minutes, two hours, tomorrow at the same time, the weekend (a
setting: Friday at 20:30 by default) and next week — all but the first two keeping the
wall-clock time rather than adding hours.

`Search.kt` answers the magnifier: one query over the open reminders and the tags they use,
returning `SearchHit.OfReminder`/`OfTag` so the screen can say which is which. Matching is
folded (case and accents dropped) and banded — whole, prefix, word start, anywhere, then letters
in order — because the cost of being forgiving is nil on a list this size, and the cost of being
strict is asking somebody to remember how they spelled it.

## Persistence

- Room (`app/.../data/`): one table, `reminder(id, text, tags, triggers, ruleMatch, actions,
  status, createdAt, updatedAt, doneAt, …, armedFor, armedRule, firedRules, repeats)`; tags/triggers/actions are JSON text columns written by
  `ReminderCodec`, read leniently (unknown trigger kinds and actions are dropped, never fatal).
  `RwilcoDatabase.VERSION` + `MIGRATIONS` are guarded by `MigrationChainTest` (JVM) and
  `DatabaseMigrationTest` (device). Schemas are exported to `app/schemas`.
- `ReminderRepository`: reactive `open`/`done` flows for the screens, suspend writes.
- `SettingsStore`: Preferences DataStore with one JSON blob (`AppSettings`: theme, default time
  for date-only reminders, the trigger kind offered first, when "the weekend" starts, haptics,
  last-seen version for What's New, the saved places). Additive changes need no migration.
  `PlaceWatchStore` is a second DataStore for the place watch's memory (last fix, which places
  it is inside, its still streak, the next look) — its own file because it is written on every
  check.
- `RwilcoApplication` is the dependency container (manual DI); ViewModels get it through a
  `Factory`.
- **A save re-arms by hand.** The editor writes a row with no armed moment (editing re-decides
  when a reminder rings), and the collector that re-arms wakes only on `schedulingKey`, which
  leaves out the words, the tags and the actions — rightly, since they change nothing about
  when it rings. So editing only the text left an alarm still set and a row saying nothing was
  armed, and `ReminderFiring` drops a firing it has no armed moment for: the reminder went
  quiet until some other re-arm came round. `EditorViewModel` calls the scheduler itself now,
  and `SchedulingKeyBlindSpotTest` holds the reason.

## UI

- Single activity, `navigation-compose` type-safe routes (`Routes.kt`): Home, Editor(id?),
  Done, Settings. Sheets, the place picker and the alert preview are ViewModel state.
- **Settings is an index, not a scroll** (`SettingsScreen.kt`, `SettingsGroup.kt`). Thirty-odd
  controls in one column is past every published ceiling for a settings screen, so they fold
  into ten rows, each carrying its own current value — the sound's name, "08:00–23:30", "3
  lugares" — and each opening where it stands. The rows are independent (closing one because
  another opened moves a header out from under the thumb that tapped it), all start closed, and
  a caret means "opens here" where an arrow means "goes somewhere else". Grouping is by
  meaning rather than by what happened to be adjacent: `NEW` is what a blank reminder starts
  as, `DAY` is the shape of the week, `PLACES` folds the permission and the saved places
  together because they were two sections saying the same thing.
- **A fold may never hide a phone that will not ring.** `AlertReadiness` (ten grants and
  blocks) and `PlaceReadiness` are held outside their cards so a closed row can say "3 cosas
  por arreglar" in the error colour with the badge to match, and the groups in trouble open
  themselves once on arrival. It is the only automatic thing on the screen, and the reason is
  that every one of those states fails silently.
- Theme (`ui/theme/`): hand-authored dark/light schemes (amber `primary` = "what fires next"),
  `RwilcoTypography` on three bundled variable fonts (Bricolage Grotesque display, Manrope body,
  JetBrains Mono for times/dates), `RwilcoShapes`, tokens (`Spacing`, `Motion`, `Sizes`) and
  `Haptics` behind one setting. Trigger families (time / place / chance) have their own colours
  in `FamilyVisuals.kt` — a `color`, a `tint` for keycaps, a `wash`/`edge` for a trigger's own
  row in the editor, and `onColor` for text on a solid fill. **A selected neutral control is
  inverted** (`onSurface` fill, `surface` ink: tags, presets, action tiles, segments, AM/PM),
  the same swap as the primary button, because three greys never read as "on"; a selected day
  is a solid disc of the time family. Plain `MaterialTheme`: material3 1.4.0 keeps the
  expressive theme internal.
- A card's one control is `HoldButton` (`ui/components/`): a 44dp disc with a control's own
  line and the verb small underneath ("Pausar"/"Reanudar", never a bare glyph, which reads as
  the state rather than the action), in the card's top row rather than among the read-only
  action glyphs. It fires only after a 700ms hold, and what reports on the hold is
  `HoldOverlay` — the whole screen dimmed behind one ring filling in the middle of it, which
  is the one place no thumb is ever over. The overlay lives at the root of `RwilcoApp` and is
  reached through `LocalHoldOverlay`, because a control in the corner of a card cannot dim the
  rest of the screen from there; it only draws, so the touch it reports on still reaches the
  button underneath. A screen reader gets a plain click action instead: a double tap is already
  deliberate. `HoldButtonTest` (device) drives the gesture against a hand-driven clock.
- Home's swipes do not act on the swipe alone: opening the card past 35% starts a 500ms fill —
  the glyph fills like a glass of water — and only then does it take, so a card cannot be dealt
  with on the way past during a scroll. Letting go or sliding back empties it and nothing has
  happened, and a gesture that outlives the app being on screen is called off. The dismiss box
  is never allowed to settle at its dismissed end: the row is leaving the list anyway, and a box
  left resting there outlives the row (the list reuses it by key), which once handed a reminder
  back from "undo" frozen halfway across the screen. `SwipeableCardTest` drives the gesture
  against a hand-driven clock; `HomeSwipeTest` walks swipe → undo → swipe on the real screen.
- **Each rule of a set carries a mark** (`RuleStanding`, pure): under ALL, ticked off or still
  to happen (`firedRules`); under TOGETHER, true at this moment or not — a window against the
  clock, a place against the watch's own memory of which circles the phone is inside, and a
  question mark when nothing has looked yet. **The mark answers two questions and keeps them
  apart**: the colour is the rule (green and filled when it is met), the shape is the battery
  (a pause instead of a dot when the watch is spending nothing on that circle,
  `TriggerRowUi.watched` ← `Gated.opensAt`). Which is what makes a green pause mean something —
  a circle whose gate is shut still knows where the phone is, because it is judged for free on
  the positions the other circles pay for; costing nothing and holding are not the same news. It changes back when the rule stops holding,
  because that is the whole difference between the two words, and it was invisible: a card said
  "todos" and nothing about what it was still waiting for. A moment under TOGETHER gets no mark
  — it is not a state, it is what rings when the states around it hold — and neither does ANY.
  **A resting set gets none either** (`restUntil`): dealt with and coming back on a span, its
  rules are not being asked at all, and what it waits for is the rest, which the recurrence row
  already says. The place marks would be worse than merely idle — the watch keeps a resting
  circle's last judgement on purpose (`Watching.remembered`), so a mark there stated last
  night's memory as this minute's fact.
- A card shows one row per rule, and — when the recurrence works out its own moments
  (`After`, `MonthlyWeekday`) — a row for that too, last, because that is the order the two
  answer in. It is the only way a reminder whose whole arrangement is "cada 6 h" says anything
  about when it rings: it carries no trigger at all, so without it the card was blank. Its
  second line says the part people get wrong, that the clock starts at the "hecho" and not at
  the ring. `ByTrigger` gets no row: the repeating trigger above it already IS that answer.
- Home: `HomeViewModel` combines the open reminders, settings, the tag filter and a minute pulse
  into `HomeUiState` (`buildHomeState`, pure and tested). The hero card's countdown ticks in its
  own composable (`rememberNow`) so nothing else recomposes. The magnifier has a flow of its own
  (`buildSearchState`, also pure): a keystroke must not put Home through grouping and next-fire
  again. Results replace the list while it is open; a reminder opens, a tag becomes the filter.
- The chip row is a `TagFilter` (`core-model/TagFilter.kt`), not a string: `Named` for a tag
  somebody typed, and two the app keeps for itself — `Untagged` and `Paused`. Those two are not
  tags (never stored on a reminder, never suggested, never edited) and they appear **only while
  they have something in them**, at the end of the row, because "sin etiqueta" is a job to do
  rather than a filing category and a row that always ends in two chips nobody can act on is a
  row people stop reading at the third one.
  Every real tag carries a colour worked out from its own name (`ui/theme/TagColors.kt`):
  nothing stored, so the same word is the same colour on every screen and after a reinstall.
  It is a **palette of sixteen hues in two shades**, not a continuum — two tags either get the
  same colour or a plainly different one, never one that is *almost* the same, which is the
  reading that helps nobody. The hue circle has four holes cut in it, the amber and the three
  family hues, so a tag can never read as a place or as the next thing due. The app's own three
  chips stay neutral: they are not somebody's word for something.
- Presets (`core-model/Preset.kt`, kept in `AppSettings.presets`): a reminder somebody makes
  often, by name — the words become the name, and the tags, rules and actions come with it.
  Nothing about a preset waits to ring, which is why it lives in the settings rather than the
  reminder table. Each gets a colour (`nextPresetColor` shares the eight out evenly;
  `ui/theme/PresetVisuals.kt` says what they are) because a preset is found by colour before it
  is read — the app's third and last colour job, and the only one that means nothing in itself.
  `presetsByPopularity` puts the ones actually used first. A preset's `name` labels the shape;
  its `text` is the optional wording a reminder made from it starts with — empty means the
  editor opens with the cursor in the words and the keyboard up (the one place in the app where
  it opens by itself, because a preset has already answered everything else), and set means the
  reminder arrives written. Home's "New" asks blank-or-preset
  (`NewReminderChooser`) only once a preset exists; picking one opens the editor pre-filled
  (`Routes.Editor(fromPresetId=…)`) rather than writing the reminder outright, because a preset
  can hold a date that has since passed and the form is where that gets seen.
- Editor: `EditorUiState` + pure reducers (`EditorState.kt`, tested). A save replaces the whole
  row and deliberately drops the snooze and the armed moment — editing re-decides when a
  reminder rings — but carries `lastDealtAt` and `lastFiredAt`. The first is the anchor a
  recurrence counts from; the second is what makes a moment spent, and for an anchored
  recurrence it is the only thing that does, so without it a save hands back a moment already
  gone — on Home as "lo siguiente" in the past, and armed for an alarm that arrives at once. A toggle turns the form
  into a preset editor (`asPreset`); the same four cards, saved to the settings instead of the
  database. The form is four cards
  (`EditorSection`), each with an icon badge and its name — the words, the tags, when, what
  happens — because four headings down one flat column read as more text. Interactive edges use
  the `Strokes` tokens (a control's line is thicker and brighter than a card's) so the screen
  says what can be pressed. With more than one trigger a segmented control chooses between
  "cualquiera" and "todos". Text, tags and
  the "when" itself are offered before they are asked for — `suggestedTexts`/`suggestedTags`/
  `suggestedTriggers` rank what has been used before by how often and how recently (a 30-day
  half-life), and nothing is auto-focused, because a keyboard that opens by itself hides the list
  that would have saved the typing. A trigger is offered by its *shape*, never its instant: a
  length comes back as a length, an hour comes back re-hung on today or tomorrow, a place comes
  back whole, and a bare date has nothing to reuse. Settings can also let `triggerKindsByUse`
  sort the six tiles, which is a favourite nobody has to keep choosing. Each row shows
  `VISIBLE_SUGGESTIONS` of them and puts the rest behind `MoreChip` → `PickSheet` (a searchable
  list), because a row that grows with every reminder ever written stops being a shortcut.
  Holding one of those chips (the shared `Modifier.holdable`, the same 700ms and the same overlay
  as `HoldButton` — a watcher that consumes nothing, so the chip keeps its own click and only
  stands it down when a hold has just completed) opens `CuratePanel` to mend the list: the pure functions in
  `core-model/Curation.kt` rename a tag or a phrase across the reminders that carry it —
  returning only the rows that changed, and leaving `updatedAt` alone so a rename is not read as
  a use — while dropping a phrase only adds it to `AppSettings.hiddenTexts`, because the
  reminders that used it are somebody's history rather than a list to tidy. While a draft
  has no trigger, "when" offers the three answers people give most (in half an hour, tonight,
  tomorrow morning) as one-tap chips that append a rule without a sheet. `TriggerKindSheet` puts the kind
  chosen in Settings (`AppSettings.defaultTriggerKind`) first and marks it; the other five keep
  their order behind it. One configurator sheet per trigger kind under `editor/sheets/`, plus `ConditionSheet` for the
  "y sólo si" fences; the countdown sheet produces an `AtDateTime`; the place
  sheet offers the places kept by name in Settings (`AppSettings.savedPlaces`, managed by
  `SavedPlacesCard` through the same sheet without the arriving/leaving choice) as one-tap
  chips, searches addresses through the platform `Geocoder` (`PlaceSearch.kt`), and asks every
  enabled provider at once for a fix (`CurrentLocation.kt`: fine *or* coarse is enough, the
  freshest last-known answers instantly, and nothing is refused because GPS alone had nothing
  to say indoors) and shows an
  osmdroid map (`OsmMap.kt`: pin by long-press, by search result or from one `LocationManager`
  fix, a crosshair button to centre on where you are, radius circle, inverted tiles on the dark
  scheme, tile cache in `cacheDir`). The alert preview is `AlertScreen`,
  the same composable phase 2 will host in a full-screen-intent activity.

## Firing

- `ReminderScheduler` keeps one `setAlarmClock` armed per reminder — the only kind of alarm Doze
  never defers and the rate limiter never holds back — and writes the armed moment back to the
  row. That, next to `lastFiredAt`, is what makes a firing the phone slept through detectable:
  an armed moment in the past with no ring to match it. What a change has to touch before the
  whole list is worked out again is `schedulingKey` — the rules, the match, what is ticked off,
  the snooze, the recurrence and the moment it counts from — and deliberately not what the
  scheduler itself writes back, or every re-arm would come round as a change and arm everything
  again. `lastDealtAt` is in it for the undo: taking a "hecho" back puts the whole row as it
  was, and on a reminder that stayed ACTIVE either side of it nothing else in the key moves.
  Under ALL only the earliest pending moment is armed, so a phone off across two of them wakes
  owing both and only one is detectably missed; `owedUnderAll` (pure) lists the one-shot
  moments after the missed one that have since passed, and `rearmAndCatchUp` fires them in
  turn, so the set completes late rather than never.
- `ReminderFiring` is the single place that decides what a firing, a "Hecho" and a snooze do, so
  the alarm, the notification buttons, the alert screen **and Home's swipe** cannot drift apart.
  Home's used to file the reminder as DONE itself, which is right for most of them and wrong for
  every one asked to come back: a "cada 6 h" was finished by the swipe instead of starting its
  next round, and the anchor its recurrence counts from was never written down. Under ALL it
  writes the moment down and returns; only the last one goes on to ring. Nothing rings for a
  moment that is not armed: a place happens when it happens, but everything else is checked
  against the row's armed moment, so a stray delivery — a stale alarm, the same broadcast twice
  — is dropped instead of ringing a timer nobody has got round to. A ring is recorded against
  the moment it rang *for* (`momentRungFor`, pure and JVM-tested), not the millisecond the alarm
  arrived, and `nextFire`/`nextWake`
  only look for moments after it (to the millisecond, which is the grain everything is stored
  at). That is what makes a moment spent: an alarm may arrive a breath early, and without it the
  same moment would still be in the future when the scheduler next looks, and ring twice. A
  place is the exception and must not reach for the armed moment at all: it has none of its own,
  and under ANY the armed one belongs to whatever else the reminder is waiting for — recording
  an arrival against it would mark tomorrow's appointment spent before it ever came. A catch-up
  (`late`) must not either: by the time it rings, the re-arm that found the missed moment has
  already written the *next* one into the row, and taking it would spend tomorrow before it came
  — which is how a daily reminder once skipped a day after every night the phone was off. A
  catch-up is recorded as rung now, and stands down if the row already shows a ring at or after
  the moment it is about (the alarm for a past moment arrives at once, racing it). Every entry
  into `ReminderFiring` — a firing, a repeat, "hecho", a snooze — takes one mutex, so two doors
  opening on the same second read each other's writes instead of both ringing.
  **An anchored recurrence on a reminder with rules is a rest, not a ring.** Dealt with, the
  rules say nothing until the span is up (`Reminder.restUntil`, counted from `lastDealtAt`):
  nothing is armed, no place is watched, a crossing is written down but does not ring. From
  then they speak again — a place is watched again and rings on a *fresh* arrival, a clock
  finds its first moment past the rest. Only when no rule has anything left to say (a date
  that has been, a countdown that ran out) does the recurrence's own moment ring, which is what
  "a las ocho, y luego cada seis horas" means; and rung and ignored, that moment is spent like
  any other (`recurrenceMoment`). With no rules at all the recurrence is the whole arrangement
  and its moment is always the ring.
- `AlertPresenter` decides *where* a firing shows itself: an app open in front of somebody gets
  the banner, and the home screen, a dark screen or the lock screen get the whole screen. The
  noise follows that decision, not the tile: a full-screen alert rings for itself, but one
  shown as a banner has no screen to ring and its notification carries the sound and the buzz
  (`AlertNotifications.post` picks the channel from the presentation it was handed). And any
  action at all implies a notification (`firingPlan`): a sound or a buzz is made by a channel,
  so "sonido" with "notificación" unticked is still a notification rather than nothing. That
  needs two permissions granted by hand — usage access (to tell an app from the launcher) and
  "display over other apps" (Android forbids a background activity start without it). Missing
  either falls back to the banner, which is what the system does on its own, and Settings says
  so. The decision itself is a pure function with JVM tests. "Hecho" finishes
  a one-shot and leaves anything that can come round again.
- `AlertNotifications` has one channel per sound/vibration combination, because a channel's
  sound is fixed the moment it is created — which is also how the vibration setting reaches a
  notification: the chosen rhythm is part of the channel id, so changing it means a different
  channel rather than an edit Android would ignore. Only the rhythm; a channel's pattern is
  durations and nothing else, with no way to say how hard, so a gentle notification and a strong
  one are the same notification. A full-screen alert's notification stays silent: the
  screen does its own ring (`AlertRinger`) and gives up after two minutes — and so does
  its hold on the screen (`FLAG_KEEP_SCREEN_ON` is cleared with the noise). Nobody answered in
  two minutes because nobody is there, and a display lit at full brightness until somebody comes
  home costs more battery than everything else in this app together. The alert is still on the
  screen when they do, and the notification is still in the shade either way.
- **Where the sound comes out, and what it does to the rest** (`AlertAudio`): the alarm stream
  always, but routed to the headphones when a pair is connected (`setPreferredDevice`, looked up
  at the moment of playing so a pair unplugged a minute ago cannot swallow a reminder;
  `AppSettings.alertToHeadphones`, on, and off is the honest setting for earbuds that live in a
  drawer). And the focus request is `TRANSIENT_MAY_DUCK`, not `EXCLUSIVE`: a ten-second chime
  has no business ending somebody's podcast, so the music drops a few decibels underneath and
  comes back by itself. The previews in Settings use the same two, or they are previews of
  something else — one for the tone, one for the continuous ring a full-screen alert makes
  (capped at half a minute), one for the insistent round with its waits shortened; each button
  is its own "parar" while it plays, and the alarm volume is a slider right there, since
  choosing a sound without it is guesswork.
- **The sound is a choice, in two parts.** *Which* one — `AppSettings.alertSound`: one of four
  chimes the app brings with it, the phone's own alarm tone, or a file somebody picked. The
  chimes are synthesised rather than sourced (`scripts/chimes.py`, run it and the same files
  come out), so they are the app's own and licensed by nobody, and they are built the way a car
  builds one — the door-left-open chime rather than the buzzer: a low note (330–800 Hz, *below*
  the band that makes a tone shrill) struck and left to decay like a bar hit with a mallet,
  carrying its own octave underneath for warmth and almost nothing above, and then silence.
  The first four sat at 660–1568 Hz with flat-topped envelopes, which is what "beep" is made of. A car does not shout, and a phone that only wants you to
  look at it should not either. A custom file is kept only once `takePersistableUriPermission`
  has succeeded — a picker Uri is readable while the app is in the foreground and an alarm three
  days out is not, so without that it would work while being chosen and be silent when it
  mattered — and anything that will not resolve at play time falls back to the phone's alarm
  tone, because the wrong sound beats no sound.
  And *how insistently* — `Action.SOUND` plays once, `Action.SOUND_UNTIL_ANSWERED` comes back
  every `soundGapMinutes` until the reminder is dealt with, `soundPlays` times in all (five and
  five by default). The two are one choice in the editor, since asking for a sound once and also
  until answered is asking for two contradictory things. The round is a chain of one-shot alarms
  (`SoundRepeater`), each carrying how many plays have gone out and which ring they belong to:
  no column, no migration, nothing written down, because a chain that lives in its own alarms
  needs no memory and a cancelled one leaves none behind. Each link re-posts the notification
  rather than playing a fresh sound — it re-alerts on its own channel, which *is* the sound, and
  it puts the card back in front of somebody who scrolled past — and never takes the screen a
  second time. Everything that ends a round is asked at fire time rather than remembered: gone,
  paused, finished, snoozed, or dealt with since it rang.
- **The vibration is built whole and finite** (`core-model/Vibration.kt`): strength and rhythm
  from `AppSettings.vibration`, and a waveform long enough to last exactly its minute
  (`VibrationLimits.LONGEST`) with **no repeat count**. The obvious way to buzz until somebody
  answers is a repeating waveform stopped by hand, and it is what this app did — but "later" is
  then a promise the app has to keep through a killed process, a crash, a `stop()` that never
  ran, and what is on the other side of a broken promise is a motor buzzing until the battery is
  flat. Handing the whole minute to the system removes the promise. A minute is also as long as
  a coil driving a weight should be asked to work in a stretch, and an alarm that has buzzed for
  one has made its point. Even the unbroken rhythm is a train of two-second stretches with a
  150 ms gap between them rather than one continuous minute: unbroken full-amplitude drive is
  the highest-power state the motor has, the gap is about the length of an LRA's own spin-down
  so it reads as texture rather than as a pause, and it hands back a sixteenth of the minute
  with the coil unpowered. Cheap insurance; the honest protection is still the minute itself.
  Amplitude needs a motor that can do it (`hasAmplitudeControl`); where it cannot, gentle and
  strong are the same vibration and Settings says so.
- `AlertActivity` shows over the lock screen and turns it on; it is its own task so dismissing
  an alarm at three in the morning does not drop anybody into the app's back stack. "Hecho" is
  the bottom-most control on it, because the bottom of the screen is where a half-awake thumb
  lands and it belongs to the one answer the screen is asking for; "Ver" (which opens the form)
  sits above it, having once sat below.
  **Two reminders within moments of each other both reach the screen.** It is `singleTask`
  and no start clears its task any more (every start once carried `CLEAR_TASK`, so the second
  alert destroyed the first — whose notification, posted on the silent full-screen channel,
  then read as "never rang"); the second arrives through `onNewIntent` and joins. What the
  screen does with it is `AppSettings.alertStacking`: one after the other (the next appears the
  instant the first is answered, with "N más esperando" over the words) or as strips
  (`AlertStackScreen`, each with its own "Hecho"). Every reminder on the screen is watched in
  the database and leaves when it stops being `awaitingAnswer` (pure, `Firing.kt`) — so
  "Hecho" from the shade takes it down here too — and the ring's two-minute budget starts
  over for each arrival.
- `GeofenceManager` registers the place rules with Play Services, wholesale, and re-registers on
  boot and from `RearmWorker` (a reboot or a Play Services update drops them all). That is the
  net: free, always on, the system's own word on where the phone is. Settings says where that
  grant stands, whether or not a place reminder exists yet (`LocationPermissionCard`), because
  a refusal discovered later is a reminder that never arrives. A place is judged against its
  conditions when it happens, not when it is armed.
- **Three readings of a list of rules.** `RuleMatch.ANY` ORs them; `ALL` accumulates (every one
  has to have happened, in any order, and the *last* of them rings — see `Reminder.firedRules`)
  **except that a place under ALL is a state and can come undone**: "cuando salga de la oficina,
  y de 18:30 a 20:00" is met by being out of the office, and somebody who goes back for their
  keys is not, so the crossing opposite the one the rule waits for takes its tick off again
  (`Crossing.TAKES_BACK`, `ReminderFiring.untick`). Nothing else on a list of rules can — a date
  that has passed has passed;
  `TOGETHER` is the conjunction, every rule true at the same moment, ringing the instant the
  last one becomes true. That last one needs each trigger read as a *state* (`Trigger.asState`):
  a place is being inside its circle, a `Trigger.Interval` ("de 17 a 19") is being in its
  window, and everything else is a **moment**, true at an instant and false either side. So it
  is implemented by folding: `Reminder.togetherRule(i)` hands back rule *i* with every other
  rule's state as one of its conditions, and firing, scheduling (`nextFire`/`nextWake`, which
  once armed the bare rule's moment and rang "a las nueve, y de ocho a diez los lunes" every
  day), Home's rows, the place watch and `warnings` all then run the ordinary conditioned-rule
  machinery. Under TOGETHER the earliest folded moment is the ring, as under ANY; "the last of
  the pending" is ALL's reading alone. Two moments asked to coincide never do, which the fold
  reports as null and `momentsCannotCoincide` says out loud — and "en casa Y a las nueve" is
  *not* that trap, because a place is a state: it means being at home at nine, and it is the
  clock's rule that rings it.
- **Conditions are the other way into the same conjunction.** They always were, and there are
  two kinds: `Condition.TimeWindow` and
  `Condition.AtPlace` ("y sólo si estoy en casa"). A place condition is the one thing nothing
  can answer in advance, so `nextFireOfRule` leaves it out and arms the alarm anyway
  (`knownInAdvance`), and `ReminderFiring` asks it for real when the alarm goes off — from the
  place watch's last fix, and only while that fix still speaks for now; past the speed memory it
  is no fix at all, and no fix means the condition holds. `warnings()` says what can be said
  before somebody waits a week to find out: a rule whose moments never meet its own hours
  (`NeverFires`, which is just `nextFireOfRule` giving up), circles that cannot both be true
  (`PlacesConflict`), either of those under ALL taking the whole reminder down with it
  (`NeverCompletes`), and a bare place rule beside a bare clock rule under ALL, which is legal
  and usually meant as one conditioned rule (`BetterAsCondition`). None of it blocks saving.
- `PlaceWatcher` is the second opinion, and the one that decides its own cost. However many
  places are being waited on there is **one** alarm, **one** fix and **one** decision: no rule
  polls on its own account. The cadence is the most impatient circle's (`planNextCheck` takes
  the soonest wait any one of them asks for) and the answer is everybody's (`stepPlaceWatch`
  judges them all against that one fix), so a place across town that would settle for half an
  hour is judged every five minutes anyway, at the doorstep's expense and nobody else's. What it watches is every circle still worth watching, and three
  things take circles off that list. A rule's trigger counts only while the rule is still
  pending (`pendingRules` — under ALL a place already ticked off has nothing left to report, and
  both the watch and the hundred-geofence allowance stop spending on it), and not while it
  rests (`restUntil`): a resting circle is left alone but **keeps its memory** of which side of
  the line the phone was on (`Watching.remembered`, merged back on every write), because that
  memory is what the next crossing is judged by. **A place that has rung is owed a leaving
  before it rings again**: the watch's own events already need the memory to say "outside"
  first, and the geofence's word is held to the same standard once `lastFiredAt` is set
  (`crossingIsNews(strict = true)`: a crossing the app has not seen the other side of is not
  news, however stale the last fix). To feed that memory the geofences register *both*
  crossings for every place; `PlaceWatcher.accept` writes either down and answers with what the
  circle says a crossing is worth (`Crossing`: rings it, takes its tick back, or nothing at
  all), and only on a circle that is live — not resting, not outside its hours. The first ring
  keeps the benefit of the doubt.
  The circles named by
  `Condition.AtPlace` are watched for their state alone (`Crossing.NOTHING`, so
  `stepPlaceWatch` never turns one into a firing) and never geofenced, because a geofence
  reports a crossing and a condition has none.
  **The whole of the gating below is pure** (`core-model`, `PlaceGate.kt`:
  `Reminder.watchedCircles`), because the same answer serves two callers — the watch, deciding
  what to spend, and a card, saying whether it is spending — and because none of it could be
  asked anything but a phone while it lived in the watch.
  And **a circle is left alone entirely while the
  hours the rest of its set needs cannot hold** (`List<Condition.TimeWindow>.openFrom`): "en la
  oficina, entre las cinco y las siete" cannot ring at three in the morning however far anybody
  walks, so the watch spends nothing on it and sleeps instead of cancelling. It wakes
  `PlaceWatchPolicy.WINDOW_LEAD` (two hours) *before* the window, not at it: a watch that began
  at the stroke of five would spend its first fix learning which side of the line the phone was
  on, so somebody who walked in at one minute past would have arrived nowhere — and a circle
  judged for the first time at the moment it can ring has had no run-up for its cadence to find
  the phone. Two hours is the run-up; a window once a day still costs two hours of looking
  rather than twenty-four, which is the whole point of gating.
  **A look that finds nothing worth a fix forgets what it can no longer vouch for**: `inside`
  is filtered down to the resting circles (`Watching.remembered`) — listeners included, for the
  reason above — because an answer left standing from before the window closed would have a card
  say "no se cumple ahora mismo" about a circle nothing has looked at since last night. `sync()` always did this; `look()`
  did not, and the mark a person saw then depended on whether the process happened to restart.
  **Under "todos" a place is never gated by its siblings, only slowed.** It was, once, and that
  was wrong in the way that costs a reminder rather than a battery: a place under ALL is a
  *state*, the crossing that meets it happens when it happens, and a circle switched off until
  a moment six weeks out lost every crossing in between — the set then waiting for a leaving
  that had already happened and would not happen again. So the circle stays on and pays for the
  waiting instead: `WatchedPlace.floor` holds it to `PlaceWatchPolicy.MAX_WAIT` (an hourly wifi
  position, never the GPS) while the soonest sibling moment is more than a run-up away, and
  hands it back to the ordinary distance arithmetic once the set is within `WINDOW_LEAD` of
  being able to ring at all. A floor is that circle's own: a doorstep three streets away still
  sets the cadence for everybody, and this one is judged on the way past for nothing. A place
  already ticked off is watched too, wearing the crossing that would take the tick back.
  **A circle that is only ever asked about is left alone until just before it is asked**:
  "a las nueve, y sólo si estoy en casa" needs the phone's position at nine and at no other
  time, so the condition's circle — and, under "a la vez", a place that cannot ring on its own
  and is only asked at a sibling's moment — is watched from `PlaceWatchPolicy.ASK_LEAD` (five
  minutes) before that rule's next moment (`nextFireOfRule`, after any snooze) and not before;
  the one look it gets is fresh when the alarm asks, and the ordinary cadence carries it there.
  A sibling place cannot gate another place, because answering it would
  need the very fix this exists to avoid spending. The geofences are *not* gated: they are the
  free eye and the net under all of this, and one firing outside its hours costs a condition
  check that says no.
  **A shut gate stops a circle from buying a look; it does not stop it from being told what one
  found.** Every gate above saves the same thing — the radios — and none of them is a reason to
  throw away an answer somebody has already paid for. So a gated circle is handed to
  `stepPlaceWatch` as a *listener* (`Watching.listening`): judged into `inside`, never given to
  `planNextCheck` (it cannot pull the cadence towards itself or wake the GPS) and never turned
  into an event — a reminder that cannot ring must not report a crossing, and `places()`, which
  is what `accept` reads, still excludes it, so neither eye can fire it. The circle behind "el
  26, y cuando llegue a casa" therefore spends the month knowing which side of its line the
  phone is on, at nobody's expense, and the morning its gate opens the first fix is a crossing
  rather than a baseline. What a listener is *not* owed is memory through a look that takes **no
  fix**: what nothing is refreshing is dropped, because a judgement left standing for weeks and
  then subtracted from a fresh one invents a crossing nobody made — and a rule ticked off under
  "todos" by a crossing nobody made is the one mistake here that cannot be seen from the card.
  On each check (an allow-while-idle alarm to `PlaceCheckReceiver`,
  exact when the phone allows it) it reads one fix from the fused provider — GPS only when the
  nearest line is close and the phone moving, the wifi/cell blend otherwise — and hands it to
  `stepPlaceWatch` (`core-model`, `PlaceWatch.kt`), which judges every place with hysteresis
  (in takes a fix inside and no sloppier than the place; out takes a fix clearly beyond the
  line), reports the crossings that match a rule, and plans the next look: the time to reach the
  nearest line at the measured speed with headroom, floored at 2 minutes, doubling while the
  phone stands still up to 15 minutes near a line. With no speed to go on (the first look of a
  session) it plans for a slow car and looks again within 15 minutes regardless — an hour blind
  is ninety motorway kilometres — and the speed memory (90 min) outlasts the longest wait, so
  the average over a look-away is the next plan's speed.
  The ceiling is an hour, which **distance alone can lift** (`reachCeiling`): a gap takes
  120 km/h to close, the fastest anybody averages by road, so a place 300 km off cannot be
  arrived at for two and a half hours and is not worth looking at until then. Past 500 km a
  flight is on the table, no road speed bounds anything, and it falls back to the plain hour —
  which next to any flight, door to door, is still short.
  The GPS is only ever asked for near a line and with the phone *known* to be moving, on the
  evidence of two fixes; a drive straight through a place between two looks is not arriving, and
  is the geofence's to call. A place with no history
  — a new rule, first launch — is baselined by the next fix without an event, which is how a
  reminder written while standing at home does not ring for "arriving home"; it waits until the
  watch has seen the phone leave, and while it waits it costs the least of anything in the app.
  Both ways of being inside a place are cheap, for different reasons. Waiting for an *arrival*
  from inside is half an hour a look and never GPS: the only thing that can happen indoors is
  going out, and stepping out and back inside that half hour is not arriving either. Waiting for
  a *leaving* from inside is the case the plain answer gets worst — standing inside a place is
  standing next to its line, so "time to the line" would ask for the fastest cadence in the app,
  all evening, for a door nobody walks through — so it starts at half an hour too and buys its
  way down only with evidence (`leavingWait`): the fraction of the place's radius the phone
  actually crossed since the last look takes that fraction off the half hour, down to a floor of
  five minutes. Never GPS either way. What would otherwise be the price of that rest — a leaving
  noticed up to half an hour late — is bought back by the sensor below: it fires as somebody
  actually walks out, and the look moves to five minutes from now (`stirredWait`). Only ever
  earlier, only within `NEAR_M` of a line (a stir three provinces from the only place being
  watched means nothing), and the sensor's one-shot re-arming caps it at one early look per
  check — so the cadence can never beat the five minutes that case was already allowed.
  Each place plans its own look and the soonest one wins, so an errand across town still sets
  the pace for a phone sitting at home.
  `MotionSensor` is the third witness and the free one: `TYPE_SIGNIFICANT_MOTION`, a one-shot
  hardware trigger evaluated by the sensor hub, no permission, no Play Services, and it keeps
  answering while the app is asleep (Activity Recognition classifies better and costs a runtime
  permission dialog; this app does not spend one on it). Its word is taken **one way only**,
  because a phone flat on a train table feels nothing: firing means the phone moved and ends
  whatever back-off it had earned, while not firing is believed only alongside a pair of fixes
  that say the same — and then it lifts the near-a-line still cap from a quarter of an hour to
  the full one, and lets the watch skip the fix entirely (`stepWithoutLooking`), because a fix taken
  of a phone that has not moved is one already in hand. That skip is bounded by the fix's own
  age: everything downstream is measured from it, so a rest is never allowed to outlive the
  speed memory. If the process died between two checks the registration died with it, and the
  honest answer becomes *I was not listening* — null, and the watch plans as it did before there
  was a sensor. (The same process-local truth is why `plannedAt`/`plannedGapM` live in memory
  rather than the store: the sensor only speaks for the process that armed it, so they are valid
  together or not at all.)
  **The battery has the last word** (`batteryFloor`, read once a check from `BatteryGauge` —
  one property, no broadcast to keep alive; charging reads as nothing to hold back for). Above
  half there is nothing to discuss. Below it the floor under every plan climbs *geometrically*,
  so the half of the fall nobody worries about costs almost nothing (37% left: a two-minute floor
  becomes ten, where a straight line would say thirty-one) and the last quarter costs everything:
  at 25% it is the hour, and the GPS goes with it — an hourly look is not the last few hundred
  metres of an approach, which is the only thing the GPS was ever for. The span it climbs is
  exactly MAX_WAIT / MIN_WAIT, so the fastest cadence the app has becomes the slowest one it has
  and there is nothing under that to fall to. A floor and never a cap, because the alternative
  eats itself: a place 300 km off has already bought two and a half hours, and an empty battery
  is no reason to go and look sooner than that.
  All of that argues in the dark, so the watch keeps its own account of it: `PlaceLogStore` (a
  third DataStore, the one thing in the app that is fine to lose) holds two hundred lines,
  one per look — what it came to (a fix and whether it woke the GPS, a rest, no fix at all, a
  stir, a crossing, an echo) and every number it decided from. `WatchLogScreen`, behind a button
  in the Location section of Settings, is that list; it is a diagnostic screen and reads as one,
  every figure in the mono face so the rows can be compared down the column. A look that spent
  radio counts as a *poll* and a rest does not, which is the whole point of the distinction: with
  `AppSettings.busyWatchNotice` on — off by default — more than `BUSY_POLLS` polls in an hour
  posts one quiet notification (`WatchNotices`), at most one an hour because the window it is
  about is an hour. MIN_WAIT is two minutes, so thirty an hour is all the watch can physically
  do and `BUSY_POLLS` sits at three fifths of it: reaching it takes over half the hour at the
  fastest cadence there is, which is a very long approach on foot or something going wrong.
  A check that gets nothing — location switched off, a cold provider — retries at ten minutes,
  doubling to the hour (`blindRetry`): the answer to "where are you" cannot change until somebody
  opens Settings, and asking every ten minutes all day is the one drain nobody would ever see
  coming. `sync()` (which runs on every process start, and the process starts every time an
  alarm reaches an app the system had cleaned up) leaves a pending look standing unless a place
  has never been judged or nothing is pending at all; looking soon unconditionally would mean a
  second fix five seconds after every check, all day, for a list of places that had not changed.
  A watched circle's id (`GeofenceIds`) carries the circle itself — pin, radius, which way it
  is waited on — and not just the rule's index, because `inside` is remembered by that id: a
  rule deleted above another once handed the survivor the memory of a place that was gone, and
  rang an arrival at somebody who had not moved. An edited circle is a new id, which costs one
  baseline look and rings nothing. With no history the baseline resolves doubt towards silence
  (`insideAfter`): a place waiting for an arrival is inside when the fix *could* be, one waiting
  for a leaving is outside unless the fix is clearly in — a cold provider's first fix is often a
  cell tower's kilometre, and the plain answer off its centre is a guess the next good fix
  "corrects" with an event. The same doubt is no answer to a place condition
  (`Condition.holdsAt`): a fix sloppier than the circle holds it, like no fix at all.
  A crossing Play Services reports is judged the same way (`crossingIsNews`): an arrival
  announced while the app's own recent fix still has the phone inside is a line nobody crossed
  and is dropped, and one that stands is written into the same `inside` map so the other eye
  knows it is old news. Anything the watch cannot vouch for — no fix, one older than the speed
  memory, a place never judged — is news, because ringing once too often beats never arriving.
  And what it will not vouch for it does not judge by: a fix older than the speed memory — the
  stale one the provider hands back when nothing fresh answers — is treated as no fix at all,
  because writing this morning's position into `inside` is how a real arrival later gets
  dismissed as a place the app thought you were already in.
  State lives in
  `PlaceWatchStore` (its own DataStore; written every check). Doze holds allow-while-idle
  alarms to one per nine minutes, and a phone in Doze is a phone not moving, so nothing is
  lost. Both eyes seeing the same arrival ring once: `ReminderFiring` drops a place firing
  that repeats within five minutes of the last ring.
  **The chain of looks is the watch**, so nothing may drop a link. `sync()`, `check()` and
  `accept()` take one mutex — they arrive through different doors at once (the alarm that
  starts a dead process runs the check while the process's own start-up runs the sync) and,
  interleaved, the later write handed the store the earlier one's stale reading and planned a
  "look soon" five seconds after the look just taken. Within a check the next look is armed
  *before* anything rings (ringing is the slow part, and the receiver's budget is ten seconds),
  a check that blows up retries like a blind one, and `PlaceCheckReceiver` calls `recover()` on
  a check it had to cut short, which arms a retry unless a future look is already planned.
  `GeofenceManager.sync()` is bounded too: it sits in the start-up chain ahead of the watch's
  sync, and a Play Services that never answered would have held that behind it for ever.
  Both grants the firing depends on are given by hand in system settings while the app keeps
  running, so `RwilcoApplication.resyncIfGrantsChanged()` (from `MainActivity.onResume`) arms
  what a new grant unlocks — background location: geofences and the watch; exact alarms: the
  alarms — rather than waiting for the six-hourly worker.
- **Diagnostics** (`diag/`): the app's own account of itself, because a reminder that never
  arrived leaves nothing behind — no crash, no error, an empty screen. `Diag.note(tag, text)` is
  written from every decision that could be the one (a firing dropped and why, a re-arm, the
  presentation decision and its five inputs, the vault, the updater, a boot), lands in a ring of
  its own (`DiagLog`: 300 lines, a week, and a repeat of the same line inside a minute replaces
  it rather than piling on), and `Diagnostics.report()` — pure, JVM-tested — turns it and the
  state around it into one block of text to copy: the build and the clock, the ten things the
  phone can do to hold a reminder back, the settings that decide when things ring, the backup,
  and one two-line entry per reminder with every stamp the firing path judges by. It carries no
  reminder text, no tag or place names and no token: a bug lives in the moments, not in the
  words, and a circle rounded to two decimals still tells two of them apart. Behind the last row
  in Settings.
- `SystemEventsReceiver` re-arms after a reboot, an install over ourselves, or the clock moving:
  a wall-clock promise is not an instant until a zone says so.
- **Nothing on the way to the person is allowed to fail quietly.** Every way `fire` has of
  *not* ringing re-arms before it leaves (the alarm that brought it is spent; a drop that left
  nothing behind was a reminder silent until the six-hourly net); `rearmAll` writes the armed
  moment *before* setting the alarm (an alarm for a past moment arrives at once and read the
  row first); the settings are read — with the defaults as the answer to a read that fails —
  *before* `markFired`, so no moment is spent by an exception nothing showed for; the app scope
  has an exception handler and every collector survives a bad pass, because the collector on
  `repository.open` is the only thing that arms a reminder just saved; the settings DataStore
  replaces a corrupt file instead of throwing on every read; `AlarmReceiver` bounds itself
  under the broadcast budget; `MainActivity.onResume` catches up (guarded to once every few
  minutes), so a timer the phone slept through is said when the app is opened. A catch-up under
  `LATE_IS_MISSED` (15 min) rings as the moment itself; past it, it is the quiet note.
  On the showing side: every alert channel carries **alarm** audio attributes, silent ones
  included, and the live notification is `CATEGORY_ALARM` — which is what lets Do Not Disturb
  tell it from a chat and the ringer switch keep its buzz; with notification-policy access
  granted the channels bypass DND outright (`_dnd` channels). `alertPresentation` asks whether
  the system will honour a full-screen intent at all (Android 14+ can refuse it) and answers
  BANNER on a dark screen when it will not, so the notification makes the noise a screen that
  never came would have made; with the screen on the alert is started *before* the notification
  is posted, and the channel follows whether it took. Notifications switched off make the
  post a no-op, so the screen is tried from anywhere. `AlertRinger` asks for exclusive audio
  focus and buzzes with alarm usage. `AlertPermissionsCard` reads ten states, the five grants
  and the five ways the phone holds a reminder back — battery optimisation, a restricted
  background, total-silence DND, the alarm volume at zero, a muted channel.

## Backup (the vault)

`vault/`, optional and off by default; with it off nothing here runs and nothing leaves the
phone. On, it is **one file, `rwilco.vault`, in a private GitHub repository of the person's**,
replaced whole through the Contents API — each replacement a commit, so the repository's
history is the backup's history for free — with a fine-grained token scoped to that repository.

- **What goes**: `VaultSnapshot` — the `reminder` rows as rows (their columns are already a
  frozen contract and their JSON columns are read by `ReminderCodec`, so a vault inherits every
  lenient read the database has) and the raw `settings_json` blob, whole. Nothing is stripped:
  the armed moments are what lets a restore ring, late, what fell due meanwhile. The place
  watch's two stores stay out (device state, a location trail), and so does the vault's own.
- **Sealed before it leaves** (`VaultCrypto`): a JSON envelope with a plaintext KDF header
  (PBKDF2-HMAC-SHA256, 600k, the vault's own salt) and two AES-256-GCM boxes under the derived
  key — a tiny `check` so a wrong passphrase fails cleanly, and the gzipped snapshot. The KDF is
  the app's own dozen lines over the platform HMAC, pinned to the RFC 7914 vectors on the JVM
  and on the device, over the NFC-normalised passphrase. The remote learns a size and a time.
- **The phone keeps the key, never the passphrase** (`VaultStore`, its own DataStore next to
  the token, the salt and the cursors — never inside the settings, which are part of what it
  backs up). A new phone has no store, so the only way in is the passphrase.
- **When** (`VaultWorker`, `BackupCadence`): **the way anacron counts** — not "every four hours
  on the hour" but *four hours after the last run that came to something*, which is a copy made
  or a look that found nothing to copy (`VaultState.lastRunAt`). A run that could not reach
  GitHub is retried with a growing wait until it goes through, and only then does the clock
  start again: three days failing, a copy on the fourth and a weekly cadence puts the next one
  on the eleventh day. So there is no periodic request — each run books the next — and the
  cadence is a setting (hourly to weekly, four hours by default), as is whether copies wait for
  wifi. A run whose fingerprint is the last uploaded makes no call. The blob sha (`git hash-object`, computed locally) is
  written down before the PUT and compared with GitHub's answer: a 409 whose remote sha is our
  own attempt is an upload that landed after its reply was lost; any other is **another
  writer**, and the run stops, records `CONFLICT` and says so — never a silent clobber. Auth
  and a missing repository stop the same way; network trouble retries with backoff.
- **Back** (`VaultRestore`, the Backup screen): open first — derive with the *file's* salt,
  show when, from which phone and how much — then apply in a fixed, idempotent order: rows in
  one transaction, settings, the vault's state (adopting the salt, key and sha), then the alarms.
  What the phone held first goes into `files/vault/before-restore.vault`, sealed under the
  incoming key, behind an "undo" row. Enabling against a repository that already holds a vault
  asks: restore it here, or replace it. The same envelope goes through the file picker as an
  export/import, for any other cloud or none.
- **What is waiting** (`pendingChanges`, pure): the reminders written or edited since the last
  copy, plus one if the settings moved — and at least one whenever the fingerprint says a copy
  is owed, because a deletion leaves nothing to count. It is the red disc in the corner of Home
  (`BackupBadge`), which exists only while it has something to say: a tap makes the copy on the
  spot, turns into a ring while it goes up and a tick when it lands, and then goes away.
- **Versions**: `VAULT_SCHEMA` for the data, `VAULT_FORMAT` for the container; a newer either is
  refused. `VaultSchemaTest` freezes the row's column list and demands a fixture per data
  version, so a change that would make old vaults unreadable fails in CI (the rule is in
  `CLAUDE.md`). A vault written by a newer *build* under the same schema is warned about in the
  preview: the rows keep what this build cannot read, but the editor's next save would not.

## Self-update

`update/`: `UpdateWorker` (periodic + boot/focus; the launch check is `MainActivity`'s, not the
`Application`'s, because the place watch starts the process every few minutes and each start
was a trip to GitHub) runs `Updater`, which reads
`version.json`, decides with the pure `nextUpdateStep` table, downloads over OkHttp (no plaintext
redirects), validates the APK through the platform parser and commits a `PackageInstaller`
session; `InstallReceiver` turns "needs confirmation" into a notification and keeps a declined
APK for the one-tap retry in Settings (`AppUpdateCard`). `BootReceiver` re-arms after boot and
after the update itself.

## Distribution

GitHub Actions: `ci.yml` (tests, coverage badge, debug APK, compiles instrumented tests) and
`release.yml` (tag `v*` → `rwilco.apk` + `version.json` on a GitHub Release). Signed with the
committed `rwilco-release.jks`. Self-update — milestone 7.
