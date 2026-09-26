#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
mkdir -p process-recovery-results
wait_result() {
  local stage="$1"
  for i in $(seq 1 120); do
    if adb shell run-as "$PACKAGE" cat "files/step3-$stage.json" > "process-recovery-results/$stage.json" 2>/dev/null; then
      python - "$stage" <<'CHECK'
import json,sys
from pathlib import Path
p=json.loads((Path('process-recovery-results')/(sys.argv[1]+'.json')).read_text())
print(json.dumps(p,indent=2))
assert p['passed'] is True,p
CHECK
      return
    fi
    sleep 1
  done
  adb logcat -d | tail -n 400
  return 1
}
for mode in rest active; do
  adb shell am force-stop "$PACKAGE"
  adb shell run-as "$PACKAGE" rm -f "files/step3-seed-$mode.json" "files/step3-verify-$mode.json"
  adb shell am start -W -n "$PACKAGE/.Step3ProcessRecoveryE2EActivity" --es stage "seed-$mode"
  wait_result "seed-$mode"
  old_pid=$(adb shell pidof "$PACKAGE" | tr -d '\r')
  test -n "$old_pid"
  adb shell am force-stop "$PACKAGE"
  if adb shell pidof "$PACKAGE" | grep -q '[0-9]'; then echo 'Old app process survived force-stop'; exit 1; fi
  adb shell am start -W -n "$PACKAGE/.Step3ProcessRecoveryE2EActivity" --es stage "verify-$mode"
  wait_result "verify-$mode"
  python - "$mode" <<'CHECK'
import json,sys
from pathlib import Path
p=Path('process-recovery-results');mode=sys.argv[1]
a=json.loads((p/f'seed-{mode}.json').read_text())
b=json.loads((p/f'verify-{mode}.json').read_text())
assert a['pid']!=b['pid'],(a,b)
CHECK
  adb shell am force-stop "$PACKAGE"
done
echo STEP3_PROCESS_KILL_RECOVERY_PASS
