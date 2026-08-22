package com.bedro96.sshclient;

/**
 * Computes where the terminal's own cursor-block highlight should be drawn inside the
 * flattened text that {@code MainActivity.renderTermBuffer()} builds each time new output
 * arrives.
 *
 * <p>This exists because relying on Android's native {@code EditText} caret
 * ({@code setCursorVisible}/{@code setSelection}) to represent the terminal cursor is
 * unreliable: {@code renderTermBuffer()} calls {@code setText(...)} on every incoming output
 * chunk (which, in a busy session, can be many times a second), and Android's native
 * blinking-caret blink timer is sensitive to that churn — the caret can end up never
 * visibly "on" once the user has moved it. Since this app already renders bold/color spans
 * onto a {@code SpannableStringBuilder} it fully controls, the cursor is rendered the same
 * way: as an explicit highlight span that inverts foreground/background at the tracked
 * cursor cell, independent of Android's native caret/focus/blink behavior.
 *
 * <p>This class contains only plain-Java decision logic (no {@code android.*} types) so it
 * can be exercised directly on the host JVM in {@code run-tests.sh}, which has no
 * Robolectric/Android runtime available. {@code MainActivity} is the thin, harder-to-unit-test
 * layer that turns a {@link Plan} into actual spans on the real {@code SpannableStringBuilder}.
 */
final class TerminalCursorRenderer {

    private TerminalCursorRenderer() { }

    /**
     * A plan for drawing the cursor block against a specific flattened snapshot text.
     *
     * <p>{@code padInsertIndex} is the index at which a single synthetic space character must
     * be inserted into the rendered text before applying the highlight span -- needed
     * whenever the tracked cursor index does not land on a real character (at the very end of
     * the buffer, or immediately before a '\n' row separator when the active row's column is
     * past its trimmed visible content). It is {@code -1} when no padding is required. When
     * padding is required, {@code highlightStart}/{@code highlightEnd} refer to offsets in the
     * text *after* the single-character insertion at {@code padInsertIndex}.
     *
     * <p>{@code highlightStart == highlightEnd} (a zero-width range) signals that no cursor
     * highlight should be drawn at all -- i.e. {@code cursorVisible} was {@code false}, matching
     * the remote program's DECTCEM show/hide cursor state ({@code \e[?25l}/{@code \e[?25h}).
     */
    static final class Plan {
        final int padInsertIndex;
        final int highlightStart;
        final int highlightEnd;

        private Plan(int padInsertIndex, int highlightStart, int highlightEnd) {
            this.padInsertIndex = padInsertIndex;
            this.highlightStart = highlightStart;
            this.highlightEnd = highlightEnd;
        }
    }

    private static final Plan HIDDEN = new Plan(-1, 0, 0);

    static Plan plan(String text, int cursorIndex, boolean cursorVisible) {
        if (!cursorVisible) {
            return HIDDEN;
        }
        int length = text.length();
        int index = Math.max(0, Math.min(cursorIndex, length));
        boolean needsPad = index >= length || text.charAt(index) == '\n';
        if (needsPad) {
            return new Plan(index, index, index + 1);
        }
        return new Plan(-1, index, index + 1);
    }
}
