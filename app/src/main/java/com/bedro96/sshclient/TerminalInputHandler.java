package com.bedro96.sshclient;

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

    private TerminalInputHandler() { }

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
