#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "${SDK_ROOT}" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set." >&2
  exit 1
fi

if [[ ! -d "${SDK_ROOT}/build-tools" ]]; then
  echo "Android build-tools were not found under ${SDK_ROOT}." >&2
  exit 1
fi

BUILD_TOOLS_DIR="$(find "${SDK_ROOT}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
if [[ -z "${BUILD_TOOLS_DIR}" ]]; then
  echo "No Android build-tools directory is available under ${SDK_ROOT}." >&2
  exit 1
fi

# Pick the newest installed android-NN platform jar (NN is a pure integer so
# extensions like android-34-ext10 are ignored).
PLATFORM_JAR=""
for candidate in $(ls -1 "${SDK_ROOT}/platforms" 2>/dev/null \
    | grep -E '^android-[0-9]+$' \
    | sed 's/^android-//' \
    | sort -n -r); do
  if [[ -f "${SDK_ROOT}/platforms/android-${candidate}/android.jar" ]]; then
    PLATFORM_JAR="${SDK_ROOT}/platforms/android-${candidate}/android.jar"
    PLATFORM_API="${candidate}"
    break
  fi
done
if [[ -z "${PLATFORM_JAR}" ]]; then
  PLATFORM_JAR="${SDK_ROOT}/platforms/android-34/android.jar"
  PLATFORM_API="34"
fi
TARGET_SDK="${PLATFORM_API:-34}"
MIN_SDK="24"

# Third-party dependencies bundled into the APK.
JSCH_VERSION="${JSCH_VERSION:-0.2.21}"
JSCH_SHA256="2330df0841be84eefa7c6ba4b5a2c98faa153855c80a5af418fdedacc2a4bc5b"
JSCH_URL="https://repo1.maven.org/maven2/com/github/mwiede/jsch/${JSCH_VERSION}/jsch-${JSCH_VERSION}.jar"
# BouncyCastle provides the Ed25519/EdDSA implementation jsch uses on Android
# (com.jcraft.jsch.bc.*). Without it, id_ed25519 identity keys fail with
# "Auth fail for methods 'publickey'" because Android drops jsch's JDK15+
# multi-release EdDSA classes.
BCPROV_VERSION="${BCPROV_VERSION:-1.81}"
BCPROV_SHA256="249f396412b0c0ce67f25c8197da757b241b8be3ec4199386c00704a2457459b"
BCPROV_URL="https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/${BCPROV_VERSION}/bcprov-jdk18on-${BCPROV_VERSION}.jar"
LIBS_DIR="${ROOT_DIR}/app/libs"
JSCH_JAR="${LIBS_DIR}/jsch-${JSCH_VERSION}.jar"
BCPROV_JAR="${LIBS_DIR}/bcprov-jdk18on-${BCPROV_VERSION}.jar"
AAPT2="${BUILD_TOOLS_DIR}/aapt2"
D8="${BUILD_TOOLS_DIR}/d8"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
APP_CATEGORY_PRODUCTIVITY="7"
BUILD_DIR="$(mktemp -d /tmp/ssh_client_for_android-build.XXXXXX)"
RELEASE_DIR="${ROOT_DIR}/release"
KEYSTORE_PATH="${BUILD_DIR}/release.keystore"
KEYSTORE_PASSWORD="${RELEASE_KEYSTORE_PASSWORD:-$(python - <<'PY'
import secrets
import string
alphabet = string.ascii_letters + string.digits
print(''.join(secrets.choice(alphabet) for _ in range(32)))
PY
)}"
KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-${KEYSTORE_PASSWORD}}"

require_file() {
  local path="$1"
  local label="$2"
  if [[ ! -x "${path}" ]]; then
    echo "Required ${label} was not found or is not executable: ${path}" >&2
    exit 1
  fi
}

cleanup() {
  rm -rf "${BUILD_DIR}"
}
trap cleanup EXIT

require_file "${AAPT2}" "aapt2"
require_file "${D8}" "d8"
require_file "${ZIPALIGN}" "zipalign"
require_file "${APKSIGNER}" "apksigner"

