#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

OUTPUT_APK="${OUTPUT_APK:-${ROOT_DIR}/build/app-debug.apk}"
export OUTPUT_APK
export RELEASE_KEYSTORE_PASSWORD="${RELEASE_KEYSTORE_PASSWORD:-android}"
export RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-android}"
export APK_KEY_ALIAS="${APK_KEY_ALIAS:-androiddebugkey}"
export APK_KEY_DNAME="${APK_KEY_DNAME:-CN=Android Debug,O=Android,C=US}"
export APK_DEBUGGABLE="${APK_DEBUGGABLE:-1}"

"${ROOT_DIR}/build-release.sh"
