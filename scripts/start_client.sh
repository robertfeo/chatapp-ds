#!/usr/bin/env bash
#
# Start a chatapp client on a real LAN host (Linux/macOS). No configuration:
# the client discovers the leader on its own. Just runs the jar.
#
# Usage:   scripts/start_client.sh        (set JAR=... to point at another jar)

set -euo pipefail

JAR="${JAR:-}"
if [[ -z "$JAR" ]]; then
  if [[ -f chatapp.jar ]]; then JAR=chatapp.jar; else JAR=target/chatapp.jar; fi
fi
[[ -f "$JAR" ]] || {
  echo "error: $JAR not found. Copy chatapp.jar next to this script." >&2
  exit 1
}

exec java -jar "$JAR" client
