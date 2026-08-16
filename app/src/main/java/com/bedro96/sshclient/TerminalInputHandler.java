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
    // Mirror android.view.KeyEvent.KEYCODE_ESCAPE so a hardware/bluetooth
    // keyboard's Escape key can be forwarded like the on-screen ESC toolbar
    // button, without an Android dependency.
    static final int KEYCODE_ESCAPE = 111;

    // Mirror android.view.KeyEvent letter key codes (A=29 .. Z=54) so Ctrl+<letter>
    // combos from a hardware/bluetooth keyboard can be mapped without an Android
    // dependency. KEYCODE_J is called out because Ctrl+J (LF, 0x0a) is bound to
    // actions in raw-mode TUIs such as the GitHub Copilot CLI.
    static final int KEYCODE_A = 29;
    static final int KEYCODE_Z = 54;
    static final int KEYCODE_J = KEYCODE_A + ('J' - 'A');

    static final class KeyState {
        private boolean tabDownHandled;
    }

    private static final byte[] TAB = new byte[] {0x09};
    private static final byte[] BACK_TAB = new byte[] {0x1b, '[', 'Z'};
    private static final byte[] CURSOR_UP = new byte[] {0x1b, '[', 'A'};
    private static final byte[] CURSOR_DOWN = new byte[] {0x1b, '[', 'B'};
    private static final byte[] CURSOR_RIGHT = new byte[] {0x1b, '[', 'C'};
    private static final byte[] CURSOR_LEFT = new byte[] {0x1b, '[', 'D'};
    private static final byte[] ESCAPE = new byte[] {0x1b};
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

    /**
     * Handles a hardware Escape key. Sends the single ESC byte (0x1b) on key-down
     * and consumes the matching key-up so the key is always forwarded remotely.
     *
     * Hardware ESC is not delivered through the soft-keyboard text-diff path, and
     * if we do not consume/send it explicitly, raw-mode TUIs never receive ESC.
     * This keeps hardware-key behavior aligned with the on-screen ESC toolbar key.
     * We also consume ACTION_UP to keep the whole keypress owned by the terminal
     * input path, preventing local/system handling from stealing part of the event.
     */
    static boolean handleEscapeKeyAction(int action, int keyCode, Sender sender) {
        if (keyCode != KEYCODE_ESCAPE) {
            return false;
        }
        if (action == ACTION_DOWN) {
            sender.send(ESCAPE);
        }
        return true;
    }

    /**
     * Maps a hardware letter key code to its ASCII control byte (Ctrl+A=0x01 ..
     * Ctrl+Z=0x1a), or {@code null} for any other key. Ctrl+J in particular must
     * reach the remote as LF (0x0a): raw-mode TUIs (e.g. the GitHub Copilot CLI)
     * bind actions to it, but a plain EditText silently swallows the combo
     * instead of producing text.
     */
    static byte[] ctrlKeySequence(int keyCode) {
        if (keyCode < KEYCODE_A || keyCode > KEYCODE_Z) {
            return null;
        }
        return new byte[] {(byte) (keyCode - KEYCODE_A + 1)};
    }

    /**
     * Handles a hardware Ctrl+<letter> combo. Sends its control byte on
     * key-down and consumes the matching key-up. Returns {@code false} when
     * Ctrl isn't held or the key isn't a letter, so other handlers/the default
     * EditText behavior can process it.
     */
    static boolean handleCtrlKeyAction(int action, int keyCode, boolean ctrlPressed,
                                        Sender sender) {
        if (!ctrlPressed) {
            return false;
        }
        byte[] sequence = ctrlKeySequence(keyCode);
        if (sequence == null) {
            return false;
        }
        if (action == ACTION_DOWN) {
            sender.send(sequence);
        }
        return true;
    }
}
