#!/usr/bin/env bash
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk

adb shell am start -W   -n com.gymbuddy.app/.SimulatorE2EActivity   --es simulatorBaseUrl http://10.0.2.2:8788

success=0
for i in $(seq 1 60); do
  count=$(curl -fsS http://127.0.0.1:8788/v1/results | python -c "import json,sys; print(len(json.load(sys.stdin)))" || echo 0)
  if [ "$count" -ge 3 ]; then
    success=1
    break
  fi
  sleep 2
done

if [ "$success" -ne 1 ]; then
  echo "E2E timed out waiting for 3 results"
  adb logcat -d | tail -n 400
  curl -fsS http://127.0.0.1:8788/v1/results || true
  exit 1
fi

curl -fsS http://127.0.0.1:8788/v1/results | tee e2e-results.json

adb shell am start -W -n com.gymbuddy.app/.ProductionMovementE2EActivity

passed=0
for i in $(seq 1 30); do
  if adb shell run-as com.gymbuddy.app cat files/production-movement-e2e.json > production-movement-e2e.json 2>/dev/null; then
    if python - <<'PY'
import json
from pathlib import Path
p = json.loads(Path("production-movement-e2e.json").read_text())
raise SystemExit(0 if p.get("passed") is True else 1)
PY
    then
      passed=1
      break
    fi
  fi
  sleep 1
done

if [ "$passed" -ne 1 ]; then
  echo "Production movement emulator gate failed"
  cat production-movement-e2e.json 2>/dev/null || true
  adb logcat -d | tail -n 400
  exit 1
fi

python - <<'PY'
import json
from pathlib import Path
p = json.loads(Path("production-movement-e2e.json").read_text())
profiles = p["profiles"]
assert len(profiles) == 3, profiles
assert {x["exercise_id"] for x in profiles} == {
    "incline_dumbbell_press",
    "smith_machine_squat",
    "dumbbell_lateral_raise",
}
for x in profiles:
    assert x["completed_reps"] == 1, x
    assert x["known_signal_frames"] > 0, x
print("ANDROID_PRODUCTION_MOVEMENT_E2E_PASS")
PY
