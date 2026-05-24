#!/usr/bin/env bash
#
# Local dev runner: start a small cluster of servers (and clients) on localhost,
# each as a background JVM writing to its own log file. Portable across Linux and
# macOS (plain bash + nohup + kill); no tmux or Docker required.
#
# Usage:
#   scripts/dev.sh up      # start SERVERS servers + CLIENTS clients
#   scripts/dev.sh down    # stop everything started by 'up', leaving no orphans
#   scripts/dev.sh status  # show what is running
#
# Everything is configured via env vars, exactly like a real host:
#   SERVERS         number of servers           (default 3, ids 1..SERVERS)
#   CLIENTS         number of clients           (default 2)
#   BASE_LISTEN_PORT  server i listens on BASE+i (default 6000 -> 6001,6002,...)
#   DISCOVERY_PORT  shared UDP discovery port   (default 4500)
#   BROADCAST_ADDR  discovery broadcast address (default 255.255.255.255)
#   LISTEN_HOST     server bind address         (default 127.0.0.1)
#   JAR             fat-jar path                (default target/chatapp.jar)
#   RUN_DIR         logs + pid file location    (default target/dev)
#
# Several servers share one UDP discovery port; the discovery code sets
# SO_REUSEPORT so that works on localhost (Linux needs it, not just SO_REUSEADDR).

set -euo pipefail

SERVERS="${SERVERS:-3}"
CLIENTS="${CLIENTS:-2}"
BASE_LISTEN_PORT="${BASE_LISTEN_PORT:-6000}"
DISCOVERY_PORT="${DISCOVERY_PORT:-4500}"
BROADCAST_ADDR="${BROADCAST_ADDR:-255.255.255.255}"
LISTEN_HOST="${LISTEN_HOST:-127.0.0.1}"
JAR="${JAR:-target/chatapp.jar}"
RUN_DIR="${RUN_DIR:-target/dev}"
PID_FILE="${RUN_DIR}/pids"

up() {
  if [[ ! -f "${JAR}" ]]; then
    echo "error: ${JAR} not found. Run 'make package' first." >&2
    exit 1
  fi
  if [[ -f "${PID_FILE}" ]]; then
    echo "error: a dev cluster is already running (${PID_FILE}). Run 'down' first." >&2
    exit 1
  fi
  mkdir -p "${RUN_DIR}"
  : > "${PID_FILE}"

  echo "starting ${SERVERS} server(s) and ${CLIENTS} client(s); logs in ${RUN_DIR}/"

  for ((id = 1; id <= SERVERS; id++)); do
    local port=$((BASE_LISTEN_PORT + id))
    SERVER_ID="${id}" \
      LISTEN_HOST="${LISTEN_HOST}" \
      LISTEN_PORT="${port}" \
      DISCOVERY_PORT="${DISCOVERY_PORT}" \
      BROADCAST_ADDR="${BROADCAST_ADDR}" \
      nohup java -jar "${JAR}" server >"${RUN_DIR}/server-${id}.log" 2>&1 &
    echo "$!" >>"${PID_FILE}"
    echo "  server id=${id} listen=${LISTEN_HOST}:${port} pid=$!"
  done

  for ((c = 1; c <= CLIENTS; c++)); do
    DISCOVERY_PORT="${DISCOVERY_PORT}" \
      BROADCAST_ADDR="${BROADCAST_ADDR}" \
      nohup java -jar "${JAR}" client >"${RUN_DIR}/client-${c}.log" 2>&1 &
    echo "$!" >>"${PID_FILE}"
    echo "  client ${c} pid=$!"
  done

  echo
  echo "tail a log with:  tail -f ${RUN_DIR}/server-1.log"
  echo "stop everything:  scripts/dev.sh down   (or make stop)"
}

down() {
  if [[ ! -f "${PID_FILE}" ]]; then
    echo "nothing to stop (${PID_FILE} not found)"
    return 0
  fi
  while read -r pid; do
    [[ -z "${pid}" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done <"${PID_FILE}"

  # Give them a moment, then force any survivors.
  sleep 1
  while read -r pid; do
    [[ -z "${pid}" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      kill -9 "${pid}" 2>/dev/null || true
    fi
  done <"${PID_FILE}"

  rm -f "${PID_FILE}"
  echo "stopped all dev processes"
}

status() {
  if [[ ! -f "${PID_FILE}" ]]; then
    echo "no dev cluster running"
    return 0
  fi
  while read -r pid; do
    [[ -z "${pid}" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      echo "alive pid=${pid}"
    else
      echo "dead  pid=${pid}"
    fi
  done <"${PID_FILE}"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  *)
    echo "usage: $0 {up|down|status}" >&2
    exit 2
    ;;
esac
