package com.bedro96.sshclient;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful ANSI escape parser that consumes CSI sequences and emits only visible text segments
 * with the currently active SGR style.
 */
final class AnsiEscapeParser {

    static final int DEFAULT_COLOR = -1;
    private static final char CSI_FINAL_BYTE_MIN = 0x40; // '@'
    private static final char CSI_FINAL_BYTE_MAX = 0x7e; // '~'

    static final class Style {
        final boolean bold;
        final int fgCode;
        final int bgCode;

        Style(boolean bold, int fgCode, int bgCode) {
            this.bold = bold;
            this.fgCode = fgCode;
            this.bgCode = bgCode;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Style)) { return false; }
            Style other = (Style) o;
            return bold == other.bold && fgCode == other.fgCode && bgCode == other.bgCode;
        }

        @Override
        public int hashCode() {
            int result = bold ? 1 : 0;
            result = 31 * result + fgCode;
            result = 31 * result + bgCode;
            return result;
        }
    }

    static final class Segment {
        final String text;
        final Style style;

        Segment(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    private final StringBuilder pendingEscape = new StringBuilder();
    private Style current = new Style(false, DEFAULT_COLOR, DEFAULT_COLOR);

    List<Segment> consume(String chunk) {
        List<Segment> out = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) { return out; }

        StringBuilder run = new StringBuilder();
        Style runStyle = current;
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (pendingEscape.length() > 0) {
                pendingEscape.append(ch);
                if (consumePendingEscape()) {
                    runStyle = current;
                }
                continue;
            }
            if (ch == 0x1b) {
                flushRun(out, run, runStyle);
                pendingEscape.append(ch);
                continue;
            }
            if (!runStyle.equals(current)) {
                flushRun(out, run, runStyle);
                runStyle = current;
            }
            run.append(ch);
        }
        flushRun(out, run, runStyle);
        return out;
    }

    void reset() {
        pendingEscape.setLength(0);
        current = new Style(false, DEFAULT_COLOR, DEFAULT_COLOR);
    }

    private boolean consumePendingEscape() {
        if (pendingEscape.length() < 2) { return false; }
        if (pendingEscape.charAt(1) != '[') {
            pendingEscape.setLength(0);
            return true;
        }
        if (pendingEscape.length() == 2) { return false; }
        char last = pendingEscape.charAt(pendingEscape.length() - 1);
        if (last < CSI_FINAL_BYTE_MIN || last > CSI_FINAL_BYTE_MAX) { return false; }
        if (last == 'm') {
            String params = pendingEscape.substring(2, pendingEscape.length() - 1);
            applySgr(params);
        }
        pendingEscape.setLength(0);
        return true;
    }

    private void applySgr(String params) {
        if (params.isEmpty()) {
            applySgrCode(0);
            return;
        }
        String[] parts = params.split(";", -1);
        for (String p : parts) {
            int code;
            if (p.isEmpty()) {
                code = 0;
            } else {
                try {
                    code = Integer.parseInt(p);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            applySgrCode(code);
        }
    }

    private void applySgrCode(int code) {
        boolean bold = current.bold;
        int fgCode = current.fgCode;
        int bgCode = current.bgCode;

        if (code == 0) {
            current = new Style(false, DEFAULT_COLOR, DEFAULT_COLOR);
            return;
        }
        if (code == 1) {
            bold = true;
        } else if (code == 21 || code == 22) {
            bold = false;
        } else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
            fgCode = code;
        } else if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) {
            bgCode = code;
        } else if (code == 39) {
            fgCode = DEFAULT_COLOR;
        } else if (code == 49) {
            bgCode = DEFAULT_COLOR;
        }
        current = new Style(bold, fgCode, bgCode);
    }

    private static void flushRun(List<Segment> out, StringBuilder run, Style style) {
        if (run.length() == 0) { return; }
        String text = run.toString();
        run.setLength(0);
        if (!out.isEmpty()) {
            Segment prev = out.get(out.size() - 1);
            if (prev.style.equals(style)) {
                out.set(out.size() - 1, new Segment(prev.text + text, style));
                return;
            }
        }
        out.add(new Segment(text, style));
    }
}
