package com.bedro96.sshclient;

final class TerminalInputHandler {

    interface Sender {
        void send(byte[] bytes);
    }

    private static final byte[] TAB = new byte[] {0x09};
    private static final byte[] BACK_TAB = new byte[] {0x1b, '[', 'Z'};

    private TerminalInputHandler() { }

    static boolean handleTab(boolean shiftPressed, Sender sender) {
        sender.send(shiftPressed ? BACK_TAB : TAB);
        return true;
    }
}
