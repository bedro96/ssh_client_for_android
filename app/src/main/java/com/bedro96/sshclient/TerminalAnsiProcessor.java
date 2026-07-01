package com.bedro96.sshclient;

/**
 * Parses ANSI escape sequences and emits plain text segments with current SGR colors.
 * Supports xterm 256-color SGR:
 * - foreground: ESC [ 38 ; 5 ; {n} m
 * - background: ESC [ 48 ; 5 ; {n} m
 */
public final class TerminalAnsiProcessor {
    private static final int ESCAPE_STATE_TEXT = 0;
    private static final int ESCAPE_STATE_AFTER_ESC = 1;
    private static final int ESCAPE_STATE_CSI = 2;
    private static final int ESCAPE_STATE_OSC = 3;
    private static final int ESCAPE_STATE_OSC_MAYBE_ST = 4;

    private static final int[] BASE_16_RGB = new int[] {
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
            0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
            0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
    };
    private static final int[] CUBE_LEVELS = new int[] {0, 95, 135, 175, 215, 255};

    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder pendingEscape = new StringBuilder();
    private int escapeState = ESCAPE_STATE_TEXT;
    private boolean bold;
    private Integer foregroundRgb;
    private Integer backgroundRgb;

    interface SegmentConsumer {
        void accept(String text, boolean bold, Integer foregroundRgb, Integer backgroundRgb);
    }

    void process(CharSequence chunk, SegmentConsumer consumer) {
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (escapeState == ESCAPE_STATE_TEXT) {
                processPlainTextChar(ch, consumer);
                continue;
            }
            processEscapeChar(ch);
        }
        flushPendingText(consumer);
    }

    void reset() {
        pendingText.setLength(0);
        pendingEscape.setLength(0);
        escapeState = ESCAPE_STATE_TEXT;
        bold = false;
        foregroundRgb = null;
        backgroundRgb = null;
    }

    static int xterm256IndexToRgb(int index) {
        if (index < 0 || index > 255) {
            throw new IllegalArgumentException("xterm-256color index out of range: " + index);
        }
        if (index < 16) {
            return BASE_16_RGB[index];
        }
        if (index < 232) {
            int shifted = index - 16;
            int red = shifted / 36;
            int green = (shifted % 36) / 6;
            int blue = shifted % 6;
            return (CUBE_LEVELS[red] << 16) | (CUBE_LEVELS[green] << 8) | CUBE_LEVELS[blue];
        }
        int gray = 8 + ((index - 232) * 10);
        return (gray << 16) | (gray << 8) | gray;
    }

    private void flushPendingText(SegmentConsumer consumer) {
        if (pendingText.length() == 0) {
            return;
        }
        consumer.accept(pendingText.toString(), bold, foregroundRgb, backgroundRgb);
        pendingText.setLength(0);
    }

    private void processPlainTextChar(char ch, SegmentConsumer consumer) {
        if (ch == 0x1b) {
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_AFTER_ESC;
            return;
        }
        pendingText.append(ch);
    }

    private void processEscapeChar(char ch) {
        if (escapeState == ESCAPE_STATE_AFTER_ESC) {
            if (ch == '[') {
                escapeState = ESCAPE_STATE_CSI;
                pendingEscape.setLength(0);
                pendingEscape.append(ch);
                return;
            }
            if (ch == ']') {
                escapeState = ESCAPE_STATE_OSC;
                return;
            }
            // Re-emit non-CSI escapes (ESC7/ESC8/ESCD/ESCM, etc.) so the
            // terminal buffer can apply cursor save/restore and scrolling ops.
            pendingText.append((char) 0x1b).append(ch);
            escapeState = ESCAPE_STATE_TEXT;
            return;
        }
        if (escapeState == ESCAPE_STATE_CSI) {
            pendingEscape.append(ch);
            if (ch >= 0x40 && ch <= 0x7e) {
                if (ch == 'm') {
                    applyEscapeSequence(pendingEscape.toString());
                } else {
                    // Re-emit cursor/erase CSI (e.g. CR redraws: ESC[K, ESC[G, ESC[C/D)
                    // as literal text so the terminal buffer can interpret them for
                    // in-place line redraws. Reassembled here so split-chunk sequences
                    // reach the buffer intact.
                    pendingText.append((char) 0x1b).append(pendingEscape);
                }
                pendingEscape.setLength(0);
                escapeState = ESCAPE_STATE_TEXT;
            }
            return;
        }
        if (escapeState == ESCAPE_STATE_OSC) {
            if (ch == 0x07) {
                escapeState = ESCAPE_STATE_TEXT;
                return;
            }
            if (ch == 0x1b) {
                escapeState = ESCAPE_STATE_OSC_MAYBE_ST;
            }
            return;
        }
        if (escapeState == ESCAPE_STATE_OSC_MAYBE_ST) {
            if (ch == '\\') {
                escapeState = ESCAPE_STATE_TEXT;
            } else if (ch != 0x1b) {
                escapeState = ESCAPE_STATE_OSC;
            }
        }
    }

    private void applyEscapeSequence(String sequence) {
        if (sequence.length() < 2 || sequence.charAt(0) != '[' || sequence.charAt(sequence.length() - 1) != 'm') {
            return;
        }
        String body = sequence.substring(1, sequence.length() - 1);
        if (body.isEmpty()) {
            applySgr(0);
            return;
        }

        String[] tokens = body.split(";", -1);
        for (int i = 0; i < tokens.length; i++) {
            int code = parseToken(tokens[i]);
            if (code < 0) {
                continue;
            }
            if (code == 38 || code == 48) {
                i = applyExtendedColor(tokens, i, code == 38);
                continue;
            }
            applySgr(code);
        }
    }

    private int applyExtendedColor(String[] tokens, int modeIndex, boolean foreground) {
        int typeIndex = modeIndex + 1;
        if (typeIndex >= tokens.length) {
            return modeIndex;
        }
        int type = parseToken(tokens[typeIndex]);
        if (type == 5) {
            int colorIndexPos = typeIndex + 1;
            if (colorIndexPos < tokens.length) {
                int paletteIndex = parseToken(tokens[colorIndexPos]);
                if (paletteIndex >= 0 && paletteIndex <= 255) {
                    int rgb = xterm256IndexToRgb(paletteIndex);
                    if (foreground) {
                        foregroundRgb = rgb;
                    } else {
                        backgroundRgb = rgb;
                    }
                }
                return colorIndexPos;
            }
            return typeIndex;
        }
        if (type == 2) {
            return Math.min(typeIndex + 3, tokens.length - 1);
        }
        return typeIndex;
    }

    private void applySgr(int code) {
        if (code == 0) {
            bold = false;
            foregroundRgb = null;
            backgroundRgb = null;
            return;
        }
        if (code == 1) {
            bold = true;
            return;
        }
        if (code == 21 || code == 22) {
            bold = false;
            return;
        }
        if (code == 39) {
            foregroundRgb = null;
            return;
        }
        if (code == 49) {
            backgroundRgb = null;
            return;
        }
        if (code >= 30 && code <= 37) {
            foregroundRgb = xterm256IndexToRgb(code - 30);
            return;
        }
        if (code >= 40 && code <= 47) {
            backgroundRgb = xterm256IndexToRgb(code - 40);
            return;
        }
        if (code >= 90 && code <= 97) {
            foregroundRgb = xterm256IndexToRgb((code - 90) + 8);
            return;
        }
        if (code >= 100 && code <= 107) {
            backgroundRgb = xterm256IndexToRgb((code - 100) + 8);
        }
    }

    private static int parseToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
