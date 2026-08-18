#!/usr/bin/env bash
#
# Bring up the whole stack against every attached device.
#
#   ./android/run-dev.sh
#
# Handles multiple emulators, which is the useful setup here: one signed in as
# a teacher, one as a student, so you can watch one side affect the other.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Android Studio's toolchain, falling back to the Homebrew one.
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
else
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

PORT=8000
PKG=edu.iitgoa.attendance
APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

# Where the seeded sessions are anchored (IIT Goa, Farmagudi).
LAT=15.3925
LON=73.8785

echo "==> Devices"
mapfile -t DEVICES < <(adb devices | awk '/\tdevice$/ {print $1}')
if [ ${#DEVICES[@]} -eq 0 ]; then
    echo "    none attached. Start an emulator from Android Studio's Device"
    echo "    Manager, or: emulator -avd <name>"
    echo "    List AVDs with: emulator -list-avds"
    exit 1
fi
printf '    %s\n' "${DEVICES[@]}"

echo "==> Django API on 0.0.0.0:$PORT"
if curl -s -m 3 -o /dev/null "http://127.0.0.1:$PORT/api/Home/username_availability"; then
    echo "    already running"
else
    (
        cd "$REPO_ROOT/Backend"
        nohup ./.venv/bin/python manage.py runserver "0.0.0.0:$PORT" --noreload \
            > /tmp/django.log 2>&1 &
    )
    sleep 4
    echo "    started (log: /tmp/django.log)"
fi

echo "==> Build"
cd "$REPO_ROOT/android"
./gradlew --quiet :app:assembleDebug

for DEVICE in "${DEVICES[@]}"; do
    echo "==> $DEVICE"

    # The reverse tunnel is what makes the API reachable. Do NOT rely on
    # 10.0.2.2: that alias only resolves to the host on the emulator's NAT
    # interface (eth0), and modern images route app traffic over their virtual
    # WiFi (wlan0), where it is not the host and connections time out. A
    # reverse tunnel goes over adb, so the guest network is irrelevant — and it
    # is the only thing that also works on a USB-connected phone.
    adb -s "$DEVICE" reverse tcp:$PORT tcp:$PORT > /dev/null
    echo "    tunnel   127.0.0.1:$PORT -> host $PORT"

    adb -s "$DEVICE" install -r "$APK" 2>&1 | grep -qE "Success" \
        && echo "    installed"

    # Emulators start with no position at all, so the geofence would refuse
    # every attempt until this is set. Note: longitude comes first.
    if [[ "$DEVICE" == emulator-* ]]; then
        adb -s "$DEVICE" emu geo fix "$LON" "$LAT" > /dev/null 2>&1 \
            && echo "    gps      $LAT, $LON"
    fi

    adb -s "$DEVICE" shell am start -n "$PKG/.MainActivity" > /dev/null
    echo "    launched"
done

cat <<EOF

Sign in — password for every seeded account is: attendance-demo-1

  teacher   prof.sinha    owns CS210 Digital Circuits (session open now)
  student   2021BCS001    enrolled in that course
  admin     admin         sees every course, plus /admin/

Useful while testing
  adb -s <serial> logcat -s OkHttp          see every API call and response
  tail -f /tmp/django.log                   server log
  adb -s <serial> emu geo fix $LON $LAT     re-set position after a cold boot
  adb -s <serial> exec-out screencap -p > shot.png

A reverse tunnel is per-adb-connection: re-run this script after restarting an
emulator or replugging a phone.
EOF
