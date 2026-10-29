#!/usr/bin/env bash
# The test emulator "nibsync_honor": as close as an x86 emulator gets to the HONOR YLE-W09 test
# tablet (Android 16 / API 36 with Google Play services, 1920x3000 panel used in landscape,
# 400 dpi, Gboard, hardware GPU). The tablet has 12 GB RAM; the emulator gets 2 GB to spare the
# dev PC (it once froze from RAM exhaustion).
#
#   scripts/emulator.sh start   create the AVD if missing, boot it headless, wait until ready
#   scripts/emulator.sh stop    shut it down
#   scripts/emulator.sh show    boot it with a window (to watch or try things by hand)
#
# Prints the adb serial (e.g. emulator-5554) when ready.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD=nibsync_honor
IMAGE="system-images;android-36;google_apis;x86_64"
PORT=5580 # fixed console port: serial emulator-5580, apart from other emulators
SERIAL="emulator-$PORT"

create() {
    [[ -d "$HOME/.android/avd/$AVD.avd" ]] && return
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --install "$IMAGE" >/dev/null
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_tablet --force >/dev/null
    python3 - "$HOME/.android/avd/$AVD.avd/config.ini" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
values = {
    'hw.lcd.width': '1920', 'hw.lcd.height': '3000', 'hw.lcd.density': '400', 'hw.lcd.depth': '32',
    'hw.initialOrientation': 'portrait', 'hw.ramSize': '2048M', 'hw.cpu.ncore': '4',
    'disk.dataPartition.size': '6G', 'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader_indirect',
    'hw.keyboard': 'no', 'skin.dynamic': 'yes', 'skin.name': '1920x3000', 'skin.path': '_no_skin',
}
for k, v in values.items():
    s, n = re.subn(rf'^{re.escape(k)}=.*$', f'{k}={v}', s, flags=re.M)
    if n == 0: s += f'{k}={v}\n'
open(p, 'w').write(s)
PY
}

running() { "$ADB" devices | grep -q "^$SERIAL[[:space:]]*device"; }

boot() {
    create
    if ! running; then
        # The PC's graphics card, reachable only while someone is logged in on the desktop (like
        # the tablet's GPU). The software renderer crashes Ink's stylus front buffer and hangs
        # the emulator, so test runs are refused without it (NIBSYNC_SOFTWARE_GPU=1 forces it).
        local gpu=swiftshader_indirect
        if DISPLAY="${NIBSYNC_GPU_DISPLAY:-:0}" glxinfo -B 2>/dev/null | grep -q "Accelerated: yes"; then
            gpu=host
            export DISPLAY="${NIBSYNC_GPU_DISPLAY:-:0}"
        elif [[ "${NIBSYNC_SOFTWARE_GPU:-}" != "1" ]]; then
            echo "No graphics card for the emulator: log in on the PC's desktop first." >&2
            exit 1
        fi
        # Always a cold boot: restoring snapshots made the emulator hang during tests.
        nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -gpu "$gpu" -memory 2048 -no-snapshot -no-audio -no-boot-anim "$@" >"/tmp/$AVD.log" 2>&1 &
        "$ADB" -s "$SERIAL" wait-for-device
        until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
        # Steady screen for UI tests: no animations, stays awake, unlocked.
        for s in window_animation_scale transition_animation_scale animator_duration_scale; do
            "$ADB" -s "$SERIAL" shell settings put global "$s" 0
        done
        "$ADB" -s "$SERIAL" shell svc power stayon true
        # German like the tablet (needs root; restarts the Android framework once).
        if [[ "$("$ADB" -s "$SERIAL" shell getprop persist.sys.locale | tr -d '\r')" != "de-DE" ]]; then
            "$ADB" -s "$SERIAL" root >/dev/null; "$ADB" -s "$SERIAL" wait-for-device
            "$ADB" -s "$SERIAL" shell "setprop persist.sys.locale de-DE; stop; start"
            sleep 5
            until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
            "$ADB" -s "$SERIAL" unroot >/dev/null 2>&1 || true; "$ADB" -s "$SERIAL" wait-for-device
        fi
        # Gboard's stylus handwriting would open a "try out your stylus" window over the app on
        # the first pen tap into a text field; the tablet's keyboard does not.
        "$ADB" -s "$SERIAL" shell settings put secure stylus_handwriting_enabled 0
        # Landscape like the tablet on its stand (3000x1920); fixed, no auto-rotation. Right after
        # booting the system sometimes switches auto-rotation on again (then the emulator turns
        # to portrait and tests tap beside the app), so lock it and check that it stays.
        for _ in 1 2 3 4 5; do
            "$ADB" -s "$SERIAL" shell settings put system accelerometer_rotation 0
            "$ADB" -s "$SERIAL" shell settings put system user_rotation 1
            "$ADB" -s "$SERIAL" shell wm user-rotation lock 1 >/dev/null 2>&1 || true
            sleep 3
            [[ "$("$ADB" -s "$SERIAL" shell settings get system accelerometer_rotation | tr -d '\r')" == "0" ]] && break
        done
        "$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    fi
    echo "$SERIAL"
}

case "${1:-start}" in
    start) boot -no-window ;;
    show) boot ;;
    stop)
        if running; then timeout 30 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true; fi
        # A hung emulator ignores the console: end it the hard way.
        sleep 2
        pkill -f "qemu-system.*-avd $AVD" 2>/dev/null || true
        sleep 3
        pkill -9 -f "qemu-system.*-avd $AVD" 2>/dev/null || true
        ;;
    *) echo "usage: $0 start|stop|show" >&2; exit 2 ;;
esac
