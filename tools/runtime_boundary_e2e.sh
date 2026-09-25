#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
adb shell run-as "$PACKAGE" rm -f files/runtime-boundary-e2e.json
adb shell am start -W -n "$PACKAGE/.RuntimeBoundaryE2EActivity"
for i in $(seq 1 60); do
  if adb shell run-as "$PACKAGE" cat files/runtime-boundary-e2e.json > runtime-boundary-e2e.json 2>/dev/null; then
    python - <<'PY'
import json
from pathlib import Path
p=json.loads(Path("runtime-boundary-e2e.json").read_text())
print(json.dumps(p,indent=2))
assert p["passed"] is True, p
assert len(p["tests"]) == 2, p
print("ANDROID_RUNTIME_BOUNDARY_E2E_PASS")
PY
    exit 0
  fi
  sleep 1
done
echo "Runtime boundary gate timed out"
adb logcat -d | tail -n 400
exit 1
