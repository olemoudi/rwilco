# TODO

Running notes: what is next, what cost time, what must not be re-derived.


## Emulator notes that cost time
- `adb shell uiautomator dump` must write to `/data/local/tmp/ui.xml` (the `/sdcard` path
  silently fails on this image). Even then, `input tap` on Compose buttons landed maybe one time
  in three and a missed tap cascades (a stray BACK on Home closes the app). The instrumented tour
  test replaced the whole approach. Cost: an hour on 2026-08-24.
- Cold start on the emulator shows an empty Home for ~2–3 s before the first DB emission
  (debug build, swiftshader). Not seen as a product problem yet; watch it on a real phone.

## Still to prove on the real phone (Pixel 8 Pro)
- A place reminder actually firing in the street, and how long the geofence takes to notice.
- The full-screen alert over the lock screen, and whether Android 14's full-screen-intent
  permission is granted or has to be asked for (Settings has the row either way).
- Whether the alarm sound at alarm volume is right, or too much for "comprar filtros".
- Haptics: the emulator has no vibrator, so every buzz in the app is unverified.

## Next
- A place condition ("y sólo si estás en el trabajo") — the `Condition` interface is ready for
  it; it needs a location read at fire time and a decision about what to do when it is unknown
  (fail open, presumably).
- Snooze from the alert screen is wired; snooze from the notification only offers ten minutes.

