package com.bedro96.sshclient;

final class TerminalBuffer {

    private static final char ESC = 0x1b;
    private static final char DEL_CHAR = 0x7f;

    private TerminalBuffer() { }

    /**
     * Applies a remote-output chunk to the terminal buffer with minimal cursor-line controls.
     */
    static void appendChunk(StringBuilder buffer, CharSequence chunk, int maxChars) {
        if (chunk == null || chunk.length() == 0) { return; }
        int cursor = buffer.length();
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == ESC && i + 1 < chunk.length() && chunk.charAt(i + 1) == '[') {
                int end = findCsiEnd(chunk, i + 2);
                if (end > 0) {
                    cursor = applyCsi(buffer, chunk, i + 2, end, cursor);
                    i = end;
                    continue;
                }
            }
            if (c == '\r') {
                cursor = lineStart(buffer, cursor);
                continue;
            }
            if (c == '\b' || c == DEL_CHAR) {
                cursor = deletePrevious(buffer, cursor);
                continue;
            }
            cursor = writeAtCursor(buffer, cursor, c);
        }
        if (buffer.length() > maxChars) {
            buffer.delete(0, buffer.length() - maxChars);
        }
    }

    private static int findCsiEnd(CharSequence chunk, int start) {
        for (int i = start; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (ch >= 0x40 && ch <= 0x7e) {
                return i;
            }
        }
        return -1;
    }

    private static int applyCsi(StringBuilder buffer, CharSequence chunk, int paramStart, int end, int cursor) {
        char command = chunk.charAt(end);
        String params = chunk.subSequence(paramStart, end).toString();
        if (command == 'K') {
            return eraseLine(buffer, cursor, parseCsiNumber(params, 0, 0));
        }
        if (command == 'J' && parseCsiNumber(params, 0, 0) == 2) {
            buffer.setLength(0);
            return 0;
        }
        if (command == 'H') {
            return 0;
        }
        if (command == 'G') {
            int column = parseCsiNumber(params, 0, 1);
            int lineStart = lineStart(buffer, cursor);
            int lineEnd = lineEnd(buffer, cursor);
            return Math.min(lineStart + Math.max(0, column - 1), lineEnd);
        }
        if (command == 'C') {
            int amount = Math.max(0, parseCsiNumber(params, 0, 1));
            return Math.min(cursor + amount, lineEnd(buffer, cursor));
        }
        if (command == 'D') {
            int amount = Math.max(0, parseCsiNumber(params, 0, 1));
            return Math.max(cursor - amount, lineStart(buffer, cursor));
        }
        return cursor;
    }

    private static int eraseLine(StringBuilder buffer, int cursor, int mode) {
        int lineStart = lineStart(buffer, cursor);
        int lineEnd = lineEnd(buffer, cursor);
        if (mode == 1) {
            buffer.delete(lineStart, Math.min(cursor + 1, lineEnd));
            return lineStart;
        }
        if (mode == 2) {
            buffer.delete(lineStart, lineEnd);
            return lineStart;
        }
        buffer.delete(Math.min(cursor, lineEnd), lineEnd);
        return Math.min(cursor, buffer.length());
    }

    private static int writeAtCursor(StringBuilder buffer, int cursor, char c) {
        int len = buffer.length();
        int safeCursor = Math.max(0, Math.min(cursor, len));
        if (safeCursor < len && buffer.charAt(safeCursor) != '\n') {
            buffer.setCharAt(safeCursor, c);
            return safeCursor + 1;
        }
        buffer.insert(safeCursor, c);
        return safeCursor + 1;
    }

    private static int deletePrevious(StringBuilder buffer, int cursor) {
        int from = deleteStartIndex(buffer, cursor);
        if (from < cursor) {
            buffer.delete(from, cursor);
            return from;
        }
        return cursor;
    }

    private static int lineStart(CharSequence buffer, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, buffer.length()));
        for (int i = safeCursor - 1; i >= 0; i--) {
            if (buffer.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private static int lineEnd(CharSequence buffer, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, buffer.length()));
        for (int i = safeCursor; i < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n') {
                return i;
            }
        }
        return buffer.length();
    }

    private static int parseCsiNumber(String params, int index, int defaultValue) {
        if (params == null || params.isEmpty()) {
            return defaultValue;
        }
        String[] parts = params.split(";", -1);
        if (index >= parts.length || parts[index].isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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
