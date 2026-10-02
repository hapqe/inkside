#!/bin/bash
# Keep the Inkside host listening on PORT (default 8787).
# Intended for launchd StartInterval (every ~2 min). The LaunchAgent MUST set
# AbandonProcessGroup=true so the nohup'd node survives when this script exits.
set -euo pipefail

BRIDGE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8787}"
HOST_CHECK="${HOST_CHECK:-127.0.0.1}"
LOG_DIR="${BRIDGE_DIR}/logs"
LOG_FILE="${LOG_DIR}/bridge.log"
PID_FILE="${LOG_DIR}/bridge.pid"
NODE="${NODE_BIN:-$(command -v node)}"

mkdir -p "$LOG_DIR"

health_ok() {
  curl -fsS -m 3 "http://${HOST_CHECK}:${PORT}/health" >/dev/null 2>&1
}

if health_ok; then
  exit 0
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') bridge unhealthy or down" >>"$LOG_FILE"

# Free a stale/hung listener so we can bind again.
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "$(date '+%Y-%m-%d %H:%M:%S') clearing listeners on :$PORT" >>"$LOG_FILE"
  # shellcheck disable=SC2046
  kill $(lsof -t -iTCP:"$PORT" -sTCP:LISTEN) 2>/dev/null || true
  sleep 1
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    # shellcheck disable=SC2046
    kill -9 $(lsof -t -iTCP:"$PORT" -sTCP:LISTEN) 2>/dev/null || true
    sleep 1
  fi
fi

if [ -z "${NODE}" ] || [ ! -x "${NODE}" ]; then
  echo "$(date '+%Y-%m-%d %H:%M:%S') node not found on PATH" >>"$LOG_FILE"
  exit 1
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') starting bridge on :$PORT with ${NODE}" >>"$LOG_FILE"
cd "$BRIDGE_DIR"
# nohup + redirect stdin; AbandonProcessGroup on the LaunchAgent keeps us alive after exit.
nohup "${NODE}" src/server.mjs >>"$LOG_FILE" 2>&1 </dev/null &
echo $! >"$PID_FILE"

for _ in $(seq 1 20); do
  if health_ok; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') bridge up (pid $(cat "$PID_FILE"))" >>"$LOG_FILE"
    exit 0
  fi
  sleep 0.5
done

echo "$(date '+%Y-%m-%d %H:%M:%S') bridge failed to become healthy" >>"$LOG_FILE"
exit 1
