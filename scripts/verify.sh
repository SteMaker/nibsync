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
# Not `adb get-state`: that fails as soon as more than one connection exists.
has_device() { "$ADB" devices | awk -F'\t' 'NR > 1 && $2 == "device"' | grep -q .; }
if ! has_device; then
    # Wireless debugging: connect to a device announced via mDNS (its port changes).
    for target in $("$ADB" mdns services | awk '/_adb-tls-connect/ {print $3}'); do
        timeout 10 "$ADB" connect "$target" >/dev/null 2>&1 || true
    done
fi
if ! has_device; then
    echo "No device connected. Connect the tablet by USB, or switch 'Wireless debugging' off and on" >&2
    echo "on the tablet (it turns itself off when the screen sleeps). Or use --no-device." >&2
    exit 1
fi

# The same tablet can show up twice (USB + WLAN, or two mDNS names); Gradle would then run every
# test twice on it. Keep one connection per hardware serial number.
declare -A seen
# Names can contain spaces (e.g. "adb-XYZ (2)._adb-tls-connect._tcp"); columns are tab separated.
while IFS= read -r target; do
    serial=$("$ADB" -s "$target" shell getprop ro.serialno </dev/null | tr -d '\r')
    if [[ -n "${seen[$serial]:-}" ]]; then
        "$ADB" disconnect "$target" >/dev/null 2>&1 || true
    else
        seen[$serial]=1
        # Pin Gradle to this connection, in case mDNS re-adds the duplicate meanwhile.
        export ANDROID_SERIAL="${ANDROID_SERIAL:-$target}"
    fi
done < <("$ADB" devices | awk -F'\t' 'NR > 1 && $2 == "device" {print $1}')

# App-level tests draw on the real screen: it must be on and unlocked.
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 1
if "$ADB" shell dumpsys window | grep -q "isKeyguardShowing=true"; then
    echo "The device is locked. Unlock it and run again (tip: developer options > 'Stay awake')." >&2
    exit 1
fi

echo "== Instrumented tests on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
./gradlew --console=plain connectedDebugAndroidTest
