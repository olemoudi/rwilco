# TODO

Running notes: what is next, what cost time, what must not be re-derived.

## Next
- Place picker map (osmdroid): drop a pin, draggable radius circle. The sheet already takes one
  fix from `LocationManager` and stores lat/lng/radius, so the map only replaces the readout.
- First release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, push a `v*` tag —
  only with the owner's explicit OK (public repo, starts the signing chain).
- Phase 2: firing (see "Decisions parked" below).

## Emulator notes that cost time
- `adb shell uiautomator dump` must write to `/data/local/tmp/ui.xml` (the `/sdcard` path
  silently fails on this image). Even then, `input tap` on Compose buttons landed maybe one time
  in three and a missed tap cascades (a stray BACK on Home closes the app). The instrumented tour
  test replaced the whole approach. Cost: an hour on 2026-08-24.
- Cold start on the emulator shows an empty Home for ~2–3 s before the first DB emission
  (debug build, swiftshader). Not seen as a product problem yet; watch it on a real phone.

## Decisions parked for phase 2 (firing)
- **Place triggers: use Play Services `GeofencingClient`** (`play-services-location`) unless the owner's
  phone has no GMS. Sideloading is not a reason to avoid it — GMS libraries work in any app on a
  GMS device — and the platform alternatives (`LocationManager.addProximityAlert`, periodic
  polling) are worse on reliability and battery. Needs `ACCESS_BACKGROUND_LOCATION` and the
  "allow all the time" prompt. The phase-1 place picker only takes one fix via `LocationManager`,
  so nothing here is decided by the current code. Owner asked on 2026-08-24; answer pending.

