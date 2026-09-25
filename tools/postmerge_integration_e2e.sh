#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
adb shell run-as "$PACKAGE" rm -f files/postmerge-integration-e2e.json
adb shell am start -W -n "$PACKAGE/.PostMergeIntegrationE2EActivity"
for i in $(seq 1 90); do
  if adb shell run-as "$PACKAGE" cat files/postmerge-integration-e2e.json > postmerge-integration-e2e.json 2>/dev/null; then
    python - <<'PY'
import json
from pathlib import Path
p=json.loads(Path("postmerge-integration-e2e.json").read_text())
print(json.dumps(p,indent=2))
expected={"attempt_checkpoint_atomicity", "stopped_set_rejects_movement", "retry_preserves_stop_time",
          "successful_finish_is_immutable", "runtime_room_controller_rep_agreement", "rest_uses_committed_end"}
assert len(p["tests"])==len(expected), p
assert {t["name"] for t in p["tests"]}==expected, p
assert all(t["passed"] is True for t in p["tests"]) and p["passed"] is True, p
print("ANDROID_POSTMERGE_INTEGRATION_E2E_PASS")
PY
    exit 0
  fi
  sleep 1
done
echo "Post-merge integration gate timed out"
adb logcat -d | tail -n 400
exit 1
