# TODO

Running notes: what is next, what cost time, what must not be re-derived.


## Emulator notes that cost time
- `adb shell uiautomator dump` must write to `/data/local/tmp/ui.xml` (the `/sdcard` path
  silently fails on this image). Even then, `input tap` on Compose buttons landed maybe one time
  in three and a missed tap cascades (a stray BACK on Home closes the app). The instrumented tour
  test replaced the whole approach. Cost: an hour on 2026-08-24.
- Cold start on the emulator shows an empty Home for ~2–3 s before the first DB emission
  (debug build, swiftshader). Not seen as a product problem yet; watch it on a real phone.

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

