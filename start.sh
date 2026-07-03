#!/usr/bin/env bash
#
# One-command launcher: ./start.sh server | ./start.sh client
#
# Finds a runnable jar in this order:
#   1. target/chatapp.jar   (a source checkout that was already built)
#   2. ./chatapp.jar        (downloaded next to this script on an earlier run)
#   3. build from source    (source checkout + Maven available)
#   4. download the latest release jar
set -euo pipefail

cd "$(dirname "$0")"

URL="https://github.com/robertfeo/chatapp-ds/releases/latest/download/chatapp.jar"

usage() {
    echo "usage: $0 <server|client>" >&2
    exit 2
}

[ $# -ge 1 ] || usage
case "$1" in
    server|client) ;;
    *) usage ;;
esac

if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java is not installed or not on PATH." >&2
    echo "Please install a Java 21 runtime: https://adoptium.net/" >&2
    exit 1
fi

VERSION_OUTPUT="$(java -version 2>&1)"
RAW="$(echo "$VERSION_OUTPUT" | head -n1 | sed -E 's/.*version "([^"]+)".*/\1/')"
MAJOR="$(echo "$RAW" | cut -d. -f1)"
if [ "$MAJOR" = "1" ]; then
    MAJOR="$(echo "$RAW" | cut -d. -f2)"
fi

if [ -z "$MAJOR" ] || ! [ "$MAJOR" -ge 21 ] 2>/dev/null; then
    echo "Error: Java 21 or newer is required. Found version $RAW." >&2
    echo "Please install a Java 21 runtime: https://adoptium.net/" >&2
    exit 1
fi

if [ -f "target/chatapp.jar" ]; then
    JAR="target/chatapp.jar"
elif [ -f "chatapp.jar" ]; then
    JAR="chatapp.jar"
elif [ -f "pom.xml" ] && command -v mvn >/dev/null 2>&1; then
    echo "Building chatapp.jar from source (mvn -DskipTests package)..."
    mvn -q -DskipTests package
    JAR="target/chatapp.jar"
else
    echo "Downloading chatapp.jar..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL -o "chatapp.jar" "$URL"
    else
        wget -O "chatapp.jar" "$URL"
    fi
    JAR="chatapp.jar"
fi

exec java -jar "$JAR" "$@"
