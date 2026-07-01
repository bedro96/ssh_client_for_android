package com.bedro96.sshclient;

final class TerminalBuffer {

    private static final char DEL_CHAR = 0x7f;

    private TerminalBuffer() { }

    /**
     * Applies a remote-output chunk to the terminal buffer, interpreting backspace/DEL deletes.
     */
    static void appendChunk(StringBuilder buffer, CharSequence chunk, int maxChars) {
        if (chunk == null || chunk.length() == 0) { return; }
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\b' || c == DEL_CHAR) {
                deletePrevious(buffer);
            } else {
                buffer.append(c);
            }
        }
        if (buffer.length() > maxChars) {
            buffer.delete(0, buffer.length() - maxChars);
        }
    }

    private static void deletePrevious(StringBuilder buffer) {
        if (buffer.length() == 0) { return; }
        int from = buffer.length() - 1;
        if (from > 0
                && Character.isLowSurrogate(buffer.charAt(from))
                && Character.isHighSurrogate(buffer.charAt(from - 1))) {
            from--;
        }
        buffer.delete(from, buffer.length());
    }
}
