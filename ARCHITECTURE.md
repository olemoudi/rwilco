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
makes them safe to AND with anything: `time_window` (hours + days, crossing midnight allowed),
`date_range` (two days of the calendar, both ends included) and `at_place`. A window also carries an optional **date**, which nobody ever types — the "y
sólo si" sheet offers hours and days — and which exists for one thing: a *dated* rule folded
into its siblings as a state. "El domingo de 20:30 a 22:00, y a la vez en casa" is a state about
one Sunday evening, and folded as hours alone it became every evening: the set rang on the
Friday somebody walked through their own front door, and the circle was paying for a position
every night until it did. It is `@EncodeDefault(NEVER)`, so nothing anybody has typed changes
shape on disk.

**A place is a state, and the doorway is the exception** (`Trigger.Location`). It used to be an
event and only an event — "al llegar" meant a line the phone had to be *seen* going through —
and that one decision spread: a reminder written at home would not ring until you had left and
come back, a set under "todos" could not tick off a place you were already standing in, and the
first fix of a session had to be biased towards silence so it did not invent an arrival. Most of
the time nobody means the doorway; they mean "cuando esté en casa". So `presence`
(`Presence.INSIDE`/`OUTSIDE`) says which side of the line the rule is about and nothing more: it
holds whenever the phone is on that side, whether or not anybody watched it get there. A reminder
that is only "mientras esté en casa", written at home, rings at once. `onCrossing` asks for the
doorway back — "al llegar", "al salir" — and means exactly one thing: **a side nobody has seen
yet does not count as the other side**, which is a single line in `stepPlaceWatch` and was
already there. It also decides which of the two a set folds in (`Trigger.asState`): **a doorway
is a moment, a side of a line is a state.** Read as a state, "al salir del club, y a la vez a
las 13:30" became "estando fuera del club, a las 13:30" — the crossing quietly dropped, the
reminder announcing itself as next, and ringing at half past one for the mere fact of being
somewhere else. As a moment it is two moments beside an hour, which never coincide: nothing is
armed, no circle is watched, and the editor says so. Beside a *window* it is the shape that
sentence was reaching for — the crossing is the moment, the window the state it has to land
in — and the window's own opening rings nothing. Four readings, two questions, and the same four words on every screen
(`placeReading`). **A place being added opens on the doorway** (`LocationSheet`): "al llegar a
casa" is the sentence somebody writes at the moment they reach for a place, and the state
reading is the same switch, one tap away. That is the editor's opening answer and nothing else:
`onCrossing` keeps its `false` on disk, where it is what every place written before the field
existed decodes to.

The on-disk shape did not move: `Presence.INSIDE` is `@SerialName("ENTER")` under the frozen key
`transition`, and `onCrossing` is a new field with a default. **The default is the migration** —
every place written before this reads as a state, which is the reading it almost always meant,
and `ReminderCodecTest` pins both halves. What it costs is stated plainly: somebody's existing
"al llegar a casa", sitting at home, will ring on the next look.

Triggers (`core-model/.../Trigger.kt`), with their frozen JSON discriminators:

| Kind (UI tile)        | Stored as                          | `type`         |
|-----------------------|------------------------------------|----------------|
| Date                  | `AtDateTime(at: LocalDateTime)`    | `at_date_time` |
| Date (no hour chosen) | `DayRandom(date, window?)` — the stretch it opens with: the one it was given, or that day's waking hours | `day_random` |
| Date range            | `DateRange(from, to)` — a stretch of the calendar, both ends included | `date_range` |
| Time of day           | `TimeOfDay(time, days)` — an hour, on the days it counts | `time_of_day` |
| Countdown             | `Countdown(minutes, startedAt?)`   | `countdown`    |
| Interval              | `Interval(from, to, days)` — a stretch of the day | `interval` |
| Place                 | `Location(lat, lng, radiusM, INSIDE/OUTSIDE, label, onCrossing)` | `location` |
| Random                | `Random(timesPer, DAY/WEEK, from, to, days)` | `random` |
| *(not a tile)* Calendar | `Repeat(startsOn, every, unit, time?, days, monthly?, ends)` | `repeat` |
| *(read only)* Date only | `OnDate(date)` — rings at the default time (a setting) | `on_date` |
| *(read only)* Time that repeats | `AtTime(time, days)`     | `at_time`      |

The last three are written by no version of the app as a *rule*. A date is one tile now, with an
hour in it or with the day's own hours behind it; a weekly time is the `WEEK` case of `Repeat`;
and `Repeat` itself is no longer a way of starting at all — it is the calendar inside
`Recurrence.Calendar`, reached from "Vuelve". All three still decode and still mean exactly what
they meant, because the discriminators are frozen and somebody's phone is full of them.
`foldRepeats` (`LegacyRepeats.kt`) turns a stored `Repeat`/`AtTime` rule into the calendar it
always was on the way in — see **Persistence** below.

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

**The hours somebody is up** (`Awake.kt`). A trigger with no `time` rings when that day's waking
window opens, rather than at some hour of the twenty-four. `AwakeHours` holds two
pairs — weekday and weekend — and `AppSettings.weekendDay`/`weekendTime` and
`weekendEndDay`/`weekendEndTime` say where the weekend span begins and ends, so the two ends of
a day are asked separately: a Friday gets up for work and goes to bed at the weekend, and a
Sunday keeps the lie-in and loses the late night. `DayShape` is that read-only view of the
settings, threaded through `nextFire`/`nextWake`/`nextFireOfRule`, and a change to it re-arms
everything (`RwilcoApplication`) because it moves real armed moments. The moment itself is
`openingOf`, the first minute of the stretch, so the screen and the scheduler agree without
storing anything. An explicit `time` ignores all of it.

**A day with no hour is a stretch, not a lottery** (`openingOf`). It used to be a moment drawn
from the waking hours by (reminder, day), and that reading only ever survived while the shape
depended on nothing else: the moment it sat in a set beside a place, the draw had to be rewritten
into the window's *opening* or the ring would land at 15:37 while the other half was false. Two
readings of one control, and which one you got depended on what else was on the card — so the
opening is the only reading now. "El jueves, me da igual la hora" rings when Thursday's waking
hours start and goes on being true all day, which is exactly what `Trigger.Interval` has always
been, one unit up. Two reminders on the same stretch therefore ring together, which is the
visible cost and the honest one: chance is a thing to ask for on purpose, and `Trigger.Random` is
the tile that asks for it.

**The opening is moved inside the fences, never judged against them afterwards.** A rule's "y
sólo si" hour windows (`TriggerRule.windows()`) and a calendar's own fences reach `openingOf`,
which walks to the first minute they all allow. A door that opened at eight for a rule saying
"sólo de 16 a 17" was a moment the fence rejected, so the rule answered *never*: "el jueves a
cualquier hora, y sólo si es entre las 16 y las 17" opens at 16:00, and "el primer viernes de
cada mes, sólo de 16 a 17" with no hour rings that Friday rather than about once a year. When no
minute of the stretch clears the fences the plain opening comes back for the walk to reject,
which is what a fence naming *other days* ("sólo los lunes") has to do to a daily calendar. The
random tile (`Trigger.Random`) is drawn inside them too (`RandomDraw.draws` takes the rule's
windows): the minutes each day allows are listed first and the draw is one of those, so "una
vez a la semana al azar, y sólo los sábados" is drawn on a Saturday rather than drawn over the
week and thrown away six times in seven — which waited seven weeks on average and could be
called *never* by the walk. A dated window folded in under "a la vez" names the one period
worth drawing. With no fence every call on the generator is the one it always was, so nothing
without a fence moved. Its window may end before it
starts, and then it crosses midnight like every other window in the model (`windowMinutes`):
"de 22 a 2" is an evening, and its small-hours draws belong to the day the window opened on,
which is the day its `days` are judged by.

**A day written while it is under way starts from what is left of it** (`settleDays`, beside
`startCountdowns` and applied at the same two saves). "Hoy, me da igual la hora" saved at five in
the afternoon has an opening — eight in the morning — that has already gone, so the reminder was
born overdue and never rang. The window is narrowed to the next whole minute → the day's end, so
it opens at 17:04 and rings within the minute, which is what "en cuanto estemos en ese día" says.
`warnings` runs the same narrowing, so what the editor says and what is saved agree. Both
`toReminder`s take a `zone` for it — required, so no save can bypass it. A day still open past
midnight — the small hours of a Saturday belong to a Friday's waking window — is laid on the
day it is now: a window on the Friday cannot start on the Saturday, and left as written the
reminder was born overdue for a stretch the same trigger read as *open* when asked as a state.

**Three answers to "when in the day", not two** (`DayWindow`). Between an hour somebody picked
and the whole of the day they are up for sits a stretch they named: "a la hora de comer". Both
shapes that leave the hour to the day carry an optional one — `Trigger.DayRandom` (the date
tile) and `Trigger.Repeat` (the calendar behind "Vuelve") — and it is only ever read when there
is no `time`, because an hour somebody typed is not a thing anything else may argue with. A
named stretch is the same `openingOf` over a narrower window. `SavedWindow` in the settings is a
name over two times, offered as chips wherever a stretch is asked for (the date tile, the
calendar, the window trigger) and never referenced: what a trigger keeps is the two times, so
renaming or deleting one never reaches back into a reminder — the same rule a place follows.
The fields are always there under the chips, so a stretch nobody has named is one tap further
and not a trip to the settings.

**And the calendar opens on whatever the rules already answered** (`DayTiming`, `dayTimingOf`).
"El 26 a las 20:00, y vuelve cada mes" is one sentence, and asking for 20:00 twice — once in the
trigger, once in "Vuelve", three rows apart — is asking somebody to notice the second control
exists and then to agree with themselves. The first rule that says anything about the time of
day seeds the calendar sheet: an hour, a stretch, or "me da igual". The first and not a vote,
because a reminder with two clocks in it has no single answer; nothing is stored, because every
trigger already carries its own; and only for a calendar that does not exist yet, because an
answer somebody has given is not something a trigger may reach back and change. A countdown, a
place, a random window and a date range say nothing here, and neither does `OnDate`, whose hour
is the settings' rather than one anybody typed.

**A stretch of the calendar is a stretch of a day, one unit up** (`Trigger.DateRange`). Some
things are true for a while: "renovar el abono" is not a Tuesday, it is the fortnight the window
is open, and writing it as a date meant picking a day out of that fortnight and hoping. It names
no hour on purpose — the sheet offers none — so it rings at `defaultTime` on `from` and is a
state (`Condition.DateRange`) for every day through to `to` inclusive, both ends counted. The
state is the half that does the work: "al llegar a casa, y a la vez entre el 1 y el 15" is the
sentence it exists to make writable. It rings on `from` and on each later day it is still open,
until somebody deals with it — the same thing `Trigger.Interval` does with a stretch of the day,
one unit up, and what stops a range written at six in the evening from being a reminder that
never rings. Bounded by the range and spent on the first "hecho", so it is not the open-ended
repeat that "Vuelve" alone is allowed to say.

**And an hour is the point that stretch is made of** (`Trigger.TimeOfDay`). "A las 09:00, y a la
vez entre el 1 y el 15" and "a las 09:00, y a la vez en casa" are sentences nothing else could
write: a date names one day, a window has to be given a width it does not have, and a calendar in
"Vuelve" cannot sit in a set at all. So the tile exists to be the *moment* of a set, with
everything else the state it has to land inside — which is the shape "a la vez" was built around.
Alone it is the next such time on a day it allows, and again on the next one if nobody deals with
it, exactly as `Trigger.Interval` behaves. It is **not** `Trigger.AtTime`, which is the same two
fields: that one is the retired "una hora que se repite" tile, a rule holding one is folded into
the calendar it always was (`foldRepeats`), and reviving it would resurrect every repeat that
move retired. An unbounded "todos los días a las nueve" is still `Recurrence.Calendar`'s to say —
a calendar names dates, carries a start and an end, and answers "¿y vuelve?".

