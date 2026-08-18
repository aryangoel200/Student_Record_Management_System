#!/usr/bin/env bash
#
# Boot the emulator, start the Django API, build and install the app.
#
#   ./android/run-dev.sh
#
# Android Studio does most of this for you; this exists so the whole stack can
# be brought up from a terminal, and to document the exact paths that work.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Adjust these two if your toolchain lives elsewhere. Android Studio's are:
#   JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home
#   ANDROID_HOME=$HOME/Library/Android/sdk
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

AVD="${AVD:-attendance}"

echo "==> Emulator"
if adb devices | grep -q "emulator-.*device$"; then
    echo "    already running"
else
    nohup emulator -avd "$AVD" -no-boot-anim -camera-front webcam0 \
        > /tmp/emulator.log 2>&1 &
    echo "    booting $AVD ..."
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 3
    done
    echo "    booted"
fi

echo "==> Django API on 0.0.0.0:8000"
if curl -s -o /dev/null "http://127.0.0.1:8000/api/Home/username_availability"; then
    echo "    already running"
else
    cd "$REPO_ROOT/Backend"
    # 0.0.0.0 so the emulator can reach it; 10.0.2.2 is the host from inside
    # the emulator, and must be in DJANGO_ALLOWED_HOSTS.
    nohup ./.venv/bin/python manage.py runserver 0.0.0.0:8000 --noreload \
        > /tmp/django.log 2>&1 &
    sleep 4
    echo "    started (log: /tmp/django.log)"
fi

echo "==> Build and install"
cd "$REPO_ROOT/android"
./gradlew --quiet installDebug

echo "==> Launch"
adb shell am start -n edu.iitgoa.attendance/.MainActivity > /dev/null
echo "    running"

cat <<'EOF'

Useful while testing
  adb logcat -s edu.iitgoa.attendance        app logs
  tail -f /tmp/django.log                    server logs
  adb emu geo fix <lon> <lat>                set the emulator's GPS position
  adb exec-out screencap -p > shot.png       screenshot

Note: `adb emu geo fix` takes LONGITUDE FIRST.
EOF
