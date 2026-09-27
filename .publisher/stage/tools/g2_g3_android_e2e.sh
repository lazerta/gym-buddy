#!/usr/bin/env bash
set -euo pipefail

PACKAGE=com.gymbuddy.app
HOST_PORT=8790
DEVICE_PORT=8790
SESSIONS_ROOT="${GYM_BUDDY_G2_G3_SESSIONS_ROOT:-build/g2-g3-sessions}"
REGISTRY="${GYM_BUDDY_G2_G3_REGISTRY:-tools/g2_g3_video_registry.json}"
G2_DIR="build/g2-g3-evidence/g2"
G3_DIR="build/g2-g3-evidence/g3"
REVIEW_DIR="build/g2-g3-review"
SERVER_PID=""

cleanup_server() {
  if [[ -n "${SERVER_PID}" ]]; then
    kill "${SERVER_PID}" >/dev/null 2>&1 || true
    wait "${SERVER_PID}" >/dev/null 2>&1 || true
    SERVER_PID=""
  fi
}
cleanup() {
  cleanup_server
  adb reverse --remove "tcp:${DEVICE_PORT}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rm -rf "${SESSIONS_ROOT}" "${G2_DIR}" "${G3_DIR}" "${REVIEW_DIR}"
mkdir -p "${G2_DIR}" "${G3_DIR}" "${REVIEW_DIR}"

# Network-free unit tests run before any external source acquisition.
python tools/g2_g3_tools_test.py

python tools/prepare_g2_g3_sessions.py \
  --registry "${REGISTRY}" \
  --output-root "${SESSIONS_ROOT}" \
  --variant both
python tools/build_g2_g3_review_artifacts.py \
  --sessions-root "${SESSIONS_ROOT}" \
  --output-root "${REVIEW_DIR}"
mkdir -p app/build/reports/androidTests/g2-g3-review
cp -f "${REVIEW_DIR}"/* app/build/reports/androidTests/g2-g3-review/

adb reverse "tcp:${DEVICE_PORT}" "tcp:${HOST_PORT}"

run_session() {
  local session="$1"
  local tier="$2"
  local name expected results outdir
  name="$(basename "${session}")"
  outdir="${G2_DIR}"
  [[ "${tier}" == "g3" ]] && outdir="${G3_DIR}"
  expected="$(python - "${session}/manifest.json" <<'PY'
import json,sys
print(len(json.load(open(sys.argv[1], encoding='utf-8'))['frames']))
PY
)"
  rm -f "${session}/results.jsonl" "${REVIEW_DIR}/${name}-server.log"
  python -m harness.frame_server \
    --session "${session}" --host 0.0.0.0 --port "${HOST_PORT}" \
    > "${REVIEW_DIR}/${name}-server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/session" >/dev/null; then break; fi
    sleep 1
  done
  curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/session" >/dev/null

  adb shell am force-stop "${PACKAGE}" >/dev/null || true
  adb shell am start -W \
    -n "${PACKAGE}/.SimulatorE2EActivity" \
    --es simulatorBaseUrl "http://127.0.0.1:${DEVICE_PORT}" >/dev/null

  local complete=0
  for _ in $(seq 1 360); do
    local count
    count="$(curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" | python -c 'import json,sys; print(len(json.load(sys.stdin)))' || echo 0)"
    if [[ "${count}" -ge "${expected}" ]]; then complete=1; break; fi
    sleep 2
  done
  if [[ "${complete}" -ne 1 ]]; then
    echo "G2/G3 session timed out: ${name} expected=${expected}" >&2
    adb logcat -d | tail -n 500 >&2 || true
    cat "${REVIEW_DIR}/${name}-server.log" >&2 || true
    return 1
  fi
  results="${outdir}/${name}-results.json"
  curl -fsS "http://127.0.0.1:${HOST_PORT}/v1/results" > "${results}"
  python tools/verify_g2_g3_results.py \
    --session "${session}" \
    --results "${results}" \
    --output "${outdir}/${name}.json"
  cleanup_server
}

for session in "${SESSIONS_ROOT}"/*-baseline; do run_session "${session}" g2; done
for session in "${SESSIONS_ROOT}"/*-stress; do run_session "${session}" g3; done

python tools/summarize_g3_integrated.py \
  --commercial-gym build/harness-contracts/commercial-gym-summary.json \
  --g2-dir "${G2_DIR}" \
  --g3-dir "${G3_DIR}" \
  --output build/g2-g3-evidence/g3-integrated-summary.json

echo "G2_REAL_RGB_ALL_RELEASE_EXERCISES_PASS"
echo "G3_INTEGRATED_REALITY_STRESS_PASS"
