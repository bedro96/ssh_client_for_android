#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="${ROOT_DIR}/app/libs"
BUILD_DIR="$(mktemp -d /tmp/ssh_client_for_android-ed25519-test.XXXXXX)"
JSCH_VERSION="${JSCH_VERSION:-0.2.21}"
JSCH_SHA256="2330df0841be84eefa7c6ba4b5a2c98faa153855c80a5af418fdedacc2a4bc5b"
JSCH_URL="https://repo1.maven.org/maven2/com/github/mwiede/jsch/${JSCH_VERSION}/jsch-${JSCH_VERSION}.jar"
JSCH_JAR="${LIBS_DIR}/jsch-${JSCH_VERSION}.jar"
BCPROV_VERSION="${BCPROV_VERSION:-1.81}"
BCPROV_SHA256="249f396412b0c0ce67f25c8197da757b241b8be3ec4199386c00704a2457459b"
BCPROV_URL="https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/${BCPROV_VERSION}/bcprov-jdk18on-${BCPROV_VERSION}.jar"
BCPROV_JAR="${LIBS_DIR}/bcprov-jdk18on-${BCPROV_VERSION}.jar"

cleanup() {
  rm -rf "${BUILD_DIR}"
}
trap cleanup EXIT

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

if ! command -v ssh-keygen >/dev/null 2>&1; then
  echo "ssh-keygen is required to run test-ed25519.sh" >&2
  exit 1
fi

mkdir -p "${LIBS_DIR}" "${BUILD_DIR}/classes"
download_and_verify_jar "JSch" "${JSCH_URL}" "${JSCH_JAR}" "${JSCH_SHA256}"
download_and_verify_jar "BouncyCastle" "${BCPROV_URL}" "${BCPROV_JAR}" "${BCPROV_SHA256}"

ssh-keygen -q -t ed25519 -N '' -f "${BUILD_DIR}/id_ed25519" >/dev/null
ssh-keygen -q -t ed25519 -N 'secret-passphrase' -f "${BUILD_DIR}/id_ed25519_enc" >/dev/null

javac \
  -Xlint:-options \
  -source 8 \
  -target 8 \
  -encoding UTF-8 \
  -classpath "${JSCH_JAR}:${BCPROV_JAR}" \
  -d "${BUILD_DIR}/classes" \
  "${ROOT_DIR}/app/src/main/java/com/bedro96/sshclient/JschEd25519Support.java" \
  "${ROOT_DIR}/app/src/test/java/com/bedro96/sshclient/JschEd25519RegressionTest.java"

java \
  -Djdk.util.jar.enableMultiRelease=false \
  -cp "${BUILD_DIR}/classes:${JSCH_JAR}:${BCPROV_JAR}" \
  com.bedro96.sshclient.JschEd25519RegressionTest \
  "${BUILD_DIR}/id_ed25519" \
  "${BUILD_DIR}/id_ed25519_enc" \
  "secret-passphrase"

echo "Ed25519 regression passed"
