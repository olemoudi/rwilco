#!/usr/bin/env bash
# Headless emulator helpers for WSL. Usage:
#   scripts/emu.sh create              # one-off: the `rwilco` AVD (API 35, 1080x2400 @420dpi)
#   scripts/emu.sh up | down | wake    # boot (and keep awake) / kill / nudge a dozing screen
#   scripts/emu.sh install | launch    # install the debug APK / start the app
#   scripts/emu.sh seed | clear        # demo data in / out (debug builds only)
#   scripts/emu.sh shot NAME           # docs/screenshots/NAME.png (raw screencap)
#   scripts/emu.sh tour                # run the instrumented UI tour, pull its screenshots into docs/screenshots
#   scripts/emu.sh dark | light        # system theme
#   scripts/emu.sh es | en             # per-app locale (API 33+)
#   scripts/emu.sh tz                  # Europe/Madrid, so demo times look real
set -euo pipefail

SDK=${ANDROID_HOME:-/home/ole/Android/Sdk}
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator
AVD=rwilco
PKG=dev.rwilco

case "${1:-}" in
  create)
    "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" \
      -k "system-images;android-35;google_apis;x86_64" -d pixel_6 --force
    cfg=~/.android/avd/$AVD.avd/config.ini
    for kv in hw.lcd.width=1080 hw.lcd.height=2400 hw.lcd.density=420 hw.ramSize=2048 hw.keyboard=no; do
      k=${kv%%=*}
      if grep -q "^$k *=" "$cfg"; then sed -i "s|^$k *=.*|$kv|" "$cfg"; else echo "$kv" >> "$cfg"; fi
    done
    ;;
  up)
    nohup "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
      > /tmp/rwilco-emu.log 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
    # The AVD dozes off within a minute of the last input; a sleeping phone breaks screenshots
    # and instrumented tests alike.
    "$ADB" shell svc power stayon true
    "$ADB" shell settings put system screen_off_timeout 86400000
    "$ADB" shell settings put system time_12_24 24
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    # Let the boot storm pass: installing and testing straight away is how SystemUI ends up
    # ANR-ing behind the screenshots.
    sleep 30
    echo "booted"
    ;;
  down) "$ADB" emu kill ;;
  wake) "$ADB" shell input keyevent KEYCODE_WAKEUP ;;
  install) "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk ;;
  launch) "$ADB" shell am start -n "$PKG/.MainActivity" ;;
  seed) "$ADB" shell am broadcast -n "$PKG/.debug.DemoSeedReceiver" --es seed demo ;;
  clear) "$ADB" shell am broadcast -n "$PKG/.debug.DemoSeedReceiver" --es seed clear ;;
  shot)
    mkdir -p docs/screenshots
    "$ADB" exec-out screencap -p > "docs/screenshots/$2.png"
    echo "docs/screenshots/$2.png"
    ;;
  tour)
    # The tour test drives the app through Compose semantics (adb `input` is unreliable against
    # Compose here) and captures every screen; see app/src/androidTest/.../EditorTourTest.kt.
    # leaveApksInstalledAfterRun: Gradle otherwise uninstalls the app, and the captures with it.
    ./gradlew -q :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=dev.rwilco.ui.EditorTourTest \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
    mkdir -p docs/screenshots
    "$ADB" exec-out run-as "$PKG" tar -cf - files/screenshots | tar -x -C docs/screenshots --strip-components=2
    ls docs/screenshots
    ;;
  dark) "$ADB" shell cmd uimode night yes ;;
  light) "$ADB" shell cmd uimode night no ;;
  es) "$ADB" shell cmd locale set-app-locales "$PKG" --user 0 --locales es-ES ;;
  en) "$ADB" shell cmd locale set-app-locales "$PKG" --user 0 --locales en-US ;;
  tz)
    "$ADB" root > /dev/null
    "$ADB" shell setprop persist.sys.timezone Europe/Madrid
    "$ADB" unroot > /dev/null
    ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
