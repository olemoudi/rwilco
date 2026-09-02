# TODO

Running notes: what is next, what cost time, what must not be re-derived.


## Emulator notes that cost time
- **A View animation that asks for its own next frame hangs every Espresso test.**
  `postInvalidateOnAnimation()` from inside `Overlay.draw` is the ordinary way to animate a
  View, and it leaves a Choreographer callback pending for ever: `AppNotIdleException ...
  MAIN_LOOPER_HAS_IDLED`, and the tour times out at whatever it was doing. It only appeared once
  the emulator had a location fix for the blue dot to draw, so the first two tour runs were
  green. Drive that kind of loop from `withInfiniteAnimationFrameNanos` (the frame clock
  `createAndroidComposeRule` excludes from idleness) and throttle it. 2026-08-31.
- **`performScrollToIndex` does not move the top row.** `HeaderScroll` travels by what the list
  *consumed* through the nested-scroll connection, and a programmatic jump consumes nothing — so
  the row stayed wherever it was, and at the top of the list no gesture can bring it back
  (nothing left to consume). `EditorTourTest` had been failing at the tap on the Settings cog
  since 0.60.0 for exactly this, which is also why the tour's screenshots had gone stale. Fixed
  in the screen rather than in the test (0.63.0): at the top of the list the row is shown.
  Cost: two emulator runs on 2026-08-31, one of them bisecting against the pre-change tree to
  prove it was not the change under review.
- **A device test may not have a backticked name with spaces.** The backticked-sentence
  convention is the JVM suites'; `androidTest` is dexed, and dex refuses spaces in a SimpleName
  prior to version 040 — it fails in `dexBuilderDebugAndroidTest`, not in the compiler, so the
  error arrives a minute later and does not name the convention.
