#!/usr/bin/env bash
# Runs every test suite of the project. Instrumented tests need a connected device
# (USB debugging enabled); pass --no-device to skip them.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== Unit tests (JVM + Robolectric) and lint"
./gradlew --console=plain test lintDebug

if [[ "${1:-}" == "--no-device" ]]; then
    echo "== Skipping instrumented tests"
    exit 0
fi

ADB="${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb"
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "No device connected (adb devices is empty). Connect the tablet or use --no-device." >&2
    exit 1
fi

echo "== Instrumented tests on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
./gradlew --console=plain connectedDebugAndroidTest
