#!/usr/bin/env bash
set -euo pipefail
APP="dev.russiankeyboard.prototype"
IME="$APP/.RussianKeyboardImeService"
APK="android/app/build/outputs/apk/debug/app-x86_64-debug.apk"
mkdir -p reports/emulator

gradle -p android --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest

# ONNX smoke verifies only numeric inference. It must not depend on the
# unrelated (potentially flaky) soft-keyboard visibility scenario.
if [ "${ONNX_ONLY:-0}" = "1" ]; then
  adb logcat -c
  gradle -p android --no-daemon --max-workers=2 connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.russiankeyboard.prototype.OnDeviceRankerTest
  adb logcat -d -v brief -s RSK_ONNX_BENCH:I '*:S' > reports/emulator/onnx-benchmark-logcat.txt
  python scripts/ci_parse_android_benchmark.py \
    --log reports/emulator/onnx-benchmark-logcat.txt \
    --out reports/emulator/onnx-benchmark.json
  exit 0
fi

adb install -r "$APK"
adb shell am start -n "$APP/.MainActivity"

# IME discovery is asynchronous on Android after package installation.
registered=0
for attempt in $(seq 1 30); do
  adb shell ime list -a -s > reports/emulator/registered-ime.txt || true
  if grep -Fq "$APP" reports/emulator/registered-ime.txt; then
    registered=1
    break
  fi
  sleep 2
done
if [ "$registered" != "1" ]; then
  echo "IME not registered; save diagnostics"
  adb shell pm path "$APP" || true
  adb shell dumpsys input_method > reports/emulator/input-method-diagnostics.txt || true
  adb shell dumpsys package "$APP" > reports/emulator/package-diagnostics.txt || true
  exit 1
fi

adb shell ime enable "$IME" | tee reports/emulator/ime-enable.txt
adb shell ime set "$IME" | tee reports/emulator/ime-select.txt
if grep -Eqi "unknown input method|cannot be" reports/emulator/ime-enable.txt reports/emulator/ime-select.txt; then
  echo "IME selection failed"
  exit 1
fi
adb shell settings put secure show_ime_with_hard_keyboard 1
gradle -p android --no-daemon --max-workers=2 connectedDebugAndroidTest

adb install -r "$APK"
adb shell am start -n "$APP/.MainActivity"
adb shell ime list -a -s > reports/emulator/registered-ime-after-tests.txt
grep -F "$APP" reports/emulator/registered-ime-after-tests.txt
adb shell dumpsys meminfo "$APP" > reports/emulator/launcher-meminfo.txt
adb shell cat /proc/meminfo | head -n 6 > reports/emulator/system-meminfo.txt
