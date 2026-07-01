package com.bedro96.sshclient;

public final class TerminalBufferTest {

    private static final String ESC = "\u001b";

    private TerminalBufferTest() { }

    public static void main(String[] args) {
        testDeleteSequenceViaBackspaceSpaceBackspace();
        testDeleteByteAlsoDeletesPreviousCharacter();
        testCursorAddressingAndSaveRestore();
        testEraseLineVariants();
        testEraseDisplayVariants();
        testAlternateScreenSwapRestoresPrimary();
        testScrollRegionAndIndexBehavior();
        testRepeatedSpinnerRepaintStaysOnSingleLine();
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

    private static void testCursorAddressingAndSaveRestore() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abc\ndef", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[1;2H" + "X", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[s" + ESC + "[2;3H" + "Y" + ESC + "[u" + "Z", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "7" + ESC + "[2;1H" + "Q" + ESC + "8" + "W", 200_000);
        assertEquals("aXZW\nQeY", b.toString(),
                "CUP/save/restore should overwrite in-place and restore cursor");
    }

    private static void testEraseLineVariants() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abcdef" + ESC + "[4G" + ESC + "[1K", 200_000);
        assertEquals("    ef", b.toString(), "ESC[1K should clear from line start to cursor");

        TerminalBuffer.appendChunk(b, ESC + "[2G" + ESC + "[0K", 200_000);
        assertEquals("", b.toString(), "ESC[0K should clear from cursor to end of line");

        TerminalBuffer.appendChunk(b, "xyz" + ESC + "[2K", 200_000);
        assertEquals("", b.toString(), "ESC[2K should clear the entire current line");
    }

    private static void testEraseDisplayVariants() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "line one\nline two\nline three", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[2;6H" + ESC + "[0J", 200_000);
        assertEquals("line one\nline", b.toString(), "ESC[0J should clear to end of display");

        TerminalBuffer.appendChunk(b, "restored", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[2J", 200_000);
        assertEquals("", b.toString(), "ESC[2J should clear entire display");
    }

    private static void testAlternateScreenSwapRestoresPrimary() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "shell output\nprompt$ ", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[?1049h" + "copilot-ui", 200_000);
        assertEquals("copilot-ui", b.toString(), "alternate screen should start clean");
        TerminalBuffer.appendChunk(b, ESC + "[?1049l", 200_000);
        assertEquals("shell output\nprompt$", b.toString(),
                "leaving alternate screen should restore primary content");
    }

    private static void testScrollRegionAndIndexBehavior() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "1\n2\n3\n4", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[2;3r" + ESC + "[3;1H" + ESC + "D" + "X", 200_000);
        assertEquals("1\n3\nX\n4", b.toString(),
                "index at bottom of scroll region should scroll region only");
        TerminalBuffer.appendChunk(b, ESC + "[2;1H" + ESC + "M" + "Y", 200_000);
        assertEquals("1\nY\n3\n4", b.toString(),
                "reverse index at top of region should scroll down within region");
    }

    private static void testRepeatedSpinnerRepaintStaysOnSingleLine() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "◎ Loading: 1 skills…", 200_000);
        TerminalBuffer.appendChunk(b, "\r" + ESC + "[2K◉ Loading: 84 skills…", 200_000);
        TerminalBuffer.appendChunk(b, "\r" + ESC + "[2K○ Loading: 84 skills…", 200_000);
        assertEquals("○ Loading: 84 skills…", b.toString(),
                "spinner repaint should update one visible line in-place");
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
