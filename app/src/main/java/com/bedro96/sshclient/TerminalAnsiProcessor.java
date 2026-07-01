package com.bedro96.sshclient;

/**
 * Parses ANSI escape sequences and emits plain text segments with current SGR colors.
 * Supports xterm 256-color SGR:
 * - foreground: ESC [ 38 ; 5 ; {n} m
 * - background: ESC [ 48 ; 5 ; {n} m
 */
public final class TerminalAnsiProcessor {

    private static final int[] BASE_16_RGB = new int[] {
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
            0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
            0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
    };
    private static final int[] CUBE_LEVELS = new int[] {0, 95, 135, 175, 215, 255};

    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder pendingEscape = new StringBuilder();
    private boolean readingEscape;
    private Integer foregroundRgb;
    private Integer backgroundRgb;

    interface SegmentConsumer {
        void accept(String text, Integer foregroundRgb, Integer backgroundRgb);
    }

    void process(CharSequence chunk, SegmentConsumer consumer) {
        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (!readingEscape) {
                if (ch == 0x1b) {
                    flushPendingText(consumer);
                    readingEscape = true;
                    pendingEscape.setLength(0);
                } else {
                    pendingText.append(ch);
                }
                continue;
            }

            if (pendingEscape.length() == 0 && ch != '[') {
                readingEscape = false;
                continue;
            }

            pendingEscape.append(ch);
            if (pendingEscape.length() > 1 && ch >= 0x40 && ch <= 0x7e) {
                applyEscapeSequence(pendingEscape.toString());
                pendingEscape.setLength(0);
                readingEscape = false;
            }
        }
        flushPendingText(consumer);
    }

    void reset() {
        pendingText.setLength(0);
        pendingEscape.setLength(0);
        readingEscape = false;
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
        consumer.accept(pendingText.toString(), foregroundRgb, backgroundRgb);
        pendingText.setLength(0);
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
            foregroundRgb = null;
            backgroundRgb = null;
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
