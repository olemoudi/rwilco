#!/usr/bin/env bash
#
# What a fling over a long Home costs the UI thread, on whatever device adb is talking to.
#
# **Read the phases, not the frame times.** A frame time is the UI thread plus the GPU plus
# whatever else the device is doing, and on the emulator (software rendering) the GPU half swamps
# everything and says nothing about this app. `gfxinfo framestats` gives the timestamps of each
# phase instead, and the CPU half is the one this app can do something about:
#
#   AnimationStart .. PerformTraversalsStart   composition (and the list composing new rows)
#   PerformTraversalsStart .. DrawStart        measure + layout
#   DrawStart .. SyncQueued                    recording the display list
#
# Measured this way on 2026-09-05, ~90 cards, debug build on the emulator: measure+layout 0.2 ms,
# recording 3.2 ms, composition 10-16 ms median and 53-95 ms at the 90th. Composing a card is the
# whole of it, which is what `app/src/main/baseline-prof.txt` exists to make cheaper.
#
# **The emulator cannot A/B this.** Three runs of the same build came out at 13.8 / 13.9 / 16.0 ms
# median and 58.8 / 53.6 / 94.7 ms at the 90th: the noise is wider than anything worth measuring.
# Run it against a real phone (`adb devices` first) and the numbers mean something.
#
#   scripts/scroll-bench.sh before      # then install the new build
#   scripts/scroll-bench.sh after
#
set -euo pipefail
SDK=${ANDROID_HOME:-/home/ole/Android/Sdk}
ADB=$SDK/platform-tools/adb
PKG=dev.rwilco
LABEL="${1:-run}"
OUT="${2:-/tmp/frames}"

"$ADB" shell am force-stop $PKG
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 7
# A pass nobody measures: the first frames are the activity arriving, not a scroll.
for _ in 1 2 3; do "$ADB" shell input swipe 540 1800 540 500 120; sleep 1.2; done
"$ADB" shell input swipe 540 500 540 2000 200; sleep 2

"$ADB" shell dumpsys gfxinfo $PKG reset >/dev/null
for _ in $(seq 1 4); do
  "$ADB" shell input swipe 540 1800 540 500 120
  sleep 1.2
  "$ADB" shell input swipe 540 500 540 1800 120
  sleep 1.2
done
"$ADB" shell dumpsys gfxinfo $PKG framestats > "$OUT-$LABEL.txt"
python3 "$(dirname "$0")/scroll-frames.py" "$OUT-$LABEL.txt" "$LABEL"