**A favourite is always one of the tiles** (`kindsOrdered`). `AppSettings.defaultTriggerKind` is
stored by name and outlives the tile it names, so it is read through `offered()` both in
`SettingsStore`'s decode — the repo's usual place for a shape that moved — and in the ordering
itself, which is the function that breaks without it: the favourite went on the top of the list
and was subtracted from nothing, so one outside the list came out as an *extra* row. It was a
phantom from the day `DATE_TIME` was retired; renaming the date tile "Fecha y hora" is only what
made it visible, as a row word for word identical to the one under it, badged "el que sueles
usar" and opening the same sheet.

**A set no longer rewrites a rule, because there is nothing left to rewrite** (`Reminder.ruleInSet`).
This is where `Trigger.whenCombined` used to live: a window meant a draw on its own and its own
opening in company, so a combining set had to swap one for the other. With the opening as the
only reading, a rule in a set is the rule as written and `ruleInSet` has one job left — under
TOGETHER, folding every *other* rule into it as a condition (`asState`), so the ring lands the
instant the whole set is true. It is the one funnel for "the rule as its own set makes it", and
`warnings` folds the same way and in the same order. Under ALL and ANY a rule now comes back
untouched. `DayWindowTest` holds it: the same trigger, the same moment, alone and in company.

**A state beside a place is the place's hours, not a moment of its own** (`nextFire`). Under "a
la vez", a `Scheduled` whose trigger is a state is dropped from the running for what to show,
and the place is the answer: its opening only rings if the phone is already there, so Home
counting down to it is a clock on a reminder that rings on arrival. It is still armed
(`nextWake`), for the morning somebody is there already. Written for `Trigger.Interval` and now
asked of every state (`isMoment`), because a day with no hour and a stretch of the calendar are
openings in exactly the same way — and a day that reached here as its own *rewritten* opening
used to slip through as a plain moment.

Wall-clock values are stored without a zone; the zone is applied when the next fire is computed.
A countdown stores the **length**, not the moment: `startedAt` is stamped by `startCountdowns`
where a reminder is written (the editor's save, or straight from a preset) and stripped by
`clearCountdowns` where a preset is, so a shape holds "half an hour" rather than one particular
half hour. Null reads as "from now", which is what the editor shows while one is being written, and `countdownOf` is what keeps a length nobody changed from becoming a *new* timer at the next save.
Reminders written before this hold their countdown as the `AtDateTime` it once produced, which
is what it always was.

`nextFireOfRule` walks a rule's candidate moments until its conditions hold. A walk that keeps
failing stops at a **horizon** (`SEARCH_HORIZON`, five years) so a rule that can never be
satisfied ("a las 09:00, y sólo si es entre las 18:00 y las 22:00") answers *never* instead of
looping; a candidate that holds is the answer wherever it lies. It used to stop after
sixty-four *candidates*, which for a daily moment is nine weeks: "a las nueve, sólo del 1 al 15
de agosto" written in April was called never — by the editor, the scheduler and the net alike.
A stretch of the calendar (`Condition.DateRange`) still ahead is not walked up to a day at a
time either: nothing before its first day can clear it, so the walk jumps to its eve
(`skipTo`), and one already behind never holds again and is not walked at all (`overFor`). `Simulation` (a test harness beside `Fixtures`) is a phone
in memory — the row, the alarm, the person answering, a phone switched off — driven exactly as
`ReminderScheduler`/`ReminderFiring` drive the real one, so `FiringAuditTest` and
`SnoozeJourneyTest` can wind a shape through a year of alarms and say of every ring when and
why; a scenario that fails there is a finding about the model, not about the test.

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
Paused. **A snooze is said on the card, not only on the hero**: the hero has always worn it in
its own words (*pospuesto hasta*), and everywhere else the rows went on describing the rule, so
a fortnightly reminder put off for two hours read as a fortnight away — one card telling
somebody the opposite of what would happen. `ReminderCardUi.snoozedUntil` carries it and
`SnoozedRow` says it, above the rules it outranks. Random moments come from `RandomDraw.kt`: SplitMix64 seeded by (reminder id, period
index), pinned by golden values in its test.

Dealing with a firing (`statusAfterDismissal`) finishes a reminder unless its `Recurrence` says
otherwise — `None` by default, because "hecho" means finished and a place can always come round
again. `Recurrence.kt` is the whole vocabulary, and it is **the only place in the app where
anything repeats**:

- `Calendar(repeat, conditions)` — the dates a series names, with an hour in them and an end.
  The shape is `Trigger.Repeat` itself, carried rather than copied: it is what every phone
  already holds on disk, its discriminators are frozen, and a second copy of the same seven
  fields is a second place for the arithmetic to disagree. `conditions` are the "y sólo si"
  fences the rule it used to be could carry.
- `After(amount, unit, from, hour)` — a span counted from something that happened. Hours are
  exact; days, weeks, months and years land on an hour of the day and never before the span is
  up. **Which hour is a question too** (`RecurrenceHour`): the hour the day starts at
  (`AppSettings.dayStart`, the default and what every reminder written before the field means),
  **the same hour** as the moment it is counted from — a reminder answered at bedtime every
  night wants to come back at bedtime, not at nine — or **one of its own**. Only read where the
  recurrence's moment is the ring: with rules that name an hour the span says which *day* and
  they say when in it (`restUntil`), which the editor says out loud rather than offering a
  control that decides nothing.
- `ByTrigger` — hands the question back to a trigger that names its own dates, which is now only
  a random window ("tres veces al día" is its own answer to "¿y vuelve?").
- `MonthlyWeekday(ordinal, day)` — read-only. It is `Calendar` of a month with a `MonthlyOn.Nth`
  in it, said twice; nothing writes one, and opening one in "Vuelve" rewrites it as the calendar
  it always was.

**A repeat used to be two things and that was the whole problem.** A "una hora que se repite"
tile wrote a `Trigger.Repeat` *rule*, this wrote a recurrence, the two overlapped almost exactly,
and nothing on either screen said which of them a given reminder had — the anchor row in "Vuelve"
even had a button that reached across and opened the trigger sheet. A repeat is not a way of
starting; it is the answer to "¿y vuelve?". What the move costs is the *simultaneous* AND: a
calendar can no longer sit in a `RuleMatch.ALL`/`TOGETHER` set beside a place. What it does not
cost is the sentence people actually write — "el día 26, y cuando llegue a casa" is a place rule
with a monthly calendar in "Vuelve", and the rest semantics below say exactly that.

**The anchor is a question only a span has** (`RecurrenceFrom`). "Cada semana" is half a
sentence — the other half is which moment the week is counted from — and there are two answers,
asked once in "Vuelve" and nowhere else: the **ringing** (`RANG`, `lastFiredAt`: answer it late
and the rhythm holds, which is what anybody who set 08:00 / 14:00 / 20:00 meant), or **dealing
with it** (`DEALT`, `lastDealtAt`: the clock starts when you do, and six hours between doses is
six hours between doses). `DEALT` is the default and what every install before the field wrote,
so old JSON reads as itself. `recurrenceAnchor` picks the instant and falls back to `lastDealtAt`
when there is no firing to count from — a reminder swiped done on Home never rang, and "cada 6 h
desde que suena" must still come back. A `Calendar` is never asked: its dates are its own, and it
answers `null` to `nextRecurrence` (the span question) on purpose. `Reminder.calendarMoment`
walks its dates instead, applying its fences the same way `nextFireOfRule` applies a rule's —
the same horizon and the same jump to a stretch still ahead — so a calendar that can never
clear them answers *never* rather than looping, which is what `recurrenceWarning` says out loud
in the editor. Its *place* fences ("todos los lunes a las 9, y sólo si estoy en casa") are the
ones nothing can ask in advance, and `ReminderFiring.fire` asks them at the calendar's moment
the way it asks a rule's — a moment with no rule behind it and no snooze pending is the
calendar's. The place watch does not yet spend a fix on a calendar's circle (`watchedCircles`
lists rules only), so the answer is whatever fix the watch has that still speaks for the moment,
and with none it holds — the house rule.

The triggers say when it rings the FIRST time and the recurrence when it comes back, so
`recurrenceMoment` takes over once it has been dealt with once — or straight away when there
are no triggers at all, which is what makes "cada 6 h" and "todos los lunes a las 9" whole
reminders on their own.
A recurrence's moment is **spent once it has rung**, the same way a rule's is: under `DEALT` the
anchor only moves when somebody deals with the firing, so a reminder that rang and was ignored
would otherwise be handed the same past moment for ever — armed for ever, and an alarm already
in the past arrives at once. Spent, it answers *nothing*, which Home reads as overdue. Under
`RANG` the anchor moves with the firing instead, so an unanswered one comes back on its rhythm
rather than waiting to be acknowledged — which is the whole difference between the two, and the
thing to know before choosing it. **With rules on the reminder, the rest counts from the ring
too** (`restUntil`, `recurrenceMoment`): "a las ocho, y luego cada 6 h desde que suena" rings at
eight, is ignored, and comes back at two — read off `lastDealtAt` alone it never spoke until
somebody answered, which is the one thing that anchor was chosen not to need.
`RecurrencePreset`s (in the settings, four built in unnamed) put the usual **spans** on buttons;
a calendar is not one of them, because a calendar carries a `startsOn` that is that reminder's
own. Room v4 added the boolean this replaced; v5 turns it into a shape (`by_trigger` for whatever
repeated) and rebuilds the table to drop it. The `calendar` shape needed no Room version at all:
the column is JSON, and what it holds is folded on read.

`Validation.problemOf(Recurrence)` blocks a save on a calendar that is nonsense in itself (a
series told to stop before it starts), reusing the checks the trigger already had, and
`recurrenceWarning` says — without blocking — that a series has run out or can never clear its
own fences.

**A day can be counted instead of pointed at** (`Trigger.RelativeDate`, 0.49.0): "mañana",
"dentro de tres días", "el próximo lunes", with the same three answers about the hour a date has
(`RelativeDay.In(amount, unit)` / `NextWeekday`). It exists for presets. A preset carrying
"mañana a las nueve" as a date carries one particular morning for ever: used on that day it is
already today, and after it Home refuses to write the preset blind at all
(`ValidationWarning.InPast` → the form opens instead). What somebody means by that shape is "the
day after I press this", which the app could say about a span ("Vuelve") and about minutes
(`Countdown`) and not about a day with an hour on it. It is **a shape and never a moment**:
`settleRelativeDates` turns it into the plain `AtDateTime`/`DayRandom` it means where the
reminder is written — beside `startCountdowns` and `settleDays`, and for the same reason — so no
alarm, card or catch-up has to know a second way of saying "a date". A draft and a preset carry
it; a saved reminder never does. Asked before it is written (the editor's "Suena…", the preset
list) it answers for the day it would be written on, and `warnings` settles it first for the same
reason it settles a day (`settleRelativeDates` beside `settleDays`): left as a shape, the walk
that looks for a moment re-reads it from wherever it has reached and it behaves like something
that comes round again — stepping over a fence the saved date can never pass, and saying nothing.

`Validation.kt` decides what blocks a save, which is only the words and a trigger that is
nonsense in itself: a reminder needs **neither a trigger nor an action**. One with neither is a
note kept under its tags, and Home files it under "kept, not timed" rather than calling it
overdue. `Snooze` offers ten minutes, a length of the person's own (`CUSTOM`,
`AppSettings.snoozeCustomMinutes`, half an hour by default), two hours, tomorrow morning (at
`dayStart` — a 23:40 alarm put off to "tomorrow" was coming back at 23:40), tomorrow at the same
time, the weekend (a setting: Friday at 20:30 by default) and next week — the wall-clock ones
keeping the time rather than adding hours. The notification has room for two of them
(`AppSettings.notificationSnoozes`, chosen in Settings → Alertas; `pickNotificationSnoozes` keeps
it at exactly two); the alert screen offers them all. It travels as a **name** everywhere it is
kept or sent — the intent extra, and those two in the settings — and never as the enum: a
settings blob is decoded all at once, so a member an older build has no word for would not cost a
snooze offer, it would reset the theme, the sound, the presets and the saved places with it.
`notificationSnoozeOffers` drops what it does not recognise and falls back to the two defaults
rather than leaving a notification with no way to postpone.

