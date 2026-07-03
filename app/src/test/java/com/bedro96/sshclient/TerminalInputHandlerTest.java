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
        testSpaceSendsSpaceByte();
        testTypedTextSendsUtf8Bytes();
        testEmptyTextSendsNothing();
        testEnterSendsCarriageReturn();
        testNewlinesInPasteBecomeCarriageReturns();
        testArrowKeysMapToAnsiCursorSequences();
        testArrowKeyDownSendsSequenceAndUpIsConsumedSilently();
        testNonArrowKeyIsNotHandledByArrowHandler();
        testCtrlJMapsToLineFeed();
        testCtrlKeyDownSendsControlByteAndUpIsConsumedSilently();
        testCtrlKeyActionIgnoredWhenCtrlNotPressed();
        testNonLetterKeyIsNotHandledByCtrlHandler();
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

    private static void testSpaceSendsSpaceByte() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleTypedText(" ", capture),
                "space should be consumed");
        assertEquals(1, capture.sendCount, "space should send exactly once");
        assertArrayEquals(new byte[] {0x20}, capture.bytes, "space should send 0x20");
    }

    private static void testTypedTextSendsUtf8Bytes() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleTypedText("ls -al", capture),
                "typed text should be consumed");
        assertEquals(1, capture.sendCount, "typed text should send exactly once");
        assertArrayEquals("ls -al".getBytes(java.nio.charset.Charset.forName("UTF-8")),
                capture.bytes, "typed text should send correct UTF-8 bytes");
        // Verify the space in the middle is 0x20
        assertEquals(0x20, capture.bytes[2] & 0xff, "space in typed text should be 0x20");
    }

    private static void testEmptyTextSendsNothing() {
        Capture capture = new Capture();
        assertFalse(TerminalInputHandler.handleTypedText("", capture),
                "empty text should not be consumed");
        assertEquals(0, capture.sendCount, "empty text should not send bytes");
    }

    private static void testEnterSendsCarriageReturn() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleTypedText("\n", capture),
                "enter should be consumed");
        assertArrayEquals(new byte[] {0x0d}, capture.bytes,
                "Enter (newline) must be sent as CR 0x0d so raw-mode TUIs (e.g. Copilot CLI) receive it");
    }

    private static void testNewlinesInPasteBecomeCarriageReturns() {
        Capture capture = new Capture();
        TerminalInputHandler.handleTypedText("a\nb", capture);
        assertArrayEquals(new byte[] {'a', 0x0d, 'b'}, capture.bytes,
                "newlines inside typed/pasted text should each become CR");
    }

    private static void testArrowKeysMapToAnsiCursorSequences() {
        assertArrayEquals(new byte[] {0x1b, '[', 'A'},
                TerminalInputHandler.arrowKeySequence(TerminalInputHandler.KEYCODE_DPAD_UP),
                "hardware Up should map to ESC [ A");
        assertArrayEquals(new byte[] {0x1b, '[', 'B'},
                TerminalInputHandler.arrowKeySequence(TerminalInputHandler.KEYCODE_DPAD_DOWN),
                "hardware Down should map to ESC [ B");
        assertArrayEquals(new byte[] {0x1b, '[', 'C'},
                TerminalInputHandler.arrowKeySequence(TerminalInputHandler.KEYCODE_DPAD_RIGHT),
                "hardware Right should map to ESC [ C");
        assertArrayEquals(new byte[] {0x1b, '[', 'D'},
                TerminalInputHandler.arrowKeySequence(TerminalInputHandler.KEYCODE_DPAD_LEFT),
                "hardware Left should map to ESC [ D");
    }

    private static void testArrowKeyDownSendsSequenceAndUpIsConsumedSilently() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleArrowKeyAction(TerminalInputHandler.ACTION_DOWN,
                TerminalInputHandler.KEYCODE_DPAD_UP, capture), "arrow key down should be consumed");
        assertEquals(1, capture.sendCount, "arrow key down should send once");
        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, capture.bytes,
                "arrow key down should send its cursor sequence");
        assertTrue(TerminalInputHandler.handleArrowKeyAction(TerminalInputHandler.ACTION_UP,
                TerminalInputHandler.KEYCODE_DPAD_UP, capture), "arrow key up should be consumed");
        assertEquals(1, capture.sendCount, "arrow key up should not send additional bytes");
    }

    private static void testNonArrowKeyIsNotHandledByArrowHandler() {
        Capture capture = new Capture();
        assertFalse(TerminalInputHandler.handleArrowKeyAction(TerminalInputHandler.ACTION_DOWN,
                999, capture), "a non-arrow key must not be consumed by the arrow handler");
        assertEquals(0, capture.sendCount, "a non-arrow key must not send bytes");
        assertTrue(TerminalInputHandler.arrowKeySequence(999) == null,
                "a non-arrow key has no cursor sequence");
    }

    private static void testCtrlJMapsToLineFeed() {
        // Ctrl+J is the classic terminal control code for LF (0x0a); raw-mode
        // TUIs (e.g. the GitHub Copilot CLI) bind actions to it, so a hardware
        // keyboard's Ctrl+J must reach the remote as byte 0x0a.
        assertArrayEquals(new byte[] {0x0a},
                TerminalInputHandler.ctrlKeySequence(TerminalInputHandler.KEYCODE_J),
                "Ctrl+J should map to LF (0x0a)");
    }

    private static void testCtrlKeyDownSendsControlByteAndUpIsConsumedSilently() {
        Capture capture = new Capture();
        assertTrue(TerminalInputHandler.handleCtrlKeyAction(TerminalInputHandler.ACTION_DOWN,
                TerminalInputHandler.KEYCODE_J, true, capture),
                "ctrl+j key down should be consumed");
        assertEquals(1, capture.sendCount, "ctrl+j key down should send once");
        assertArrayEquals(new byte[] {0x0a}, capture.bytes, "ctrl+j key down should send LF");
        assertTrue(TerminalInputHandler.handleCtrlKeyAction(TerminalInputHandler.ACTION_UP,
                TerminalInputHandler.KEYCODE_J, true, capture),
                "ctrl+j key up should be consumed");
        assertEquals(1, capture.sendCount, "ctrl+j key up should not send additional bytes");
    }

    private static void testCtrlKeyActionIgnoredWhenCtrlNotPressed() {
        Capture capture = new Capture();
        assertFalse(TerminalInputHandler.handleCtrlKeyAction(TerminalInputHandler.ACTION_DOWN,
                TerminalInputHandler.KEYCODE_J, false, capture),
                "j without ctrl held must not be handled as a control sequence");
        assertEquals(0, capture.sendCount, "j without ctrl held must not send bytes");
    }

    private static void testNonLetterKeyIsNotHandledByCtrlHandler() {
        Capture capture = new Capture();
        assertFalse(TerminalInputHandler.handleCtrlKeyAction(TerminalInputHandler.ACTION_DOWN,
                999, true, capture), "a non-letter key must not be handled by the ctrl handler");
        assertEquals(0, capture.sendCount, "a non-letter key must not send bytes");
        assertTrue(TerminalInputHandler.ctrlKeySequence(999) == null,
                "a non-letter key has no ctrl sequence");
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
