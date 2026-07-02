package com.bedro96.sshclient;

import java.util.List;

public final class TerminalScreenTest {

    private static final String ESC = "\u001b";

    private TerminalScreenTest() { }

    public static void main(String[] args) {
        testAutowrapAndCoordinateClamping();
        testCursorAddressingAndSaveRestore();
        testEraseLineVariants();
        testEraseDisplayVariants();
        testAlternateScreenSwapRestoresPrimary();
        testScrollRegionAndIndexBehavior();
        testRepeatedSpinnerRepaintStaysOnSingleLine();
        testTmuxStyleAlternateScreenRedrawRestoresShell();
        testCopilotCliStyleFullScreenRepaintStaysStable();
        testBasicShellScrollbackAndLineEditing();
        testLongCatScrollbackPreservesEarlyAndLateLines();
        testCursorVisibilityModes();
        testTruecolorStyleOverwrite();
        System.out.println("TERMINAL SCREEN TESTS PASSED");
    }

    private static void testAutowrapAndCoordinateClamping() {
        TerminalScreen screen = new TerminalScreen(2, 4);
        screen.append("ABCDX");
        assertEquals("ABCD\nX", screen.snapshot(200_000).text,
                "autowrap should move additional text onto the next row");

        screen.append(ESC + "[999;999HZ");
        assertEquals("ABCD\nX  Z", screen.snapshot(200_000).text,
                "out-of-range CUP coordinates should clamp to the bottom-right cell");
    }

