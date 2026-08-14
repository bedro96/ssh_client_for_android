#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-build/app-debug.apk}"
PACKAGE="com.bedro96.sshclient"
ACTIVITY="${PACKAGE}/.MainActivity"
# Keep in sync with MainActivity.CI_SMOKE_ESC_LOG_MARKER.
LOG_MARKER="CI_SMOKE_ESC_FORWARDED:1b"
# Keep in sync with MainActivity.CI_SMOKE_TEST_EXTRA.
SMOKE_EXTRA_KEY="ci_smoke_test"

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 1
done
adb install -r "${APK_PATH}" >/dev/null
adb logcat -c
if adb logcat -d MainActivity:I \*:S | grep -q "${LOG_MARKER}"; then
  echo "Logcat still contains stale smoke marker after clear: ${LOG_MARKER}" >&2
  exit 1
fi
adb shell am start -W -S -n "${ACTIVITY}" --es "${SMOKE_EXTRA_KEY}" 1 >/dev/null
sleep 1

for ((i = 0; i < 20; i++)); do
  adb shell input keyevent 111
  sleep 1
  if adb logcat -d MainActivity:I \*:S | grep -q "${LOG_MARKER}"; then
    echo "Escape key smoke test passed (${LOG_MARKER})"
    exit 0
  fi
done

echo "Escape key smoke test failed: missing log marker ${LOG_MARKER}" >&2
adb logcat -d MainActivity:I \*:S | tail -200 >&2
exit 1
