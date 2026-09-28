#!/usr/bin/env bash
set -euo pipefail

ROOT=${1:-build/g2g3}
PACKAGE=com.gymbuddy.app
HOST_PORT=8788
DEVICE_PORT=8788

if [ ! -f "$ROOT/index.json" ]; then
  echo "missing $ROOT/index.json" >&2
  exit 1
fi

if [ -f frame-server.pid ]; then
  kill "$(cat frame-server.pid)" >/dev/null 2>&1 || true
  rm -f frame-server.pid
fi

mapfile -t sessions < <(python - "$ROOT/index.json" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
for item in json.loads(p.read_text())["sessions"]:
    print(str(p.parent/item))
PY
)

for session in "${sessions[@]}"; do
  echo "G2G3_ANDROID_SESSION_START $session"
  rm -f "$session/results.jsonl" "$session/android-results.json"
  nohup python -m harness.frame_server     --session "$session"     --host 0.0.0.0     --port "$HOST_PORT"     > "$session/frame-server.log" 2>&1 &
  server_pid=$!

  cleanup_server() {
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
  }

  ready=0
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/session" >/dev/null; then
      ready=1
      break
    fi
    sleep 1
  done
  if [ "$ready" -ne 1 ]; then
    cat "$session/frame-server.log" || true
    cleanup_server
    exit 1
  fi

  frame_count=$(python - "$session/manifest.json" <<'PY'
import json,sys
print(len(json.load(open(sys.argv[1]))["frames"]))
PY
)

  adb shell am force-stop "$PACKAGE" >/dev/null || true
  adb shell pm clear "$PACKAGE" >/dev/null
  adb reverse "tcp:${DEVICE_PORT}" "tcp:${HOST_PORT}"
  adb logcat -c || true
  adb shell am start -W     -n "$PACKAGE/.SimulatorE2EActivity"     --es simulatorBaseUrl "http://127.0.0.1:${DEVICE_PORT}" >/dev/null

  success=0
  for _ in $(seq 1 120); do
    count=$(curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" | python -c "import json,sys; print(len(json.load(sys.stdin)))" || echo 0)
    if [ "$count" -ge "$frame_count" ]; then
      success=1
      break
    fi
    sleep 1
  done
  if [ "$success" -ne 1 ]; then
    echo "G2/G3 session timed out: $session expected=$frame_count" >&2
    adb logcat -d | tail -n 300 || true
    cat "$session/frame-server.log" || true
    cleanup_server
    exit 1
  fi

  curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" > "$session/android-results.json"
  cleanup_server
  echo "G2G3_ANDROID_SESSION_PASS $session frames=$frame_count"
done

python tools/g2g3_release_pipeline.py score   --root "$ROOT"   --sim-summary build/harness-contracts/commercial-gym-summary.json   --output "$ROOT/release-report.json"
