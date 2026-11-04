#!/usr/bin/env bash
set -euo pipefail
APP="dev.russiankeyboard.prototype"
IME="$APP/.RussianKeyboardImeService"
APK="android/app/build/outputs/apk/debug/app-x86_64-debug.apk"
mkdir -p reports/emulator

# Fail closed, but keep diagnostics and an emulator-only screenshot on error.
# All app input in these tests is synthetic and never collected from real users.
collect_failure_diagnostics() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ] && [ "${ONNX_ONLY:-0}" != "1" ]; then
    adb shell settings get secure default_input_method > reports/emulator/selected-ime-on-failure.txt 2>&1 || true
    adb shell dumpsys input_method > reports/emulator/ime-dumpsys-on-failure.txt 2>&1 || true
    adb shell dumpsys window > reports/emulator/window-dumpsys-on-failure.txt 2>&1 || true
    # Only system IME lifecycle tags; never log the editor's text.
    adb logcat -d -v brief -s 'ImeTracker:*' 'InputMethodManager:*' 'InputMethodManagerService:*' 'ActivityTaskManager:*' '*:S' \
      > reports/emulator/ime-lifecycle-on-failure.txt 2>&1 || true
    adb exec-out screencap -p > reports/emulator/ime-screen-on-failure.png 2>/dev/null || true
  fi
}
trap collect_failure_diagnostics EXIT

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
adb logcat -c
# Remove only this suite's old synthetic reports; a skipped run cannot reuse them.
adb shell run-as "$APP" rm -f files/extended-qa-summary.json files/extended-qa-cases.tsv
# UTP otherwise uninstalls the target app (and its private report files) on exit.
if gradle -p android --no-daemon --max-workers=2 connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true; then
  instrumentation_status=0
else
  instrumentation_status=$?
fi
# Public/synthetic QA only, written by ExtendedKeyboardDeviceTest; never user text.
adb exec-out run-as "$APP" cat files/extended-qa-summary.json > reports/emulator/extended-qa-summary.json
adb exec-out run-as "$APP" cat files/extended-qa-cases.tsv > reports/emulator/extended-qa-cases.tsv
python - "$instrumentation_status" <<'PY'
import json,sys
from pathlib import Path
path=Path('reports/emulator/extended-qa-summary.json')
try:
    report=json.loads(path.read_text())
except (OSError, ValueError):
    print('Extended QA report missing after instrumentation; status='+sys.argv[1])
    raise SystemExit(0 if int(sys.argv[1]) else 1)
assert report['cases']==report['unique_inputs']==7500
print('EXTENDED_ANDROID_QA_SUMMARY='+json.dumps(report,ensure_ascii=False))
PY
if [ "$instrumentation_status" -ne 0 ]; then exit "$instrumentation_status"; fi
adb logcat -d -v brief -s RSK_PUNCT_BENCH:I '*:S' > reports/emulator/punctuation-benchmark.txt

adb install -r "$APK"
adb shell am start -n "$APP/.MainActivity"
adb shell ime list -a -s > reports/emulator/registered-ime-after-tests.txt
grep -F "$APP" reports/emulator/registered-ime-after-tests.txt
adb shell dumpsys meminfo "$APP" > reports/emulator/launcher-meminfo.txt
adb shell cat /proc/meminfo | head -n 6 > reports/emulator/system-meminfo.txt
