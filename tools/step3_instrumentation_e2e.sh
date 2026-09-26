#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
OUT=process-recovery-results/ui-instrumentation
mkdir -p "$OUT"
# Preserve a raw runner trace even when the runner crashes or exits nonzero.
trap 'adb logcat -d > "$OUT/logcat.txt" 2>&1 || true' EXIT
python -m unittest discover -s tools -p test_verify_step3_instrumentation.py -v
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop "$PACKAGE"
adb shell pm clear "$PACKAGE"
adb logcat -c
set +e
timeout 300s adb shell am instrument -w -r \
  -e class com.gymbuddy.app.Step3ComposeRepairTest,com.gymbuddy.app.Step3ActivityRecreationTest \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/runner.txt"
runner_status=${PIPESTATUS[0]}
set -e
printf '%s\n' "$runner_status" > "$OUT/runner-exit.txt"
# Do not use -e log true (discovery-only). Empty reports, skipped tests,
# missing methods, runner crashes and assertion failures all block this gate.
python tools/verify_step3_instrumentation.py --log "$OUT/runner.txt" --output "$OUT"
test "$runner_status" -eq 0
