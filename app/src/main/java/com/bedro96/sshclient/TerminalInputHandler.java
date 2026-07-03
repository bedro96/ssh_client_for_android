package com.bedro96.sshclient;

import java.nio.charset.Charset;

final class TerminalInputHandler {

    interface Sender {
        void send(byte[] bytes);
    }

    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;

    // Mirror android.view.KeyEvent D-pad key codes so this stays Android-free/testable.
    static final int KEYCODE_DPAD_UP = 19;
    static final int KEYCODE_DPAD_DOWN = 20;
    static final int KEYCODE_DPAD_LEFT = 21;
    static final int KEYCODE_DPAD_RIGHT = 22;

    static final class KeyState {
        private boolean tabDownHandled;
    }

    private static final byte[] TAB = new byte[] {0x09};
    private static final byte[] BACK_TAB = new byte[] {0x1b, '[', 'Z'};
    private static final byte[] CURSOR_UP = new byte[] {0x1b, '[', 'A'};
    private static final byte[] CURSOR_DOWN = new byte[] {0x1b, '[', 'B'};
    private static final byte[] CURSOR_RIGHT = new byte[] {0x1b, '[', 'C'};
    private static final byte[] CURSOR_LEFT = new byte[] {0x1b, '[', 'D'};
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private TerminalInputHandler() { }

    /**
     * Encodes {@code text} as UTF-8 and sends it.  Covers the soft-keyboard /
     * paste path where characters (including {@code 0x20} for Space) arrive as
     * a {@link CharSequence} diff from the text-watcher rather than as a key
     * event.  Returns {@code true} when bytes were sent, {@code false} when
     * {@code text} was empty.
     */
    static boolean handleTypedText(String text, Sender sender) {
        if (text == null || text.isEmpty()) { return false; }
        // The Enter key (and pasted newlines) arrive as LF (0x0a), but a terminal
        // sends CR (0x0d) for Enter. Cooked-mode shells tolerate LF, but raw-mode
        // TUIs (e.g. the GitHub Copilot CLI) only accept CR, so translate here.
        sender.send(text.replace('\n', '\r').getBytes(UTF8));
        return true;
    }

    static boolean handleTab(boolean shiftPressed, Sender sender) {
        sender.send(shiftPressed ? BACK_TAB : TAB);
        return true;
    }

    static boolean handleTabKeyAction(int action, boolean shiftPressed, KeyState keyState,
                                      Sender sender) {
        if (action == ACTION_DOWN) {
            keyState.tabDownHandled = handleTab(shiftPressed, sender);
            return keyState.tabDownHandled;
        }
        if (action == ACTION_UP) {
            boolean handled = keyState.tabDownHandled;
            keyState.tabDownHandled = false;
            return handled;
        }
        return false;
    }

    /**
     * Maps a hardware D-pad/arrow key code to its ANSI cursor sequence, or
     * {@code null} for any other key. Physical arrow keys otherwise move the
     * caret inside the local EditText instead of reaching the remote shell.
     */
    static byte[] arrowKeySequence(int keyCode) {
        switch (keyCode) {
            case KEYCODE_DPAD_UP: return CURSOR_UP;
            case KEYCODE_DPAD_DOWN: return CURSOR_DOWN;
            case KEYCODE_DPAD_RIGHT: return CURSOR_RIGHT;
            case KEYCODE_DPAD_LEFT: return CURSOR_LEFT;
            default: return null;
        }
    }

    /**
     * Handles a hardware arrow key. Sends its cursor sequence on key-down and
     * consumes the matching key-up so the local caret never moves. Returns
     * {@code false} for non-arrow keys so other handlers can process them.
     */
    static boolean handleArrowKeyAction(int action, int keyCode, Sender sender) {
        byte[] sequence = arrowKeySequence(keyCode);
        if (sequence == null) {
            return false;
        }
        if (action == ACTION_DOWN) {
            sender.send(sequence);
        }
        return true;
    }
}
