#!/usr/bin/env bash
# Offline unit tests for the SSH client's Ed25519 (identity-key) support.
#
# This project builds without Gradle, so there is no JUnit runner. The tests are
# plain `main`-based assertions compiled against the same jars the APK bundles
# (mwiede/jsch + BouncyCastle) and run on the host JVM. They deliberately avoid
# the JDK15 multi-release EdDSA classes so they exercise the exact Bouncy Castle
# code path used on Android.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="${ROOT_DIR}/app/libs"

JSCH_VERSION="${JSCH_VERSION:-0.2.21}"
JSCH_SHA256="2330df0841be84eefa7c6ba4b5a2c98faa153855c80a5af418fdedacc2a4bc5b"
JSCH_JAR="${LIBS_DIR}/jsch-${JSCH_VERSION}.jar"
JSCH_URL="https://repo1.maven.org/maven2/com/github/mwiede/jsch/${JSCH_VERSION}/jsch-${JSCH_VERSION}.jar"

BCPROV_VERSION="${BCPROV_VERSION:-1.81}"
BCPROV_SHA256="249f396412b0c0ce67f25c8197da757b241b8be3ec4199386c00704a2457459b"
BCPROV_JAR="${LIBS_DIR}/bcprov-jdk18on-${BCPROV_VERSION}.jar"
BCPROV_URL="https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/${BCPROV_VERSION}/bcprov-jdk18on-${BCPROV_VERSION}.jar"

mkdir -p "${LIBS_DIR}"

download_verify() {
  local jar="$1" url="$2" sha="$3"
  if [[ ! -f "${jar}" ]]; then
    echo "Downloading ${url}"
    curl -fsSL --retry 3 -o "${jar}" "${url}"
  fi
  local actual
  actual="$(sha256sum "${jar}" | awk '{print $1}')"
  if [[ "${actual}" != "${sha}" ]]; then
    echo "Checksum mismatch for ${jar}: expected ${sha}, got ${actual}" >&2
    exit 1
  fi
}

download_verify "${JSCH_JAR}" "${JSCH_URL}" "${JSCH_SHA256}"
download_verify "${BCPROV_JAR}" "${BCPROV_URL}" "${BCPROV_SHA256}"

BUILD_DIR="$(mktemp -d /tmp/ssh_client_for_android-test.XXXXXX)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

CP="${JSCH_JAR}:${BCPROV_JAR}"

# Compile the production helper plus the test, WITHOUT the Java15+ multi-release
# EdDSA classes so we validate the Android (Bouncy Castle) code path.
javac -encoding UTF-8 -classpath "${CP}" -d "${BUILD_DIR}" \
  "${ROOT_DIR}/app/src/main/java/com/bedro96/sshclient/Utf8ChunkReader.java" \
  "${ROOT_DIR}/app/src/main/java/com/bedro96/sshclient/TerminalBuffer.java" \
  "${ROOT_DIR}/app/src/main/java/com/bedro96/sshclient/TerminalAnsiProcessor.java" \
  "${ROOT_DIR}/app/src/main/java/com/bedro96/sshclient/SshKeyAuth.java" \
  "${ROOT_DIR}/app/src/test/java/com/bedro96/sshclient/SshKeyAuthTest.java" \
  "${ROOT_DIR}/app/src/test/java/com/bedro96/sshclient/Utf8ChunkReaderTest.java" \
  "${ROOT_DIR}/app/src/test/java/com/bedro96/sshclient/TerminalBufferTest.java" \
  "${ROOT_DIR}/app/src/test/java/com/bedro96/sshclient/TerminalAnsiProcessorTest.java"

echo "Running SshKeyAuthTest (multi-release disabled to simulate Android)..."
java -Djdk.util.jar.enableMultiRelease=false \
  -classpath "${BUILD_DIR}:${CP}" \
  com.bedro96.sshclient.SshKeyAuthTest

echo "Running Utf8ChunkReaderTest..."
java -classpath "${BUILD_DIR}:${CP}" \
  com.bedro96.sshclient.Utf8ChunkReaderTest

echo "Running TerminalBufferTest..."
java -Djdk.util.jar.enableMultiRelease=false \
  -classpath "${BUILD_DIR}:${CP}" \
  com.bedro96.sshclient.TerminalBufferTest

echo "Running TerminalAnsiProcessorTest..."
java -Djdk.util.jar.enableMultiRelease=false \
  -classpath "${BUILD_DIR}:${CP}" \
  com.bedro96.sshclient.TerminalAnsiProcessorTest
