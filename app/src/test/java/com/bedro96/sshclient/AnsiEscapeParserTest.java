package com.bedro96.sshclient;

import java.util.List;

public final class AnsiEscapeParserTest {

    public static void main(String[] args) {
        testStripsSgrSequences();
        testParsesSplitEscapeAcrossChunks();
        testConsumesNonSgrCsi();
        testRendersBoldAndColors();
        System.out.println("ANSI PARSER TESTS PASSED");
    }

    private static void testStripsSgrSequences() {
        AnsiEscapeParser parser = new AnsiEscapeParser();
        List<AnsiEscapeParser.Segment> out = parser.consume("\u001b[01;34mhello\u001b[0m world");
        assertEquals(2, out.size(), "segment count");
        assertEquals("hello", out.get(0).text, "styled text");
        assertEquals(" world", out.get(1).text, "reset text");
    }

    private static void testParsesSplitEscapeAcrossChunks() {
        AnsiEscapeParser parser = new AnsiEscapeParser();
        List<AnsiEscapeParser.Segment> out1 = parser.consume("\u001b[01;");
        assertEquals(0, out1.size(), "no visible output while escape is incomplete");
        List<AnsiEscapeParser.Segment> out2 = parser.consume("31mred");
        assertEquals(1, out2.size(), "one visible segment after escape completes");
        assertEquals("red", out2.get(0).text, "text after split sequence");
        assertEquals(31, out2.get(0).style.fgCode, "foreground code");
    }

    private static void testConsumesNonSgrCsi() {
        AnsiEscapeParser parser = new AnsiEscapeParser();
        List<AnsiEscapeParser.Segment> out = parser.consume("a\u001b[2Kb");
        assertEquals(1, out.size(), "content segment count");
        assertEquals("ab", out.get(0).text, "text around consumed CSI");
    }

    private static void testRendersBoldAndColors() {
        AnsiEscapeParser parser = new AnsiEscapeParser();
        List<AnsiEscapeParser.Segment> out = parser.consume("\u001b[1;92;44mx\u001b[22;39;49my");
        assertEquals(2, out.size(), "segment count");
        assertTrue(out.get(0).style.bold, "bold enabled");
        assertEquals(92, out.get(0).style.fgCode, "bright fg");
        assertEquals(44, out.get(0).style.bgCode, "background");
        assertTrue(!out.get(1).style.bold, "bold reset");
        assertEquals(-1, out.get(1).style.fgCode, "default fg");
        assertEquals(-1, out.get(1).style.bgCode, "default bg");
    }

    private static void assertEquals(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError("FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FAILED " + what);
        }
    }
}