- **"System UI isn't responding" on the emulator sits over every capture.** SystemUI ANRs
  by itself on this image a while after boot and its dialog is in every screenshot from then
  on; the app's tests still pass under it. Tapping "Wait" does nothing; "Close app" restarts
  SystemUI — and a test running at that moment loses its activity ("No compose hierarchies
  found"), so close it *between* runs, then re-run only the class that needs clean captures.
  Cost: one extra device run on 2026-09-02.
- `adb shell uiautomator dump` must write to `/data/local/tmp/ui.xml` (the `/sdcard` path
  silently fails on this image). Even then, `input tap` on Compose buttons landed maybe one time
  in three and a missed tap cascades (a stray BACK on Home closes the app). The instrumented tour
  test replaced the whole approach. Cost: an hour on 2026-08-24.
- **The keyboard comes up a beat after `performTextInput`**, resizes the window (`adjustResize`)
  and the editor's lower sections leave the composition under it: a chip node found before that
  is stale, `performClick` lands on a key, and `performScrollTo` cannot reach what is not
  composed. Hide it first through `InputMethodManager` (see `WhenChipTest.hideKeyboard`); there
  is no Espresso here to `closeSoftKeyboard()` with. Cost: four emulator runs on 2026-08-30.
- Cold start on the emulator shows a blank Home for ~2–3 s before the first DB emission (debug
  build, swiftshader). Since 0.45.0 that gap draws card-shaped placeholders rather than nothing.

## Shipped a wrong version.json in v0.1.0 and v0.2.0
`release.yml` greps `versionName = "..."` out of `app/build.gradle.kts`, and the comment right
above the real line spelled the pattern out — so the grep matched the comment's placeholder
first and both matches ended up in the JSON. Updates never broke (the version *code* regex
needs digits, which the comment did not have), so the only symptom was an update card offering
"X\n0.2.0-alpha". Fixed in 0.2.1: the comment no longer spells the pattern, both greps take
`head -1`, and the workflow now parses its own version.json before publishing it. Found by
curling the published file rather than by trusting the build. 2026-08-24.

## "Tags do not suggest the previous ones" (2026-08-24)
Reported from the phone; could not be reproduced. `TagReuseTest` drives it on a device — one
saved reminder with a tag, open a new one, the tag is offered — and passes. What was wrong was
the presentation: the "Nueva etiqueta" button sat *among* the chips with nothing saying they
were previous tags, and on a new reminder the whole section was below a long list of text
suggestions. Now the button is on top, the chips are under an "O reutilizar una" heading, and
they narrow as a new tag is typed. If it still looks empty on the phone, the honest answer is
that there are no tagged reminders yet.

## "Use my location" failed with the permission granted (2026-08-24)
Two bugs, both in the asking rather than the reading. The picker requested only
`ACCESS_FINE_LOCATION`, so somebody answering the dialog with **Approximate** got `granted =
false` back and was told "could not get a location" while COARSE sat granted — and every read
checked FINE alone, so it would have refused anyway. Then `currentLocation` picked the *first*
enabled provider, which is GPS: indoors it never answers, and the 15-second wait ended in
failure with the network provider — which answers instantly — sitting untouched behind it.
Now both permissions are asked for and either is enough, every enabled provider is asked at
once and the first answer wins, and the freshest last-known fix answers immediately or serves
as the fallback. "Could not get a fix" and "not allowed" are two different sentences now,
because they need two different things from the person.

## Review round, 0.61.1 (2026-08-31)
The pass after the five features of 0.61.0. Four findings, all in the new work's blast radius,
and the first one is the only serious one.

- **"Justo el plazo" did not take the rules out of the loop for a place.** `SpanLanding.EXACT`
  says the span alone decides, and `nextFire`/`nextWake` honoured it — which is enough for a
  clock rule, because nothing arms one any more. A place has no armed moment by design, so its
  circle stayed watched, stayed registered with Play Services, and an arrival rang the reminder
  anyway: the button's own sentence was false, and the reminder rang twice a round. Now one
  predicate, `Reminder.spanHasTakenOver` (said without a clock so the fences can ask it), and
  the same question at all three doors — `watchedCircles`, `geofenceChoices` and
  `ReminderFiring.fire`, mirrored in `Simulation`. A snooze waiting at a place outranks it, as
  it outranks everything.
- **A set the span had taken over still wore standing marks.** `ruleStandings` already answers
  all-null for a resting set — "a mark there would be an answer to a question nobody put" — and
  this is the same argument one step further: the place really is not being watched, and saying
  so as "no se cumple" states a promise as a fault.
- **A machine-written "a la vez" could be inherited.** Add two rules (the second now defaults to
  `TOGETHER`), remove both, add two *moments* back: the leftover was a choice nobody could have
  made — the control only exists with two rules on the form — and two moments under "a la vez"
  never ring. `matchAfterAdding` now refuses to keep it. `ALL` over two moments means something
  and is never touched.
- One `!!` in the new landing button, removed.

Checked and found sound: `owedUnderAll` (the catch-up's rules are dropped at the firing door),
`SafetyNet` (reads `nextWake`), the sound previews in Settings (their own player, so the
twenty-second handover to the speaker cannot cut a preview short), and the string-array parity
of the release notes. Left alone deliberately: the editor's "luego… luego…" line shows the
*rules'* cadence rather than the recurrence's for a span counted from the "hecho", because the
walk models a moment that has rung and not one that has been dealt with — pre-existing, and
each reading is a true future.

## Reliability pass, 0.7.5 (2026-08-25)
A read of everything on the firing path, looking for the ways a reminder rings when it should
not and stays silent when it should ring. Five real bugs, all in the same seam — what a moment
means once it has rung — plus two smaller ones. Every one now has a test that fails without the
fix.

- **A recurrence rang until you answered it.** The worst of them. `recurrenceMoment` counts from
  `lastDealtAt`, which does not move while a firing goes unanswered, so the same past moment was
  handed back for ever; `setAlarmClock` for a moment in the past fires at once, and the ring
  re-armed it. Fixed by making a recurrence's moment spent once `lastFiredAt` covers it — the
  same rule `searchFrom` applies to triggers. `RingingTwiceTest`, and the second round is driven
  end-to-end in `FiringOnceTest`.
- **Editing a reminder threw away its recurrence anchor.** `Draft.toReminder` did not carry
  `lastDealtAt` and a save replaces the whole row, so fixing a typo either stopped the reminder
  dead (with triggers: nothing to count from until dealt with again) or threw its next moment
  back to `createdAt`. It is now passed in explicitly, with the reason next to it.
- **Asking for a recurrence armed nothing.** `schedulingKey` left `recurrence` out, and on a
  reminder with no other "when" that is the only edit that changes nothing else — so the flow
  that re-arms on change never fired. Silent until the next launch or the six-hourly worker.
  `SchedulingKeyTest` now pins what is in the key and what must stay out of it.
- **A place arrival spent a future appointment.** The ring was recorded against
  `max(now, armedFor, late)`, and a place has no armed moment of its own: under ANY that
  `armedFor` belongs to another rule and can be days away. "Al llegar a casa, o mañana a las
  nueve" marked tomorrow's nine spent on the way through the front door. Now `momentRungFor`
  (`core-model`, tested) refuses the armed moment for an event-driven firing.
- **`dayStart` changed nothing until a reboot.** The re-arm watcher looked at `defaultTime`
  alone, and `dayStart` is just as much a scheduling input (it is where a recurrence in days,
  weeks or months lands).
- **The place watch believed a stale fix.** With nothing fresh to be had, `lastLocation` hands
  back whatever the phone had — this morning's, if location has been off. Judging places by it
  writes the wrong answer into `inside`, and then `crossingIsNews` throws away a real geofence
  arrival as somewhere we thought we already were. A fix older than the speed memory is now no
  fix at all, which is the bound `crossingIsNews` already used.
- **Two random draws could land on the same minute.** Only against the end of the window, where
  "push the collision a minute on" had nowhere to push. Each draw now leaves room for the ones
  behind it. The golden values are untouched.

Looked at and found sound (tests added to keep it that way): wall-clock reminders across a time
zone and across a clock put backwards or forwards (`TravellingClockTest`), a whole ALL round
wound forward through a restart (`RuleMatchTest`), a recurrence or a condition from a newer
build (`ReminderCodecTest`).

Left alone on purpose: `Geofence.setLoiteringDelay` is inert without a DWELL transition type,
which these fences do not ask for — the comment now says what actually damps a wobbling fix
(`setNotificationResponsiveness`). Adding DWELL would change when a place fires and wants a
real phone to judge it.

## "19 location reads in the last hour", reported from the phone (2026-08-29, 0.43.0)
The busy notice fired — which is the notice working, not a bug: it is off by default, it was
turned on to watch exactly this, and 18 polls an hour is the line it was set at. Some of the
hour was testing place reminders. The mechanism underneath was real, and there were three of
them compounding, all in the one case the design already called its worst.

- **`leavingWait` measured the wrong thing.** Inside a place with an "al salir" rule it took the
  fraction of the *radius the phone had moved* off the half hour. Ground covered is not progress
  towards a door: a phone carried about a 200 m flat covers a radius between two looks without
  once nearing the line, so the wait pinned at its five-minute floor all evening — twelve fixes
  an hour for a crossing nobody was going to make. It is now `closingM`, the change in that
  circle's own gap, deadbanded by the two fixes' doubt, and the plan is time-to-the-line at the
  rate the line is actually being closed. Pacing about closes nothing and buys nothing.
- **The motion sensor came back through the other door.** `stirred()` pulled the next look to
  five minutes on any significant motion within 400 m of a line — and standing *inside* a circle
  is being within 400 m of its line, so a trip to the kitchen bought a look, all evening, back
  to the same twelve an hour. Stirs from inside are now counted and back off 5/10/20/30 while
  the looks that follow keep finding the phone on the same side. From outside nothing is
  counted: settled-then-set-off is what the sensor is for.
- **Every look spent radio at the same price.** One tier for everything but the GPS, so the
  hourly look from the sofa at an errand across town paid for a wifi scan to learn it was still
  across town. Three tiers now (`FixTier`), and before any of them the provider's own last fix
  is tried against the question this look was going to ask (`Fix.answersFor`).

Also here, because they were the same afternoon: an exact allow-while-idle alarm for every look
(exactness above a quarter of an hour buys nothing Doze honours, and an exact alarm is one the
system may not batch), and `sync()` pulling a look to five seconds for every unjudged circle —
which, since a circle's id carries its geometry, means one fix per drag of a pin. Doorways are
now baselined from the fix in hand; a *state* still buys its look, because its first judgement
is a ring.

Not moved: `BUSY_POLLS`. It did its job.

To prove on the phone: Settings → Ubicación → the day's tally reading two to four looks an hour
through an evening at home with an "al salir" rule, "gratis" and "ahorradas" lines appearing at
all, and GPS only around real arrivals.

## The medicine routine, reported from the phone (2026-08-25, 0.7.6)
"Cada 1 h" fired, "hecho" opened the edit form, saving it put a card on Home reading *"Lo
siguiente · hace 47 minutos"*. Three separate faults in one minute of use:

- **Home's "hecho" swipe never went through `ReminderFiring.dismiss`.** It called
  `setStatus(DONE)` straight out, so a reminder asked to repeat was finished by the swipe and
  `lastDealtAt` — the moment the next round is counted from — was never stamped. The notification
  and the alert screen had always done it properly; Home was the one door that had drifted.
  `HomeSwipeTest` now swipes a "cada 1 h" card and checks it comes back, armed an hour from the
  swipe.
- **On the alert screen, "Ver" sat below "Hecho".** The bottom of the screen is where the thumb
  lands, and it was handing people the edit form when they meant to answer the alarm. That is how
  the form opened. "Hecho" is the bottom control now.
- **A save resurrected a spent moment.** 0.7.5 taught the editor to carry `lastDealtAt` but not
  `lastFiredAt`, and for an anchored recurrence the last ring is the *only* thing marking its
  moment spent (the anchor does not move until it is dealt with). So saving un-spent 15:14 and
  Home showed it as "lo siguiente", three quarters of an hour in the past — with an alarm armed
  for it, which arrives at once. Both are carried now.

The expected shape, confirmed and now covered end to end: write it → it fires after X → "hecho"
from the notification, the alert screen **or** the Home swipe → it reactivates itself → it fires
again X after the *hecho*, not after the previous firing.

## The routine, finished off (0.7.7)
Two follow-ups to the report above, both asked for directly.

- **Undo of a "hecho" left the alarm where the "hecho" put it.** Restoring the row put every
  column back, which is right, but `schedulingKey` did not carry `lastDealtAt` — and on a
  reminder that stayed ACTIVE either side of the swipe *nothing else in the key moves*, so the
  re-arm never fired. The alarm stayed set for the round that had just been taken back, and
  since `armedFor` had been restored to its old value the firing would then be dropped as
  unarmed: silence until the next launch. The anchor is in the key now.
- **A recurrence has a row on the card.** A reminder whose only arrangement is "cada 6 h"
  carries no trigger, so its card said nothing whatsoever about when it rings — a shape that was
  real, armed and invisible. It gets a keycap and two lines: the shape ("Cada 8 h") and the part
  people get wrong ("desde que lo marcas hecho"). `ByTrigger` deliberately gets none — the
  repeating trigger above it is already that answer. The hero's badge falls back to it too.
  `DemoData` never had one of these either, which is why nobody saw the hole; it does now, and
  the tour captures it (`home-recurrence.png`).

## The review round, 0.48.1 (2026-08-29)
A read of everything 0.45.0–0.48.0 added, looking for what a day of use would find. Fifteen
things; the ones worth not re-deriving:

- **Home's readiness strip could not be dismissed.** `rememberAlertReadiness()` starts optimistic
  (everything granted, so no screen flashes red before it has looked) and Home acted on that
  first value: "all good" → forget what was waved off. Every recomposition threw away the "ahora
  no" that had just been given. The default is a guess now and says so (`read`), and what is
  remembered is *pruned* to the problems still present rather than emptied at zero — which also
  fixes the other half: with one thing still broken "todo en orden" never arrives, and a channel
  muted a second time was never mentioned again.
- **The strip counted problems already waved off** ("3 cosas por arreglar" for the one that was
  new). It counts what is left to say.
- **Typing a time flipped AM/PM.** See `afternoonAfterTyping`; the rule has a test of its own,
  because the bug lived in a lambda and no assertion could have caught it.
- **The notification's two snooze offers were persisted as an enum.** A settings blob decodes all
  at once, so a name an older build has no member for (a vault taken back to 0.47) would have
  reset every setting there is. Names on disk, unknown ones dropped.
- **Keeping a reminder as a preset opened the form already dirty** (`presetText` filled,
  `initialPresetText` empty), so Back asked to discard changes nobody had made.
- **A shared line came back on every rotation**: `getIntent()` outlives the activity.
- **Test alerts piled up**: only "hecho" removes one, and three taps left three overdue cards.
- **"Hecho" could be pushed off the alert screen** by seven snooze offers and a long reminder.
- The editor's "Suena…" line used the default day start rather than the setting, so it could name
  an hour the reminder would not ring at.
- Two consequences of moving the readiness reads off the main thread, both wanted: the groups in
  trouble open themselves a beat later (so `openGroup` in the tour has to check first), and the
  places group now *does* open itself on a phone with no background permission — it was losing a
  race with the database read before.

## Traps found on 2026-08-29, worth not re-deriving
- **`weight()` does nothing inside a bottom sheet.** M3 measures the sheet's content with an
  unbounded height, so `weight(1f, fill = false)` on the scrolling part and a button row after it
  puts the row wherever the content ends — off the screen once the content is tall. Every sheet
  looked fine only because none of them was tall enough yet. `SheetScaffold` caps the height now.
- **A blur commits one snapshot late.** The new-tag field committed `onFocusChanged` and the save
  called `clearFocus()` first; the save still read the draft without the tag. Anything a button
  needs has to be state the button can see, not a side effect of losing focus.
- **A group title can be a chip's label somewhere else.** The tour's `openGroup` matched "Sonido"
  by text and found two once the alert rehearsal grew a "Sonido" action chip; it matches the
  heading now.

## The review round, 0.51.0 (2026-08-30)
A read of every screen after 0.50.0, asking what a day of use would find. Worth not re-deriving:

- **"Novedades" had been silent since 0.20.0** — forty-five releases — because `RELEASES` is a
  hand-kept list and nothing checked it against the build. `WhatsNewTest` does now; a release
  without its line fails `./gradlew test`. The gap is one summary entry keyed to code 95.
- **A Home tag filter that matches nothing cannot happen.** The inventory flagged "a filter that
  matches nothing shows a blank list", and it is true of the composable alone — but a chip is only
  offered for a tag or state present among the open reminders, and the filter is normalised
  against the chips (`buildHomeState`), so the state is unreachable. Dropped rather than built.
- **Search read `repository.open`**, so letting `search()` see done reminders did nothing until
  `HomeViewModel` fed it `open + done`. A model change is not a feature until the screen's
  source carries it.
- **The countdown step of one minute is deliberate** (the comment in `CountdownSheet` says why:
  five made three impossible); the seventeen-tap problem is answered by a stepper that repeats
  when held, which every stepper in the app now does.

## The review round, 0.58.0 (2026-08-30)
A read of everything after 0.55.0 — the two features and the machinery under them — by three
reviewers with different remits, asking what a day of use would find. Thirty-odd findings,
twenty-six fixed; the ones worth not re-deriving:

- **One unknown word in the settings blob reset every setting.** `decodeSettings` is all or
  nothing, and only a preset's rules and recurrence had a tolerant serializer — a theme, a
  favourite tile, an action or a sound from a newer build (a vault restored on an older one)
  handed back `AppSettings()`, and the next write made it permanent. `coerceInputValues` for
  the plain enums, `TolerantActions`/`TolerantSound` for the rest; `SettingsToleranceTest`
  swaps real encoded values (splicing a duplicate key lets the base value win, and the first
  version of the test was vacuous for exactly that reason).
- **A place snooze could go silent for good.** `accept` judged its circle "strictly" because the
  *reminder* had rung — strict is for a circle that rang — so with no side yet seen the first
  arrival home was dropped and not written, and the next look baselined *inside*. Strict is
  per circle now (`lastFiredRule == triggerIndexOf`), and never for a snooze circle.
- **`look()` wrote `inside` before handing the crossings on**, with no guard against the
  receiver's budget running out mid-ring: a second arrival in the same look was lost, and could
  never be reported again. The hand-off is `NonCancellable` and per-event `runCatching`.
- **"Hecho" on a ring wiped `dealtThrough`** (`consumed` was null while awaiting an answer, and
  the DAO wrote it as given): rounds skipped ahead came back.
- **The net's word replaced the alert it was about** — same notification id — turning a pinned
  insistent card into a low-priority note. It has an id of its own now, and `cancel` takes both.
- **Deleting a reminder and undoing it lost its history** (the cascade); the undo carries it.
- **Undoing a place snooze never re-told the watch the side**, which `sync` had pruned; the
  event carries the side it knew.
- **`GeofenceIds.encode` wrote `E` for both sides** (`EXIT` starts with an E too). Fixed; every
  "al salir" id changed once, which is one re-registration and one fresh baseline.
- The parser: "esta noche a las 9" was 09:00, "a las 12 de la noche" was midday, "a la 1" was
  one in the morning, "at once" was eleven o'clock, "12.50" was a clock, "en 200 horas" was a
  chip the sheet then refused, "el 5." read nothing, and a repeat read from the words was
  unreachable once any rule was on the form (it lives in "Vuelve" now).
- Smaller: the random window promoted `ByTrigger` on every edit and left it behind when
  removed; the preset-words dialog lost its words on rotation; `StringsParityTest` skipped
  string-arrays; `PresetChip` was 44 dp and said nothing to a screen reader when selected; a
  preset deleted from the editor came back at the end of the list; the hero's swipe was keyed
  by the slot, not the reminder; the backup badge said "done" while a copy was going up; the
  geofence receiver had no budget; a settings change re-armed alarms but not the watch's
  gates; a rest reset the blind streak; a snooze alarm a second early was dropped; the
  arrival's notification read "pospuesto hasta llegar a casa"; the snooze circle could be cut
  by the hundred-fence cap; "al salir de aquí" could be drawn around a fix half an hour old.

Left alone, on purpose: the strips (`AlertStackScreen`) offer no place answers (two per
strip is a screen of buttons); ~~editing a reminder waiting at a place drops the wait, as it
drops a clock snooze~~ — fixed in 0.63.0, a save keeps both unless the edit changed the "when"; "el viernes" is the next Friday strictly after today while "todos los
viernes" may start today — two readings pinned by their tests.

## The reliability round, 0.59.0 (2026-08-30)
An audit with one question — can a reminder stay quiet when it should have rung? Three mapping
passes, a line-by-line read of the six critical files, an adversarial check of every fix before
it went in. The clock path held (0.7.5 and 0.58.0 did their work); everything real was in the
place path, all of it the class 0.58.0 started closing. Each fix has a test that fails without it
(the wiring-only ones lean on the pure half plus the device suite).

- **The geofence door could consume a crossing without ringing it.** `accept` writes the side
  into the watch's memory before `fire` runs, and the receiver's nine-second budget could cut
  the coroutine between the two: the memory then said "already there", and no later look could
  report the arrival again. Hardened the way `look()` was in 0.58.0 — per-place
  `NonCancellable`, the timeout landing only between places.
- **The place echo was measured per reminder, not per rule.** "Al llegar a casa, o a las
  21:00": the nine o'clock ring silenced a genuine arrival three minutes later. `isPlaceEcho`
  (core-model, tested) asks whether THIS circle rang — with a null rule still an echo, because
  a ring with no rule behind it (a snooze's own crossing) holds every circle of its own pin.
- **`accept` failed towards silence.** A repository or settings read that threw wore the same
  null as "circle not watchable": the crossing was written down and dropped, unlogged. A failed
  read now leaves the memory unwritten and the crossing to the next look — late, not never.
- **A remove Play Services never answered poisoned the fingerprint.** The add went in, the
  stale remove could land on top of it, and the store said "registered" for up to six blind
  hours. The fingerprint is only written when the remove actually answered.
- **An alarm refused at the last moment was an alarm never set.** `setAlarmClock` throwing (the
  exact-alarm grant taken between check and call, Android 12/13) now falls back to
  `setAndAllowWhileIdle`, and both failures reach the diagnostics.
- **A place snooze without a trustworthy fix could be baselined "inside".** `insideAfter`'s
  no-history lean read a doubtful fix (unknown accuracy counts as 500 m) towards the rule's own
  side — for an Arrive snooze, "already there" — and the wait went silent with no clock and no
  net behind it. A snooze circle's first side is now the person's word: waiting to arrive
  starts outside, waiting to leave starts inside; the honest side `remember()` wrote still wins.
- **And the wait got its backstop** (asked for, and approved): `NetWord.WAITING` — two of the
  net's longest waits after the ring the snooze answered, one quiet note that it is still
  waiting, opening Home where the wait can be taken back.

Also: the fence list moved to core-model as `geofenceChoices` (the hundred-cap that never cuts
a snooze circle finally has tests); the clock got backwards-with-a-snooze and no-re-owing
tests; DST is pinned outside Madrid (Sydney both ways, Kathmandu's +05:45).

Left alone, on purpose: a missed moment is held with no fresh OS alarm until a catch-up door
(the two-at-nine race fix; the doors are frequent and the net stands under it); no direct-boot
(Room lives in credential-encrypted storage, and BOOT_COMPLETED after unlock catches up);
`conditionsHold` fails open for places; a past recurrence rings once immediately by design.

## The review round, 0.67.0 (2026-09-02)
A read of the whole experience by three reviewers with different remits (Home and cards; the
editor and presets; the alert, notifications, settings and accessibility), eighty-odd findings,
the thirteen that lose data, crash or dead-end fixed first. Worth not re-deriving:

- **Delete's undo was a four-second snackbar, and the next snackbar killed it.**
  `SnackbarController` shows one at a time on purpose (five quick swipes must not queue five
  "Eliminado"), which is right for "hecho" and wrong for the one act with no other way back.
  The fix is not a longer snackbar but a second door: the ViewModel keeps the last delete for a
  minute and Home draws a row for it.
- **Notification channels were never deleted.** One per tone, rhythm and DND grant, kept for
  ever, and `anyAlertChannelMuted` tested every one of them — so a channel muted under a tone
  nobody rang any more was a red strip on Home that nothing could clear. Sweep in
  `ensureChannels`, and make the channels with the *chosen* tone at start-up (the defaults
  first would have swept the chosen ones on every launch).
- **`startActivity` on a settings page is not safe.** Three of the pages the readiness card
  opens are absent on some OEM builds and on Android Go; `openSettingsPage` falls back to the
  app's page and says so.
- **A `Row` with no weights measures its unweighted children first**, and the wordmark took the
  header's width from the four buttons at a large font scale; the strips' answers did the same
  to "Hecho". `weight(1f, fill = false)` on the thing that should give, `FlowRow` where the
  things should wrap.
- The compose test clock virtualises `delay` in effects and fast-forwards animations while
  waiting for idle (see `AlertGuardTest`); the emulator's SystemUI ANR dialog sits over every
  capture once it appears and "Close app" restarts SystemUI — between runs only.

The "should-fix" third of the round shipped as 0.68.0 (see ARCHITECTURE.md's 0.68.0 entries).
Still open, for the polish release: the copy pass (Borrar/Eliminar/Vaciar, "En pausa" as an
event, English "Done" that closes a dialog, the widget's description, solo/sólo, «», recordatorio
vs aviso in the channel names, "Disparado por", `watch_busy_title`, the "Vuelve" chips), the
token pass (loose dp/sp, numbers in Manrope, `TimeField`'s stroke, glyph sizes), haptics on the
header buttons and FABs, the gesture hints on the empty Home, the words-chip beside the field,
weekday chips in the date sheet, `RuleTree` in the editor, one preset panel, "my location" on the
big map, the calendar's and the time wheel's semantics, the fades in `CuratePanel`/`PickSheet`,
search's cap and "when" line, the hero's tags via `FittingRow`, the volume button, `InfoBadge`'s
titles, `Trigger.Countdown`'s zone, and the dead code (`HoldButton`'s disc, `TimeText.dateLong`,
`RuleRoot(muted)`, `nextRoundHour`, `EVERY_DAY`, `editor_repeats*`, `RuleMatch.joinRes` twice).
To prove on the phone from 0.68.0: the alert opened from a card arms at once; a strip answered
leaves the others armed; the periodic update check posts its notification and does not open the
install dialog by itself; the first-run sentence before the notification dialog.

## Still to prove on the real phone (Pixel 8 Pro)
- The reliability round (0.59.0): a real arrival three minutes after a clock ring must ring
  (the echo is per rule now); «al llegar a casa» said from the metro with no good fix must
  ring at the real doorway; and a place snooze left waiting two days should produce the one
  quiet «sigue esperando» note, opening Home.
- The idempotent geofence sync (0.53.0): the diagnostics `-- log --` should read
  `geo fences=n unchanged` on successive process starts and `registered` only after a
  reboot, an update, a saved place reminder or a `NOT_AVAILABLE`. And a place reminder must
  still ring after a day of that.
- The launcher shortcuts (0.53.0): hold the icon with a preset pinned; the disc and initial,
  and one tap writing the reminder with the app closed.
- The review round (0.58.0): a vault from this build restored on 0.57.0 keeps its saved places
  (the tolerance is in the *reader*, so only a downgrade proves it); "al llegar a casa" from
  the metro with the watch idle — the first arrival must ring; two place reminders arriving in
  one look both ring.
- Put off until a place (0.57.0): from the ringing screen, "al llegar a casa" and then the
  real doorway in the street — once, not twice (the fence and the watch both see it) — and
  "al salir de aquí" walking out of the building. Settings → Ubicación → the log should show a
  single circle while it waits, and none once it has rung. And the offer should be absent while
  standing at home.
- The words-reading chip (0.56.0): share a line from WhatsApp ("Cita dentista jueves 17:30")
  and see the chip first under "Cuándo"; type a few real sentences and note any reading that is
  wrong or missing — the table in `WhenInTextTest` is where they go.
- The widget (0.55.0): place it at two and four cells, watch it redraw on a "hecho" and turn
  "hoy" into "mañana" past midnight, and tap the words into the editor. The emulator has no
  launcher worth placing a widget on, so this one is the phone's alone.
- The history card (0.54.0): after a ring and a "hecho", the two lines under the form.
- Settings → Alertas → "Probar una alerta" is the way to prove most of the below now: lock the
  phone, wait ten seconds, and see what arrives.
- The overlay rule end to end: an alert while another app is open (banner), on the home screen
  (full screen), and with either special permission missing (banner, and Settings says which).
  The emulator can be made to show it but not to be convincing about it.
- A reminder with rules combined by "todos" completing across a reboot: the place is recorded,
  the phone restarts, and the time still rings it.
- A place reminder actually firing in the street, and how long the geofence takes to notice.
- The full-screen alert over the lock screen, and whether Android 14's full-screen-intent
  permission is granted or has to be asked for (Settings has the row either way).
- Whether the alarm sound at alarm volume is right, or too much for "comprar filtros".
- Haptics: the emulator has no vibrator, so every buzz in the app is unverified.

## Next
- ~~A place condition ("y sólo si estás en el trabajo")~~ — shipped: read at fire time from the
  watch's own memory, and what nobody can vouch for holds (fail open). See `conditionsHold`.
- ~~Snooze from the alert screen is wired; the notification offers ten minutes and two hours~~ —
  since 0.47.0 the notification's two are chosen in Settings, there is a custom length and a
  "mañana por la mañana", and a card on Home can be put off (only once it has rung) or let back.
- Background location is a permission, not a service. Two eyes on every place now: the
  geofences (the phone's) and `PlaceWatcher` (the app's own, 2026-08-25), which reads one fix
  per look and sets its own next look from distance and speed — never a foreground service,
  never continuous updates. Settings → Location shows what it last saw. Two platform limits
  to keep in mind if it ever seems slow: Doze holds allow-while-idle alarms to one per nine
  minutes (fine: a phone in Doze is not moving), and Android throttles a *background* app's
  continuous location requests to a few an hour — the watch asks for one fix at a time, which
  in practice answers, and falls back to the fused provider's last location when it does not.
  To prove on the phone: the "last look · next look" line in Settings moving, a walk up to a
  saved place ringing once (not twice), and the battery page showing nothing worth naming
  after a day with a place reminder set.
