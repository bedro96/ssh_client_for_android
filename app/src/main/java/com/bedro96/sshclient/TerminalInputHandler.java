package com.bedro96.sshclient;

import java.nio.charset.Charset;

final class TerminalInputHandler {

    interface Sender {
        void send(byte[] bytes);
    }

    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;

    static final class KeyState {
        private boolean tabDownHandled;
    }

    private static final byte[] TAB = new byte[] {0x09};
    private static final byte[] BACK_TAB = new byte[] {0x1b, '[', 'Z'};
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
}
