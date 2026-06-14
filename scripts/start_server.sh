#!/usr/bin/env bash
#
# Start the chatapp server on a real LAN host (Linux/macOS, e.g. the Raspberry Pi).
# No configuration: the server detects its own LAN address and broadcast, picks a
# random id, and uses the default ports. Just runs the jar.
#
# Usage:   scripts/start_server.sh        (set JAR=... to point at another jar)

set -euo pipefail

JAR="${JAR:-}"
if [[ -z "$JAR" ]]; then
  if [[ -f chatapp.jar ]]; then JAR=chatapp.jar; else JAR=target/chatapp.jar; fi
fi
[[ -f "$JAR" ]] || {
  echo "error: $JAR not found. Build with 'mvn package' or copy chatapp.jar next to this script." >&2
  exit 1
}

exec java -jar "$JAR" server
