package com.bedro96.sshclient;

public final class TerminalGeometryTest {

    private TerminalGeometryTest() { }

    public static void main(String[] args) {
        testColumnsFloorsToWholeCells();
        testRowsFloorsSoTheLastRowIsFullyVisible();
        testNeverReturnsLessThanOne();
        System.out.println("TERMINAL GEOMETRY TESTS PASSED");
    }

    private static void testColumnsFloorsToWholeCells() {
        assertEquals(10, TerminalGeometry.columns(100, 10f),
                "an exact fit should yield exactly that many columns");
        assertEquals(10, TerminalGeometry.columns(109, 10f),
                "a partial trailing cell should be floored, not rounded up");
    }

    private static void testRowsFloorsSoTheLastRowIsFullyVisible() {
        assertEquals(24, TerminalGeometry.rows(480, 20),
                "an exact fit should yield exactly that many rows");
        assertEquals(24, TerminalGeometry.rows(499, 20),
                "a partial trailing row must be floored so the last row is not clipped");
    }

    private static void testNeverReturnsLessThanOne() {
        assertEquals(1, TerminalGeometry.columns(4, 10f),
                "columns must never drop below 1");
        assertEquals(1, TerminalGeometry.rows(4, 20),
                "rows must never drop below 1");
        assertEquals(1, TerminalGeometry.columns(0, 0f),
                "degenerate metrics must still yield at least 1 column");
        assertEquals(1, TerminalGeometry.rows(0, 0),
                "degenerate metrics must still yield at least 1 row");
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
