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
| Countdown             | `AtDateTime(now + duration)`       | `at_date_time` |
| Date only             | `OnDate(date)` — rings at the default time (a setting) | `on_date` |
| Time that repeats     | `AtTime(time, days)`               | `at_time`      |
| Place                 | `Location(lat, lng, radiusM, ENTER/EXIT, label)` | `location` |
| Random                | `Random(timesPer, DAY/WEEK, from, to, days)` | `random` |

Wall-clock values are stored without a zone; the zone is applied when the next fire is computed.

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
index), pinned by golden values in its test. `Validation.kt` decides what blocks a save, which is only the words and a trigger that is
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
  status, createdAt, updatedAt, doneAt, …, armedFor, armedRule, firedRules)`; tags/triggers/actions are JSON text columns written by
  `ReminderCodec`, read leniently (unknown trigger kinds and actions are dropped, never fatal).
  `RwilcoDatabase.VERSION` + `MIGRATIONS` are guarded by `MigrationChainTest` (JVM) and
  `DatabaseMigrationTest` (device). Schemas are exported to `app/schemas`.
- `ReminderRepository`: reactive `open`/`done` flows for the screens, suspend writes.
- `SettingsStore`: Preferences DataStore with one JSON blob (`AppSettings`: theme, default time
  for date-only reminders, the trigger kind offered first, when "the weekend" starts, haptics,
  last-seen version for What's New). Additive changes need no migration.
- `RwilcoApplication` is the dependency container (manual DI); ViewModels get it through a
  `Factory`.

## UI

- Single activity, `navigation-compose` type-safe routes (`Routes.kt`): Home, Editor(id?),
  Done, Settings. Sheets, the place picker and the alert preview are ViewModel state.
- Theme (`ui/theme/`): hand-authored dark/light schemes (amber `primary` = "what fires next"),
  `RwilcoTypography` on three bundled variable fonts (Bricolage Grotesque display, Manrope body,
  JetBrains Mono for times/dates), `RwilcoShapes`, tokens (`Spacing`, `Motion`, `Sizes`) and
  `Haptics` behind one setting. Trigger families (time / place / chance) have their own colours
  in `FamilyVisuals.kt`. Plain `MaterialTheme`: material3 1.4.0 keeps the expressive theme
  internal.
- Home: `HomeViewModel` combines the open reminders, settings, the tag filter and a minute pulse
  into `HomeUiState` (`buildHomeState`, pure and tested). The hero card's countdown ticks in its
  own composable (`rememberNow`) so nothing else recomposes. The magnifier has a flow of its own
  (`buildSearchState`, also pure): a keystroke must not put Home through grouping and next-fire
  again. Results replace the list while it is open; a reminder opens, a tag becomes the filter.
- Editor: `EditorUiState` + pure reducers (`EditorState.kt`, tested). The form is four cards
  (`EditorSection`), each with an icon badge and its name — the words, the tags, when, what
  happens — because four headings down one flat column read as more text. Interactive edges use
  the `Strokes` tokens (a control's line is thicker and brighter than a card's) so the screen
  says what can be pressed. With more than one trigger a segmented control chooses between
  "cualquiera" and "todos". Text and tags are
  offered before they are asked for — `suggestedTexts`/`suggestedTags` rank what has been written before
  by how often and how recently (a 30-day half-life), and nothing is auto-focused, because a
  keyboard that opens by itself hides the list that would have saved the typing. While a draft
  has no trigger, "when" offers the three answers people give most (in half an hour, tonight,
  tomorrow morning) as one-tap chips that append a rule without a sheet. `TriggerKindSheet` puts the kind
  chosen in Settings (`AppSettings.defaultTriggerKind`) first and marks it; the other five keep
  their order behind it. One configurator sheet per trigger kind under `editor/sheets/`, plus `ConditionSheet` for the
  "y sólo si" fences; the countdown sheet produces an `AtDateTime`; the place
  sheet searches addresses through the platform `Geocoder` (`PlaceSearch.kt`), and asks every
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
  an armed moment in the past with no ring to match it.
- `ReminderFiring` is the single place that decides what a firing, a "Hecho" and a snooze do, so
  the alarm, the notification buttons and the alert screen cannot drift apart. Under ALL it
  writes the moment down and returns; only the last one goes on to ring.
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
  boot and from `RearmWorker` (a reboot or a Play Services update drops them all). Nothing polls
  a position: the phone's own location stack does the watching, which is why "all the time" is
  the whole cost of a place reminder. Settings says where that grant stands, whether or not a
  place reminder exists yet (`LocationPermissionCard`), because a refusal discovered later is a
  reminder that never arrives. A place is
  judged against its conditions when it happens, not when it is armed.
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