`Search.kt` answers the magnifier: one query over the reminders and the tags the open ones use,
returning `SearchHit.OfReminder`/`OfTag` so the screen can say which is which. Matching is
folded (case and accents dropped) and banded — whole, prefix, word start, anywhere, then letters
in order — because the cost of being forgiving is nil on a list this size, and the cost of being
strict is asking somebody to remember how they spelled it. **What was done is found too**
(0.51.0), after everything open whatever its score — somebody typing on Home is after something
to do before something they did — and the row says "hecho" where it said "recordatorio". The
history kept three months and the only way through it was scrolling; `HomeViewModel` feeds the
search `open + done` for that reason, while a tag is still counted over the open ones alone,
because that is what its chip would show.

## Persistence

- Room (`app/.../data/`): one table, `reminder(id, text, tags, triggers, ruleMatch, actions,
  status, createdAt, updatedAt, doneAt, …, armedFor, armedRule, firedRules, repeats)`; tags/triggers/actions are JSON text columns written by
  `ReminderCodec`, read leniently (unknown trigger kinds and actions are dropped, never fatal).
  **The read path is also where a shape that moved is migrated**, which is why the repeating
  time needed no Room version: `foldRepeats` (`LegacyRepeats.kt`, pure and idempotent) lifts a
  stored `Repeat`/`AtTime` **rule** into `Recurrence.Calendar` in `ReminderEntity.toDomain` —
  keeping the shape byte for byte and carrying the rule's conditions across as the calendar's
  fences — and moves `armedRule`/`firedRules` with the rules it removes, one place for every
  one of them (`Folded.removed`). The row itself is rewritten the next time anything saves it.
  A *second* repeating rule on one reminder is dropped: two calendars is a thing the app can no
  longer say, and a rule nothing can edit is worse than one that is gone. `SettingsStore` folds the presets the same way on the way out of
  DataStore, because a preset written then holds a repeating rule too. Room v8 adds
  **Room v9 adds a second table, `firing_event` (0.54.0)**: what happened to a reminder, one row
  per happening — rang, rang late, the net spoke, dealt with, a round skipped, put off (until
  when), a place rule come undone — capped at `HISTORY_KEEP` per reminder and gone with it
  (a foreign key cascade; a restore replaces the reminders and takes the history with them).
  The row keeps one of each stamp and `DiagLog` keeps a week of everything, so "¿sonó ayer?" —
  the question under half the reports from the phone — had no answer anywhere a person could
  find; `ReminderFiring` writes these where it writes its diagnostic notes, the editor shows the
  last fortnight of them on a card of their own (`HistoryList`), and the report carries five
  per reminder (`hist=`). Not in the vault: it is diagnostic, like the place watch's log — what
  the row *is* is what the backup copies, and what happened to it stays on the phone it
  happened on.
  `lastFiredRule` (see the place watch below): additive, null on every older row, and one more
  name on the vault's frozen column list.
  `RwilcoDatabase.VERSION` + `MIGRATIONS` are guarded by `MigrationChainTest` (JVM) and
  `DatabaseMigrationTest` (device). Schemas are exported to `app/schemas`.
- `ReminderRepository`: reactive `open`/`done` flows for the screens, suspend writes.
- `SettingsStore`: Preferences DataStore with one JSON blob (`AppSettings`: theme, default time
  for date-only reminders, the trigger kind offered first, when "the weekend" starts, haptics,
  last-seen version for What's New, the saved places). Additive changes need no migration.
  **What's New is guarded (0.51.0):** `RELEASES` went silent at 0.20.0 and nobody noticed for
  forty-five builds, because a sheet with nothing to say says nothing. `WhatsNewTest` now
  refuses a build whose code is not the head of the list, so a release cannot be cut without its
  line; the thirty unannounced versions are one entry keyed to the build that brought the notes
  back, so a phone that last saw 0.20.0 is told once what happened since.
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
  Done, Settings. Sheets, the place picker and the alert preview are ViewModel state. Two more
  doors in from outside (0.48.0), both worked out by `Destinations` (pure, tested) from the
  intent's parts: holding the launcher icon offers "Nuevo" — **and, since 0.53.0, the pinned
  presets** (`PresetShortcuts`: dynamic shortcuts republished whenever what one is made of
  changes — a pin, a name, a colour — each a disc in the preset's colour with its initial, and
  an intent with `ACTION_PRESET` that `Destinations` turns into `preset:<id>`; Home writes it
  the way its own button does, or asks for the words when the shape left them open, and pops
  back to itself first if the app was elsewhere) — (`res/xml/shortcuts.xml`, action
  `dev.rwilco.action.NEW`) straight into a blank form, and a line of text shared from another
  app (`ACTION_SEND` `text/plain`) opens the form with that line as the words
  (`Routes.Editor(sharedText)`). **An intent is answered once**: `getIntent()` survives a rotation
  and a process-death restore, so it is read only on a fresh start and cleared once consumed —
  otherwise every rotation pushed the shared text back on top of whatever the person was doing.
  Settings → Aspecto has a language row on API 33+ that opens the
  system's per-app page (`locales_config.xml` names the two).
- **A sheet's height is capped** (`SheetScaffold`, 0.49.0). The confirm row is outside the
  scrolling content so it can never sink below the fold — which was a wish, not a fact: a bottom
  sheet measures its content with no height limit (that is how a sheet taller than the screen can
  be dragged), so the `weight(1f, fill = false)` did nothing and the row was simply placed after
  however tall the content wanted to be. It held while every sheet happened to be short enough,
  and stopped the day the date sheet grew a row. It is bounded now to the height the slot is
  actually given, and — when it is given none — to the window less the sheet's own furniture (the
  drag handle above the content and the gap under the status bar); capping against the whole
  window instead asks for more room than the sheet has and clips the row all the same, only by
  less. With a real bound the weight means what it says.
- **A tag still being typed belongs to the reminder** (0.49.0). The field committed on losing the
  focus and "Guardar" cleared the focus on its way in — which reads like it should work and does
  not: the commit lands one snapshot after the save has read the draft, so a reminder saved
  straight from a half-typed tag was saved without it. The word is held by the screen now
  (`pendingTag`) and the save adds it before saving. `TagReuseTest` types one and presses
  Guardar.
- The backup is the one row in the settings index that is not a fold (a group whose whole
  content is one link costs a tap and hides a row), so it is set like the headings it stands
  among — `SettingsLinkRow(topLevel = true)`, `titleMedium`, and `heading()` in the semantics so
  a screen reader walking the index by its headings does not step over it. In the same face as
  its neighbours, because the same word in a lighter one looks like a mistake.
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
  that every one of those states fails silently. **Nor may Home** (0.48.0): the same readiness,
  re-read on every resume, puts a strip at the top of Home (`ReadinessStrip`, error colour,
  never amber) with "Arreglar" into Settings and "Ahora no", which remembers *these* problems by
  name (`AppSettings.dismissedAlertProblems`, `stripShows`, pure and tested) — a phone that
  breaks in a new way is told again. **What keeps that promise is pruning, not emptying**
  (`liveDismissals`, 0.48.1): a problem that has been fixed drops out of the set, so it is news
  if it comes back; emptying only at "todo en orden" meant that with one other thing still in the
  way, a channel muted a second time was never mentioned again. And **the default reading is a
  guess** (`AlertReadiness.read`): everything starts granted so no screen flashes red before it
  has looked, which means nothing may act on "all good" until a real read has landed — done
  blindly, every recomposition of Home threw away the "ahora no" that had just been given.
  The reads themselves are **off the main thread** (`readAlertReadiness`, on `Dispatchers.IO`):
  twelve binder calls on every resume of the screen somebody actually looks at. One consequence
  worth knowing: the groups in trouble now open themselves a beat *after* Settings arrives, and a
  test that toggles a group has to check whether it is already open (`EditorTourTest.openGroup`).
  The Do Not Disturb row is two rows now: the red one only under total
  silence (the one mode that blocks an alarm), and otherwise a plain offer to grant policy
  access in advance — total silence is what people put on for the night, and the grant cannot
  be given from inside it. That grant is read with the other ten (`AlertReadiness.policyAccess`)
  and deliberately not counted among them: it is an offer, not a fault. The channels that carry the grant in their id are re-made the moment
  it changes (`Grants.policyAccess` in `resyncIfGrantsChanged`).
- **"Probar una alerta"** (`TestAlert`, 0.48.0) is a real row ten seconds out with every action
  on, and **only ever one at a time** (0.48.1: a rehearsal nobody answers stays a row, and three
  taps while testing left three overdue cards, each with a net of its own a day later): saved and
  nothing else, so the scheduling watcher arms it, `AlarmReceiver` fires it and
  `AlertPresenter` shows it — the whole path the ten rows above are about, lock screen included.
  A synthetic reminder handed to the presenter would prove nothing (`AlertActivity` re-reads the
  row and drops one it cannot find). Its id is marked, and `ReminderFiring.dismiss` deletes it
  instead of finishing it: a rehearsal is not a thing that got done, and "Hechos" counts the
  week. **And it asks what to try** (`TestAlertCard`, 0.49.0): everything at once answers "does
  anything arrive", but the questions people have are narrower — the full screen over the lock,
  the alarm sound at alarm volume, the buzz in a pocket — and each wants the others out of the
  way, so the rehearsal takes the reminder's own action tiles. `ReadinessAndRehearsalTest` (device) watches the notification arrive and go. **"Vibración al tocar" is the same shape of
  problem in one row**: Android gates every app's touch feedback behind its own switch and there
  is no asking it nicely, so with that off ours can only ever turn the tick *off* — somebody
  turns it on, nothing happens, and the app looks broken. It gets the same red row and the same
  one button rather than a fix of our own: driving the motor ourselves would override a
  preference somebody set on purpose, and would feel worse than the haptics the phone has
  already tuned.