if [[ ! -f "${PLATFORM_JAR}" ]]; then
  echo "Required Android platform jar is missing: ${PLATFORM_JAR}" >&2
  exit 1
fi

mkdir -p "${RELEASE_DIR}" "${BUILD_DIR}/classes" "${BUILD_DIR}/dex" "${LIBS_DIR}"

download_and_verify_jar() {
  local label="$1"
  local url="$2"
  local path="$3"
  local expected_sha="$4"
  if [[ ! -f "${path}" ]]; then
    echo "Downloading ${url}"
    curl -fsSL --retry 3 -o "${path}" "${url}"
  fi
  local actual_sha
  actual_sha="$(sha256sum "${path}" | awk '{print $1}')"
  if [[ "${actual_sha}" != "${expected_sha}" ]]; then
    echo "Checksum mismatch for ${label} jar: expected ${expected_sha}, got ${actual_sha}" >&2
    exit 1
  fi
}

download_and_verify_jar "JSch" "${JSCH_URL}" "${JSCH_JAR}" "${JSCH_SHA256}"
download_and_verify_jar "BouncyCastle" "${BCPROV_URL}" "${BCPROV_JAR}" "${BCPROV_SHA256}"

# Classpath of bundled jars, shared by javac and d8.
DEP_CLASSPATH="${JSCH_JAR}:${BCPROV_JAR}"

"${AAPT2}" compile --dir "${ROOT_DIR}/app/src/main/res" -o "${BUILD_DIR}/resources.zip"
"${AAPT2}" link \
  -I "${PLATFORM_JAR}" \
  --manifest "${ROOT_DIR}/app/src/main/AndroidManifest.xml" \
  --java "${BUILD_DIR}/generated" \
  --min-sdk-version "${MIN_SDK}" \
  --target-sdk-version "${TARGET_SDK}" \
  --auto-add-overlay \
  -o "${BUILD_DIR}/base.apk" \
  "${BUILD_DIR}/resources.zip"

javac \
  -source 8 \
  -target 8 \
  -encoding UTF-8 \
  -bootclasspath "${PLATFORM_JAR}" \
  -classpath "${DEP_CLASSPATH}" \
  -d "${BUILD_DIR}/classes" \
  $(find "${ROOT_DIR}/app/src/main/java" "${BUILD_DIR}/generated" -name '*.java' | sort)

"${D8}" \
  --min-api "${MIN_SDK}" \
  --lib "${PLATFORM_JAR}" \
  --output "${BUILD_DIR}/dex" \
  $(find "${BUILD_DIR}/classes" -name '*.class' | sort) \
  "${JSCH_JAR}" \
  "${BCPROV_JAR}"
(
  cd "${BUILD_DIR}/dex"
  zip -qj "${BUILD_DIR}/base.apk" *.dex
)

"${ZIPALIGN}" -f -p 4 "${BUILD_DIR}/base.apk" "${BUILD_DIR}/aligned.apk"
keytool -genkeypair \
  -keystore "${KEYSTORE_PATH}" \
  -storepass "${KEYSTORE_PASSWORD}" \
  -keypass "${KEY_PASSWORD}" \
  -alias androidreleasekey \
  -dname "CN=Android Release,O=bedro96,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 >/dev/null 2>&1
"${APKSIGNER}" sign \
  --ks "${KEYSTORE_PATH}" \
  --ks-pass pass:"${KEYSTORE_PASSWORD}" \
  --key-pass pass:"${KEY_PASSWORD}" \
  --ks-key-alias androidreleasekey \
  --v4-signing-enabled false \
  --out "${RELEASE_DIR}/app-release.apk" \
  "${BUILD_DIR}/aligned.apk"
"${APKSIGNER}" verify "${RELEASE_DIR}/app-release.apk"

if ! "${AAPT2}" dump xmltree --file AndroidManifest.xml "${RELEASE_DIR}/app-release.apk" \
    | grep -Eq 'appCategory\(.*\)='"${APP_CATEGORY_PRODUCTIVITY}"'$'; then
  echo "Built APK is missing android:appCategory=\"productivity\" in AndroidManifest.xml" >&2
  exit 1
fi

echo "Built ${RELEASE_DIR}/app-release.apk"