    private static void testCursorAddressingAndSaveRestore() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        screen.append("abc\r\ndef");
        screen.append(ESC + "[1;2H" + "X");
        screen.append(ESC + "[s" + ESC + "[2;3H" + "Y" + ESC + "[u" + "Z");
        screen.append(ESC + "7" + ESC + "[2;1H" + "Q" + ESC + "8" + "W");
        assertEquals("aXZW\nQeY", screen.snapshot(200_000).text,
                "CUP/save/restore should overwrite in-place and restore the cursor");
    }

    private static void testEraseLineVariants() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append("abcdef" + ESC + "[4G" + ESC + "[1K");
        assertEquals("    ef", screen.snapshot(200_000).text,
                "ESC[1K should blank from line start to cursor");

        screen.append(ESC + "[2G" + ESC + "[0K");
        assertEquals("", screen.snapshot(200_000).text,
                "ESC[0K should blank from cursor to line end");

        screen.append("xyz" + ESC + "[2K");
        assertEquals("", screen.snapshot(200_000).text,
                "ESC[2K should blank the entire current line");
    }

    private static void testEraseDisplayVariants() {
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append("line one\r\nline two\r\nline three");
        screen.append(ESC + "[2;6H" + ESC + "[0J");
        assertEquals("line one\nline", screen.snapshot(200_000).text,
                "ESC[0J should clear from the cursor to the display end");

        screen.append("restored");
        screen.append(ESC + "[2J");
        assertEquals("\n", screen.snapshot(200_000).text,
                "ESC[2J should clear the visible display while preserving cursor row");

        screen.append("one\r\ntwo\r\nthree\r\nfour\r\nfive");
        screen.append(ESC + "[3J");
        assertEquals("two\nthree\nfour\nfive", screen.snapshot(200_000).text,
                "ESC[3J should clear scrollback without disturbing visible cells");
    }

    private static void testAlternateScreenSwapRestoresPrimary() {
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append("shell output\r\nprompt$ ");
        screen.append(ESC + "[?1049h" + "copilot-ui");
        assertEquals("copilot-ui", screen.snapshot(200_000).text,
                "alternate screen should start with a clean buffer");
        screen.append(ESC + "[?1049l");
        assertEquals("shell output\nprompt$", screen.snapshot(200_000).text,
                "leaving alternate screen should restore the primary buffer");
    }

    private static void testScrollRegionAndIndexBehavior() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;3r" + ESC + "[3;1H" + ESC + "D" + "X");
        assertEquals("1\n3\nX\n4", screen.snapshot(200_000).text,
                "index at the bottom of a scroll region should scroll only that region");
        screen.append(ESC + "[2;1H" + ESC + "M" + "Y");
        assertEquals("1\nY\n3\n4", screen.snapshot(200_000).text,
                "reverse index at the top of a region should scroll down within that region");
    }

    private static void testRepeatedSpinnerRepaintStaysOnSingleLine() {
        TerminalScreen screen = new TerminalScreen(4, 32);
        screen.append("◎ Loading: 1 skills…");
        screen.append("\r" + ESC + "[2K◉ Loading: 84 skills…");
        screen.append("\r" + ESC + "[2K○ Loading: 84 skills…");
        assertEquals("○ Loading: 84 skills…", screen.snapshot(200_000).text,
                "spinner repaint should update a single visible line in place");
    }

    private static void testTmuxStyleAlternateScreenRedrawRestoresShell() {
        TerminalScreen screen = new TerminalScreen(6, 24);
        screen.append("shell output\r\nprompt$ ");
        screen.append(ESC + "[?1049h" + ESC + "[2J" + ESC + "[H");
        screen.append("pane1" + ESC + "[1;13Hpane2");
        screen.append(ESC + "[2;1Hleft$ ls" + ESC + "[2;13Hright$ pwd");
        screen.append(ESC + "[3;1Hfile-a" + ESC + "[3;13H/home/u");
        screen.append(ESC + "[6;1H[tmux 1]");
        screen.append(ESC + "[3;1H" + ESC + "[2Kfile-a file-b");
        screen.append(ESC + "[6;1H" + ESC + "[2K[tmux 2]");

        String tmux = screen.snapshot(200_000).text;
        assertContains(tmux, "pane1", "tmux redraw should preserve the left pane");
        assertContains(tmux, "pane2", "tmux redraw should preserve the right pane");
        assertContains(tmux, "left$ ls", "tmux redraw should preserve pane command text");
        assertContains(tmux, "right$ pwd", "tmux redraw should preserve split-pane content");
        assertContains(tmux, "file-a file-b", "tmux redraw should repaint pane content in place");
        assertContains(tmux, "[tmux 2]", "tmux redraw should update the status bar in place");
        assertNotContains(tmux, "[tmux 1]", "tmux redraw should not leave stale status bars behind");

        screen.append(ESC + "[?1049l");
        assertEquals("shell output\nprompt$", screen.snapshot(200_000).text,
                "quitting tmux should restore the primary shell screen");
    }

    private static void testCopilotCliStyleFullScreenRepaintStaysStable() {
        TerminalScreen screen = new TerminalScreen(6, 32);
        screen.append(ESC + "[?1049h" + ESC + "[?25l");
        screen.append(ESC + "[2J" + ESC + "[HGitHub Copilot CLI");
        screen.append(ESC + "[2;1HLoading: 1 skills…" + ESC + "[3;1H◎ Synthesizing...");
        screen.append(ESC + "[2;1H" + ESC + "[2KLoading: 84 skills…");
        screen.append(ESC + "[3;1H" + ESC + "[2K○ Ready");
        screen.append(ESC + "[4;1H> explain this diff");

        TerminalScreen.Snapshot snapshot = screen.snapshot(200_000);
        assertFalse(snapshot.cursorVisible, "Copilot CLI full-screen mode should hide the cursor");
        assertContains(snapshot.text, "GitHub Copilot CLI",
                "Copilot CLI title should remain visible after redraws");
        assertContains(snapshot.text, "Loading: 84 skills…",
                "Copilot CLI should show the latest loading state");
        assertContains(snapshot.text, "○ Ready",
                "Copilot CLI should repaint status lines in place");
        assertContains(snapshot.text, "> explain this diff",
                "Copilot CLI prompt should remain on the current screen");
        assertNotContains(snapshot.text, "Loading: 1 skills…",
                "Copilot CLI redraw should not leave stale loading lines behind");
        assertNotContains(snapshot.text, "◎ Synthesizing...",
                "Copilot CLI redraw should replace older transient status text");
    }

    private static void testBasicShellScrollbackAndLineEditing() {
        TerminalScreen screen = new TerminalScreen(4, 40);
        StringBuilder lsOutput = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            lsOutput.append("-rw-r--r-- 1 user group ")
                    .append(i)
                    .append(" Jul 02 00:0")
                    .append(i)
                    .append(" file")
                    .append(i)
                    .append("\r\n");
        }
        screen.append(lsOutput);
        String rendered = screen.snapshot(200_000).text;
        assertContains(rendered, "file1", "scrollback should keep the earliest shell lines");
        assertContains(rendered, "file10", "scrollback should keep the latest shell lines");

        screen.append("prompt$ hell");
        screen.append("\b \b");
        screen.append("o");
        assertContains(screen.snapshot(200_000).text, "prompt$ helo",
                "backspace-space-backspace editing should stay aligned");
    }

    private static void testLongCatScrollbackPreservesEarlyAndLateLines() {
        TerminalScreen screen = new TerminalScreen(4, 32);
        StringBuilder catOutput = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            catOutput.append("line ").append(i).append(" from long cat\r\n");
        }
        screen.append(catOutput);
        String rendered = screen.snapshot(200_000).text;
        assertContains(rendered, "line 1 from long cat",
                "long cat output should preserve the earliest lines in scrollback");
        assertContains(rendered, "line 20 from long cat",
                "long cat output should preserve the latest lines in scrollback");
    }

    private static void testCursorVisibilityModes() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append(ESC + "[?25l");
        assertFalse(screen.snapshot(200_000).cursorVisible, "ESC[?25l should hide the cursor");
        screen.append(ESC + "[?25h");
        assertTrue(screen.snapshot(200_000).cursorVisible, "ESC[?25h should show the cursor");
    }

    private static void testTruecolorStyleOverwrite() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append("A", false, 0x112233, 0x445566);
        screen.append("\rB", true, 0xaabbcc, 0x010203);
        TerminalScreen.Snapshot snapshot = screen.snapshot(200_000);
        assertEquals("B", snapshot.text, "overwriting a cell should update the visible glyph");
        List<TerminalScreen.StyleRun> runs = snapshot.runs;
        assertEquals(1, runs.size(), "single styled cell should produce one run");
        TerminalScreen.StyleRun run = runs.get(0);
        assertTrue(run.bold, "overwritten cell should retain bold style");
        assertEquals(Integer.valueOf(0xaabbcc), run.foregroundRgb,
                "overwritten cell should retain truecolor foreground");
        assertEquals(Integer.valueOf(0x010203), run.backgroundRgb,
                "overwritten cell should retain truecolor background");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expectedPart, String message) {
        if (actual == null || !actual.contains(expectedPart)) {
            throw new AssertionError("FAILED " + message + ": expected to find <" + expectedPart + "> in <" + actual + ">");
        }
    }

    private static void assertNotContains(String actual, String unexpectedPart, String message) {
        if (actual != null && actual.contains(unexpectedPart)) {
            throw new AssertionError("FAILED " + message + ": did not expect to find <" + unexpectedPart + "> in <" + actual + ">");
        }
    }

    private static void assertFalse(boolean actual, String message) {
        if (actual) {
            throw new AssertionError("FAILED " + message);
        }
    }

    private static void assertTrue(boolean actual, String message) {
        if (!actual) {
            throw new AssertionError("FAILED " + message);
        }
    }
}