- Theme (`ui/theme/`): hand-authored dark/light schemes (amber `primary` = "what fires next"),
  `RwilcoTypography` on three bundled variable fonts (Bricolage Grotesque display, Manrope body,
  JetBrains Mono for times/dates), `RwilcoShapes`, tokens (`Spacing`, `Motion`, `Sizes`) and
  `Haptics` behind one setting. Trigger families (time / place / chance) have their own colours
  in `FamilyVisuals.kt` — a `color`, a `tint` for keycaps, a `wash`/`edge` for a trigger's own
  row in the editor, and `onColor` for text on a solid fill. Every card also carries a
  **rail** (`RwilcoCard(rail = …)`): a 5dp band down the leading edge, in **the colour of the
  reminder's first tag**, and nothing for an untagged one — a state Home already has a chip for.
  It started as the family of whatever fires next, which is the honest reading and the wrong one
  to look at: on a real list nearly everything next is a clock, so nearly every card came out the
  same blue and the rhythm the band exists for never appeared. A tag is what actually varies from
  one card to the next, and it is the person's own word for the thing. It costs nothing to be
  sure of, either — the tag hues already have the amber and the three family hues cut out of
  them, so a rail can never be read as a keycap. Drawn *inside* the surface, so the card's clip
  curves it into the corner; the hero has none (it already wears the amber); a paused card's goes
  grey with the rest of it. The keycap wash went up alongside it (0.22/0.14 → 0.28/0.18): beside
  a solid band the old one read as a grey-blue square.
  `SectionHeader` was raised to `titleMedium` at full contrast with the count in a pill for the
  same reason — on a screen of full-contrast cards, "Vencidos 1" in muted `titleSmall` was the
  least visible thing on Home and it is the one somebody is looking for. **A selected neutral control is
  inverted** (`onSurface` fill, `surface` ink: tags, presets, action tiles, segments, AM/PM),
  the same swap as the primary button, because three greys never read as "on"; a selected day
  is a solid disc of the time family. Plain `MaterialTheme`: material3 1.4.0 keeps the
  expressive theme internal.
- A card's one control is `HoldButton` (`ui/components/`): a control's own line and the verb
  ("Pausar"/"Reanudar", never a bare glyph, which reads as the state rather than the action), in
  one of two shapes — a 44dp disc with the verb underneath where the control is the point, and a
  `compact` pill with the verb beside the glyph on a card, at the end of the footer's read-only
  action glyphs. The pill is what it wears on Home: as a disc in the top row it took a column
  ~96dp wide out of the one line of the card anybody actually reads, and the reminder's own words
  were wrapping around it. It fires only after a 700ms hold, and what reports on the hold is
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
  **And nothing moves until the hand leaves**: the glass fills under a thumb that is still on the
  screen, so acting there and then pulled the next card up into the space this one was being
  held in — and a reminder that comes back is worse, since it is dealt with, sorted, and lands
  back in the same place, so the card under the thumb turns into a different reading of itself.
  The row goes blank at once (the action has taken: the glass is full and the phone has said so)
  and keeps its height, leaving the hole it made; **the hole closes on the release**, when there
  is no finger left for it to move anything under — the card below sliding up as the row leaves,
  or this same one fading back in with its next moment on it, which is what a reminder that
  comes round again does. Left blank instead it stayed blank: the row is still in the list under
  the same key, and nothing rebuilt it until a scroll took it off screen and back. Which way it went is remembered from when the
  glass filled, not read off the box at release — by then the box is sliding back and would
  answer "neither".
- **Held, a card says what can be done to it** (`ReminderActionsMenu`), and the menu opens at the
  **top of the screen**: a held press lands wherever the card is, which on a list is the middle,
  and a menu that opens under the thumb that opened it has to be read around the hand holding it.
  "Nuevo" can ask its question in the middle because by then the thumb is on a button at the
  bottom; this one has no such luxury. The reminder's own words are the title, because a long
  press does not say which card it caught. One action so far: **clonar**
  (`Routes.Editor(cloneOfId)`), a new reminder wearing this one's tags, rules, reading, actions
  and recurrence with **the words left blank and the keyboard already up** — the same `focusText`
  a preset with no wording uses, and for the same reason: everything else has been answered. A
  countdown is cleared on the way (`clearCountdowns`), so "dentro de media hora" copied at noon
  is half an hour from the save; nothing that *happened* to the original comes with it, because
  `toDraft` carries the shape alone. Nothing is written until Guardar. `HomeCloneTest` walks the
  hold → menu → editor on the real screen. **Since 0.47.0 the menu does the rest too**: hecho,
  pausar/reanudar and borrar as a row of three tiles under the title (the three answers a swipe
  or a hold already gives, here for the hero — which has no pause control of its own — and for
  a hand that would rather not swipe), then **posponer**, **quitar el posponer**, clonar, and
  **guardar como preset** (`Routes.Editor(cloneOfId, newPreset = true)`: the clone path with the
  words kept, because they are the name the shape is filed under). Posponer goes through
  `ReminderFiring.snooze`, the same door as the notification, and is offered **only where it is
  an answer** (`ReminderCardUi.snoozeOffered`, pure and tested): the reminder rang and nobody
  dealt with it since (`awaitingAnswer`), or it is already put off. A card whose moment is still
  ahead gets no offer — a snooze outranks every rule and is spent on its own, so putting a future
  reminder off to before its moment would ring it twice; moving a moment that has not come is
  editing. Every one of these says what it did in the snackbar at the top with an undo, the
  snooze's undo restoring whatever snooze the row had before. `HomeMenuTest` walks put off →
  let back → keep as preset on the real screen.
  **And "saltar la próxima" (0.51.0)**, for a reminder that comes back and is not ringing: the
  act is not new — a "hecho" given to such a reminder already spends its next round
  (`momentDealtWith`) — but "hecho" on something that has not rung reads as a lie, and the one
  honest word for it was nowhere, so the only way to miss one Tuesday of "cada martes" was to
  pause it and remember to come back. `ReminderCardUi.skipsMoment` (pure, tested) is the moment
  that would be let pass, said on the row as a hint; the act goes through `ReminderFiring.dismiss`
  like the swipe and comes back in the snackbar as "saltada" with the same undo. Not offered for
  a ring waiting for an answer: that answer is "hecho". The hero got the pause pill every other
  card had in the same release; it was the one card that could only be paused through this menu.
