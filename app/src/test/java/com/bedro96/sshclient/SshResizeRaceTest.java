package com.bedro96.sshclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards against the terminal/PTY geometry desync behind issue #57 (tmux
 * status-bar corruption/duplication and blank redraw gaps after resize).
 *
 * <p>Root cause: {@link SshConnectionService#connect} opens the remote PTY
 * with whatever {@code cols}/{@code rows} the caller passed in at the moment
 * CONNECT was tapped (while the connection-input panel was still visible, so
 * the terminal viewport was smaller than it becomes once the panel
 * collapses). {@code MainActivity}'s layout-change listener fires
 * {@code resizeTerminal(cols, rows)} almost immediately afterwards once the
 * panel collapses — but if that races ahead of the background SSH handshake
 * finishing (i.e. {@code channel} is still {@code null}),
 * {@code resizeTerminal} resizes the <em>local</em> {@link TerminalScreen}
 * but silently drops the corresponding {@code ch.setPtySize(...)} call
 * (guarded by {@code ch != null}). The remote PTY — and therefore tmux —
 * never learns about the new size, so it keeps drawing (including its
 * status line) for stale geometry while the local grid renders those bytes
 * against different dimensions: exactly the corruption/duplication observed.
 *
 * <p>The fix is to apply the most recently requested size ({@code lastCols}/
 * {@code lastRows}, already tracked but previously never read) to the
 * channel as soon as it becomes connected, so no resize that raced ahead of
 * the handshake is ever lost.
 *
 * <p>{@link SshConnectionService} extends {@code android.app.Service} and
 * cannot be loaded on the host JVM that runs these offline tests (see
 * run-tests.sh), so — consistent with {@link SshReaderConfigTest} — this
 * test inspects its source directly rather than exercising the class.
 */
public final class SshResizeRaceTest {

    private SshResizeRaceTest() { }

    public static void main(String[] args) throws Exception {
        testChannelIsSyncedToLatestRequestedSizeOnceConnected();
        System.out.println("SSH RESIZE RACE TESTS PASSED");
    }

    private static void testChannelIsSyncedToLatestRequestedSizeOnceConnected() throws IOException {
        String source = readSource("SshConnectionService.java");

        // Find the `channel = ch;` assignment inside connect(...) and require that,
        // somewhere after it (before the method's opening try block ends), the code
        // re-applies the latest tracked size to the now-connected channel. This is
        // the only point where a resize that raced ahead of the handshake can still
        // be reconciled: after this point, resizeTerminal()'s own `ch != null` guard
        // will handle every subsequent resize correctly.
        int channelAssignIndex = source.indexOf("channel = ch;");
        if (channelAssignIndex < 0) {
            throw new AssertionError("FAILED: could not find `channel = ch;` in SshConnectionService.java");
        }

        String afterAssignment = source.substring(channelAssignIndex);
        Matcher m = Pattern.compile(
                "ch\\.setPtySize\\(\\s*lastCols\\s*,\\s*lastRows\\s*,\\s*0\\s*,\\s*0\\s*\\)").matcher(afterAssignment);
        if (!m.find()) {
            throw new AssertionError(
                    "FAILED: after `channel = ch;`, connect() must re-apply the latest requested size via "
                            + "ch.setPtySize(lastCols, lastRows, 0, 0) so a resize that raced ahead of the SSH "
                            + "handshake (dropped by resizeTerminal()'s `ch != null` guard) is not lost, leaving "
                            + "the remote PTY (and tmux) permanently desynced from the local terminal grid");
        }
    }

    private static String readSource(String fileName) throws IOException {
        Path path = mainSourceDir().resolve(fileName);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path mainSourceDir() {
        String repoRoot = System.getProperty("ssh.resize.race.test.repoRoot", ".");
        return Paths.get(repoRoot, "app", "src", "main", "java", "com", "bedro96", "sshclient");
    }
}
