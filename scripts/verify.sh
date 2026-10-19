#!/usr/bin/env bash
# Runs every test suite of the project. Instrumented tests run on the emulator "nibsync_honor"
# (scripts/emulator.sh, modelled on the HONOR test tablet), started and stopped as needed.
#
#   scripts/verify.sh              unit tests, lint, instrumented tests on the emulator
#   scripts/verify.sh --tablet     instrumented tests on the real tablet instead (USB/WLAN)
#   scripts/verify.sh --no-device  skip the instrumented tests
#
# Set NIBSYNC_KEEP_EMULATOR=1 to leave the emulator running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:-emulator}"
ADB="${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb"

# The Gradle and Kotlin daemons keep several GB after the run; the dev PC needs that memory
# back (it once froze from RAM exhaustion). Set NIBSYNC_KEEP_DAEMONS=1 to keep them warm.
cleanup() {
    if [[ "${NIBSYNC_KEEP_DAEMONS:-}" != "1" ]]; then ./gradlew --stop >/dev/null 2>&1 || true; fi
    if [[ "${STARTED_EMULATOR:-}" == "1" && "${NIBSYNC_KEEP_EMULATOR:-}" != "1" ]]; then scripts/emulator.sh stop || true; fi
}
trap cleanup EXIT

echo "== Unit tests (JVM + Robolectric) and lint"
./gradlew --console=plain test lintDebug

case "$MODE" in
    --no-device)
        echo "== Skipping instrumented tests"
        exit 0
        ;;
    emulator)
        # Free the build daemons' memory before the emulator (about 4 GB) starts.
        ./gradlew --stop >/dev/null 2>&1 || true
        "$ADB" devices | grep -q '^emulator-5580[[:space:]]*device' || STARTED_EMULATOR=1
        export ANDROID_SERIAL="$(scripts/emulator.sh start)"
        # The emulator reaches the PC (and its Docker Nextcloud) as 10.0.2.2.
        export NIBSYNC_TEST_NEXTCLOUD_URL="${NIBSYNC_TEST_NEXTCLOUD_URL:-http://10.0.2.2:8089}"
        ;;
    --tablet)
        source scripts/tablet.sh
        export NIBSYNC_TEST_NEXTCLOUD_URL="${NIBSYNC_TEST_NEXTCLOUD_URL:-http://$(hostname -I | awk '{print $1}'):8089}"
        ;;
    *)
        echo "usage: $0 [--tablet|--no-device]" >&2
        exit 2
        ;;
esac

echo "== Instrumented tests on $("$ADB" shell getprop ro.product.model | tr -d '\r') ($ANDROID_SERIAL)"
# A full run takes about 5 minutes; a hung device must not block forever.
timeout 30m ./gradlew --console=plain connectedDebugAndroidTest
