package com.bedro96.sshclient;

public final class TerminalBufferTest {

    private static final String ESC = "\u001b";

    private TerminalBufferTest() { }

    public static void main(String[] args) {
        testDeletePreviousCharacterOnBackspace();
        testRepeatedDeletesStayInSync();
        testBackspaceAtColumnZeroDoesNotCorruptBuffer();
        testDeleteByteAlsoDeletesPreviousCharacter();
        testCarriageReturnOverwritesFromColumnZero();
        testEraseToEndOfLine();
        testEraseLineVariants();
        testRepeatedRedrawStaysOnSingleLine();
        testClearAndHomeResetVisibleBuffer();
        System.out.println("TERMINAL BUFFER TESTS PASSED");
    }

    private static void testDeletePreviousCharacterOnBackspace() {
        StringBuilder b = new StringBuilder("abc");
        TerminalBuffer.appendChunk(b, "\b \b", 200_000);
        assertEquals("ab", b.toString(), "backspace visibly deletes previous character");
    }

    private static void testRepeatedDeletesStayInSync() {
        StringBuilder b = new StringBuilder("prompt$ hello");
        TerminalBuffer.appendChunk(b, "\b \b\b \b\b \b", 200_000);
        assertEquals("prompt$ he", b.toString(), "repeated backspaces should stay in sync");
    }

    private static void testBackspaceAtColumnZeroDoesNotCorruptBuffer() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "\b\b", 200_000);
        assertEquals("", b.toString(), "backspace at column zero should be a no-op");
    }

    private static void testDeleteByteAlsoDeletesPreviousCharacter() {
        StringBuilder b = new StringBuilder("abcd");
        TerminalBuffer.appendChunk(b, "\u007f", 200_000);
        assertEquals("abc", b.toString(), "DEL should delete previous character");
    }

    private static void testCarriageReturnOverwritesFromColumnZero() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abc\rX", 200_000);
        assertEquals("Xbc", b.toString(), "carriage return should overwrite from column zero");
    }

    private static void testEraseToEndOfLine() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "foobar\rfoo" + ESC + "[K", 200_000);
        assertEquals("foo", b.toString(), "ESC[K should clear the tail from cursor to end");
    }

    private static void testEraseLineVariants() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "abcdef" + ESC + "[4G" + ESC + "[1K", 200_000);
        assertEquals("ef", b.toString(), "ESC[1K should clear from line start to cursor");

        TerminalBuffer.appendChunk(b, ESC + "[2K", 200_000);
        assertEquals("", b.toString(), "ESC[2K should clear entire current line");
    }

    private static void testRepeatedRedrawStaysOnSingleLine() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "loading 0%" + ESC + "[K", 200_000);
        TerminalBuffer.appendChunk(b, "\rloading 50%" + ESC + "[K", 200_000);
        TerminalBuffer.appendChunk(b, "\rloading 100%" + ESC + "[K", 200_000);
        assertEquals("loading 100%", b.toString(), "repeated redraw should update in-place");
    }

    private static void testClearAndHomeResetVisibleBuffer() {
        StringBuilder b = new StringBuilder();
        TerminalBuffer.appendChunk(b, "line one\nline two", 200_000);
        TerminalBuffer.appendChunk(b, ESC + "[2J" + ESC + "[H" + "fresh", 200_000);
        assertEquals("fresh", b.toString(), "ESC[2J + ESC[H should clear and reset output");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
