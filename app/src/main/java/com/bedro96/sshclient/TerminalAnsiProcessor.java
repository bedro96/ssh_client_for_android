package com.bedro96.sshclient;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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
    private static final int ESCAPE_STATE_STRING = 5;
    private static final int ESCAPE_STATE_STRING_MAYBE_ST = 6;

    private static final int[] BASE_16_RGB = new int[] {
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
            0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
            0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
    };
    private static final int[] CUBE_LEVELS = new int[] {0, 95, 135, 175, 215, 255};

    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder pendingEscape = new StringBuilder();
    private final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] pendingUtf8 = new byte[64];
    private int pendingUtf8Length;
    private int escapeState = ESCAPE_STATE_TEXT;
    private boolean bold;
    private Integer foregroundRgb;
    private Integer backgroundRgb;

    interface SegmentConsumer {
        void accept(String text, boolean bold, Integer foregroundRgb, Integer backgroundRgb);
    }

    void process(CharSequence chunk, SegmentConsumer consumer) {
        for (int i = 0; i < chunk.length(); i++) {
            processChar(chunk.charAt(i), consumer);
        }
        flushPendingText(consumer);
    }

    void process(byte[] chunk, int offset, int length, SegmentConsumer consumer) {
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int value = chunk[i] & 0xff;
            if (escapeState == ESCAPE_STATE_TEXT) {
                processPlainTextByte(value, consumer);
                continue;
            }
            processEscapeChar((char) value);
        }
        flushPendingUtf8(consumer, false);
        flushPendingText(consumer);
    }

    void reset() {
        pendingText.setLength(0);
        pendingEscape.setLength(0);
        pendingUtf8Length = 0;
        utf8Decoder.reset();
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
        if (ch == 0x9b) {
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_CSI;
            pendingEscape.setLength(0);
            pendingEscape.append('[');
            return;
        }
        if (ch == 0x9d) {
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_OSC;
            return;
        }
        if (ch == 0x90 || ch == 0x98 || ch == 0x9e || ch == 0x9f) {
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_STRING;
            return;
        }
        if (ch == 0x9c) {
            return;
        }
        pendingText.append(ch);
    }

    private void processPlainTextByte(int value, SegmentConsumer consumer) {
        if (trailingIncompleteUtf8Bytes() > 0) {
            if ((value & 0xc0) == 0x80) {
                appendUtf8Byte((byte) value);
                return;
            }
            flushPendingUtf8(consumer, true);
        }
        if (value >= 0xc2 && value <= 0xdf) {
            appendUtf8Byte((byte) value);
            return;
        }
        if (value >= 0xe0 && value <= 0xef) {
            appendUtf8Byte((byte) value);
            return;
        }
        if (value >= 0xf0 && value <= 0xf4) {
            appendUtf8Byte((byte) value);
            return;
        }
        if (value == 0x1b) {
            flushPendingUtf8(consumer, true);
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_AFTER_ESC;
            return;
        }
        if (value == 0x9b) {
            flushPendingUtf8(consumer, true);
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_CSI;
            pendingEscape.setLength(0);
            pendingEscape.append('[');
            return;
        }
        if (value == 0x9d) {
            flushPendingUtf8(consumer, true);
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_OSC;
            return;
        }
        if (value == 0x90 || value == 0x98 || value == 0x9e || value == 0x9f) {
            flushPendingUtf8(consumer, true);
            flushPendingText(consumer);
            escapeState = ESCAPE_STATE_STRING;
            return;
        }
        if (value == 0x9c) {
            return;
        }
        appendUtf8Byte((byte) value);
    }

    private void processChar(char ch, SegmentConsumer consumer) {
        if (escapeState == ESCAPE_STATE_TEXT) {
            processPlainTextChar(ch, consumer);
            return;
        }
        processEscapeChar(ch);
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
            if (ch == 'P' || ch == 'X' || ch == '^' || ch == '_') {
                escapeState = ESCAPE_STATE_STRING;
                return;
            }
            pendingText.append((char) 0x1b).append(ch);
            escapeState = ESCAPE_STATE_TEXT;
            return;
        }
        if (escapeState == ESCAPE_STATE_CSI) {
            pendingEscape.append(ch);
            if (ch >= 0x40 && ch <= 0x7e) {
                if (ch == 'm') {
                    applyEscapeSequence(pendingEscape.toString());
                } else if (isLineEditCsiFinal(ch)) {
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
            if (ch == 0x07 || ch == 0x9c) {
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
            return;
        }
        if (escapeState == ESCAPE_STATE_STRING) {
            if (ch == 0x9c) {
                escapeState = ESCAPE_STATE_TEXT;
                return;
            }
            if (ch == 0x1b) {
                escapeState = ESCAPE_STATE_STRING_MAYBE_ST;
            }
            return;
        }
        if (escapeState == ESCAPE_STATE_STRING_MAYBE_ST) {
            if (ch == '\\') {
                escapeState = ESCAPE_STATE_TEXT;
            } else if (ch != 0x1b) {
                escapeState = ESCAPE_STATE_STRING;
            }
        }
    }

    private void appendUtf8Byte(byte value) {
        if (pendingUtf8Length == pendingUtf8.length) {
            byte[] grown = new byte[pendingUtf8.length * 2];
            System.arraycopy(pendingUtf8, 0, grown, 0, pendingUtf8.length);
            pendingUtf8 = grown;
        }
        pendingUtf8[pendingUtf8Length++] = value;
    }

    private void flushPendingUtf8(SegmentConsumer consumer, boolean endOfInput) {
        int decodableLength = endOfInput
                ? pendingUtf8Length
                : pendingUtf8Length - trailingIncompleteUtf8Bytes();
        if (decodableLength == 0) {
            if (endOfInput) { utf8Decoder.reset(); }
            return;
        }
        ByteBuffer input = ByteBuffer.wrap(pendingUtf8, 0, decodableLength);
        CharBuffer output = CharBuffer.allocate(Math.max(8, decodableLength));
        try {
            utf8Decoder.reset();
            while (true) {
                CoderResult result = utf8Decoder.decode(input, output, true);
                appendDecodedChars(output, consumer);
                if (result.isOverflow()) {
                    continue;
                }
                if (result.isError()) {
                    result.throwException();
                }
                break;
            }
            while (true) {
                CoderResult result = utf8Decoder.flush(output);
                appendDecodedChars(output, consumer);
                if (result.isOverflow()) {
                    continue;
                }
                if (result.isError()) {
                    result.throwException();
                }
                utf8Decoder.reset();
                break;
            }
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("UTF-8 decode failed", e);
        }
        int remaining = pendingUtf8Length - decodableLength;
        if (remaining > 0) {
            System.arraycopy(pendingUtf8, decodableLength, pendingUtf8, 0, remaining);
        }
        pendingUtf8Length = remaining;
        flushPendingText(consumer);
    }

    private int trailingIncompleteUtf8Bytes() {
        if (pendingUtf8Length == 0) {
            return 0;
        }
        int lastIndex = pendingUtf8Length - 1;
        while (lastIndex >= 0 && (pendingUtf8[lastIndex] & 0xc0) == 0x80) {
            lastIndex--;
        }
        if (lastIndex < 0) {
            return 0;
        }
        int expectedLength = utf8SequenceLength(pendingUtf8[lastIndex] & 0xff);
        if (expectedLength <= 1) {
            return 0;
        }
        int actualLength = pendingUtf8Length - lastIndex;
        return actualLength < expectedLength ? actualLength : 0;
    }

    private static int utf8SequenceLength(int value) {
        if (value >= 0xc2 && value <= 0xdf) {
            return 2;
        }
        if (value >= 0xe0 && value <= 0xef) {
            return 3;
        }
        if (value >= 0xf0 && value <= 0xf4) {
            return 4;
        }
        return 1;
    }

    private void appendDecodedChars(CharBuffer output, SegmentConsumer consumer) {
        if (output.position() == 0) {
            return;
        }
        output.flip();
        while (output.hasRemaining()) {
            processChar(output.get(), consumer);
        }
        output.clear();
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
            int redIndex = typeIndex + 1;
            int greenIndex = typeIndex + 2;
            int blueIndex = typeIndex + 3;
            if (blueIndex < tokens.length) {
                int red = parseToken(tokens[redIndex]);
                int green = parseToken(tokens[greenIndex]);
                int blue = parseToken(tokens[blueIndex]);
                if (isRgbComponent(red) && isRgbComponent(green) && isRgbComponent(blue)) {
                    int rgb = (red << 16) | (green << 8) | blue;
                    if (foreground) {
                        foregroundRgb = rgb;
                    } else {
                        backgroundRgb = rgb;
                    }
                }
            }
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

    private static boolean isLineEditCsiFinal(char ch) {
        return ch == 'K' || ch == 'G' || ch == 'C' || ch == 'D' || ch == 'H' || ch == 'J';
    }

    private static boolean isRgbComponent(int value) {
        return value >= 0 && value <= 255;
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
