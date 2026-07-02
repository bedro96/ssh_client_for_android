package com.bedro96.sshclient;

public final class TerminalBufferTest {

    private static final String ESC = "\u001b";

    private TerminalBufferTest() { }

    public static void main(String[] args) {
        testDeleteSequenceViaBackspaceSpaceBackspace();
        testDeleteByteAlsoDeletesPreviousCharacter();
        testBasicShellScrollbackStaysIntact();
        testEraseLineVariants();
        testRepeatedSpinnerRepaintStaysOnSingleLine();
        testAlternateScreenSwapRestoresPrimary();
        testCursorVisibilityModes();
        System.out.println("TERMINAL BUFFER TESTS PASSED");
    }

    private static void testDeleteSequenceViaBackspaceSpaceBackspace() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abc\b \b", 200_000);
        assertEquals("ab", b.toString(), "backspace visibly deletes previous character");
    }

    private static void testDeleteByteAlsoDeletesPreviousCharacter() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abcd\u007f", 200_000);
        assertEquals("abc", b.toString(), "DEL should delete previous character");
    }

    private static void testBasicShellScrollbackStaysIntact() {
        StringBuilder b = new StringBuilder();
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
        TerminalBuffer.appendChunk(b, lsOutput, 200_000);
        assertContains(b.toString(), "file1", "shell scrollback should keep early output");
        assertContains(b.toString(), "file10", "shell scrollback should keep later output");
    }

    private static void testEraseLineVariants() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abcdef" + ESC + "[4G" + ESC + "[1K", 200_000);
        assertEquals("    ef", b.toString(), "ESC[1K should blank from line start to cursor");

        TerminalBuffer.appendChunk(b, ESC + "[2G" + ESC + "[0K", 200_000);
        assertEquals("", b.toString(), "ESC[0K should clear from cursor to line end");
    }

    private static void testRepeatedSpinnerRepaintStaysOnSingleLine() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "◎ Loading: 1 skills…", 200_000);
        TerminalBuffer.appendChunk(b, "\r" + ESC + "[2K◉ Loading: 84 skills…", 200_000);
        TerminalBuffer.appendChunk(b, "\r" + ESC + "[2K○ Loading: 84 skills…", 200_000);
        assertEquals("○ Loading: 84 skills…", b.toString(), "repeated redraw should update in-place");
    }

    private static void testAlternateScreenSwapRestoresPrimary() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "shell output\r\nprompt$ ", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[?1049h" + "copilot-ui", 200_000);
        assertEquals("copilot-ui", b.toString(), "alternate screen should start clean");
        TerminalBuffer.appendChunk(b, ESC + "[?1049l", 200_000);
        assertEquals("shell output\nprompt$", b.toString(),
                "leaving alternate screen should restore primary content");
    }

    private static void testCursorVisibilityModes() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, ESC + "[?25l", 200_000);
        assertFalse(TerminalBuffer.isCursorVisible(b), "ESC[?25l should hide cursor");
        TerminalBuffer.appendChunk(b, ESC + "[?25h", 200_000);
        assertTrue(TerminalBuffer.isCursorVisible(b), "ESC[?25h should show cursor");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expectedPart, String message) {
        if (actual == null || !actual.contains(expectedPart)) {
            throw new AssertionError("FAILED " + message + ": expected to find <" + expectedPart + "> in <" + actual + ">");
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
