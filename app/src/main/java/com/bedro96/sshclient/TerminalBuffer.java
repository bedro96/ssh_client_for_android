package com.bedro96.sshclient;

import java.util.ArrayDeque;
import java.util.WeakHashMap;

final class TerminalBuffer {

    private static final char ESC = 0x1b;
    private static final char DEL_CHAR = 0x7f;
    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLS = 80;
    private static final WeakHashMap<StringBuilder, State> STATES = new WeakHashMap<>();

    private TerminalBuffer() { }

    /**
     * Applies a remote-output chunk to the terminal screen model.
     */
    static void appendChunk(StringBuilder buffer, CharSequence chunk, int maxChars) {
        if (chunk == null || chunk.length() == 0) {
            return;
        }
        State state = stateFor(buffer);
        state.applyChunk(chunk);
        state.renderTo(buffer, maxChars);
    }

    static boolean isCursorVisible(StringBuilder buffer) {
        return stateFor(buffer).cursorVisible;
    }

    private static State stateFor(StringBuilder buffer) {
        State state = STATES.get(buffer);
        if (state == null) {
            state = new State(DEFAULT_ROWS, DEFAULT_COLS);
            STATES.put(buffer, state);
        }
        return state;
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

    private static final class State {
        private final Screen primary;
        private final Screen alternate;
        private Screen active;
        private final ArrayDeque<String> scrollback = new ArrayDeque<>();
        private int row;
        private int col;
        private int savedRow;
        private int savedCol;
        private int primaryRowBeforeAlt;
        private int primaryColBeforeAlt;
        private int scrollTop;
        private int scrollBottom;
        private boolean cursorVisible = true;
        private boolean useAlternate;
        private String pendingEscape = "";

        private State(int rows, int cols) {
            primary = new Screen(rows, cols);
            alternate = new Screen(rows, cols);
            active = primary;
            scrollTop = 0;
            scrollBottom = rows - 1;
        }

        private void applyChunk(CharSequence chunk) {
            String text;
            if (pendingEscape.isEmpty() && chunk instanceof String) {
                text = (String) chunk;
            } else {
                text = pendingEscape + chunk;
            }
            pendingEscape = "";
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ESC) {
                    if (i + 1 >= text.length()) {
                        pendingEscape = text.substring(i);
                        break;
                    }
                    char next = text.charAt(i + 1);
                    if (next == '[') {
                        int end = findCsiEnd(text, i + 2);
                        if (end < 0) {
                            pendingEscape = text.substring(i);
                            break;
                        }
                        applyCsi(text.substring(i + 2, end), text.charAt(end));
                        i = end;
                        continue;
                    }
                    applyEsc(next);
                    i++;
                    continue;
                }
                if (c == '\r') {
                    col = 0;
                    continue;
                }
                if (c == '\n') {
                    lineFeed(true);
                    continue;
                }
                if (c == '\b') {
                    col = Math.max(0, col - 1);
                    continue;
                }
                if (c == DEL_CHAR) {
                    if (col > 0) {
                        col--;
                        setCell(row, col, ' ');
                    }
                    continue;
                }
                if (c < 0x20) {
                    continue;
                }
                write(c);
            }
        }

        private void renderTo(StringBuilder out, int maxChars) {
            StringBuilder rendered = new StringBuilder();
            boolean hasText = false;
            for (String line : scrollback) {
                if (hasText) {
                    rendered.append('\n');
                }
                rendered.append(line);
                hasText = true;
            }
            int lastRow = active.lastNonBlankRow();
            for (int r = 0; r <= lastRow; r++) {
                if (hasText) {
                    rendered.append('\n');
                }
                rendered.append(active.rowText(r));
                hasText = true;
            }
            if (rendered.length() > maxChars) {
                rendered.delete(0, rendered.length() - maxChars);
            }
            out.setLength(0);
            out.append(rendered);
        }

        private void applyEsc(char command) {
            if (command == '7') {
                savedRow = row;
                savedCol = col;
                return;
            }
            if (command == '8') {
                restoreSavedCursor();
                return;
            }
            if (command == 'D') {
                lineFeed(false);
                return;
            }
            if (command == 'M') {
                reverseIndex();
            }
        }

