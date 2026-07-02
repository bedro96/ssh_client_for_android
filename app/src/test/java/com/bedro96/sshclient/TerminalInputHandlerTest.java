package com.bedro96.sshclient;

import java.util.Arrays;

public final class TerminalInputHandlerTest {

    private TerminalInputHandlerTest() { }

    public static void main(String[] args) {
        testTabSendsHorizontalTab();
        testShiftTabSendsBackTabSequence();
        testTabKeyDownAndUpAreConsumedWithoutDuplicateBytes();
        testShiftTabKeyDownAndUpAreConsumedWithoutDuplicateBytes();
        testTabKeyUpWithoutMatchingDownIsIgnored();
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

    private static void testTabKeyDownAndUpAreConsumedWithoutDuplicateBytes() {
        Capture capture = new Capture();
        TerminalInputHandler.KeyState keyState = new TerminalInputHandler.KeyState();
        assertTrue(TerminalInputHandler.handleTabKeyAction(TerminalInputHandler.ACTION_DOWN,
                false, keyState, capture), "tab key down should be consumed");
        assertTrue(TerminalInputHandler.handleTabKeyAction(TerminalInputHandler.ACTION_UP,
                false, keyState, capture), "tab key up should be consumed after handled down");
        assertEquals(1, capture.sendCount, "tab key should only send once");
        assertArrayEquals(new byte[] {0x09}, capture.bytes, "tab key should send HT");
    }

    private static void testShiftTabKeyDownAndUpAreConsumedWithoutDuplicateBytes() {
        Capture capture = new Capture();
        TerminalInputHandler.KeyState keyState = new TerminalInputHandler.KeyState();
        assertTrue(TerminalInputHandler.handleTabKeyAction(TerminalInputHandler.ACTION_DOWN,
                true, keyState, capture), "shift+tab key down should be consumed");
        assertTrue(TerminalInputHandler.handleTabKeyAction(TerminalInputHandler.ACTION_UP,
                true, keyState, capture), "shift+tab key up should be consumed after handled down");
        assertEquals(1, capture.sendCount, "shift+tab key should only send once");
        assertArrayEquals(new byte[] {0x1b, '[', 'Z'}, capture.bytes,
                "shift+tab key should send ESC [ Z");
    }

    private static void testTabKeyUpWithoutMatchingDownIsIgnored() {
        Capture capture = new Capture();
        TerminalInputHandler.KeyState keyState = new TerminalInputHandler.KeyState();
        assertFalse(TerminalInputHandler.handleTabKeyAction(TerminalInputHandler.ACTION_UP,
                false, keyState, capture), "tab key up should not be consumed without down");
        assertEquals(0, capture.sendCount, "tab key up should not send bytes without down");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError("FAILED " + message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError("FAILED " + message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError("FAILED " + message + ": expected <"
                    + expected + "> but was <" + actual + ">");
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
        private int sendCount;

        @Override public void send(byte[] bytes) {
            this.bytes = bytes;
            sendCount++;
        }
    }
}
