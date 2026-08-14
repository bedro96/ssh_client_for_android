#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-build/app-debug.apk}"
PACKAGE="com.bedro96.sshclient"
ACTIVITY="${PACKAGE}/.MainActivity"
LOG_MARKER="CI_SMOKE_ESC_FORWARDED:1b"

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

adb wait-for-device
adb install -r "${APK_PATH}" >/dev/null
adb logcat -c
adb shell am start -W -n "${ACTIVITY}" --es ci_smoke_test 1 >/dev/null
sleep 2
adb shell input keyevent 111

for _ in $(seq 1 20); do
  if adb logcat -d | grep -q "${LOG_MARKER}"; then
    echo "Escape key smoke test passed (${LOG_MARKER})"
    exit 0
  fi
  sleep 1
done

echo "Escape key smoke test failed: missing log marker ${LOG_MARKER}" >&2
adb logcat -d | tail -200 >&2
exit 1
