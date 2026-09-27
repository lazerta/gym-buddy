#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.gymbuddy.app
HOST_PORT=8788
DEVICE_PORT=8788
SESSIONS_ROOT=${GYM_BUDDY_REAL_RGB_SESSIONS_ROOT:-build/real-rgb-sessions}
RESULTS_ROOT=${GYM_BUDDY_REAL_RGB_RESULTS_ROOT:-build/real-rgb-results}
mkdir -p "$RESULTS_ROOT"

cleanup_server() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
    SERVER_PID=""
  fi
}
cleanup() {
  cleanup_server
  adb reverse --remove "tcp:${DEVICE_PORT}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The ordinary simulator E2E server may still own this port. Release-RGB sessions
# use the same public frame protocol, so stop that server before taking over.
if [[ -f frame-server.pid ]]; then
  OLD_SERVER_PID=$(cat frame-server.pid || true)
  if [[ -n "${OLD_SERVER_PID:-}" ]]; then
    kill "$OLD_SERVER_PID" >/dev/null 2>&1 || true
    wait "$OLD_SERVER_PID" >/dev/null 2>&1 || true
  fi
fi

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear "$PACKAGE" >/dev/null
adb reverse "tcp:${DEVICE_PORT}" "tcp:${HOST_PORT}"

mapfile -t sessions < <(python - "$SESSIONS_ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
order={'g2-real-rgb':0,'g3-degraded':1,'g3-gap':2}
def key(p):
    prefix=next((k for k in order if p.name.startswith(k)),p.name)
    return order.get(prefix,99),p.name
for p in sorted((x for x in root.iterdir() if x.is_dir() and (x/'manifest.json').exists()),key=key):
    print(p)
PY
)
[[ ${#sessions[@]} -eq 9 ]] || { echo "Expected 9 release RGB sessions, got ${#sessions[@]}"; exit 1; }

for session in "${sessions[@]}"; do
  name=$(basename "$session")
  rm -f "$session/results.jsonl"
  log="$RESULTS_ROOT/${name}-server.log"
  python -m harness.frame_server --session "$session" --host 0.0.0.0 --port "$HOST_PORT" >"$log" 2>&1 &
  SERVER_PID=$!
  ready=0
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/session" >/dev/null; then ready=1; break; fi
    sleep 1
  done
  [[ $ready -eq 1 ]] || { cat "$log"; exit 1; }
  expected=$(python - "$session/manifest.json" <<'PY'
import json,sys
print(len(json.load(open(sys.argv[1]))['frames']))
PY
)
  adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  adb shell am start -W -n "$PACKAGE/.SimulatorE2EActivity" --es simulatorBaseUrl "http://127.0.0.1:${DEVICE_PORT}" >/dev/null
  complete=0
  for _ in $(seq 1 120); do
    count=$(curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" | python -c 'import json,sys; print(len(json.load(sys.stdin)))' || echo 0)
    if [[ "$count" -ge "$expected" ]]; then complete=1; break; fi
    sleep 1
  done
  if [[ $complete -ne 1 ]]; then
    echo "$name timed out: expected $expected results"
    adb logcat -d | tail -n 300 || true
    cat "$log" || true
    exit 1
  fi
  curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" > "$RESULTS_ROOT/${name}.json"
  echo "REAL_RGB_SESSION_COMPLETE session=$name frames=$expected"
  cleanup_server
  sleep 1
done

python tools/verify_real_rgb_release_results.py \
  --sessions-root "$SESSIONS_ROOT" \
  --results-root "$RESULTS_ROOT" \
  --output "$RESULTS_ROOT/real-rgb-verification.json"