        private void applyCsi(String params, char command) {
            if (command == 'H' || command == 'f') {
                int targetRow = parseCsiNumber(params, 0, 1) - 1;
                int targetCol = parseCsiNumber(params, 1, 1) - 1;
                row = clamp(targetRow, 0, active.rows - 1);
                col = clamp(targetCol, 0, active.cols - 1);
                return;
            }
            if (command == 'A') {
                row = Math.max(0, row - Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'B') {
                row = Math.min(active.rows - 1, row + Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'C') {
                col = Math.min(active.cols - 1, col + Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'D') {
                col = Math.max(0, col - Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'G') {
                col = clamp(parseCsiNumber(params, 0, 1) - 1, 0, active.cols - 1);
                return;
            }
            if (command == 's') {
                savedRow = row;
                savedCol = col;
                return;
            }
            if (command == 'u') {
                restoreSavedCursor();
                return;
            }
            if (command == 'J') {
                eraseDisplay(parseCsiNumber(params, 0, 0));
                return;
            }
            if (command == 'K') {
                eraseLine(parseCsiNumber(params, 0, 0));
                return;
            }
            if (command == 'r') {
                int top = parseCsiNumber(params, 0, 1);
                int bottom = parseCsiNumber(params, 1, active.rows);
                top = clamp(top, 1, active.rows);
                bottom = clamp(bottom, 1, active.rows);
                if (top <= bottom) {
                    scrollTop = top - 1;
                    scrollBottom = bottom - 1;
                    row = 0;
                    col = 0;
                }
                return;
            }
            if (command == 'S') {
                scrollUp(Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'T') {
                scrollDown(Math.max(1, parseCsiNumber(params, 0, 1)));
                return;
            }
            if (command == 'h' || command == 'l') {
                applyMode(params, command == 'h');
            }
        }

        private void applyMode(String params, boolean enable) {
            if (params == null || params.isEmpty() || params.charAt(0) != '?') {
                return;
            }
            String mode = params.substring(1);
            if ("25".equals(mode)) {
                cursorVisible = enable;
                return;
            }
            if ("47".equals(mode) || "1047".equals(mode) || "1049".equals(mode)) {
                if (enable) {
                    primaryRowBeforeAlt = row;
                    primaryColBeforeAlt = col;
                    useAlternate = true;
                    active = alternate;
                    alternate.clear();
                    row = 0;
                    col = 0;
                    scrollTop = 0;
                    scrollBottom = active.rows - 1;
                    return;
                }
                if (useAlternate) {
                    useAlternate = false;
                    active = primary;
                    scrollTop = 0;
                    scrollBottom = active.rows - 1;
                    row = clamp(primaryRowBeforeAlt, 0, active.rows - 1);
                    col = clamp(primaryColBeforeAlt, 0, active.cols - 1);
                }
            }
        }

        private void eraseDisplay(int mode) {
            if (mode == 2) {
                active.clear();
                return;
            }
            if (mode == 3) {
                scrollback.clear();
                return;
            }
            if (mode == 1) {
                for (int r = 0; r <= row; r++) {
                    int endCol = (r == row) ? col : active.cols - 1;
                    for (int c = 0; c <= endCol; c++) {
                        setCell(r, c, ' ');
                    }
                }
                return;
            }
            for (int r = row; r < active.rows; r++) {
                int startCol = (r == row) ? col : 0;
                for (int c = startCol; c < active.cols; c++) {
                    setCell(r, c, ' ');
                }
            }
        }

        private void eraseLine(int mode) {
            if (mode == 1) {
                for (int c = 0; c <= col; c++) {
                    setCell(row, c, ' ');
                }
                return;
            }
            if (mode == 2) {
                for (int c = 0; c < active.cols; c++) {
                    setCell(row, c, ' ');
                }
                return;
            }
            for (int c = col; c < active.cols; c++) {
                setCell(row, c, ' ');
            }
        }

        private void write(char c) {
            setCell(row, col, c);
            col++;
            if (col >= active.cols) {
                col = 0;
                lineFeed(false);
            }
        }

        private void lineFeed(boolean carriageReturn) {
            if (carriageReturn) {
                col = 0;
            }
            if (row == scrollBottom) {
                scrollUp(1);
                return;
            }
            row = Math.min(active.rows - 1, row + 1);
        }

        private void reverseIndex() {
            if (row == scrollTop) {
                scrollDown(1);
                return;
            }
            row = Math.max(0, row - 1);
        }

        private void scrollUp(int count) {
            for (int i = 0; i < count; i++) {
                if (scrollTop == 0 && scrollBottom == active.rows - 1 && !useAlternate) {
                    scrollback.addLast(active.rowText(0));
                }
                active.copyRows(scrollTop + 1, scrollBottom, -1);
                active.clearRow(scrollBottom);
            }
        }

        private void scrollDown(int count) {
            for (int i = 0; i < count; i++) {
                active.copyRows(scrollTop, scrollBottom - 1, 1);
                active.clearRow(scrollTop);
            }
        }

        private void setCell(int r, int c, char ch) {
            if (r < 0 || r >= active.rows || c < 0 || c >= active.cols) {
                return;
            }
            active.cells[r][c] = ch;
        }

        private void restoreSavedCursor() {
            row = clamp(savedRow, 0, active.rows - 1);
            col = clamp(savedCol, 0, active.cols - 1);
        }
    }

    private static final class Screen {
        private final int rows;
        private final int cols;
        private final char[][] cells;

        private Screen(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.cells = new char[rows][cols];
            clear();
        }

        private void clear() {
            for (int r = 0; r < rows; r++) {
                clearRow(r);
            }
        }

        private void clearRow(int row) {
            for (int c = 0; c < cols; c++) {
                cells[row][c] = ' ';
            }
        }

        private void copyRows(int fromStart, int fromEnd, int delta) {
            if (delta < 0) {
                for (int r = fromStart; r <= fromEnd; r++) {
                    cells[r + delta] = cells[r].clone();
                }
            } else {
                for (int r = fromEnd; r >= fromStart; r--) {
                    cells[r + delta] = cells[r].clone();
                }
            }
            int clearRow = delta < 0 ? fromEnd : fromStart;
            cells[clearRow] = new char[cols];
            for (int c = 0; c < cols; c++) {
                cells[clearRow][c] = ' ';
            }
        }

        private int lastNonBlankRow() {
            for (int r = rows - 1; r >= 0; r--) {
                if (!rowText(r).isEmpty()) {
                    return r;
                }
            }
            return -1;
        }

        private String rowText(int row) {
            int end = cols - 1;
            while (end >= 0 && cells[row][end] == ' ') {
                end--;
            }
            if (end < 0) {
                return "";
            }
            return new String(cells[row], 0, end + 1);
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
