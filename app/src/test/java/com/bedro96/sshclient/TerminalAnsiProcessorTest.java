package com.bedro96.sshclient;

import java.util.ArrayList;
import java.util.List;

public final class TerminalAnsiProcessorTest {

    public static void main(String[] args) {
        testXterm256IndexMapping();
        testExtendedSgrForegroundAndBackground();
        testSplitSgrAcrossChunks();
        System.out.println("ALL TESTS PASSED");
    }

    private static void testXterm256IndexMapping() {
        int[] base = new int[] {
                0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
                0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
                0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
        };
        for (int i = 0; i < base.length; i++) {
            assertEquals(base[i], TerminalAnsiProcessor.xterm256IndexToRgb(i), "base index " + i);
        }

        for (int index = 16; index <= 231; index++) {
            int shifted = index - 16;
            int[] levels = new int[] {0, 95, 135, 175, 215, 255};
            int expected = (levels[shifted / 36] << 16)
                    | (levels[(shifted % 36) / 6] << 8)
                    | levels[shifted % 6];
            assertEquals(expected, TerminalAnsiProcessor.xterm256IndexToRgb(index),
                    "cube index " + index);
        }

        for (int index = 232; index <= 255; index++) {
            int gray = 8 + ((index - 232) * 10);
            int expected = (gray << 16) | (gray << 8) | gray;
            assertEquals(expected, TerminalAnsiProcessor.xterm256IndexToRgb(index),
                    "gray index " + index);
        }
    }

    private static void testExtendedSgrForegroundAndBackground() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();

        processor.process("\u001b[38;5;196mR", new Capture(segments));
        processor.process("\u001b[48;5;22mG", new Capture(segments));
        processor.process("\u001b[0mN", new Capture(segments));

        assertEquals(3, segments.size(), "segment count");
        assertSegment(segments.get(0), "R", 0xff0000, null, "fg segment");
        assertSegment(segments.get(1), "G", 0xff0000, 0x005f00, "fg+bg segment");
        assertSegment(segments.get(2), "N", null, null, "reset segment");
    }

    private static void testSplitSgrAcrossChunks() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        processor.process("\u001b[38;5;", capture);
        processor.process("82mOK", capture);

        assertEquals(1, segments.size(), "split sequence segment count");
        assertSegment(segments.get(0), "OK", 0x5fff00, null, "split sequence fg");
    }

    private static void assertSegment(
            Segment segment, String text, Integer fg, Integer bg, String what) {
        assertEquals(text, segment.text, what + " text");
        assertEquals(fg, segment.foreground, what + " foreground");
        assertEquals(bg, segment.background, what + " background");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    "FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static final class Capture implements TerminalAnsiProcessor.SegmentConsumer {
        private final List<Segment> segments;

        private Capture(List<Segment> segments) {
            this.segments = segments;
        }

        @Override
        public void accept(String text, Integer foregroundRgb, Integer backgroundRgb) {
            segments.add(new Segment(text, foregroundRgb, backgroundRgb));
        }
    }

    private static final class Segment {
        private final String text;
        private final Integer foreground;
        private final Integer background;

        private Segment(String text, Integer foreground, Integer background) {
            this.text = text;
            this.foreground = foreground;
            this.background = background;
        }
    }
}
