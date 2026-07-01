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
        int from = deleteStartIndex(buffer);
        if (from < buffer.length()) {
            buffer.delete(from, buffer.length());
        }
    }

    /**
     * Index at which a "delete previous character" should start, accounting for
     * surrogate pairs. Returns {@code buffer.length()} when there is nothing to delete.
     * Shared with the spannable terminal buffer in {@code MainActivity}.
     */
    static int deleteStartIndex(CharSequence buffer) {
        int len = buffer.length();
        if (len == 0) { return len; }
        int from = len - 1;
        if (from > 0
                && Character.isLowSurrogate(buffer.charAt(from))
                && Character.isHighSurrogate(buffer.charAt(from - 1))) {
            from--;
        }
        return from;
    }
}
