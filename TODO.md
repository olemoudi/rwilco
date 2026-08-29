# TODO

Running notes: what is next, what cost time, what must not be re-derived.


## Emulator notes that cost time
- `adb shell uiautomator dump` must write to `/data/local/tmp/ui.xml` (the `/sdcard` path
  silently fails on this image). Even then, `input tap` on Compose buttons landed maybe one time
  in three and a missed tap cascades (a stray BACK on Home closes the app). The instrumented tour
  test replaced the whole approach. Cost: an hour on 2026-08-24.
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

## Still to prove on the real phone (Pixel 8 Pro)
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
- Snooze from the alert screen is wired; the notification offers ten minutes and two hours (three
  actions is the platform's cap; the rest of the offers live on the alert screen).
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
