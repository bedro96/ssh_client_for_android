package com.bedro96.sshclient;

import java.util.WeakHashMap;

final class TerminalBuffer {

    private static final WeakHashMap<StringBuilder, TerminalScreen> STATES = new WeakHashMap<>();

    private TerminalBuffer() { }

    /**
     * Applies a remote-output chunk to the terminal screen model.
     */
    static void appendChunk(StringBuilder buffer, CharSequence chunk, int maxChars) {
        if (chunk == null || chunk.length() == 0) {
            return;
        }
        TerminalScreen screen = stateFor(buffer);
        screen.append(chunk);
        buffer.setLength(0);
        buffer.append(screen.snapshot(maxChars).text);
    }

    static boolean isCursorVisible(StringBuilder buffer) {
        return stateFor(buffer).snapshot(Integer.MAX_VALUE).cursorVisible;
    }

    private static TerminalScreen stateFor(StringBuilder buffer) {
        TerminalScreen screen = STATES.get(buffer);
        if (screen == null) {
            screen = new TerminalScreen();
            STATES.put(buffer, screen);
        }
        return screen;
    }

    /**
     * Index at which a "delete previous character" should start, accounting for
     * surrogate pairs. Returns {@code buffer.length()} when there is nothing to delete.
     * Shared with the spannable terminal buffer in {@code MainActivity}.
     */
    static int deleteStartIndex(CharSequence buffer) {
        return deleteStartIndex(buffer, buffer.length());
    }

    static int deleteStartIndex(CharSequence buffer, int cursorExclusive) {
        int len = Math.max(0, Math.min(cursorExclusive, buffer.length()));
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
