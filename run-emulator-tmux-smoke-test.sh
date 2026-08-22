#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "${SDK_ROOT}" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set." >&2
  exit 1
fi

ADB="${SDK_ROOT}/platform-tools/adb"
ARTIFACT_DIR="${ROOT_DIR}/emulator-artifacts/tmux-smoke"
UI_XML="${ARTIFACT_DIR}/window_dump.xml"
PACKAGE="com.bedro96.sshclient"
ROTATION_WAIT_SECONDS="${ROTATION_WAIT_SECONDS:-4}"

require_file() {
  local path="$1"
  local label="$2"
  if [[ ! -x "${path}" ]]; then
    echo "Required ${label} was not found or is not executable: ${path}" >&2
    exit 1
  fi
}

dump_ui() {
  local attempt
  for attempt in $(seq 1 10); do
    timeout 15s "${ADB}" shell uiautomator dump >/dev/null 2>&1 || true
    "${ADB}" pull /sdcard/window_dump.xml "${UI_XML}" >/dev/null 2>&1 || true
    if [[ -s "${UI_XML}" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Failed to dump UI hierarchy." >&2
  return 1
}

center_for_id() {
  local resource_id="$1"
  python3 - "${UI_XML}" "${resource_id}" <<'PY'
import re
import sys
from pathlib import Path

xml_path = Path(sys.argv[1])
resource_id = sys.argv[2]
text = xml_path.read_text()
pattern = re.compile(rf'<node[^>]*resource-id="{re.escape(resource_id)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
match = pattern.search(text)
if not match:
    raise SystemExit(f"resource-id not found: {resource_id}")
x1, y1, x2, y2 = map(int, match.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

resource_present() {
  local resource_id="$1"
  python3 - "${UI_XML}" "${resource_id}" <<'PY'
import re
import sys
from pathlib import Path

xml_path = Path(sys.argv[1])
resource_id = sys.argv[2]
text = xml_path.read_text()
pattern = re.compile(rf'resource-id="{re.escape(resource_id)}"')
raise SystemExit(0 if pattern.search(text) else 1)
PY
}

field_text_for_id() {
  local resource_id="$1"
  python3 - "${UI_XML}" "${resource_id}" <<'PY'
import re
import sys
from pathlib import Path

xml_path = Path(sys.argv[1])
resource_id = sys.argv[2]
text = xml_path.read_text()
pattern = re.compile(rf'<node[^>]*text="([^"]*)"[^>]*resource-id="{re.escape(resource_id)}"')
match = pattern.search(text)
if not match:
    raise SystemExit(f"resource-id not found: {resource_id}")
print(match.group(1))
PY
}

tap_id() {
  local resource_id="$1"
  dump_ui
  read -r x y < <(center_for_id "${resource_id}")
  "${ADB}" shell input tap "${x}" "${y}"
  sleep 1
}

wait_for_resource() {
  local resource_id="$1"
  for _ in $(seq 1 15); do
    dump_ui
    if resource_present "${resource_id}"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for resource-id ${resource_id}" >&2
  return 1
}

clear_focused_field() {
  "${ADB}" shell input keyevent 123
  for _ in $(seq 1 32); do
    "${ADB}" shell input keyevent 67
  done
}

send_keyevents() {
  local text="$1"
  local ch keycode
  for ((i = 0; i < ${#text}; i++)); do
    ch="${text:i:1}"
    case "${ch}" in
      0) keycode=7 ;;
      1) keycode=8 ;;
      2) keycode=9 ;;
      3) keycode=10 ;;
      4) keycode=11 ;;
      5) keycode=12 ;;
      6) keycode=13 ;;
      7) keycode=14 ;;
      8) keycode=15 ;;
      9) keycode=16 ;;
      .) keycode=56 ;;
      -) keycode=69 ;;
      :) keycode=74 ;;
      *)
        echo "Unsupported keyevent character: ${ch}" >&2
        exit 1
        ;;
    esac
    "${ADB}" shell input keyevent "${keycode}"
  done
}

capture_screenshot() {
  local name="$1"
  "${ADB}" exec-out screencap -p > "${ARTIFACT_DIR}/${name}.png"
}

wait_for_connected_status() {
  local status
  for _ in $(seq 1 30); do
    dump_ui
    status="$(field_text_for_id "${PACKAGE}:id/txtStatus" || true)"
    if [[ "${status}" == Connected* ]]; then
      echo "${status}"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for Connected status." >&2
  return 1
}

set_rotation() {
  local rotation="$1"
  "${ADB}" shell settings put system accelerometer_rotation 0
  "${ADB}" shell settings put system user_rotation "${rotation}"
  sleep "${ROTATION_WAIT_SECONDS}"
}

ensure_login_form() {
  wait_for_resource "${PACKAGE}:id/btnConnect"
  dump_ui
  if ! resource_present "${PACKAGE}:id/editHost"; then
    if [[ "$(field_text_for_id "${PACKAGE}:id/btnConnect")" == "DISCONNECT" ]]; then
      tap_id "${PACKAGE}:id/btnConnect"
      sleep 3
    fi
  fi
  wait_for_resource "${PACKAGE}:id/editHost"
}

set_host_field() {
  local expected="10.0.2.2"
  local actual=""
  for _ in $(seq 1 3); do
    tap_id "${PACKAGE}:id/editHost"
    clear_focused_field
    send_keyevents "${expected}"
    sleep 1
    dump_ui
    actual="$(field_text_for_id "${PACKAGE}:id/editHost" || true)"
    if [[ "${actual}" == "${expected}" ]]; then
      return 0
    fi
  done
  echo "Failed to populate host field, last value: ${actual}" >&2
  return 1
}

require_file "${ADB}" "adb"
mkdir -p "${ARTIFACT_DIR}"

"${ADB}" wait-for-device
"${ADB}" shell pm grant "${PACKAGE}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"${ADB}" logcat -c || true
"${ADB}" shell am force-stop "${PACKAGE}" || true
"${ADB}" shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 3
ensure_login_form

set_host_field

tap_id "${PACKAGE}:id/editUser"
"${ADB}" shell input text "sshtest"

tap_id "${PACKAGE}:id/editPassword"
"${ADB}" shell input text "sshtest123"

tap_id "${PACKAGE}:id/btnConnect"
wait_for_connected_status >/dev/null
sleep 5
capture_screenshot "after-connect"
sleep 6
capture_screenshot "mid-scroll"
set_rotation 1
capture_screenshot "rotated"
set_rotation 0
capture_screenshot "after-rotate-back"
"${ADB}" logcat -d | grep -iE 'sshclient|TerminalScreen|MainActivity|Exception|ANR' > "${ARTIFACT_DIR}/logcat.txt" || true

echo "Artifacts written to ${ARTIFACT_DIR}"
