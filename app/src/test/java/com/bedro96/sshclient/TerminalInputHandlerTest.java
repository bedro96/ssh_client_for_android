package com.bedro96.sshclient;

import java.util.Arrays;

public final class TerminalInputHandlerTest {

    private TerminalInputHandlerTest() { }

    public static void main(String[] args) {
        testTabSendsHorizontalTab();
        testShiftTabSendsBackTabSequence();
        System.out.println("TERMINAL INPUT HANDLER TESTS PASSED");
    }

    private static void testTabSendsHorizontalTab() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleTab(false, capture), "tab should be consumed");
        assertArrayEquals(new byte[] {0x09}, capture.bytes, "tab should send HT");
    }

    private static void testShiftTabSendsBackTabSequence() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleTab(true, capture), "shift+tab should be consumed");
        assertArrayEquals(new byte[] {0x1b, '[', 'Z'}, capture.bytes,
                "shift+tab should send ESC [ Z");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError("FAILED " + message);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("FAILED " + message + ": expected <"
                    + Arrays.toString(expected) + "> but was <" + Arrays.toString(actual) + ">");
        }
    }

    private static final class Capture implements TerminalInputHandler.Sender {
        private byte[] bytes;

        @Override public void send(byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
