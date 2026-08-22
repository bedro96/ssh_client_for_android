package com.bedro96.sshclient;

import java.nio.charset.StandardCharsets;
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
        testPrivateModeAndScrollRegionCsiAreForwardedToEmulator();
        testSplitUnsupportedCsiAcrossChunksIsConsumed();
        testLineEditCsiIsStillReEmittedAsText();
        testSplitLineEditCsiIsReassembledAsText();
        testNonCsiEscapesAreReEmittedAsText();
        testSs3FinalByteIsConsumedSilently();
        testSplitSs3AcrossChunksIsConsumed();
        testSs2FinalByteIsConsumedSilently();
        testCharsetDesignationIsConsumedSilently();
        testSplitCharsetAcrossChunksIsConsumed();
        testUnrecognizedTwoCharEscapeIsConsumedSilently();
        testDcsPmApcSosStringsAreDiscarded();
        test8BitDcsPmApcSosStringsAreDiscarded();
        testSplit8BitOscAcrossChunksIsDiscarded();
        testRawByteOscLeakIsDiscardedAcrossChunks();
        testRawByteStringPayloadsAreDiscardedAcrossChunks();
        testRawByte7BitStringControlsStayDiscardedAcrossChunks();
        testUtf8DecodedC1StillActsAsControl();
        testRawByteUtf8StillRendersAcrossChunks();
        testCompleteUtf8SequenceBeforeTrailingPartialStillDecodes();
        testIncompleteUtf8SequenceDoesNotCrossIntoControls();
        testTruecolorSgrForegroundAndBackground();
        testBoldBrightensStandardColorRegardlessOfOrder();
        testKoreanCharacterSplitAcrossTwo1024ByteReadsDecodesCorrectly();
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

    // Regression test: `ls -al` typically colors directories with bold blue ("\u001b[01;34m"),
    // which used to render as the hard-to-read dark blue (0x0000ee) instead of the brighter
    // variant (0x5c5cff). Verify bold upgrades the base color no matter which SGR code arrives
    // first.
    private static void testBoldBrightensStandardColorRegardlessOfOrder() {
        TerminalAnsiProcessor processorBoldFirst = new TerminalAnsiProcessor();
        List<Segment> boldFirst = new ArrayList<>();
        processorBoldFirst.process("\u001b[1;34mD", new Capture(boldFirst));
        assertSegment(boldFirst.get(0), "D", 0x5c5cff, null, "bold then color");

        TerminalAnsiProcessor processorColorFirst = new TerminalAnsiProcessor();
        List<Segment> colorFirst = new ArrayList<>();
        processorColorFirst.process("\u001b[34;1mD", new Capture(colorFirst));
        assertSegment(colorFirst.get(0), "D", 0x5c5cff, null, "color then bold");

        TerminalAnsiProcessor processorPlain = new TerminalAnsiProcessor();
        List<Segment> plain = new ArrayList<>();
        processorPlain.process("\u001b[34mD", new Capture(plain));
        assertSegment(plain.get(0), "D", 0x0000ee, null, "non-bold color stays dark");

        TerminalAnsiProcessor processorUnbold = new TerminalAnsiProcessor();
        List<Segment> unbold = new ArrayList<>();
        processorUnbold.process("\u001b[1;34m", new Capture(unbold));
        processorUnbold.process("\u001b[22mD", new Capture(unbold));
        assertSegment(unbold.get(0), "D", 0x0000ee, null, "unbolding reverts to dark color");
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
        // The processor forwards CSI verbatim to the VT100 emulator, which consumes
        // unknown/private sequences without ever rendering their parameters as text.
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("A\u001b[?111;110lB\u001b[0cC", new Capture(segments));
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append(joinText(segments));
        assertEquals("ABC", screen.snapshot(200_000).text,
                "unsupported/private CSI must not leak params as visible text on screen");
    }

    private static void testPrivateModeAndScrollRegionCsiAreForwardedToEmulator() {
        // Alt-screen (?1049h), cursor visibility (?25l) and scroll-region (r)
        // sequences must reach the emulator; otherwise full-screen TUIs like tmux
        // and GitHub Copilot CLI never switch buffers and their scrollback leaks
        // in, clipping rows and making the cursor slip lines.
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("A\u001b[?1049hB\u001b[?25lC\u001b[2;5rD", new Capture(segments));
        assertEquals("A\u001b[?1049hB\u001b[?25lC\u001b[2;5rD", joinText(segments),
                "private-mode and scroll-region CSI must be re-emitted for the emulator");
    }

    private static void testSplitUnsupportedCsiAcrossChunksIsConsumed() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        processor.process("A\u009b?111;", capture);
        processor.process("110lB", capture);
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append(joinText(segments));
        assertEquals("AB", screen.snapshot(200_000).text,
                "split unsupported 8-bit csi must not leak params as visible text on screen");
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

    private static void testNonCsiEscapesAreReEmittedAsText() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("A\u001b7B\u001b8C\u001bDD\u001bME", new Capture(segments));
        assertEquals("A\u001b7B\u001b8C\u001bDD\u001bME", joinText(segments),
                "non-CSI escapes should be re-emitted so terminal cursor ops survive parsing");
    }

    private static void testSs3FinalByteIsConsumedSilently() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        // ESC O B = SS3 cursor-down: the 'B' is the SS3 final and must NOT appear as text
        processor.process("before\u001bOBafter", new Capture(segments));
        assertEquals("beforeafter", joinText(segments),
                "ESC O B (SS3 cursor-down) must be fully consumed; 'B' must not leak");
    }

    private static void testSplitSs3AcrossChunksIsConsumed() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        // Chunk boundary falls between ESC O and the final byte
        processor.process("x\u001bO", capture);
        processor.process("By", capture);
        assertEquals("xy", joinText(segments),
                "SS3 split across chunks must still consume the final byte without leaking");
    }

    private static void testSs2FinalByteIsConsumedSilently() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        // ESC N = SS2, similarly consumes one final byte
        processor.process("a\u001bNBb", new Capture(segments));
        assertEquals("ab", joinText(segments),
                "ESC N <final> (SS2) must be fully consumed without leaking the final byte");
    }

    private static void testCharsetDesignationIsConsumedSilently() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        // ESC ( B = designate ASCII as G0 (emitted constantly by tmux/bash)
        processor.process("p\u001b(Bq\u001b)0r\u001b*As\u001b+Bt", new Capture(segments));
        assertEquals("pqrst", joinText(segments),
                "ESC ( B / ESC ) 0 / ESC * A / ESC + B charset sequences must be fully consumed");
    }

    private static void testSplitCharsetAcrossChunksIsConsumed() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);
        // Chunk boundary falls between ESC ( and the designator byte B
        processor.process("m\u001b(", capture);
        processor.process("Bn", capture);
        assertEquals("mn", joinText(segments),
                "charset designation split across chunks must consume designator without leaking");
    }

    private static void testUnrecognizedTwoCharEscapeIsConsumedSilently() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        // ESC = (keypad app mode), ESC > (keypad numeric mode), ESC c (full reset)
        // ESC E (NEL), none of which should emit visible text or raw ESC bytes
        processor.process("a\u001b=b\u001b>c\u001bcde\u001bEf", new Capture(segments));
        assertEquals("abcdef", joinText(segments),
                "unrecognized two-char escapes must be consumed silently without leaking");
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

    private static void testRawByteOscLeakIsDiscardedAcrossChunks() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        byte[] chunk1 = new byte[] {(byte) 0x9d, '0', ';', 'G', 'i', 't', 'H', 'u'};
        byte[] chunk2 = new byte[] {'b', ' ', 'C', 'o', 'p', 'i', 'l', 'o', 't', 0x07};
        byte[] chunk3 = "prompt$ ".getBytes(StandardCharsets.UTF_8);

        processor.process(chunk1, 0, chunk1.length, capture);
        processor.process(chunk2, 0, chunk2.length, capture);
        processor.process(chunk3, 0, chunk3.length, capture);

        assertEquals("prompt$ ", joinText(segments),
                "raw 8-bit osc bytes should not leak 0; title payload");
    }

    private static void testRawByteStringPayloadsAreDiscardedAcrossChunks() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        byte[] chunk1 = new byte[] {
                (byte) 0x9d, '1', '0', ';', '?', 0x07,
                (byte) 0x9d, '1', '1', ';', '#', '0', 'D'
        };
        byte[] chunk2 = new byte[] {
                '1', '1', '1', '7', (byte) 0x9c,
                (byte) 0x9d, '4', ';', '1', '5', ';', '?', 0x07,
                (byte) 0x90, 'd', 'c', 's'
        };
        byte[] chunk3 = new byte[] {
                (byte) 0x9c, (byte) 0x9e, 'p', 'm', (byte) 0x9c,
                (byte) 0x9f, 'a', 'p', 'c', (byte) 0x9c,
                (byte) 0x98, 's', 'o', 's', (byte) 0x9c,
                'D', 'O', 'N', 'E'
        };

        processor.process(chunk1, 0, chunk1.length, capture);
        processor.process(chunk2, 0, chunk2.length, capture);
        processor.process(chunk3, 0, chunk3.length, capture);

        assertEquals("DONE", joinText(segments),
                "raw byte OSC color and string payloads should be consumed");
    }

    private static void testRawByte7BitStringControlsStayDiscardedAcrossChunks() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        byte[] chunk1 = new byte[] {0x1b, ']', '0', ';', 't', 'i', 't', 'l', 'e', 0x1b};
        byte[] chunk2 = new byte[] {'\\', 0x1b, 'P', 'd', 'c', 's', 0x1b};
        byte[] chunk3 = new byte[] {'\\', 'O', 'K'};

        processor.process(chunk1, 0, chunk1.length, capture);
        processor.process(chunk2, 0, chunk2.length, capture);
        processor.process(chunk3, 0, chunk3.length, capture);

        assertEquals("OK", joinText(segments),
                "raw 7-bit OSC/DCS with split ST should stay discarded across chunks");
    }

    private static void testUtf8DecodedC1StillActsAsControl() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        processor.process(new byte[] {(byte) 0xc2}, 0, 1, capture);
        processor.process(new byte[] {(byte) 0x9d, '0', ';', 't', 'i', 't', 'l', 'e', 0x07}, 0, 9, capture);
        processor.process("OK".getBytes(StandardCharsets.UTF_8), 0, 2, capture);

        assertEquals("OK", joinText(segments),
                "decoded C1 bytes should still enter OSC mode instead of leaking payload text");
    }

    private static void testRawByteUtf8StillRendersAcrossChunks() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        byte[] prefix = "prefix ".getBytes(StandardCharsets.UTF_8);
        byte[] osc = new byte[] {(byte) 0x9d, '0', ';', 't', 'i', 't', 'l', 'e', 0x07};
        byte[] suffix = "한글🙂 suffix".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + osc.length + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        System.arraycopy(osc, 0, bytes, prefix.length, osc.length);
        System.arraycopy(suffix, 0, bytes, prefix.length + osc.length, suffix.length);
        processor.process(bytes, 0, 13, capture);
        processor.process(bytes, 13, 4, capture);
        processor.process(bytes, 17, bytes.length - 17, capture);

        assertEquals("prefix 한글🙂 suffix", joinText(segments),
                "raw byte path should keep split UTF-8 text intact");
    }

    private static void testCompleteUtf8SequenceBeforeTrailingPartialStillDecodes() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        byte[] smileAndPrefixOfSnowman = new byte[] {
                (byte) 0xf0, (byte) 0x9f, (byte) 0x99, (byte) 0x82,
                (byte) 0xe2
        };
        byte[] remainderOfSnowman = new byte[] {(byte) 0x98, (byte) 0x83};

        processor.process(smileAndPrefixOfSnowman, 0, smileAndPrefixOfSnowman.length, capture);
        assertEquals("🙂", joinText(segments),
                "complete UTF-8 before a trailing partial sequence should decode immediately");

        processor.process(remainderOfSnowman, 0, remainderOfSnowman.length, capture);
        assertEquals("🙂☃", joinText(segments),
                "trailing partial UTF-8 should resume on the next chunk");
    }

    private static void testIncompleteUtf8SequenceDoesNotCrossIntoControls() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        processor.process(new byte[] {(byte) 0xe2}, 0, 1, capture);
        processor.process(new byte[] {0x1b, ']', '0', ';', 't', 'i', 't', 'l', 'e', 0x07}, 0, 10, capture);
        processor.process("OK".getBytes(StandardCharsets.UTF_8), 0, 2, capture);

        assertEquals("\ufffdOK", joinText(segments),
                "an incomplete UTF-8 sequence should flush before an escape control");
    }

    private static void testTruecolorSgrForegroundAndBackground() {
        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        processor.process("\u001b[38;2;17;34;51;48;2;68;85;102mT", new Capture(segments));
        assertEquals(1, segments.size(), "truecolor sgr segment count");
        assertSegment(segments.get(0), "T", 0x112233, 0x445566, "truecolor sgr");
    }

    // Reproduces the production reader thread's real 1024-byte read buffer
    // (see SshConnectionService#startReader): a Korean (3-byte UTF-8) character
    // is positioned so its bytes straddle the boundary between the first and
    // second 1024-byte read, and the two chunks are fed to the processor
    // exactly as the reader thread would, one at a time, in order.
    private static void testKoreanCharacterSplitAcrossTwo1024ByteReadsDecodesCorrectly() {
        final int READ_BUFFER_SIZE = 1024;
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 1022; i++) {
            original.append('A');
        }
        // "한글" straddles the 1024-byte boundary: "한" starts at byte offset
        // 1022 and its 3 UTF-8 bytes (ED 95 9C) span offsets 1022-1024, so the
        // first 1024-byte read ends mid-character.
        original.append("한글 suffix");

        byte[] bytes = original.toString().getBytes(StandardCharsets.UTF_8);
        int firstChunkLength = Math.min(READ_BUFFER_SIZE, bytes.length);
        byte[] chunk1 = new byte[firstChunkLength];
        System.arraycopy(bytes, 0, chunk1, 0, firstChunkLength);
        byte[] chunk2 = new byte[bytes.length - firstChunkLength];
        System.arraycopy(bytes, firstChunkLength, chunk2, 0, chunk2.length);

        TerminalAnsiProcessor processor = new TerminalAnsiProcessor();
        List<Segment> segments = new ArrayList<>();
        Capture capture = new Capture(segments);

        processor.process(chunk1, 0, chunk1.length, capture);
        processor.process(chunk2, 0, chunk2.length, capture);

        assertEquals(original.toString(), joinText(segments),
                "Korean text split across two 1024-byte reads must decode without corruption");
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
