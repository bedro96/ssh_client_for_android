package com.bedro96.sshclient;

public final class TerminalBufferTest {

    private TerminalBufferTest() { }

    public static void main(String[] args) {
        testDeletePreviousCharacterOnBackspace();
        testRepeatedDeletesStayInSync();
        testBackspaceAtColumnZeroDoesNotCorruptBuffer();
        testDeleteByteAlsoDeletesPreviousCharacter();
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

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
