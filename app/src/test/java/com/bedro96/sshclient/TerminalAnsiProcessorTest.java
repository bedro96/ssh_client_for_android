package com.bedro96.sshclient;

import java.util.ArrayList;
import java.util.List;

public final class TerminalAnsiProcessorTest {

    public static void main(String[] args) {
        testXterm256IndexMapping();
        testExtendedSgrForegroundAndBackground();
        testSplitSgrAcrossChunks();
        testBoldTracking();
        testOscTerminatedByBelIsDiscarded();
        testOscTerminatedByStIsDiscarded();
        testSplitOscAcrossChunksIsDiscarded();
        test8BitOscIsDiscarded();
        testOscColorQueryAndSetPayloadsAreDiscarded();
        test8BitCsiSgrIsApplied();
        testUnsupportedCsiIsConsumedWithoutLeakingParams();
        testSplitUnsupportedCsiAcrossChunksIsConsumed();
        testLineEditCsiIsStillReEmittedAsText();
        testSplitLineEditCsiIsReassembledAsText();
        testDcsPmApcSosStringsAreDiscarded();
        test8BitDcsPmApcSosStringsAreDiscarded();
        testSplit8BitOscAcrossChunksIsDiscarded();
        System.out.println("ALL TESTS PASSED");
    }

    private static void testBoldTracking() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        final List<Boolean> bolds = new ArrayList<>();
        TerminalAnsiProcessor.SegmentConsumer consumer = new TerminalAnsiProcessor.SegmentConsumer() {
            @Override public void accept(String text, boolean bold, Integer fg, Integer bg) {
                bolds.add(bold);
            }
        };
        processor.process("\u001b[1mB", consumer);   // bold on
        processor.process("\u001b[22mN", consumer);  // bold off
        processor.process("\u001b[1;0mR", consumer);  // bold then full reset -> off
        assertEquals(Boolean.TRUE, bolds.get(0), "bold on");
        assertEquals(Boolean.FALSE, bolds.get(1), "bold off");
        assertEquals(Boolean.FALSE, bolds.get(2), "bold reset");
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

    private static void testOscTerminatedByBelIsDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u001b]0;user@host: ~\u0007OK", new Capture(segments));
        assertEquals("OK", joinText(segments), "osc BEL should be discarded");
    }

    private static void testOscTerminatedByStIsDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u001b]0;user@host: ~\u001b\\OK", new Capture(segments));
        assertEquals("OK", joinText(segments), "osc ST should be discarded");
    }

    private static void testSplitOscAcrossChunksIsDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        processor.process("\u001b]0;user@host:", capture);
        processor.process(" ~\u0007OK", capture);
        assertEquals("OK", joinText(segments), "split osc should be discarded");
    }

    private static void test8BitOscIsDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u009d0;GitHub Copilot\u0007OK", new Capture(segments));
        assertEquals("OK", joinText(segments), "8-bit osc BEL should be discarded");
    }

    private static void testOscColorQueryAndSetPayloadsAreDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u001b]10;?\u0007\u001b]11;?\u0007\u001b]4;15;?\u001b\\", new Capture(segments));
        processor.process("\u001b]11;#0D1117\u0007\u001b]10;#F0F6FC\u001b\\DONE", new Capture(segments));
        assertEquals("DONE", joinText(segments), "osc color payloads should be discarded");
    }

    private static void test8BitCsiSgrIsApplied() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u009b38;5;82mOK", new Capture(segments));
        assertEquals(1, segments.size(), "8-bit csi sgr segment count");
        assertSegment(segments.get(0), "OK", 0x5fff00, null, "8-bit csi sgr");
    }

    private static void testUnsupportedCsiIsConsumedWithoutLeakingParams() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("A\u001b[?111;110lB\u001b[0cC", new Capture(segments));
        assertEquals("ABC", joinText(segments),
                "unsupported/private CSI should be consumed without leaking params");
    }

    private static void testSplitUnsupportedCsiAcrossChunksIsConsumed() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        processor.process("A\u009b?111;", capture);
        processor.process("110lB", capture);
        assertEquals("AB", joinText(segments),
                "split unsupported 8-bit csi should be consumed");
    }

    private static void testLineEditCsiIsStillReEmittedAsText() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("A\u001b[2KB", new Capture(segments));
        assertEquals("A\u001b[2KB", joinText(segments),
                "line-edit CSI should still be re-emitted as text");
    }

    private static void testSplitLineEditCsiIsReassembledAsText() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        processor.process("A\u001b[1", capture);
        processor.process("0GB", capture);
        assertEquals("A\u001b[10GB", joinText(segments),
                "split line-edit CSI should be reassembled and re-emitted intact");
    }

    private static void testDcsPmApcSosStringsAreDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u001bP1$r0 q\u001b\\A\u001b^meta\u001b\\B\u001b_apc\u001b\\C\u001bXsos\u001b\\D",
                new Capture(segments));
        assertEquals("ABCD", joinText(segments), "7-bit DCS/PM/APC/SOS should be discarded");
    }

    private static void test8BitDcsPmApcSosStringsAreDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u0090dcs\u009cA\u009epm\u009cB\u009fapc\u009cC\u0098sos\u009cD", new Capture(segments));
        assertEquals("ABCD", joinText(segments), "8-bit DCS/PM/APC/SOS should be discarded");
    }

    private static void testSplit8BitOscAcrossChunksIsDiscarded() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        processor.process("\u009d0;azureuser@kukovm: ~/ssh", capture);
        processor.process("_client_for_android\u009cOK", capture);
        assertEquals("OK", joinText(segments), "split 8-bit osc should be discarded");
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

    private static String joinText(List<Segment> segments) {
        StringBuilder allText = new StringBuilder();
        for (Segment segment : segments) {
            allText.append(segment.text);
        }
        return allText.toString();
    }

    private static final class Capture implements TerminalAnsiProcessor.SegmentConsumer {
        private final List<Segment> segments;

        private Capture(List<Segment> segments) {
            this.segments = segments;
        }

        @Override
        public void accept(String text, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
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
