#!/usr/bin/env bash
set -euo pipefail

PREPARED_ROOT=${1:?prepared root required}
OUTPUT_ROOT=${2:?output root required}
PACKAGE=com.gymbuddy.app
HOST_PORT=8788
DEVICE_PORT=8788
mkdir -p "$OUTPUT_ROOT" "$OUTPUT_ROOT/logs"

cleanup_server() {
  if [ -n "${SERVER_PID:-}" ]; then
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

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse "tcp:${DEVICE_PORT}" "tcp:${HOST_PORT}"

python - "$PREPARED_ROOT/prepared.json" > "$OUTPUT_ROOT/sessions.tsv" <<'PY'
import json, sys
p=json.load(open(sys.argv[1], encoding='utf-8'))
for s in p['sessions']:
    print('\t'.join([s['session_id'],s['path'],s['exercise_id'],s['kind'],str(s['expected_reps'])]))
PY

while IFS=$'\t' read -r session_id rel_path exercise_id kind expected_reps; do
  session_root="$PREPARED_ROOT/$rel_path"
  manifest="$session_root/manifest.json"
  frame_count=$(python - "$manifest" <<'PY'
import json,sys
print(len(json.load(open(sys.argv[1],encoding='utf-8'))['frames']))
PY
)
  rm -f "$session_root/results.jsonl"
  log="$OUTPUT_ROOT/logs/${session_id}.server.log"
  python -m harness.frame_server --session "$session_root" --host 0.0.0.0 --port "$HOST_PORT" >"$log" 2>&1 &
  SERVER_PID=$!
  ready=0
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/session" >/dev/null; then ready=1; break; fi
    sleep 1
  done
  if [ "$ready" -ne 1 ]; then
    cat "$log" || true
    echo "frame server failed for $session_id"
    exit 1
  fi

  adb shell pm clear "$PACKAGE" >/dev/null
  adb reverse "tcp:${DEVICE_PORT}" "tcp:${HOST_PORT}" >/dev/null
  adb logcat -c || true
  adb shell am start -W     -n "$PACKAGE/.SimulatorE2EActivity"     --es simulatorBaseUrl "http://127.0.0.1:${DEVICE_PORT}" >/dev/null

  complete=0
  for _ in $(seq 1 180); do
    count=$(curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" | python -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
    if [ "$count" -ge "$frame_count" ]; then complete=1; break; fi
    sleep 1
  done
  if [ "$complete" -ne 1 ]; then
    echo "Android production session timed out: $session_id expected=$frame_count"
    adb logcat -d | tail -n 500 || true
    cat "$log" || true
    exit 1
  fi

  curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" > "$OUTPUT_ROOT/${session_id}.json"
  python - "$OUTPUT_ROOT/${session_id}.json" "$session_id" "$frame_count" <<'PY'
import json,sys
p=json.load(open(sys.argv[1],encoding='utf-8'))
sid=sys.argv[2]; expected=int(sys.argv[3])
assert len(p)==expected,(sid,len(p),expected)
for i,x in enumerate(p):
    assert x['session_id']==sid,(i,x.get('session_id'),sid)
    assert x['frame_id']==i,(sid,i,x.get('frame_id'))
    a=x['analysis']
    for k in ['pose_count','tracking_state','tracking_reason','camera_guidance','set_lifecycle_state','rep_count','rep_events','cues']:
        assert k in a,(sid,i,k,a)
print(f'G2G3_ANDROID_SESSION_PASS session={sid} frames={len(p)} final_reps={p[-1]["analysis"]["rep_count"]}')
PY
  cleanup_server
done < "$OUTPUT_ROOT/sessions.tsv"

echo "G2G3_ANDROID_ALL_SESSIONS_PASS"