- **The month's name is a door (0.52.0).** `MonthCalendar` paged one month per swipe with
  nothing between that and typing, so a date a year out was twelve swipes. Tapped, the grid
  gives way to the year and its twelve months (`MonthJump`, stepped within `MonthGrid`'s pages)
  and a month tapped there turns the pager to it.
- **The three "cuándo" chips everybody starts with stay (0.51.0).** `QuickWhenRow` used to
  *replace* "en 30 min / esta noche / mañana por la mañana" with the shapes learnt from history
  the moment there was any, so "esta noche" was gone for good after the first reminder ever
  saved. They are merged now — suggestions first, then whichever starters none of them already
  amounts to (`shapeOf`, made public for it), six at most.
- **A held stepper keeps counting (0.51.0).** A step is one unit on purpose (three minutes has
  to be sayable, and the countdown sheet says why), and the price was seventeen taps from thirty
  to forty-seven. `Stepper` repeats after `Motion.holdRepeatDelay`, quickening to
  `holdRepeatFloor`, and swallows the click Material fires on release when the hold has already
  stepped; every repeat ticks like a tap so the thumb can count.
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
- **A set of rules is drawn as a set** (`RuleTree`, `ui/components/`): a root carrying the
  reading's glyph, and a branch per rule hanging off a trunk. Three rows used to be three rows
  with a small grey word over them — and no word at all on the commonest reading — so "al llegar
  a casa" and "a las nueve" on one card read as a list, and a list reads as an OR whatever it
  means. The trunk is **dashed for "cualquiera"**, where the rows are alternatives and any one of
  them is the whole thing, and **solid for "todos" and "a la vez"**, where none of them means
  anything alone; the glyph separates those two — a list being ticked off for the one that
  accumulates over days, two overlapping circles for the one that has to be true at a single
  instant. The word is still only written for those two: "cualquiera" is what a list already
  looks like, and the glyph says it to a screen reader. Never drawn for one rule, which is not a
  set. `RuleTreeTest` renders the three side by side (`docs/screenshots/rule-trees.png`), for the
  same reason the standing marks have a test of their own: a dashed line and a 16dp glyph cannot
  be judged from the code.
- A card shows one row per rule, and — when the recurrence works out its own moments
  (`Calendar`, `After`, `MonthlyWeekday`) — a row for that too, last, because that is the order
  the two answer in. It is the only way a reminder whose whole arrangement is "cada 6 h" or
  "todos los lunes a las 9" says anything about when it rings: it carries no trigger at all, so
  without it the card was blank. Its second line says the part people get wrong — that the clock
  starts at the "hecho" and not at the ring, or that a calendar keeps its own dates — and a
  calendar's fences read under it exactly as a rule's do. `ByTrigger` gets no row: the random
  window above it already IS that answer.
- **The corner where things go wrong** (`Header`): beside the magnifier and the history there is
  a bug, and one tap puts the whole diagnostic report on the clipboard with a line to say so. It
  has always existed three screens deep in the settings, which is the wrong depth for the thing
  somebody reaches for at the exact moment the app has just done something inexplicable — by the
  time they have found it, the log has moved on. Built in `RwilcoApp` and not in Home's
  ViewModel: a report is a snapshot of the whole app, and that ViewModel knows about none of it.
- **The undo lives at the top of the screen**, which is the only part of it nothing else wants.
  At the bottom it landed squarely on "Nuevo" — the one button every screen puts in the thumb's
  way on purpose — so the price of being told a reminder was deleted was not being able to write
  the next one for five seconds. An undo is a thing to read; the reaching is the button under it.
  Pause and resume get one too (0.45.0): a paused card goes grey and slides to the bottom of
  the list, which from the middle of a scroll is a card that vanished, and the hold that did it
  was the same gesture as a pause on a different card.
  **Everything that can be deleted has one now (0.51.0).** Four rules governed the app's
  destructive acts — hold-to-confirm, snackbar-undo, a dialog, and nothing at all — and the
  last covered a preset, a saved place or window, a recurrence preset, a restore from the done
  list, the two logs and the curation panel. The rule is one: **an undo where there is an
  inverse, a question where there is not.** A preset comes back through
  `EditorEvent.PresetDeleted` and `RwilcoApp`'s host, because the editor's scope dies with the
  screen; a place or window goes back at the index it left (`restorePlace`/`restoreWindow`); a
  restored done reminder goes back *as the row it was* (`repository.restore`), not as one done
  now. Emptying a log asks (`ClearDialog`), and so does removing a tag from every reminder or
  hiding a phrase — there is no row to put back from a rewrite of forty rows, and a hidden
  phrase has no door back through. A rename asks nothing: rename it back.
- Home: `HomeViewModel` combines the open reminders, settings, the tag filter and a minute pulse
  into `HomeUiState` (`buildHomeState`, pure and tested). The hero card's countdown ticks in its
  own composable (`rememberNow`) so nothing else recomposes. The magnifier has a flow of its own
  (`buildSearchState`, also pure): a keystroke must not put Home through grouping and next-fire
  again. Results replace the list while it is open; a reminder opens, a tag becomes the filter.
  **Before the first emission Home draws card shapes** (`ListPlaceholder`, static — nothing on
  Home moves on its own), never the empty state: `HomeUiState.loaded` is what separates "nothing
  to remember" from "not read yet", and a blank screen for the two seconds a cold start takes
  said the first. The empty state carries the invitation as a button ("Escribe uno"), the same
  door as "Nuevo". "Hechos" wears the same placeholder before its list arrives.
- **"Hechos" opens with a number and a fortnight**, not with a list: how many were dealt with in
  the last seven days in `displayLarge` — the one Material role this app sets in JetBrains Mono,
  the size a number is read at when it is the only thing being said — over `DayBars`, one bar per
  day for a fortnight (`doneByDay`, pure and tested, counting by the same `finishedAt()` the
  bands do). A list of what got done answers "did I do it?"; this answers "how is it going?",
  which is the question somebody opens that screen with and which no amount of scrolling was
  going to answer — it was a title, two cards and half a phone of nothing. The bars are ink and
  never amber, an empty day is a dash on the floor rather than a gap, and the scale has a floor
  of three so a week with one "hecho" in it does not draw a full-height bar about a Tuesday.
- The chip row is a `TagFilter` (`core-model/TagFilter.kt`), not a string: `Named` for a tag
  somebody typed, and three the app keeps for itself — `Untagged`, `Paused` and `Place`. Those
  three are not tags (never stored on a reminder, never suggested, never edited) and they appear
  **only while they have something in them**, at the end of the row, because "sin etiqueta" is a
  job to do rather than a filing category and a row that always ends in chips nobody can act on
  is a row people stop reading at the third one. `Place` is the one the app works out best: a
  place is the thing people write most and remember least — *¿qué tenía puesto para cuando
  llegue?* — and it is **the pin and no word at all**, in the place family's own green. A pin is
  what a place looks like everywhere else here, and that hue is one no tag can have (the tag
  circle has the three family hues cut out of it), so a wordless chip cannot be read as
  somebody's word for something. Only a *trigger* counts: a place used as a fence ("a las nueve,
  y sólo si estoy en casa") is a reminder about nine o'clock, and filing it under the places
  would answer a question nobody asked.
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
  back whole, and a bare date has nothing to reuse. A repeating time is deliberately not offered
  back either: no tile opens one any more, and a chip that opens nothing is worse than a chip
  that is not there. Settings can also let `triggerKindsByUse`
  sort the five tiles, which is a favourite nobody has to keep choosing. A stored favourite that
  is no longer a tile (`DATE_TIME`, `REPEAT_TIME`) falls back to the date rather than opening
  nothing: the enum keeps every name it ever had, because one that loses a name loses the whole
  settings file with it. Each row shows
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
  "y sólo si" fences and `CalendarSheet`, which belongs to "Vuelve" rather than to the tiles and
  is where the whole calendar is built (how often, what inside that, when in the day, from when,
  until when, read back in the words the card will use); the countdown sheet produces an
  `AtDateTime`; the place
  sheet offers the places kept by name in Settings (`AppSettings.savedPlaces`, managed by
  `SavedPlacesCard` through the same sheet without the arriving/leaving choice) as one-tap
  chips, searches addresses through the platform `Geocoder` (`PlaceSearch.kt`), and asks every
  enabled provider at once for a fix (`CurrentLocation.kt`: fine *or* coarse is enough, the
  freshest last-known answers instantly, and nothing is refused because GPS alone had nothing
  to say indoors) and shows an
  osmdroid map (`OsmMap.kt`: pin by long-press, by search result or from one `LocationManager`
  fix, a crosshair button to centre on where you are, radius circle, inverted tiles on the dark
  scheme, tile cache in `cacheDir`). **A new place asks the phone where it is as it opens** —
  only when the permission is already given, because a sheet that arrives under a system dialog
  is a worse first second than a map with a crosshair on it, and that quiet fix loses to any pin
  chosen while it was in flight and says nothing when it fails. **And the map opens on a circle
  that fits in it** (`zoomFittingCircle`): the zoom is worked out from the 260dp the map is tall
  and the circle it has to hold — never tighter than a 300-metre one, because the radius starts
  at 200 and the first thing anybody does is drag it. The four-bucket table it replaces was too
  close in at every bucket: 800 metres of circle in 476 metres of view, with the thing the sheet
  is *for* running off the top and the bottom. **The map is a share of the window now, and
  opens full screen (0.52.0).** 260dp was a letterbox on a tall phone; it is `Sizes.mapShare` of
  the window's height with 260dp as the floor, `OsmMap` takes its height from the caller and
  `zoomFittingCircle` follows it, and a button in the map's corner opens `FullScreenMap` — the
  same pin, the same long-press, the same radius (`RadiusControl`, shared) — for looking rather
  than aiming: panning out along a street to find the corner is what a map in a sheet cannot
  do. The dialog holds no state of its own; "Listo" only closes the door. **And a place made
  here can be kept** ("Guardar como lugar", `onKeepPlace` → `EditorViewModel.keepPlace`, which
  replaces a namesake because the chips are read by name): a saved place could only ever be
  made in Settings, and the condition sheet — which offers nothing but saved places — was
  unreachable until somebody had been there. `ConditionSheet` offers the place kind always now,
  with "Nuevo lugar" opening the place sheet in its Settings shape over itself, and tracks its
  choice by name rather than index so the chip that lights is the one just made. The countdown
  sheet produces a `Trigger.Countdown` (the note above saying `AtDateTime` was stale). The alert
  preview is `AlertScreen`, the same composable `AlertActivity` hosts under a full-screen intent.
- **On the alert screen the words are what gives** (0.48.1). They sat between two weighted
  spacers, which centres them and lets everything below overflow: seven snooze offers and a
  six-line reminder at a large font scale pushed "Hecho" — the one answer the screen is asking
  for — off the bottom of a ringing alarm, with no scroll to reach it. The words and tags are one
  weighted, centred block now, so they are measured with whatever is left once the buttons have
  had theirs, and the auto-sizing text steps down into it.
- **The time picker's AM/PM is its own state** (0.48.1, `afternoonAfterTyping` in `core-model`):
  read off the hour, it moved with every keystroke, so typing 1-3-0 for half past one passed
  through "13" — which reads as the afternoon, and 01:30 came out as 13:30 with a PM button
  nobody had pressed. A keystroke is not a press; only once the minutes are being typed is the
  hour settled enough to speak for itself, and then "1730" is the evening whichever button was
  lit. "Ahora" and "En 15 min" read the app's own clock (`LocalClock`) rather than the system
  default zone.
- Six ways into the editor (`EditorViewModel.init`): an existing reminder, a preset being edited,
  a new reminder wearing a preset's shape, a copy of another reminder, that copy kept as a
  preset, and a blank one.
- **The draft reads itself back over the button that saves it** (`ReminderSentence`, and
  `sentenceParts` beside it — pure, so the shape of the sentence is JVM-tested and the wording is
  the composable's job). The form is five cards down a scrolling column: by the time somebody
  reaches "Guardar" the words are three screens up, and what is about to be saved has to be
  assembled in the head out of four separate places. The line says it in one sentence — the
  words, the rules with the reading's own word between them ("o", "y", "y, a la vez,"), each
  rule's fences, and the recurrence last — with **each piece in the colour of what it is**, the
  same family code the keycaps use. Every "when" has a **clause of its own** for this
  (`triggerPhrase`, beside `triggerLine` and deliberately not a flag on it): a row is two halves
  laid one over the other, and folding those gives "Casa mientras no estoy", which is a label
  with a space in it. Prose puts the preposition where speech puts it — *mientras no estoy en
  Casa*, *durante la franja 18:30–20:00 laborables*, *al llegar a la oficina* — and every phrase
  carries its own, so it drops in after the words and after "o"/"y" with nothing to patch around
  it. The fences read the same way (`conditionPhrase`): "sólo" said once, then clauses joined
  with the same "y" the rules use. `SentenceTest` renders every shape
  (`docs/screenshots/sentences.png`), because a phrase is right or wrong by ear and no assertion
  ever caught "Casa mientras no estoy". It stays off the screen until there is
  something to say beyond the words themselves (`saysMoreThanWords`), which is also what keeps it
  from sitting under the keyboard while somebody types into a blank draft.
- **And under the sentence, what it comes to** (`UpcomingLine`, 0.46.0): "Suena mañana 09:00 ·
  luego vie 09:00 · luego sáb 09:00", worked out by `upcomingMoments` (`core-model/Upcoming.kt`,
  tested) — `nextFire`, then the same question again with that moment spent the way the firing
  spends it (`lastFiredAt`), up to three times. The sentence says what was asked for; this says
  what will happen, which for a rule with fences and a recurrence behind it is the only way to
  check the arrangement without saving it and waiting. The walk stops where the next moment is
  not the model's to know: after a random draw (the window is shown, never the draw), after a
  snooze, after the ring of an "all of them" set, and a span from the "hecho" stops on its own
  because nothing has been dealt with. A place is no moment at all and a list starting with one
  says nothing. The first moment is amber, because that is exactly what amber means.
- **A refused save says so** (0.46.0): "Guardar" on a draft that cannot be saved used to set
  `showErrors` and stop, and the only sign was a red line under a field three cards up — a button
  that does nothing looks broken, not refused. `EditorEvent.Invalid` carries the first error to a
  snackbar (the words, too long, a rule, the calendar), and for the words it also scrolls to the
  top and puts the cursor in the field (`focusKey` on `TextSection`, the one other thing besides
  a preset that opens the keyboard by itself).
- **The date sheet has chips again** (`DateShortcut`, 0.46.0): hoy, mañana, el lunes que viene,
  este finde. The first set was taken out because each also picked the hour; these touch the date
  alone (`WhenInTheDay` is untouched) and the grid turns to the day they name
  (`MonthCalendar` follows `selected` across pages). The weekend is the calendar's Saturday, on
  purpose not `Snooze.WEEKEND`'s Friday evening: that one answers an alarm, this one is a day.
- **The time wheel has a keypad behind a toggle** (`TypedTime` in `TimeWheel.kt`, 0.46.0): a time
  somebody knows to the minute is two flicks and a correction on a wheel and four digits on a
  keypad. The digits are read the way the time is said (`parseTypedTime`, `core-model`: "7",
  "930", "1730"; on a 12-hour phone AM/PM decides and an hour past twelve is taken at its
  word), the reading follows them live, a typo leaves the last good time where it was and
  disables "Hecho". "Ahora" and "En 15 min" sit under both, read off the phone's clock in the
  dialog — a moment of the screen, not of the model.

## Firing

- `ReminderScheduler` keeps one `setAlarmClock` armed per reminder — the only kind of alarm Doze
  never defers and the rate limiter never holds back — and writes the armed moment back to the
  row. **One pass at a time** (a mutex): passes come from six doors, and two side by side could
  read a row either side of an edit and let the older reading write last — the row and the alarm
  then named a moment somebody had just edited away. Nothing that holds another lock calls back
  into it, so there is no order to get wrong. **The clock's zone is read live**
  (`SystemZoneClock`): `Clock.systemDefaultZone()` copies the zone once, and a process the place
  watch keeps alive for days re-armed every wall-clock moment in the zone it started in, after a
  `TIMEZONE_CHANGED` that had already reached it.
  That, next to `lastFiredAt`, is what makes a firing the phone slept through detectable:
  an armed moment in the past with no ring to match it (`missedFire` — and no answer either: a
  "hecho" or a "posponer" given after the moment, from the card, is an answer to it). **A pass
  holds a missed moment; it never moves it on.** `nextWake` only answers with a moment still
  ahead, so a pass that wrote it back over one that had come and not yet rung was spending
  that moment: two reminders due at nine, the first one's ring re-arming everything while the
  second's broadcast was on its way, and the second arriving to a row saying "nothing armed" and
  dropped as a stray — a day skipped in silence, or a one-shot that never rang. The row and its
  alarm are left as they stand for the delivery in flight, or the next catch-up, to ring. What
  spends a moment is the ring, a judgement in `fire` that dropped it (which writes `armedFor` off
  itself, `spendArmed`, or it would be held and re-judged for ever), a "hecho", a "posponer" or an
  edit. `Simulation.arm` holds the same way, and `HeldMomentTest` pins it. What a change has to touch before the
  whole list is worked out again is `schedulingKey` — the rules, the match, what is ticked off,
  the snooze, the recurrence and the moment it counts from — and deliberately not what the
  scheduler itself writes back, or every re-arm would come round as a change and arm everything
  again. `lastDealtAt` is in it for the undo: taking a "hecho" back puts the whole row as it
  was, and on a reminder that stayed ACTIVE either side of it nothing else in the key moves.
  The settings have a key of their own (`settingsKey`): the default hour, the day's start, the
  shape of the day and the safety net's numbers — the last because the net's word is an alarm
  too, and a net re-tuned in Settings used to stay armed on the old numbers until something
  else re-armed. Under ALL only the earliest pending moment is armed, so a phone off across two of them wakes
  owing both and only one is detectably missed; `owedUnderAll` (pure) lists the one-shot
  moments after the missed one that have since passed, and `rearmAndCatchUp` fires them in
  turn, so the set completes late rather than never.
- **A "hecho" deals with whatever is owed** (`momentDealtWith`, pure). Usually that is the firing
  waiting for an answer, and then it spends nothing else. When nothing is waiting, what is being
  dealt with is the moment that was *coming*: a daily at two o'clock ticked off this morning
  means tomorrow's two o'clock is done, the day after is what is next, and ticking it off again
  sends it on another day. The moment goes into `Reminder.dealtThrough` (Room v7), which
  `searchFrom` reads like a ring and `recurrenceAnchor` counts a span from — so "cada día" moves
  a day per "hecho" rather than a day per afternoon somebody happened to tick it off in. It is
  kept apart from `lastFiredAt` on purpose: that one means "it rang", which is what tells a
  firing the phone slept through from one that was answered, and a moment that never rang has no
  business in it. A place answers null: nothing about arriving somewhere can be done in advance.
  **What the reminder becomes is asked of the row as the "hecho" leaves it** — the anchor
  stamped, the moment spent — and not of the row one write behind: without the anchor a
  calendar beside a date already gone had no rest to hand its rule, the date was spent, and "el
  26 a las 20:00, y vuelve cada mes" was filed DONE by the first "hecho" it ever got. The
  mirror holds too (`statusAfterDismissal`): a calendar with no date left finishes the rules
  with it, because a series that has ended has no rest to hand them and, left ACTIVE, a daily
  window beside it spoke again on its own, every day, unfenced. **And a "hecho" only does the
  round that is coming**: with a recurrence on the reminder the moment it may spend is one
  inside the next step, counted from now or from what was already done ahead (which is what
  makes a second "hecho" do the next round). "Al llegar a casa, o el 31 de diciembre" with
  "cada día", swiped done in August, used to spend the 31st of December — the anchor then
  counted a day from *that*, and the place was neither watched nor armed until January.
- `ReminderFiring` is the single place that decides what a firing, a "Hecho" and a snooze do, so
  the alarm, the notification buttons, the alert screen **and Home's swipe** cannot drift apart.
  Every answer it gives is written down before the notification comes down, and its settings read
  is caught and bounded (`settings()`): the other way round, a store that would not answer took
  the whole answer with it — a "posponer" that only cleared the shade, leaving the reminder
  counting down to its own next moment as if nobody had said anything.
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
  opening on the same second read each other's writes instead of both ringing. **"Hecho" is one
  write** (`ReminderDao.dealtWith`: the snooze, the round, the anchor and the status together),
  because four writes could be cut in two by a process dying, and a round closed with its anchor
  unmoved is a reminder that never comes back. **A place fence on a catch-up is asked about the
  moment, not about now** (`Fix.speaksFor`): "a las nueve, y sólo si estoy en casa", slept
  through and caught up at noon from the office, is a question about nine o'clock, a fix from
  noon does not answer it, and what nobody can vouch for holds.
  **An anchored recurrence on a reminder with rules is a rest, not a ring.** Dealt with, the
  rules say nothing until the span is up (`Reminder.restUntil`, counted from `lastDealtAt`):
  nothing is armed, no place is watched, a crossing is written down but does not ring. From
  then they speak again — a place is watched again and rings on a *fresh* arrival, a clock
  finds its first moment past the rest. Only when no rule has anything left to say (a date
  that has been, a countdown that ran out) does the recurrence's own moment ring, which is what
  "a las ocho, y luego cada seis horas" means; and rung and ignored, that moment is spent like
  any other (`recurrenceMoment`). With no rules at all the recurrence is the whole arrangement
  and its moment is always the ring.
- **The safety net** (`core-model/SafetyNet.kt`) is the one thing the app does about a reminder
  that got away. **There are two ways one does** (`NetWord`), and one switch for both, because
  nobody knows in advance which it will be: it **rang and was never answered** (`LET_GO`), or it
  **never rang at all** (`NEVER_RANG`) — the moment came while a fence was shut, or while the
  other half of an "a la vez" was false, and there is no moment left for it to ring at
  (`nextFire == null`, never rung, never dealt with). The second one is anchored on
  `lastMomentGone`: the last moment the shape named that came and went, walked forwards from the
  day it was written, because forwards is the only direction any of this arithmetic goes — and
  only ever asked of a reminder with nothing left ahead, which is why the walk is short.
  **It is not asked for.** It began as a switch on each reminder, which was a switch about the
  one thing nobody can answer in advance — which of your reminders is going to be the one that
  gets away — and anybody who could answer it would not need a net. So it holds for every
  reminder; the switch, the red mark on the card and the chip that filtered by it are all gone,
  and what is left to decide is only how long it waits. Said **once per moment**
  (`Reminder.nudgedAt`) and **at any hour** — a silent word on a low channel wakes nobody, so
  holding it back until morning would only make it later without making it quieter — quietly:
  `CHANNEL_NET` is
  `IMPORTANCE_LOW`, silent, still, never a screen and never pinned — the same card with the same
  three buttons — "hecho" and the two snoozes the settings chose — on a line that says *puede
  que se te haya pasado* and a clock counting up from
  the ring. What it waits for is `nudgeAt`: the whole wait (`afterHours`, a day) when the
  reminder has nothing left to ring, and otherwise a **tenth** (`fraction`) of the gap to its
  next ring, whichever is shorter — the point being to catch it before the next one buries it.
  Under `minCadenceMinutes` (an hour) it cannot be armed at all: there the next ring already is
  the net. The gap is `ringCadence`, asked of the *shape* rather than of the row (nothing
  rung, dealt with or done ahead) — a six-hourly
  reminder that rang and was ignored has no next moment of its own (an anchored recurrence
  counts from the "hecho") and its rhythm is six hours all the same. It keeps **its own alarm**,
  on its own `PendingIntent` (`nudgeUri`), deliberately not touching `armedFor`: that column
  means "a firing is owed here", and a net's moment recorded in it would have the catch-up ring
  the reminder rather than whisper about it. And that alarm answers to `nudgeAt` and to nothing
  else: a reminder with nothing left to ring loses its *ring* alarm (`cancelRing`) and keeps the
  net's, because "nothing left to ring" is exactly the reminder the net has a word for — it rang
  and was let go, or its moment came while a fence was shut. Until 0.41.0 the two were cancelled
  together, three lines after the net was armed, so the word was never said about the reminders
  it existed for and only survived on the ones with a next ring — where the next ring already
  is the net. Inexact (`setAndAllowWhileIdle`), because a word a
  quarter of an hour late is the same word and the exact kind would announce the quietest thing
  the app does in the loudest surface the phone has. Everything is asked again at fire time
  (`ReminderFiring.nudge`): a day is long, and dealt with, paused, put off and rung again all
  happen inside it. The three numbers live in Settings, under their own group, which is also
  where the rule is written down — and one line at the foot of the editor (`SafetyNetNote`) says
  what they come to for the reminder being written, because "un aviso discreto 36 min después"
  is a number somebody can agree or disagree with and "una décima parte de la cadencia" is a
  rule they would have to work out.
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
- **What the shade actually shows** (`AlertNotifications`): the reminder's words as the title,
  **why it rang** on the line under them, the tags beside the app's name, three actions
  ("Hecho", ten minutes, two hours — three is what a notification shows), the amber on the glyph
  and the app-name line (`AMBER_ARGB`, the one place that cannot ask a Compose theme for a colour
  because it is built in a receiver), and the time. A missed one counts *up* from the moment it
  should have rung — "this should have reached you an hour and ten minutes ago" is the whole
  point of that notification, and a bare timestamp makes somebody work it out. One asked to
  insist (`SOUND_UNTIL_ANSWERED`) is `setOngoing`, so the half-asleep swipe that clears the shade
  cannot take it; everything else stays swipeable, because most reminders are read and let go.
- **The line under the title is why it arrived, not the title again.** It was the reminder's own
  words in the title and the same words underneath — one sentence twice, and the second one
  carrying nothing at the moment somebody most needs telling *why their phone just went off*. It
  is now the sentence the editor shows over its save button, minus the words themselves
  (`reminderSummary`): "al llegar a Casa", "a las 18:30 cualquier día, y vuelve cada semana",
  the rules joined by the word their reading joins them with and the fences said as "sólo …".
  The tags move to the sub-text, beside the app's name, which is where a label belongs — and
  give that place up to the net's word or a missed ring's, which is about *this* arrival rather
  than about the reminder. A reminder with no rules at all leaves the line off rather than
  printing a blank one.
  **Which is why the phrasing stopped being a Compose thing.** `triggerPhrase`, `conditionPhrase`
  and `recurrenceLabel` read their strings out of the composition, which is fine while the only
  reader is a screen and impossible from a receiver — and two functions saying the same sentence
  drift, which on a notification means the shade quietly disagreeing with the form about what a
  rule means. So the three things composition was being asked for (the strings, the language, the
  clock's shape) are carried in `Words` instead: a screen fills it from composition
  (`rememberWords`), anything else from a `Context` (`Context.words()`), and there is one
  function per sentence. `recurrenceLabel` and its two helpers moved out of `RecurrenceSection`
  into `ui/format` at the same time, beside the trigger wording they belong with.

  All of them join one bundle with a summary line, and **the summary comes down only when there
  is nothing left**: cancelling a group's summary cancels its surviving children too, so pulling
  it at "fewer than two" — the reading that sounds right — cleared the shade of an alert somebody
  still had to deal with. `NotificationBundleTest` found that and holds the door shut. **How many
  are left is counted from what the call just did** (`bundleChildren`), not from what the system
  answers: cancelling is handed to a thread of its own and is not done when the call returns, so
  asking straight afterwards could still be told about the notification on its way out — and the
  summary posted for it then stayed behind alone, an empty line reading "1 recordatorio" over
  nothing, to be swiped away by hand. There is
  nothing above `IMPORTANCE_HIGH` + `PRIORITY_HIGH` + `CATEGORY_ALARM` for an ordinary
  notification; anything more prominent than this means `MessagingStyle` conversations or
  `CallStyle`, which are a different kind of thing to be.
- **A chosen sound is copied into the app's own storage** (`SoundStore`). A picked file belongs
  to whoever picked it: it can be deleted, moved, emptied out of a downloads folder, or live in
  an app that gets uninstalled, and the persistable permission goes with any of those. An alarm
  whose tone quietly stops existing is the failure this app exists not to have, so the file is
  copied in the moment it is chosen and the reminder points at the copy from then on. It is
  handed out through a `FileProvider` and not as a bare path, because a channel's tone is played
  by **the system**, which cannot read anything inside an app's own files; the read is granted to
  `com.android.systemui` and `android` every time the channels are ensured, since a grant does
  not survive a reboot. `SoundStoreTest` checks that grant against systemui's real uid, because
  a channel the system cannot open is silent and silence is the one failure worse than a wrong
  tone. The channel id already hashes the Uri, so adopting a sound makes a new channel with the
  new tone rather than reusing one whose sound is frozen.
- **At launch and after a restore the two tones are settled** (`RwilcoApplication.settleSounds`):
  one of our copies is kept, somebody else's is adopted while it can still be read — which is
  what carries a sound chosen before any of this existed — and anything that cannot be opened at
  all goes back to the phone's own alarm. A vault from another phone names files that were never
  on this one, and the honest answer to that is the default tone, not silence and not a broken
  setting. It is written back only when something actually changed, because a settings write
  re-encodes the whole blob and a restored one from a newer build carries fields this build has
  no words for. Copies nothing points at are then dropped; the one thing that costs is undoing a
  restore that had changed the sound, which lands on the default tone like any missing one.
- **Two tones, split by what the reminder was asked to do** (`AppSettings.soundFor`): "sonido"
  says it once, "hasta que reciba caso" comes back every few minutes until somebody answers, and
  a tone that is right for the first is often wrong for the second — you are going to hear the
  second one five times. `insistentSound` is null until somebody draws that distinction, and null
  means "the same one": a default of its own would have quietly changed what half of everybody's
  reminders sound like on the update, which is the one thing an alarm may never do. The switch
  that turns it on starts from whatever is already chosen, for the same reason. The alert screen
  can be carrying several reminders at once and takes the insistent tone if any of them asks for
  it. The channel id already carries the tone, so a second one is simply a second channel.
  **The choice is offered whether or not anything is asking for it yet**: the switch sat inside
  the fold that hides the round's two numbers (how many times, how far apart — those do only
  mean something to a reminder that has asked for one, and they stay there), so with nothing
  insistent written yet the row was simply absent, and somebody who went to Settings to choose
  that tone found no such setting. A preference that appears only after you have written the
  reminder is, to anybody looking for it, a preference the app does not have.
- `AlertNotifications` has one channel per sound/vibration combination, because a channel's
  sound is fixed the moment it is created — which is also how the vibration setting reaches a
  notification: the chosen rhythm is part of the channel id, so changing it means a different
  channel rather than an edit Android would ignore. Only the rhythm; a channel's pattern is
  durations and nothing else, with no way to say how hard, so a gentle notification and a strong
  one are the same notification. A full-screen alert's notification stays silent: the
  screen does its own ring (`AlertRinger`) — **round and round only if that is what was asked
  for** (`loopsOnScreen`): "sonido" says the tone once here as it does anywhere else, and the
  loop belongs to "hasta que reciba caso" alone. It looped whatever it was given until 0.36.1,
  which made the two tiles the same thing on the one surface where the difference is loudest —
  a reminder asked to say it once said it over and over for a minute. It gives up **when the
  buzz does** — one minute,
  `VibrationLimits.LONGEST` — and so does its hold on the screen (`FLAG_KEEP_SCREEN_ON` is
  cleared with the noise). The two are one alarm, and they used to end a minute apart: the motor
  stopped at its limit and the looping tone went on alone. Nobody answered in
  a minute because nobody is there, and a display lit at full brightness until somebody comes
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
  (`AlertStackScreen`, each with its own "Hecho"). **Under the strips, one answer for all of
  them (0.52.0)**: "Hecho con todos" is a `HoldButton` — it is three in the morning and five
  reminders are gone on release — and "Posponer todos" unfolds the strip's two offers once for
  everyone; `AlertActivity.answerAll` empties the screen first and answers each in turn through
  the same `ReminderFiring` doors. The hold ring draws in the screen's own `HoldOverlay`, since
  the app's host is not under an alert. Every reminder on the screen is watched in
  the database and leaves when it stops being `awaitingAnswer` (pure, `Firing.kt`) — so
  "Hecho" from the shade takes it down here too — and the ring's minute starts
  over for each arrival.
- `GeofenceManager` registers the place rules with Play Services, wholesale, and re-registers on
  boot and from `RearmWorker` (a reboot or a Play Services update drops them all) — and on the
  spot when Play Services says it has dropped them (`GeofenceReceiver`, an event with
  `GEOFENCE_NOT_AVAILABLE`: location switched off, the network provider gone), which used to go
  to the log and nowhere else, leaving the places blind until the six-hourly pass.
  **Wholesale, but no longer on every process start (0.53.0).** `sync()` runs from
  `Application.onCreate`, and the place watch's own alarm starts the process every few minutes
  to an hour on a phone that kills it — so the fences were torn down and put back at the
  watch's cadence, and a crossing in the gap between the remove and the add was a crossing
  nobody saw. A sync first works out what it *would* register (`geofenceFingerprint`, pure: the
  ids, which carry their circles and which way they are waited on, plus whether the grant is
  there) and compares it with what it last did (`GeofenceStore`, its own file); the same
  answer leaves Play Services alone and says `fences=n unchanged` in the log. The memory is of
  the *outcome* — written once the fences are known to be in, cleared when Play refused, so a
  refusal is asked again. `RearmWorker` alone forces a fresh registration, because it is the one
  caller that runs *because* the system's copy may be gone: boot, an update, `NOT_AVAILABLE`,
  the six-hourly net. The remove
  before each registration is awaited: both go through the same `PendingIntent`, and a remove
  completing after the add took every fence just registered with it. That is the
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
  a place asked for as a side of a line is being on it, a `Trigger.Interval` ("de 17 a 19") is
  being in its window, and everything else — a place asked for as a **doorway** included — is a
  **moment**, true at an instant and false either side. So it
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
  (`knownInAdvance`), and `ReminderFiring` asks it for real when the alarm goes off — **from the
  watch's memory of which side of that circle the phone is on** (`PlaceWatchState.sideOf`),
  matched on the circle's geometry, because the same doorway is watched under a different id by
  every rule that names it and a condition carries the place with no id to look it up by.
  Measuring the last raw fix against the circle instead — which is what it did — is a second and
  worse opinion about a question that map already answers: it knows nothing of the system's
  geofences (a crossing writes straight into it, with no fix of its own) and it has to resolve
  its own doubt, which `holdsAt` does towards *yes* whenever the fix is sloppier than the circle.
  Fifty metres is the tightest circle the app allows and is smaller than an ordinary network fix
  is accurate, so on exactly the circles somebody draws when they mean "at home and not next
  door" the fence was never a fence: a "a la vez" set rang in the street twenty minutes after
  the phone's own geofences had recorded it leaving. `TogetherPlaceFiringTest` walks that
  evening. The house rule is untouched — a circle nobody has judged still holds, and the memory
  counts only while a fix still speaks for now; past the speed memory it is old news like
  everything else, and no answer means the condition holds. `warnings()` says what can be said
  before somebody waits a week to find out: a rule whose moments never meet its own hours
  (`NeverFires`, which is just `nextFireOfRule` giving up), circles that cannot both be true
  (`PlacesConflict`), either of those under ALL taking the whole reminder down with it
  (`NeverCompletes`), and a bare place rule beside a bare clock rule under ALL, which is legal
  and usually meant as one conditioned rule (`BetterAsCondition`). None of it blocks saving.
  **It is asked with the id the draft will be saved under**, which is minted when the editor
  opens (`EditorViewModel.draftId`) rather than at the save: a random window's moments are drawn
  from that id, so read off any other seed `NeverFires` is a coin flip about a different
  reminder — said of one that rings, unsaid of one that never will. The same reason a countdown
  is stamped and a day left to the day is narrowed where the reminder is written. (The
  calendar's own `recurrenceWarning` needs no id: a calendar with no hour draws *inside* its
  fences, so whether any minute clears them is the same answer on every seed.)
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
  walks, so the watch spends nothing on it and sleeps instead of cancelling. The conjunction of
  windows begins where one of them begins, but not necessarily at its *next* beginning: with
  days on the windows ("los sábados de 10 a 12" and "viernes y sábados de 11 a 13") the first
  opening both allow is Saturday at eleven, which is neither window's next opening — so each
  window's next sixteen openings are probed, and a null is a set whose hours never meet at all. It wakes
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
  **A state has had its say only if the ring was its own** (`Reminder.lastFiredRule`, Room
  v8): under "cualquiera" a clock sibling ringing at nine and going unanswered used to count as
  the place having spoken, and the arrival at six was dropped for a ring that belonged to a
  different rule. The column is which rule `lastFiredAt` rang for; null — a snooze, a
  recurrence's own moment, every row written before it — is read as it always was.
  **A state that has already had its say is not watched at all.** "Mientras esté en casa" rings
  once a round (`presenceAlreadyRang`), so once it has rung every firing of that rule is dropped
  until somebody deals with it — and a circle that can only produce dropped firings is a radio
  spent to learn nothing. On a real phone it was the one circle with no gate whatsoever: a
  single-rule set has no window to close it and a recurrence cannot rest a reminder nobody has
  dealt with, so it bought a fix every few minutes, GPS included. Only when it is the reminder's
  *only* rule, though: with siblings the circle is still wanted, because under "a la vez" it is
  folded into every other rule as a state and those rules are not spent.
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
  exact only for a look under a quarter of an hour away — above that the exactness buys nothing
  Doze was going to honour anyway, and an inexact alarm is one the system may batch with
  everybody else's) it reads one fix from the fused provider at one of three tiers (`FixTier`):
  the satellites only when the nearest line is close and the phone moving, the towers alone when
  the line is still ten kilometres off and the only question is "am I still far?", and the
  wifi/cell blend — which is a wifi scan, and the ordinary cost of a look — for everything
  between, and always from inside a place. A coarse fix cannot invent a crossing, which is what
  makes it safe to spend so little on: `insideAfter` refuses a fix sloppier than the circle on
  the way in and keeps the phone where it was on the way out, and `gapToLine` eats the doubt
  before any of it reaches a cadence, so a vague fix asks to be looked at sooner, never later.
  **The cheapest fix is the one already taken.** Before any radio is spent the provider's own
  last position is read — kept warm by whatever else on the phone asks for one, at no cost here —
  and when it answers the question this look was going to ask (`Fix.answersFor`: young next to
  how long the watch has been away from a fresh reading, and its doubt stopping short of the line
  it has to judge) that is the look, and it cost nothing. It is its own kind in the log
  (`NoteKind.CACHE`) and deliberately not a poll, because a saving counted as a poll is a saving
  nobody can see. It hands the fix to
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
  — a new rule, first launch — is judged by the next fix, and what that judgement *means*
  depends on the reading: a **state** that finds itself true says so at once (which is how
  "mientras esté en casa", written at home, rings), while a **doorway** (`onCrossing`) is
  baselined without an event and waits until the watch has seen the phone on the far side. Doubt
  in that first fix leans the same way for both — towards the side the rule is about
  (`insideAfter`) — and it buys the state its ring and the doorway its silence. While a doorway
  waits it costs the least of anything in the app.
  A state says it *once*: the watch reports the moment it becomes true and holds its tongue
  while it stays true, and what stops a second ring after that is the round it already rang in
  (`presenceAlreadyRang`), which dealing with the reminder starts again. That is also why a
  resting circle keeps its baseline only when it waits for a doorway (`Watching.remembered`): a
  state has to be *asked afresh* when a recurrence's rest is over, or "mientras esté en casa, y
  vuelve cada día" would ring once and never again for a phone that never left.
  Both ways of being inside a place are cheap, for different reasons. Waiting for an *arrival*
  from inside is half an hour a look and never GPS: the only thing that can happen indoors is
  going out, and stepping out and back inside that half hour is not arriving either. Waiting for
  a *leaving* from inside is the case the plain answer gets worst — standing inside a place is
  standing next to its line, so "time to the line" would ask for the fastest cadence in the app,
  all evening, for a door nobody walks through — so it starts at half an hour too and buys its
  way down only with evidence (`leavingWait`).
  **The evidence is how much nearer the line the phone got, and for a while it was how much
  ground the phone covered, which is not the same thing and is the more expensive mistake of the
  two.** A life being lived inside a place covers a radius' worth of floor in half an hour
  without once approaching the door, and the old rule read that as most of the way to leaving:
  the wait sat on its five-minute floor from tea time until bed, twelve fixes an hour for a line
  nobody crossed. It is the one thing in this app that ever announced itself on a battery page —
  by way of the app's own busy notice, which is what it is there for. So the measure is
  `closingM`: the change in *this circle's* gap between the last two fixes, deadbanded by their
  own doubt the way `speedBetween` is, and the plan is the ordinary one after all — time to the
  line, at the rate the line is actually being approached, with the usual headroom, clamped
  between five minutes and the half hour. Walking about closes nothing and costs nothing; walking
  to the door shortens the wait on its own. Never GPS either way, and never the towers either:
  inside is exactly where a fix vaguer than the circle has nothing to say.
  What would otherwise be the price of that rest — a leaving noticed up to half an hour late — is
  bought back by the sensor below: it fires as somebody actually walks out, and the look moves to
  five minutes from now (`stirredWait`). Only ever earlier, only within `NEAR_M` of a line (a
  stir three provinces from the only place being watched means nothing), and the sensor's
  one-shot re-arming caps it at one early look per check.
  **And from inside, a stir has to be going somewhere.** Significant motion means the phone's
  location changed, and a kitchen is a change of location, so a phone being carried about its own
  place stirs every few minutes all evening — every one of which used to buy a look at five
  minutes' notice, which is the same twelve fixes an hour coming back through the other door. So
  stirs from inside a circle are counted, and each one that the following look finds on the same
  side of the same line doubles the next one's notice, 5 to 10 to 20 to the half hour the case
  started at; a look that finds a crossing, or any circle changing sides, starts the count again.
  From *outside* nothing is counted: a phone that has settled near a line and then sets off is
  precisely what the sensor is for. The streak lives in the watcher's memory rather than its
  store, for the same reason the planned moment does — the sensor speaks only for the process
  that armed it.
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
  one per look — what it came to (a fix and which tier of radio it woke, a look answered out of
  the cache, a rest, no fix at all, a stir, a crossing, an echo) and every number it decided
  from. `WatchLogScreen`, behind a button in the Location section of Settings, is that list; it
  is a diagnostic screen and reads as one, every figure in the mono face so the rows can be
  compared down the column.
  **And it is written for the person who opens it, not for the person who wrote it.** It was the
  log itself for a while: a kind ("lectura", "eco") and then every number the cadence was argued
  from — metres to the line, speed, what the sensor felt, the still streak, the battery, the
  radio tier — which is a diagnostic trace on the one screen somebody opens to find out whether
  their phone is following them about. All of that is real and all of it is in the diagnostics
  report already (`DiagReport`, "-- place watch --"), which is where a number that needs this
  code open to be understood belongs. So each line here says what happened, in a sentence: *Miró
  dónde estabas · a 210 m de Casa · volvería a mirar en 2 min*, *No hizo falta mirar · ya sabía
  dónde estabas*, *Saliste de Casa*. The wait is said in the tense it is in — it is the plan that
  line made, not a promise being kept now, and on a line from Tuesday the present tense was the
  screen's only lie. Above them, the day in the two numbers that answer the
  question actually being asked (`WatchTally`) — how often it looked, how often it decided not
  to — and which place was nearest, because the cadence is always the nearest circle's ask.
  Two things the raw log was getting wrong on a real phone. A crossing that arrives for a circle
  the watch is no longer spending anything on — one ticked off, one whose hours are shut — has no
  live `WatchedPlace` to read a label from, and fell back to **the geofence id**: a UUID and a
  pin, printed on screen instead of "Club". It now finds the name on the rule itself, and says
  nothing rather than an id when even that is gone.
  Fixing the writing does nothing for a log already written, though, and this one keeps two
  hundred lines — days of somebody's afternoons, ids and all. So there is a guard at the reading
  end too: `GeofenceIds.looksLikeId` knows the shape of one (matched on the `@lat,lng,radius,side`
  tail, because a person may well call a place "Café #1 @ Sol" and nobody calls one
  "@40.50074,-3.66413,150,E"), and `WatchNote.placeName` is what everything that shows a name
  reads. A line whose name is an id has no name, which is what it always meant. And a place named by six rules is six
  geofences, so walking through its door wrote six identical lines; `asEvents` folds a run of
  crossings of the same circle, the same way, inside a minute into the one thing that happened.
  Neither touches the store: the report still has all six, which is where the fact that there
  were six is worth having.
  **Three more the phone found later, all of them the same fault: a line that is true and says
  nothing.** *"El teléfono repitió un aviso de Santiago Bernabéu"* is an echo, and an echo is not
  a crossing — it is Play Services saying again which side of a line the phone is on. So it is
  written as a side and not as a movement (*El teléfono dijo que estabas fuera de Santiago
  Bernabéu · ya estabas fuera antes · no cambió nada*), which needs the claim itself:
  `WatchNote.reported`, because `inside` is what the watch *believed* and on the ordinary echo the
  two agree by definition. They do not agree on the other silence this kind holds — a crossing
  dropped because the app never saw the far side of it (`crossingIsNews(strict = true)`), where
  there is no belief at all — and that one is exactly the answer to *"¿por qué no sonó cuando
  llegué?"*, which is what the screen is for. It says so: *no se te vio salir antes · no sonó nada*.
  **A crossing that came to nothing said so too.** `accept` writes the line either way but acts
  only on a live circle asked for that way round, so *"Saliste de Casa"* read the same whether it
  rang the phone or fell on the floor with the rule's hours shut. `WatchNote.acted` is what came
  of it, decided before the line is written; and it is the one field `asEvents` has to *fold*
  rather than drop, because the six geofences of a place share a circle and not an outcome —
  keeping the head's own answer would print "no sonó nada" over a walk through a door that rang.
  **And the list says which day it is looking at** (`byDay`, `WatchLogScreen`'s day headers: *Hoy*,
  *Ayer*, then `TimeText.dayDate`). A row shows an hour and this log keeps two hundred lines with
  no age on them — four to eight days of a quiet watch, where `DiagLog` expires at seven — so a
  line from last Tuesday at 06:44 read exactly like one from this morning. That is not a small
  thing on the one screen somebody opens to work out what their phone did: it had an entry looking
  like it predated the place it named. A look that spent
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
  knows it is old news. Its `strict` reading — "already rung, so it is owed the far side" — is
  now asked only of a doorway; a state needs none of it, because what stops it ringing twice is
  the round, not the geometry. Anything the watch cannot vouch for — no fix, one older than the speed
  memory, a place never judged — is news, because ringing once too often beats never arriving.
  And what it will not vouch for it does not judge by: a fix older than the speed memory — the
  stale one the provider hands back when nothing fresh answers — is treated as no fix at all,
  because writing this morning's position into `inside` is how a real arrival later gets
  dismissed as a place the app thought you were already in.
  Every note says **which circle** it is about — the geometry, rounded to about a kilometre like
  everything else here, never the label, because a place name is somebody's life and a circle is
  a fact about a bug — and how wide the fix's doubt was. Without those, a page of geofence
  crossings says only that *something* was crossed, and a false ring and a real one look
  identical afterwards; both were learnt chasing a real one.
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
- `SystemEventsReceiver` re-arms after a reboot, an install over ourselves, the clock moving — a
  wall-clock promise is not an instant until a zone says so — and the exact-alarm grant changing
  hands (`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`): on Android 12 and 13 taking
  "Alarms & reminders" away cancels every exact alarm the app had, and given back, nothing else
  would set them again until the app was opened.
- **Nothing on the way to the person is allowed to fail quietly.** Every way `fire` has of
  *not* ringing re-arms before it leaves (the alarm that brought it is spent; a drop that left
  nothing behind was a reminder silent until the six-hourly net); `rearmAll` writes the armed
  moment *before* setting the alarm (an alarm for a past moment arrives at once and read the
  row first); the settings are read — with the defaults as the answer to a read that fails —
  *before* `markFired`, so no moment is spent by an exception nothing showed for; the app scope
  has an exception handler and every collector survives a bad pass, because the collector on
  `repository.open` is the only thing that arms a reminder just saved; the settings DataStore
  replaces a corrupt file instead of throwing on every read (so do the place watch's, the
  watch log's and the vault's); `AlarmReceiver` and `AlertActionReceiver` bound themselves
  under the broadcast budget; `MainActivity.onResume` catches up (guarded to once every few
  minutes), so a timer the phone slept through is said when the app is opened. A catch-up under
  `LATE_IS_MISSED` (15 min) rings as the moment itself; past it, it is the quiet note. From the
  moment a ring is written down to the screen it shows on, `fire` runs `NonCancellable` — the
  receiver's timeout cannot land between `markFired` and the showing and spend a moment nothing
  showed — and whatever the showing throws, the re-arm at the end still runs. The place watch
  counts a look as planned only once its alarm exists (`scheduleAt`): planned first, a set that
  threw left `recover()` standing down for a look that was never coming.
  On the showing side: every alert channel carries **alarm** audio attributes, silent ones
  included, and the live notification is `CATEGORY_ALARM` — which is what lets Do Not Disturb
  tell it from a chat and the ringer switch keep its buzz; with notification-policy access
  granted the channels bypass DND outright (`_dnd` channels). `alertPresentation` asks whether
  the system will honour a full-screen intent at all (Android 14+ can refuse it) and answers
  BANNER on a dark screen when it will not, so the notification makes the noise a screen that
  never came would have made; with the screen on the alert is started *before* the notification
  is posted, and the channel follows whether it took. Notifications switched off make the
  post a no-op, so the screen is tried from anywhere. `AlertRinger` asks for transient audio
  focus that may duck (see the sound bullet above) and buzzes with alarm usage. `AlertPermissionsCard` reads ten states, the five grants
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
  export/import, for any other cloud or none. **And a copy anybody can read (0.52.0)**:
  `readableExport` writes everything as plain text in the app's own sentences
  (`reminderSummary`, the line the notification and the save button say), open ones first and
  done ones after, to a `.txt` or the share sheet. The vault is the copy that survives; this is
  the copy that can be looked at, pasted, and opened in ten years by whatever is around then —
  which a sealed envelope with a key derived on this phone is not. It says it is not sealed.
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
  The **settings blob** is the one part with no element-by-element tolerance of its own — it is
  decoded whole — so anything inside it that an older build cannot name is a reset of every
  setting rather than a loss of one. From 0.49.0 the two places where that can happen are read
  leniently (`TolerantRules`, `TolerantRecurrence`: a preset drops the rule or the recurrence it
  cannot read and keeps the rest), which protects every build from here on; a vault carrying a
  0.49 preset restored on an *older* build than that still costs the settings, and the schema is
  deliberately not bumped for it — a bump would make those builds refuse a vault whose reminders
  they can read perfectly well, which is the worse trade for a phone that only moves forward.

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
