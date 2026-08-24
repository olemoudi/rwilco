# Architecture

Kept current on purpose: when the model, the persistence or the screen structure changes, this
file changes in the same commit.

## Modules

- `:core-model` — pure Kotlin (`java.time`, kotlinx-serialization), no Android. The domain
  model (`Reminder`, `Trigger`, `Action`, `Status`, `AppSettings`), tag normalisation, and — as
  milestones land — the trigger JSON codec, next-fire computation, Home grouping and
  validation. Fully unit-tested with JUnit 5.
- `:app` — the Android app: Room + DataStore persistence, the Compose UI, the self-updater.

## Domain model

A `Reminder` is text + tags + a list of `Trigger`s + a set of `Action`s + a `Status`.

A reminder rings by **rules**: a list of `TriggerRule`, each an event plus the conditions that
have to hold when it happens. Any rule is enough (they are ORed); a rule's own conditions all
have to hold (ANDed). That shape — an OR of ANDs — expresses any combination somebody can
reasonably mean and, unlike a free-form tree, can be read off a phone screen: *"al llegar a
casa, y sólo si es entre las 18:00 y las 22:00"*.

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

`nextFire(reminder, now, zone, defaultTime)` (`NextFire.kt`) picks the earliest definite
moment (`Scheduled`), else the earliest random draw (`Sometime`, shown as a window), else a place
(`WhenAt`). `groupForHome` (`HomeSections.kt`) lifts the earliest `Scheduled` out as the hero and
files the rest under Overdue / Today / Tomorrow / This week (rolling 7 days) / Later / Whenever /
Paused. Random moments come from `RandomDraw.kt`: SplitMix64 seeded by (reminder id, period
index), pinned by golden values in its test. `Validation.kt` decides what blocks a save.

## Persistence

- Room (`app/.../data/`): one table, `reminder(id, text, tags, triggers, actions, status,
  createdAt, updatedAt, doneAt)`; tags/triggers/actions are JSON text columns written by
  `ReminderCodec`, read leniently (unknown trigger kinds and actions are dropped, never fatal).
  `RwilcoDatabase.VERSION` + `MIGRATIONS` are guarded by `MigrationChainTest` (JVM) and
  `DatabaseMigrationTest` (device). Schemas are exported to `app/schemas`.
- `ReminderRepository`: reactive `open`/`done` flows for the screens, suspend writes.
- `SettingsStore`: Preferences DataStore with one JSON blob (`AppSettings`: theme, default time
  for date-only reminders, haptics, last-seen version for What's New). Additive changes need
  no migration.
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
  own composable (`rememberNow`) so nothing else recomposes.
- Editor: `EditorUiState` + pure reducers (`EditorState.kt`, tested); text and tags are offered
  before they are asked for — `suggestedTexts`/`suggestedTags` rank what has been written before
  by how often and how recently (a 30-day half-life), and nothing is auto-focused, because a
  keyboard that opens by itself hides the list that would have saved the typing. One
  configurator sheet per trigger kind under `editor/sheets/`, plus `ConditionSheet` for the
  "y sólo si" fences; the countdown sheet produces an `AtDateTime`; the place
  sheet searches addresses through the platform `Geocoder` (`PlaceSearch.kt`) and shows an
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
  the alarm, the notification buttons and the alert screen cannot drift apart. "Hecho" finishes
  a one-shot and leaves anything that can come round again.
- `AlertNotifications` has one channel per sound/vibration combination, because a channel's
  sound is fixed the moment it is created. A full-screen alert's notification stays silent: the
  screen does its own looping ring (`AlertRinger`) and gives up after two minutes.
- `AlertActivity` shows over the lock screen and turns it on; it is its own task so dismissing
  an alarm at three in the morning does not drop anybody into the app's back stack.
- `GeofenceManager` registers the place rules with Play Services, wholesale, and re-registers on
  boot and from `RearmWorker` (a reboot or a Play Services update drops them all). A place is
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
