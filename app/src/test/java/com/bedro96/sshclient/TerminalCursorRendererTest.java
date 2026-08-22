package com.bedro96.sshclient;

/**
 * Tests for {@link TerminalCursorRenderer}, the pure-logic seam that decides where
 * (and whether) to draw the terminal's own cursor-block highlight in the rendered
 * {@code SpannableStringBuilder}. This class deliberately has no {@code android.*}
 * imports so it can be exercised on the host JVM without Robolectric/an Android jar
 * (see run-tests.sh) — the actual span application in
 * {@code MainActivity.renderTermBuffer()} is a thin, hard-to-unit-test wrapper
 * around this seam.
 */
public final class TerminalCursorRendererTest {

    private TerminalCursorRendererTest() { }

    public static void main(String[] args) {
        testHiddenCursorProducesNoHighlight();
        testVisibleCursorMidTextHighlightsSingleCharacterAtCursorIndex();
        testVisibleCursorAtEndOfBufferPadsWithSyntheticSpaceToHighlight();
        testVisibleCursorRightBeforeLineBreakPadsWithSyntheticSpaceToHighlight();
        testCursorIndexBeyondTextLengthIsClampedToEnd();
        testCursorIndexNegativeIsClampedToStart();
        System.out.println("TERMINAL CURSOR RENDERER TESTS PASSED");
    }

    private static void testHiddenCursorProducesNoHighlight() {
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("hello", 2, false);
        assertEquals(-1, plan.padInsertIndex, "no padding needed when cursor is hidden");
        assertEquals(plan.highlightStart, plan.highlightEnd,
                "hidden cursor must yield a zero-width (no-op) highlight range");
    }

    private static void testVisibleCursorMidTextHighlightsSingleCharacterAtCursorIndex() {
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("hello", 2, true);
        assertEquals(-1, plan.padInsertIndex, "existing character under the cursor needs no padding");
        assertEquals(2, plan.highlightStart, "highlight should start at the tracked cursor index");
        assertEquals(3, plan.highlightEnd, "highlight should cover exactly one character cell");
    }

    private static void testVisibleCursorAtEndOfBufferPadsWithSyntheticSpaceToHighlight() {
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("hello", 5, true);
        assertEquals(5, plan.padInsertIndex,
                "a synthetic space must be inserted so there is a cell to invert at buffer end");
        assertEquals(5, plan.highlightStart, "highlight should start where the space was inserted");
        assertEquals(6, plan.highlightEnd, "highlight should cover the single inserted space");
    }

    private static void testVisibleCursorRightBeforeLineBreakPadsWithSyntheticSpaceToHighlight() {
        // The active row's tracked column can sit past the row's trimmed visible content
        // (e.g. the user moved right past the last printed character on a line that isn't
        // the final line in the buffer) -- the next character in the flattened text is the
        // '\n' row separator, not a real cell, so it must be padded too.
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("ab\ncd", 2, true);
        assertEquals(2, plan.padInsertIndex,
                "a synthetic space must be inserted before the row-separator newline");
        assertEquals(2, plan.highlightStart, "highlight should start where the space was inserted");
        assertEquals(3, plan.highlightEnd, "highlight should cover the single inserted space");
    }

    private static void testCursorIndexBeyondTextLengthIsClampedToEnd() {
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("hello", 99, true);
        assertEquals(5, plan.padInsertIndex, "out-of-range cursor index must clamp to buffer end");
        assertEquals(5, plan.highlightStart, "clamped highlight start must match buffer end");
        assertEquals(6, plan.highlightEnd, "clamped highlight end covers the padded space");
    }

    private static void testCursorIndexNegativeIsClampedToStart() {
        TerminalCursorRenderer.Plan plan = TerminalCursorRenderer.plan("hello", -3, true);
        assertEquals(-1, plan.padInsertIndex, "negative index clamps to 0, which has a real character");
        assertEquals(0, plan.highlightStart, "negative cursor index must clamp to buffer start");
        assertEquals(1, plan.highlightEnd, "clamped highlight end covers one character");
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
