#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DESTINATION="$ROOT/gradle/wrapper/gradle-wrapper.jar"
URL="https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

mkdir -p "$(dirname "$DESTINATION")"
if command -v curl >/dev/null 2>&1; then
    curl -L --fail "$URL" -o "$DESTINATION"
else
    wget -O "$DESTINATION" "$URL"
fi

ACTUAL=$(sha256sum "$DESTINATION" | awk '{print $1}')
if [ "$ACTUAL" != "$EXPECTED" ]; then
    rm -f "$DESTINATION"
    echo "Gradle wrapper checksum mismatch. Expected $EXPECTED, got $ACTUAL." >&2
    exit 1
fi

echo "Gradle wrapper installed and verified: $DESTINATION"
