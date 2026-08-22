package com.bedro96.sshclient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class TerminalScreen {

    static final int DEFAULT_ROWS = 24;
    static final int DEFAULT_COLS = 80;

    private static final char ESC = 0x1b;
    private static final char DEL_CHAR = 0x7f;
    private static final int NO_COLOR = -1;

    // Reply to CSI c / CSI 0c (primary Device Attributes) with a VT500-class
    // capability set matching real xterm-256color: 132 columns (1), printer
    // port (2), selective erase (6), national replacement charsets (9),
    // technical characters (15), user-defined keys (18), horizontal scrolling
    // (21), ANSI color (22) — the "64" leading token is xterm's VT500 class id.
    private static final byte[] PRIMARY_DA_REPLY =
            (ESC + "[?64;1;2;6;9;15;18;21;22c").getBytes(StandardCharsets.US_ASCII);
    // Reply to CSI > c (secondary Device Attributes): terminal type 41 ("xterm"),
    // a plausible firmware/patch version, and 0 for "no PC keyboard".
    private static final byte[] SECONDARY_DA_REPLY =
            (ESC + "[>41;300;0c").getBytes(StandardCharsets.US_ASCII);
    // Reply to CSI 5n (Device Status Report): "terminal OK".
    private static final byte[] DSR_OK_REPLY = (ESC + "[0n").getBytes(StandardCharsets.US_ASCII);

    /** Receives bytes that {@link TerminalScreen} needs to send back to the remote shell in
     * response to a query sequence (Device Attributes, Device Status Report, ...). Mirrors the
     * {@link TerminalAnsiProcessor.SegmentConsumer} callback pattern already used to move data
     * out of this class without a hard dependency on {@code SshConnectionService}. */
    interface ReplySink {
        void send(byte[] bytes);
    }

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
    private boolean bracketedPasteMode;
    private String pendingEscape = "";
    // No-op by default: TerminalScreen is exercised directly by unit tests and by
    // SshConnectionService (which wires a real sink once a session connects). Never
    // null so applyCsi() can call it unconditionally.
    private ReplySink replySink = new ReplySink() {
        @Override public void send(byte[] bytes) { }
    };

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

    // append()/resize()/reset()/snapshot() are the only entry points TerminalScreen
    // exposes across threads: SshConnectionService's background reader thread calls
    // append() for every incoming chunk while the UI thread calls resize() (rotation,
    // keyboard show/hide) and reset() (reconnect), and snapshot() is read for
    // rendering from the UI thread too. None of the mutated fields (active, row, col,
    // scrollTop/scrollBottom, scrollback, ...) were ever guarded, so a resize
    // interleaved with an in-flight append could observe/mutate them inconsistently
    // (see issue #54). Synchronizing on `this` for all four keeps the fix minimal and
    // consistent with the existing single-instance-shared-across-threads design.
    synchronized void reset() {
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
        bracketedPasteMode = false;
        pendingEscape = "";
    }

    /**
     * Registers where DA/DSR/CPR query replies should be written. Defaults to a no-op sink so
     * unit tests (and any TerminalScreen used before a connection exists) can call append()
     * without wiring one up. SshConnectionService sets a real sink that forwards to the SSH
     * remote input stream.
     */
    synchronized void setReplySink(ReplySink sink) {
        replySink = sink != null ? sink : new ReplySink() {
            @Override public void send(byte[] bytes) { }
        };
    }

    boolean isBracketedPasteMode() {
        return bracketedPasteMode;
    }

    synchronized void resize(int rows, int cols) {
        rows = Math.max(1, rows);
        cols = Math.max(1, cols);
        if (rows == active.rows && cols == active.cols) {
            return;
        }
        // Screen.resized() top-aligns on growth (existing rows keep their index,
        // new rows appear below) and bottom-aligns on shrink (rows that no longer
        // fit are dropped off the top). Row-tracking state below must shift by the
        // same amount the shrink path drops, or the cursor and saved positions end
        // up pointing at content that moved: writes then land on the wrong line
        // while the real last line is left stale on screen.
        int rowOffset = Math.max(0, active.rows - rows);
        primary = primary.resized(rows, cols);
        alternate = alternate.resized(rows, cols);
        active = useAlternate ? alternate : primary;
        scrollTop = 0;
        scrollBottom = rows - 1;
        row = clamp(row - rowOffset, 0, rows - 1);
        col = clamp(col, 0, cols - 1);
        savedRow = clamp(savedRow - rowOffset, 0, rows - 1);
        savedCol = clamp(savedCol, 0, cols - 1);
        primaryRowBeforeAlt = clamp(primaryRowBeforeAlt - rowOffset, 0, rows - 1);
        primaryColBeforeAlt = clamp(primaryColBeforeAlt, 0, cols - 1);
        wrapPending = false;
    }

    void append(CharSequence chunk) {
        append(chunk, false, null, null);
    }

    synchronized void append(CharSequence chunk, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
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

    synchronized Snapshot snapshot(int maxChars) {
        int safeMaxChars = Math.max(0, maxChars);
        StringBuilder text = new StringBuilder();
        List<StyleRun> runs = new ArrayList<>();

        int cursorIndex = 0;
        boolean cursorPlaced = false;
        boolean hasRows = false;

        // The alternate screen (tmux, vim, GitHub Copilot CLI) fully replaces the
        // display: it must render only its own rows, never the primary screen's
        // scrollback. Leaking scrollback here pushes the alternate rows down and
        // out of the viewport (bottom lines clipped) and offsets cursor-addressed
        // repaints, so a spinner/indicator appears to slip onto the next line.
        if (!useAlternate) {
            for (StoredRow rowData : scrollback) {
                if (hasRows) {
                    text.append('\n');
                }
                appendRow(text, runs, rowData);
                hasRows = true;
            }
        }

        int lastVisibleRow = Math.max(active.lastNonBlankRow(), row);
        for (int r = 0; r <= lastVisibleRow; r++) {
            if (hasRows) {
                text.append('\n');
            }
            int rowStart = text.length();
            StoredRow rowData = active.snapshotRow(r, r == row ? col : 0);
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
        if (command == 'L') {
            insertLines(Math.max(1, parseCsiNumber(params, 0, 1)));
            return;
        }
        if (command == 'M') {
            deleteLines(Math.max(1, parseCsiNumber(params, 0, 1)));
            return;
        }
        if (command == '@') {
            insertChars(Math.max(1, parseCsiNumber(params, 0, 1)));
            return;
        }
        if (command == 'P') {
            deleteChars(Math.max(1, parseCsiNumber(params, 0, 1)));
            return;
        }
        if (command == 'X') {
            eraseChars(Math.max(1, parseCsiNumber(params, 0, 1)));
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
            return;
        }
        if (command == 'c') {
            replyDeviceAttributes(params);
            return;
        }
        if (command == 'n') {
            replyDeviceStatusReport(params);
        }
    }

    // CSI c (bare/"0"): primary Device Attributes. CSI > c: secondary Device Attributes.
    // Real programs (tmux, vim, shells with fancy prompts) send these to probe terminal
    // capabilities and can stall or misbehave waiting for a reply that never arrives.
    private void replyDeviceAttributes(String params) {
        if (params != null && params.startsWith(">")) {
            replySink.send(SECONDARY_DA_REPLY);
            return;
        }
        if (params == null || params.isEmpty() || "0".equals(params)) {
            replySink.send(PRIMARY_DA_REPLY);
        }
    }

    // CSI 5n: "are you OK?" -> reply "I'm OK". CSI 6n: cursor position report, used by
    // e.g. vim/readline to locate the cursor; reply with the current 1-based row/column.
    private void replyDeviceStatusReport(String params) {
        int mode = parseCsiNumber(params, 0, 0);
        if (mode == 5) {
            replySink.send(DSR_OK_REPLY);
            return;
        }
        if (mode == 6) {
            String reply = ESC + "[" + (row + 1) + ";" + (col + 1) + "R";
            replySink.send(reply.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void applyMode(String params, boolean enable) {
        if (params == null || params.isEmpty() || params.charAt(0) != '?') {
            return;
        }
        // xterm allows batching several DEC private modes into one sequence, e.g.
        // "ESC[?1000;2004;1006h"; apply each token individually so every mode in
        // the batch takes effect, not just the first.
        String[] modes = params.substring(1).split(";", -1);
        for (String mode : modes) {
            applyPrivateMode(mode, enable);
        }
    }

    private void applyPrivateMode(String mode, boolean enable) {
        if ("25".equals(mode)) {
            cursorVisible = enable;
            return;
        }
        if ("2004".equals(mode)) {
            // Bracketed paste: no local UI paste path wraps input in ESC[200~/201~ today
            // (see TerminalInputHandler), so just track the flag rather than leaking the
            // raw mode-set/reset sequence into the visible grid.
            bracketedPasteMode = enable;
            return;
        }
        if ("1000".equals(mode) || "1002".equals(mode) || "1003".equals(mode)
                || "1005".equals(mode) || "1006".equals(mode) || "1015".equals(mode)
                || "1016".equals(mode)) {
            // Mouse tracking/reporting modes: this client has no mouse-event reporting
            // path, so acknowledge the query is understood by consuming it silently
            // rather than falling through to the same no-op default as a genuinely
            // unrecognized DEC private mode.
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
        int width = isWideChar(ch) ? 2 : 1;
        if (width == 2 && col == active.cols - 1) {
            // A double-width glyph (CJK ideograph, Hangul syllable, ...) never fits in a
            // single trailing column: defer it to the next row instead of splitting it
            // across the wrap boundary, matching how real terminals (and tmux's own
            // column bookkeeping) treat East Asian Wide characters.
            col = 0;
            lineFeed(false);
        }
        setCell(row, col, ch, bold, foregroundRgb, backgroundRgb);
        if (width == 2 && col + 1 < active.cols) {
            // Blank the glyph's continuation cell so stale content from a previous,
            // differently-sized write can never leak through the second column of a
            // double-width character.
            setCell(row, col + 1, ' ', false, null, null);
        }
        int nextCol = col + width;
        if (nextCol >= active.cols) {
            col = active.cols - 1;
            wrapPending = true;
        } else {
            col = nextCol;
        }
    }

    // Unicode East Asian Width ranges rendered as double-width by real terminals (and
    // assumed double-width by remote programs like tmux when they compute cursor/column
    // positions). Without this, our column bookkeeping silently drifts one column behind
    // the remote's for every CJK/Hangul character written, so anything drawn immediately
    // afterwards without an intervening absolute cursor move - e.g. a tmux vertical
    // pane-divider glyph continuing straight on from a neighboring pane's redrawn row -
    // lands at the wrong column (see issue #61). Restricted to the BMP: this class already
    // operates on individual UTF-16 chars, so supplementary-plane wide glyphs (rare in
    // terminal use) are out of scope.
    private static boolean isWideChar(char ch) {
        int c = ch;
        return (c >= 0x1100 && c <= 0x115F)   // Hangul Jamo
                || (c >= 0x2E80 && c <= 0x303E)  // CJK Radicals/Kangxi, CJK symbols & punctuation
                || (c >= 0x3041 && c <= 0x33FF)  // Hiragana..CJK Compatibility
                || (c >= 0x3400 && c <= 0x4DBF)  // CJK Unified Ideographs Extension A
                || (c >= 0x4E00 && c <= 0x9FFF)  // CJK Unified Ideographs
                || (c >= 0xA000 && c <= 0xA4CF)  // Yi Syllables/Radicals
                || (c >= 0xAC00 && c <= 0xD7A3)  // Hangul Syllables
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK Compatibility Ideographs
                || (c >= 0xFE30 && c <= 0xFE4F)  // CJK Compatibility Forms
                || (c >= 0xFF00 && c <= 0xFF60)  // Fullwidth Forms
                || (c >= 0xFFE0 && c <= 0xFFE6); // Fullwidth Signs
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

    // CSI L (IL): insert blank lines at the cursor row, shifting rows below it
    // down within the active scroll region. Rows pushed past scrollBottom are
    // dropped. A no-op if the cursor sits outside the scroll region.
    private void insertLines(int count) {
        if (row < scrollTop || row > scrollBottom) {
            return;
        }
        int n = Math.min(count, scrollBottom - row + 1);
        if (row + n <= scrollBottom) {
            active.copyRows(row, scrollBottom - n, n);
        }
        for (int r = row; r < row + n; r++) {
            active.clearRow(r);
        }
    }

    // CSI M (DL): delete lines starting at the cursor row, shifting rows below
    // it up within the active scroll region and pulling in blank rows at
    // scrollBottom. A no-op if the cursor sits outside the scroll region.
    private void deleteLines(int count) {
        if (row < scrollTop || row > scrollBottom) {
            return;
        }
        int n = Math.min(count, scrollBottom - row + 1);
        if (row + n <= scrollBottom) {
            active.copyRows(row + n, scrollBottom, -n);
        }
        for (int r = scrollBottom - n + 1; r <= scrollBottom; r++) {
            active.clearRow(r);
        }
    }

    // CSI @ (ICH): insert blank cells at the cursor column, shifting the rest
    // of the row right. Cells pushed past the last column are dropped.
    private void insertChars(int count) {
        int n = Math.min(count, active.cols - col);
        if (n <= 0) {
            return;
        }
        active.insertChars(row, col, n);
    }

    // CSI P (DCH): delete cells at the cursor column, shifting the rest of the
    // row left and filling the vacated cells at the row end with blanks.
    private void deleteChars(int count) {
        int n = Math.min(count, active.cols - col);
        if (n <= 0) {
            return;
        }
        active.deleteChars(row, col, n);
    }

    // CSI X (ECH): blank cells starting at the cursor column in place, without
    // shifting any other cells on the row.
    private void eraseChars(int count) {
        int n = Math.min(count, active.cols - col);
        if (n <= 0) {
            return;
        }
        active.eraseChars(row, col, n);
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
            // Growing: keep every existing row at its current index and add the
            // new rows below (top-aligned). Shrinking: keep the bottom rows —
            // the ones actually visible on screen — and drop the rest off the
            // top (bottom-aligned), matching how a real terminal keeps the
            // active viewport when it loses rows.
            int sourceRowStart = newRows >= rows ? 0 : rows - rowsToCopy;
            int targetRowStart = newRows >= rows ? 0 : newRows - rowsToCopy;
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

        // Shift cells [startCol, cols-1-count] right by count, dropping cells
        // that fall off the right edge, then blank the count cells starting at
        // startCol that were vacated by the shift.
        private void insertChars(int targetRow, int startCol, int count) {
            for (int c = cols - 1; c >= startCol + count; c--) {
                chars[targetRow][c] = chars[targetRow][c - count];
                flags[targetRow][c] = flags[targetRow][c - count];
                foregrounds[targetRow][c] = foregrounds[targetRow][c - count];
                backgrounds[targetRow][c] = backgrounds[targetRow][c - count];
            }
            blankCells(targetRow, startCol, Math.min(cols, startCol + count));
        }

        // Shift cells [startCol+count, cols-1] left by count, then blank the
        // count cells at the end of the row that were vacated by the shift.
        private void deleteChars(int targetRow, int startCol, int count) {
            for (int c = startCol; c < cols - count; c++) {
                chars[targetRow][c] = chars[targetRow][c + count];
                flags[targetRow][c] = flags[targetRow][c + count];
                foregrounds[targetRow][c] = foregrounds[targetRow][c + count];
                backgrounds[targetRow][c] = backgrounds[targetRow][c + count];
            }
            blankCells(targetRow, Math.max(startCol, cols - count), cols);
        }

        // Blank count cells starting at startCol in place, without shifting.
        private void eraseChars(int targetRow, int startCol, int count) {
            blankCells(targetRow, startCol, Math.min(cols, startCol + count));
        }

        private void blankCells(int targetRow, int fromCol, int toColExclusive) {
            for (int c = fromCol; c < toColExclusive; c++) {
                chars[targetRow][c] = ' ';
                flags[targetRow][c] = 0;
                foregrounds[targetRow][c] = NO_COLOR;
                backgrounds[targetRow][c] = NO_COLOR;
            }
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
            return snapshotRow(sourceRow, 0);
        }

        private StoredRow snapshotRow(int sourceRow, int minLength) {
            int trimmed = visibleLength(sourceRow);
            int visibleLength = trimmed > 0
                    ? Math.max(trimmed, Math.max(0, Math.min(minLength, cols)))
                    : trimmed;
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
