#!/usr/bin/env bash
set -euo pipefail
adb shell run-as com.gymbuddy.app rm -f files/step3-review-e2e.json
adb shell am start -W -n com.gymbuddy.app/.Step3ReviewE2EActivity
for i in $(seq 1 90); do
  if adb shell run-as com.gymbuddy.app cat files/step3-review-e2e.json > step3-review-e2e.json 2>/dev/null; then
    python - <<'PY'
import json
from pathlib import Path
p=json.loads(Path('step3-review-e2e.json').read_text())
print(json.dumps(p,indent=2))
expected={'selection_leaves_ui_thread_free','selection_failure_rolls_back_and_retries','reset_targets_selected_equipment','new_workout_failure_is_atomic','completion_uses_durable_set_count'}
assert len(p['tests'])==len(expected) and {t['name'] for t in p['tests']}==expected
assert p['passed'] is True and all(t['passed'] is True for t in p['tests']), p
print('STEP3_REVIEW_E2E_PASS')
PY
    exit 0
  fi
  sleep 1
done
adb logcat -d | tail -n 200
exit 1
