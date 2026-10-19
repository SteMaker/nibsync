# Sourced by verify.sh --tablet: connects the real test tablet (USB or wireless debugging),
# pins ANDROID_SERIAL to it and makes sure its screen is on and unlocked.

# Real devices only: emulators are left out.
devices() { "$ADB" devices | awk -F'\t' 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {print $1}'; }
if [[ -z "$(devices)" ]]; then
    # Wireless debugging: connect to a device announced via mDNS (its port changes).
    for target in $("$ADB" mdns services | awk '/_adb-tls-connect/ {print $3}'); do
        timeout 10 "$ADB" connect "$target" >/dev/null 2>&1 || true
    done
fi
if [[ -z "$(devices)" ]]; then
    echo "No tablet connected. Connect it by USB, or switch 'Wireless debugging' off and on" >&2
    echo "on the tablet (it turns itself off when the screen sleeps)." >&2
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
done < <(devices)

# App-level tests draw on the real screen: it must be on and unlocked.
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 1
if "$ADB" shell dumpsys window | grep -q "isKeyguardShowing=true"; then
    echo "The tablet is locked. Unlock it and run again (tip: developer options > 'Stay awake')." >&2
    exit 1
fi
