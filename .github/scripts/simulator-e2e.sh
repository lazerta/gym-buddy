#!/usr/bin/env bash
set -euo pipefail

adb install -r app/build/outputs/apk/debug/app-debug.apk

adb shell am start -W \
  -n com.gymbuddy.app/.MainActivity \
  --es simulatorBaseUrl http://10.0.2.2:8788 \
  --ez autoStartSimulator true

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
