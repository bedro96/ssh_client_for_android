package com.bedro96.sshclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the terminal's baseline connection contract (see GitHub issue #52):
 * the SSH reader thread must read in 1024-byte chunks, announce
 * {@code xterm-256color} as its PTY type, and rely on exactly one UTF-8
 * decoding code path in production (the manual decoder built into
 * {@link TerminalAnsiProcessor}, fed raw bytes from the reader thread).
 *
 * {@link SshConnectionService} extends {@code android.app.Service} and cannot
 * be loaded on the host JVM that runs these offline tests (see run-tests.sh),
 * so this test inspects its source directly rather than exercising the class.
 */
public final class SshReaderConfigTest {

    private SshReaderConfigTest() { }

    public static void main(String[] args) throws Exception {
        testSshReadBufferIs1024Bytes();
        testPtyTypeRemainsXterm256Color();
        testUtf8ChunkReaderIsRetiredAsDeadCode();
        System.out.println("SSH READER CONFIG TESTS PASSED");
    }

    private static void testSshReadBufferIs1024Bytes() throws IOException {
        String source = readSource("SshConnectionService.java");
        Matcher m = Pattern.compile("byte\\[]\\s+buf\\s*=\\s*new\\s+byte\\[(\\d+)]").matcher(source);
        if (!m.find()) {
            throw new AssertionError(
                    "FAILED: could not locate the reader thread's `byte[] buf = new byte[N]` read buffer declaration");
        }
        int size = Integer.parseInt(m.group(1));
        assertEquals(1024, size, "SSH reader read-buffer size");
    }

    private static void testPtyTypeRemainsXterm256Color() throws IOException {
        String source = readSource("SshConnectionService.java");
        if (!source.contains("setPtyType(\"xterm-256color\"")) {
            throw new AssertionError(
                    "FAILED: PTY type must remain announced as xterm-256color (ch.setPtyType(\"xterm-256color\", ...))");
        }
    }

    private static void testUtf8ChunkReaderIsRetiredAsDeadCode() throws IOException {
        Path productionSourceDir = mainSourceDir();
        Path utf8ChunkReader = productionSourceDir.resolve("Utf8ChunkReader.java");
        if (Files.exists(utf8ChunkReader)) {
            throw new AssertionError(
                    "FAILED: Utf8ChunkReader.java must be retired as dead code (only one UTF-8 decode "
                            + "path is allowed in production: TerminalAnsiProcessor's byte[] decoder)");
        }
        String source = readSource("SshConnectionService.java");
        if (source.contains("Utf8ChunkReader")) {
            throw new AssertionError(
                    "FAILED: SshConnectionService must not reference Utf8ChunkReader; "
                            + "TerminalAnsiProcessor.process(byte[], ...) must remain the sole UTF-8 decode path");
        }
    }

    private static String readSource(String fileName) throws IOException {
        Path path = mainSourceDir().resolve(fileName);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path mainSourceDir() {
        // run-tests.sh passes the repo root explicitly via this system property so
        // the test does not depend on the working directory the JVM was launched
        // from; fall back to a repo-root-relative path for IDE/manual runs.
        String repoRoot = System.getProperty("ssh.reader.config.test.repoRoot", ".");
        return Paths.get(repoRoot, "app", "src", "main", "java", "com", "bedro96", "sshclient");
    }

    private static void assertEquals(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError(
                    "FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
