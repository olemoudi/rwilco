# Architecture

Kept current on purpose: when the model, the persistence or the screen structure changes, this
file changes in the same commit.

## Modules

- `:core-model` — pure Kotlin (`java.time`, kotlinx-serialization), no Android. The domain
  model (`Reminder`, `Trigger`, `Action`, `Status`, `AppSettings`), tag normalisation, and — as
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
| Date and time         | `AtDateTime(at: LocalDateTime)`    | `at_date_time` |
| Countdown             | `Countdown(minutes, startedAt?)`   | `countdown`    |
| Date only             | `OnDate(date)` — rings at the default time (a setting) | `on_date` |
| Time that repeats     | `AtTime(time, days)`               | `at_time`      |
| Place                 | `Location(lat, lng, radiusM, ENTER/EXIT, label)` | `location` |
| Random                | `Random(timesPer, DAY/WEEK, from, to, days)` | `random` |

Wall-clock values are stored without a zone; the zone is applied when the next fire is computed.
A countdown stores the **length**, not the moment: `startedAt` is stamped by `startCountdowns`
where a reminder is written (the editor's save, or straight from a preset) and stripped by
`clearCountdowns` where a preset is, so a shape holds "half an hour" rather than one particular
half hour. Null reads as "from now", which is what the editor shows while one is being written.
Reminders written before this hold their countdown as the `AtDateTime` it once produced, which
is what it always was.

`nextFireOfRule` walks a rule's candidate moments until its conditions hold, stopping after 64
so a rule that can never be satisfied ("a las 09:00, y sólo si es entre las 18:00 y las 22:00")
answers *never* instead of looping.

`nextFire(reminder, now, zone, defaultTime)` (`NextFire.kt`) picks, under ANY, the earliest
definite moment (`Scheduled`), else the earliest random draw (`Sometime`, shown as a window),
else a place (`WhenAt`); under ALL it answers with the *last* of the pending ones, or with the
place when one is among them, because then there is no date to give. `nextWake` is the other
question — what the alarm is set for — and is always the earliest pending moment. `groupForHome` (`HomeSections.kt`) lifts the earliest `Scheduled` out as the hero and
files the rest under Overdue / Today / Tomorrow / This week (rolling 7 days) / Later / Whenever /
Paused. Random moments come from `RandomDraw.kt`: SplitMix64 seeded by (reminder id, period
index), pinned by golden values in its test.

Dealing with a firing (`statusAfterDismissal`) finishes a reminder unless its `Recurrence` says
otherwise — `None` by default, because "hecho" means finished and a place can always come round
again. `Recurrence.kt` is the whole vocabulary: `ByTrigger` hands the question back to the
triggers (a repeating time, a random window), while `After(amount, unit)` and
`MonthlyWeekday(ordinal, day)` work out their own moments from `Reminder.lastDealtAt` — the
instant the person dealt with the last firing, which is the only one that knows anything. Hours
are exact; days, weeks and months land on `AppSettings.dayStart` and never before the span is
up. The triggers say when it rings the FIRST time and the recurrence when it comes back, so
`recurrenceMoment` takes over once it has been dealt with once — or straight away when there
are no triggers at all, which is what makes "cada 6 h" a whole reminder on its own.
A recurrence's moment is **spent once it has rung**, the same way a rule's is: the anchor only
moves when somebody deals with the firing, so a reminder that rang and was ignored would
otherwise be handed the same past moment for ever — armed for ever, and an alarm already in the
past arrives at once. Spent, it answers *nothing*, which Home reads as overdue.
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

## UI

- Single activity, `navigation-compose` type-safe routes (`Routes.kt`): Home, Editor(id?),
  Done, Settings. Sheets, the place picker and the alert preview are ViewModel state.
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
- Home: `HomeViewModel` combines the open reminders, settings, the tag filter and a minute pulse
  into `HomeUiState` (`buildHomeState`, pure and tested). The hero card's countdown ticks in its
  own composable (`rememberNow`) so nothing else recomposes. The magnifier has a flow of its own
  (`buildSearchState`, also pure): a keystroke must not put Home through grouping and next-fire
  again. Results replace the list while it is open; a reminder opens, a tag becomes the filter.
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
  row and deliberately drops the snooze, the last ring and the armed moment — editing re-decides
  when a reminder rings — but carries `lastDealtAt`, which is the anchor a recurrence counts
  from rather than a firing's leftovers. A toggle turns the form
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
  the snooze and the recurrence — and deliberately not what the scheduler itself writes back,
  or every re-arm would come round as a change and arm everything again.
- `ReminderFiring` is the single place that decides what a firing, a "Hecho" and a snooze do, so
  the alarm, the notification buttons and the alert screen cannot drift apart. Under ALL it
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
  an arrival against it would mark tomorrow's appointment spent before it ever came.
- `AlertPresenter` decides *where* a firing shows itself: an app open in front of somebody gets
  the banner, and the home screen, a dark screen or the lock screen get the whole screen. That
  needs two permissions granted by hand — usage access (to tell an app from the launcher) and
  "display over other apps" (Android forbids a background activity start without it). Missing
  either falls back to the banner, which is what the system does on its own, and Settings says
  so. The decision itself is a pure function with JVM tests. "Hecho" finishes
  a one-shot and leaves anything that can come round again.
- `AlertNotifications` has one channel per sound/vibration combination, because a channel's
  sound is fixed the moment it is created. A full-screen alert's notification stays silent: the
  screen does its own looping ring (`AlertRinger`) and gives up after two minutes.
- `AlertActivity` shows over the lock screen and turns it on; it is its own task so dismissing
  an alarm at three in the morning does not drop anybody into the app's back stack.
- `GeofenceManager` registers the place rules with Play Services, wholesale, and re-registers on
  boot and from `RearmWorker` (a reboot or a Play Services update drops them all). That is the
  net: free, always on, the system's own word on where the phone is. Settings says where that
  grant stands, whether or not a place reminder exists yet (`LocationPermissionCard`), because
  a refusal discovered later is a reminder that never arrives. A place is judged against its
  conditions when it happens, not when it is armed.
- `PlaceWatcher` is the second opinion, and the one that decides its own cost. On each check
  (an allow-while-idle alarm to `PlaceCheckReceiver`, exact when the phone allows it) it reads
  one fix from the fused provider — GPS only when the nearest line is close and the phone
  moving, the wifi/cell blend otherwise — and hands it to `stepPlaceWatch` (`core-model`,
  `PlaceWatch.kt`), which judges every place with hysteresis (in takes a fix inside and no
  sloppier than the place; out takes a fix clearly beyond the line), reports the crossings
  that match a rule, and plans the next look: the time to reach the nearest line at the
  measured speed with headroom, clamped to 2–60 minutes, doubling while the phone stands
  still up to 15 minutes near a line. With no speed to go on (the first look of a session)
  it plans for a slow car and looks again within 15 minutes regardless — an hour blind is
  ninety motorway kilometres — and the speed memory (90 min) outlasts the longest wait, so
  the average over a look-away is the next plan's speed. The GPS is only ever asked for near
  a line and with the phone *known* to be moving; a drive straight through a place between
  two looks is not arriving, and is the geofence's to call. A place with no history
  — a new rule, first launch — is baselined by the next fix without an event, which is how a
  reminder written while standing at home does not ring for "arriving home"; it waits until the
  watch has seen the phone leave, and while it waits it costs the least of anything in the app:
  a place already inside that waits for an arrival is planned at half an hour a look and never
  with GPS, because the only thing that can happen indoors is going out, and stepping out and
  back inside that half hour is not arriving either. Each place plans its own look and the
  soonest one wins, so an errand across town still sets the pace for a phone sitting at home.
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
- `SystemEventsReceiver` re-arms after a reboot, an install over ourselves, or the clock moving:
  a wall-clock promise is not an instant until a zone says so.

## Self-update

`update/`: `UpdateWorker` (periodic + launch/boot/focus) runs `Updater`, which reads
`version.json`, decides with the pure `nextUpdateStep` table, downloads over OkHttp (no plaintext
redirects), validates the APK through the platform parser and commits a `PackageInstaller`
session; `InstallReceiver` turns "needs confirmation" into a notification and keeps a declined
APK for the one-tap retry in Settings (`AppUpdateCard`). `BootReceiver` re-arms after boot and
after the update itself.

## Distribution

GitHub Actions: `ci.yml` (tests, coverage badge, debug APK, compiles instrumented tests) and
`release.yml` (tag `v*` → `rwilco.apk` + `version.json` on a GitHub Release). Signed with the
committed `rwilco-release.jks`. Self-update — milestone 7.
