package com.bedro96.sshclient;

/**
 * Pure geometry for mapping the terminal viewport (in pixels) to a character
 * grid. Columns and rows are floored to whole cells so the rightmost column and
 * the bottom row (e.g. a tmux status bar) are never partially clipped, and never
 * drop below one so the PTY size stays valid.
 */
final class TerminalGeometry {

    private TerminalGeometry() { }

    static int columns(int usableWidthPx, float cellWidthPx) {
        if (usableWidthPx <= 0 || cellWidthPx <= 0f) {
            return 1;
        }
        return Math.max(1, (int) (usableWidthPx / cellWidthPx));
    }

    static int rows(int usableHeightPx, int cellHeightPx) {
        if (usableHeightPx <= 0 || cellHeightPx <= 0) {
            return 1;
        }
        return Math.max(1, usableHeightPx / cellHeightPx);
    }
}
