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
- Editor: `EditorUiState` + pure reducers (`EditorState.kt`, tested); one configurator sheet per
  trigger kind under `editor/sheets/`; the countdown sheet produces an `AtDateTime`; the place
  sheet takes one fix from `LocationManager` (map to come). The alert preview is `AlertScreen`,
  the same composable phase 2 will host in a full-screen-intent activity.

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
