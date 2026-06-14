#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

URL="https://github.com/robertfeo/chatapp-ds/releases/download/v1.0.0/chatapp.jar"
JAR="chatapp.jar"

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

if [ ! -f "$JAR" ]; then
    echo "Downloading $JAR..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL -o "$JAR" "$URL"
    else
        wget -O "$JAR" "$URL"
    fi
fi

java -jar "$JAR" "$@"
