#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "${SDK_ROOT}" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set." >&2
  exit 1
fi

BUILD_TOOLS_DIR="$(find "${SDK_ROOT}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
PLATFORM_JAR="${SDK_ROOT}/platforms/android-34/android.jar"
AAPT2="${BUILD_TOOLS_DIR}/aapt2"
D8="${BUILD_TOOLS_DIR}/d8"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
BUILD_DIR="$(mktemp -d /tmp/ssh_client_for_android-build.XXXXXX)"
RELEASE_DIR="${ROOT_DIR}/release"
KEYSTORE_PATH="${BUILD_DIR}/release.keystore"

cleanup() {
  rm -rf "${BUILD_DIR}"
}
trap cleanup EXIT

mkdir -p "${RELEASE_DIR}" "${BUILD_DIR}/classes" "${BUILD_DIR}/dex"

"${AAPT2}" compile --dir "${ROOT_DIR}/app/src/main/res" -o "${BUILD_DIR}/resources.zip"
"${AAPT2}" link \
  -I "${PLATFORM_JAR}" \
  --manifest "${ROOT_DIR}/app/src/main/AndroidManifest.xml" \
  --java "${BUILD_DIR}/generated" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  -o "${BUILD_DIR}/base.apk" \
  "${BUILD_DIR}/resources.zip"

javac \
  -source 8 \
  -target 8 \
  -encoding UTF-8 \
  -bootclasspath "${PLATFORM_JAR}" \
  -d "${BUILD_DIR}/classes" \
  $(find "${ROOT_DIR}/app/src/main/java" "${BUILD_DIR}/generated" -name '*.java' | sort)

"${D8}" --lib "${PLATFORM_JAR}" --output "${BUILD_DIR}/dex" $(find "${BUILD_DIR}/classes" -name '*.class' | sort)
(
  cd "${BUILD_DIR}/dex"
  zip -qj "${BUILD_DIR}/base.apk" classes.dex
)

"${ZIPALIGN}" -f -p 4 "${BUILD_DIR}/base.apk" "${BUILD_DIR}/aligned.apk"
keytool -genkeypair \
  -keystore "${KEYSTORE_PATH}" \
  -storepass android \
  -keypass android \
  -alias androidreleasekey \
  -dname "CN=Android Release,O=bedro96,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 >/dev/null 2>&1
"${APKSIGNER}" sign \
  --ks "${KEYSTORE_PATH}" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androidreleasekey \
  --v4-signing-enabled false \
  --out "${RELEASE_DIR}/app-release.apk" \
  "${BUILD_DIR}/aligned.apk"
"${APKSIGNER}" verify "${RELEASE_DIR}/app-release.apk"

echo "Built ${RELEASE_DIR}/app-release.apk"
