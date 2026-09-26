#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
adb shell run-as "$PACKAGE" rm -f files/step4-whole-workflow-e2e.json
adb shell am start -W -n "$PACKAGE/.Step4WholeWorkflowE2EActivity"
for i in $(seq 1 120); do
  if adb shell run-as "$PACKAGE" cat files/step4-whole-workflow-e2e.json > step4-whole-workflow-e2e.json 2>/dev/null; then
    python - <<'PY'
import json
from pathlib import Path
p=json.loads(Path("step4-whole-workflow-e2e.json").read_text())
print(json.dumps(p,indent=2))
assert p["passed"] is True, p
assert [t["name"] for t in p["tests"]]==[
    "two_set_restart_substitution_equipment_load_interruption_history_export"
], p
assert all(t["passed"] is True for t in p["tests"]), p
print("STEP4_WHOLE_WORKFLOW_E2E_PASS")
PY
    exit 0
  fi
  sleep 1
done
echo "Step 4 whole-workflow gate timed out"
adb logcat -d | tail -n 400
exit 1
