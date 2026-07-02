package com.bedro96.sshclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class TerminalScreen {

    static final int DEFAULT_ROWS = 24;
    static final int DEFAULT_COLS = 80;

    private static final char ESC = 0x1b;
    private static final char DEL_CHAR = 0x7f;
    private static final int NO_COLOR = -1;

    private Screen primary;
    private Screen alternate;
    private Screen active;
    private final ArrayDeque<StoredRow> scrollback = new ArrayDeque<>();
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
    private boolean wrapPending;
    private String pendingEscape = "";

    TerminalScreen() {
        this(DEFAULT_ROWS, DEFAULT_COLS);
    }

    TerminalScreen(int rows, int cols) {
        rows = Math.max(1, rows);
        cols = Math.max(1, cols);
        primary = new Screen(rows, cols);
        alternate = new Screen(rows, cols);
        active = primary;
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    void reset() {
        int rows = active.rows;
        int cols = active.cols;
        primary = new Screen(rows, cols);
        alternate = new Screen(rows, cols);
        active = primary;
        scrollback.clear();
        row = 0;
        col = 0;
        savedRow = 0;
        savedCol = 0;
        primaryRowBeforeAlt = 0;
        primaryColBeforeAlt = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cursorVisible = true;
        useAlternate = false;
        wrapPending = false;
        pendingEscape = "";
    }

    void resize(int rows, int cols) {
        rows = Math.max(1, rows);
        cols = Math.max(1, cols);
        if (rows == active.rows && cols == active.cols) {
            return;
        }
        primary = primary.resized(rows, cols);
        alternate = alternate.resized(rows, cols);
        active = useAlternate ? alternate : primary;
        scrollTop = 0;
        scrollBottom = rows - 1;
        row = clamp(row, 0, rows - 1);
        col = clamp(col, 0, cols - 1);
        savedRow = clamp(savedRow, 0, rows - 1);
        savedCol = clamp(savedCol, 0, cols - 1);
        primaryRowBeforeAlt = clamp(primaryRowBeforeAlt, 0, rows - 1);
        primaryColBeforeAlt = clamp(primaryColBeforeAlt, 0, cols - 1);
        wrapPending = false;
    }

    void append(CharSequence chunk) {
        append(chunk, false, null, null);
    }

    void append(CharSequence chunk, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
        if (chunk == null || chunk.length() == 0) {
            return;
        }
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
                wrapPending = false;
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
                wrapPending = false;
                continue;
            }
            if (c == '\n') {
                lineFeed(true);
                continue;
            }
            if (c == '\b') {
                wrapPending = false;
                col = Math.max(0, col - 1);
                continue;
            }
            if (c == DEL_CHAR) {
                wrapPending = false;
                if (col > 0) {
                    col--;
                    setCell(row, col, ' ', false, null, null);
                }
                continue;
            }
            if (c < 0x20) {
                wrapPending = false;
                continue;
            }
            write(c, bold, foregroundRgb, backgroundRgb);
        }
    }

    Snapshot snapshot(int maxChars) {
        int safeMaxChars = Math.max(0, maxChars);
        StringBuilder text = new StringBuilder();
        List<StyleRun> runs = new ArrayList<>();

        int cursorIndex = 0;
        boolean cursorPlaced = false;
        boolean hasRows = false;

        for (StoredRow rowData : scrollback) {
            if (hasRows) {
                text.append('\n');
            }
            appendRow(text, runs, rowData);
            hasRows = true;
        }

        int lastVisibleRow = Math.max(active.lastNonBlankRow(), row);
        for (int r = 0; r <= lastVisibleRow; r++) {
            if (hasRows) {
                text.append('\n');
            }
            int rowStart = text.length();
            StoredRow rowData = active.snapshotRow(r);
            appendRow(text, runs, rowData);
            if (r == row) {
                cursorIndex = rowStart + Math.min(col, rowData.visibleLength);
                cursorPlaced = true;
            }
            hasRows = true;
        }

        if (!cursorPlaced) {
            cursorIndex = Math.min(text.length(), cursorIndex);
        }
        if (text.length() > safeMaxChars) {
            int overflow = text.length() - safeMaxChars;
            text.delete(0, overflow);
            cursorIndex = Math.max(0, cursorIndex - overflow);
            trimRuns(runs, overflow, safeMaxChars);
        }

        if (cursorIndex > text.length()) {
            cursorIndex = text.length();
        }
        return new Snapshot(text.toString(), cursorIndex, cursorVisible, runs);
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
        wrapPending = false;
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
            int top = clamp(parseCsiNumber(params, 0, 1), 1, active.rows);
            int bottom = clamp(parseCsiNumber(params, 1, active.rows), 1, active.rows);
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
                wrapPending = false;
                return;
            }
            if (useAlternate) {
                useAlternate = false;
                active = primary;
                scrollTop = 0;
                scrollBottom = active.rows - 1;
                row = clamp(primaryRowBeforeAlt, 0, active.rows - 1);
                col = clamp(primaryColBeforeAlt, 0, active.cols - 1);
                wrapPending = false;
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
                    setCell(r, c, ' ', false, null, null);
                }
            }
            return;
        }
        for (int r = row; r < active.rows; r++) {
            int startCol = (r == row) ? col : 0;
            for (int c = startCol; c < active.cols; c++) {
                setCell(r, c, ' ', false, null, null);
            }
        }
    }

    private void eraseLine(int mode) {
        if (mode == 1) {
            for (int c = 0; c <= col; c++) {
                setCell(row, c, ' ', false, null, null);
            }
            return;
        }
        if (mode == 2) {
            for (int c = 0; c < active.cols; c++) {
                setCell(row, c, ' ', false, null, null);
            }
            return;
        }
        for (int c = col; c < active.cols; c++) {
            setCell(row, c, ' ', false, null, null);
        }
    }

    private void write(char ch, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
        if (wrapPending) {
            col = 0;
            lineFeed(false);
            wrapPending = false;
        }
        setCell(row, col, ch, bold, foregroundRgb, backgroundRgb);
        if (col == active.cols - 1) {
            wrapPending = true;
        } else {
            col++;
        }
    }

    private void lineFeed(boolean carriageReturn) {
        if (carriageReturn) {
            col = 0;
        }
        wrapPending = false;
        if (row == scrollBottom) {
            scrollUp(1);
            return;
        }
        row = Math.min(active.rows - 1, row + 1);
    }

    private void reverseIndex() {
        wrapPending = false;
        if (row == scrollTop) {
            scrollDown(1);
            return;
        }
        row = Math.max(0, row - 1);
    }

    private void scrollUp(int count) {
        for (int i = 0; i < count; i++) {
            if (scrollTop == 0 && scrollBottom == active.rows - 1 && !useAlternate) {
                scrollback.addLast(active.snapshotRow(0));
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

    private void setCell(int targetRow, int targetCol, char ch, boolean bold,
            Integer foregroundRgb, Integer backgroundRgb) {
        if (targetRow < 0 || targetRow >= active.rows || targetCol < 0 || targetCol >= active.cols) {
            return;
        }
        active.chars[targetRow][targetCol] = ch;
        active.flags[targetRow][targetCol] = bold ? (byte) 1 : 0;
        active.foregrounds[targetRow][targetCol] = foregroundRgb == null ? NO_COLOR : foregroundRgb;
        active.backgrounds[targetRow][targetCol] = backgroundRgb == null ? NO_COLOR : backgroundRgb;
    }

    private void restoreSavedCursor() {
        wrapPending = false;
        row = clamp(savedRow, 0, active.rows - 1);
        col = clamp(savedCol, 0, active.cols - 1);
    }

    private static void appendRow(StringBuilder text, List<StyleRun> runs, StoredRow rowData) {
        for (int i = 0; i < rowData.visibleLength; i++) {
            int start = text.length();
            text.append(rowData.chars[i]);
            boolean bold = (rowData.flags[i] & 1) != 0;
            Integer foreground = rowData.foregrounds[i] == NO_COLOR ? null : rowData.foregrounds[i];
            Integer background = rowData.backgrounds[i] == NO_COLOR ? null : rowData.backgrounds[i];
            addRun(runs, start, start + 1, bold, foreground, background);
        }
    }

    private static void addRun(List<StyleRun> runs, int start, int end, boolean bold,
            Integer foregroundRgb, Integer backgroundRgb) {
        if (!bold && foregroundRgb == null && backgroundRgb == null) {
            return;
        }
        if (!runs.isEmpty()) {
            StyleRun last = runs.get(runs.size() - 1);
            if (last.end == start && last.bold == bold
                    && equalsColor(last.foregroundRgb, foregroundRgb)
                    && equalsColor(last.backgroundRgb, backgroundRgb)) {
                last.end = end;
                return;
            }
        }
        runs.add(new StyleRun(start, end, bold, foregroundRgb, backgroundRgb));
    }

    private static void trimRuns(List<StyleRun> runs, int overflow, int maxChars) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            StyleRun run = runs.get(i);
            int start = run.start - overflow;
            int end = run.end - overflow;
            if (end <= 0 || start >= maxChars) {
                runs.remove(i);
                continue;
            }
            run.start = Math.max(0, start);
            run.end = Math.min(maxChars, end);
        }
    }

    private static boolean equalsColor(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
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

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    static final class Snapshot {
        final String text;
        final int cursorIndex;
        final boolean cursorVisible;
        final List<StyleRun> runs;

        private Snapshot(String text, int cursorIndex, boolean cursorVisible, List<StyleRun> runs) {
            this.text = text;
            this.cursorIndex = cursorIndex;
            this.cursorVisible = cursorVisible;
            this.runs = runs;
        }
    }

    static final class StyleRun {
        int start;
        int end;
        final boolean bold;
        final Integer foregroundRgb;
        final Integer backgroundRgb;

        private StyleRun(int start, int end, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
            this.start = start;
            this.end = end;
            this.bold = bold;
            this.foregroundRgb = foregroundRgb;
            this.backgroundRgb = backgroundRgb;
        }
    }

    private static final class StoredRow {
        private final char[] chars;
        private final byte[] flags;
        private final int[] foregrounds;
        private final int[] backgrounds;
        private final int visibleLength;

        private StoredRow(char[] chars, byte[] flags, int[] foregrounds, int[] backgrounds, int visibleLength) {
            this.chars = chars;
            this.flags = flags;
            this.foregrounds = foregrounds;
            this.backgrounds = backgrounds;
            this.visibleLength = visibleLength;
        }
    }

    private static final class Screen {
        private final int rows;
        private final int cols;
        private final char[][] chars;
        private final byte[][] flags;
        private final int[][] foregrounds;
        private final int[][] backgrounds;

        private Screen(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            chars = new char[rows][cols];
            flags = new byte[rows][cols];
            foregrounds = new int[rows][cols];
            backgrounds = new int[rows][cols];
            clear();
        }

        private Screen resized(int newRows, int newCols) {
            Screen resized = new Screen(newRows, newCols);
            int rowsToCopy = Math.min(rows, newRows);
            int colsToCopy = Math.min(cols, newCols);
            int sourceRowStart = rows - rowsToCopy;
            int targetRowStart = newRows - rowsToCopy;
            for (int r = 0; r < rowsToCopy; r++) {
                int sourceRow = sourceRowStart + r;
                int targetRow = targetRowStart + r;
                System.arraycopy(chars[sourceRow], 0, resized.chars[targetRow], 0, colsToCopy);
                System.arraycopy(flags[sourceRow], 0, resized.flags[targetRow], 0, colsToCopy);
                System.arraycopy(foregrounds[sourceRow], 0, resized.foregrounds[targetRow], 0, colsToCopy);
                System.arraycopy(backgrounds[sourceRow], 0, resized.backgrounds[targetRow], 0, colsToCopy);
            }
            return resized;
        }

        private void clear() {
            for (int r = 0; r < rows; r++) {
                clearRow(r);
            }
        }

        private void clearRow(int targetRow) {
            for (int c = 0; c < cols; c++) {
                chars[targetRow][c] = ' ';
                flags[targetRow][c] = 0;
                foregrounds[targetRow][c] = NO_COLOR;
                backgrounds[targetRow][c] = NO_COLOR;
            }
        }

        private void copyRows(int fromStart, int fromEnd, int delta) {
            if (delta < 0) {
                for (int r = fromStart; r <= fromEnd; r++) {
                    chars[r + delta] = chars[r].clone();
                    flags[r + delta] = flags[r].clone();
                    foregrounds[r + delta] = foregrounds[r].clone();
                    backgrounds[r + delta] = backgrounds[r].clone();
                }
                clearRow(fromEnd);
                return;
            }
            for (int r = fromEnd; r >= fromStart; r--) {
                chars[r + delta] = chars[r].clone();
                flags[r + delta] = flags[r].clone();
                foregrounds[r + delta] = foregrounds[r].clone();
                backgrounds[r + delta] = backgrounds[r].clone();
            }
            clearRow(fromStart);
        }

        private int lastNonBlankRow() {
            for (int r = rows - 1; r >= 0; r--) {
                if (visibleLength(r) > 0) {
                    return r;
                }
            }
            return -1;
        }

        private StoredRow snapshotRow(int sourceRow) {
            int visibleLength = visibleLength(sourceRow);
            char[] rowChars = new char[visibleLength];
            byte[] rowFlags = new byte[visibleLength];
            int[] rowForegrounds = new int[visibleLength];
            int[] rowBackgrounds = new int[visibleLength];
            if (visibleLength > 0) {
                System.arraycopy(chars[sourceRow], 0, rowChars, 0, visibleLength);
                System.arraycopy(flags[sourceRow], 0, rowFlags, 0, visibleLength);
                System.arraycopy(foregrounds[sourceRow], 0, rowForegrounds, 0, visibleLength);
                System.arraycopy(backgrounds[sourceRow], 0, rowBackgrounds, 0, visibleLength);
            }
            return new StoredRow(rowChars, rowFlags, rowForegrounds, rowBackgrounds, visibleLength);
        }

        private int visibleLength(int sourceRow) {
            int end = cols - 1;
            while (end >= 0 && chars[sourceRow][end] == ' ') {
                end--;
            }
            return end + 1;
        }
    }
}
