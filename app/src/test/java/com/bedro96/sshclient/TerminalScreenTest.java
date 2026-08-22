package com.bedro96.sshclient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TerminalScreenTest {

    private static final String ESC = "\u001b";

    private TerminalScreenTest() { }

    public static void main(String[] args) {
        testAutowrapAndCoordinateClamping();
        testCursorAddressingAndSaveRestore();
        testEraseLineVariants();
        testEraseDisplayVariants();
        testAlternateScreenSwapRestoresPrimary();
        testAlternateScreenHidesPrimaryScrollback();
        testScrollRegionAndIndexBehavior();
        testRepeatedSpinnerRepaintStaysOnSingleLine();
        testTmuxStyleAlternateScreenRedrawRestoresShell();
        testCopilotCliStyleFullScreenRepaintStaysStable();
        testBasicShellScrollbackAndLineEditing();
        testLongCatScrollbackPreservesEarlyAndLateLines();
        testCursorVisibilityModes();
        testTruecolorStyleOverwrite();
        testCursorSitsPastTrailingSpaces();
        testGrowingRowsKeepsContentAndCursorAnchoredAtTop();
        testShrinkingRowsKeepsCursorOnItsLine();
        testInsertLineDefaultCountShiftsRowsDownAndDropsOverflow();
        testInsertLineExplicitCountDropsMultipleOverflowRows();
        testInsertLineRespectsCustomScrollRegion();
        testInsertLineAtRegionBottomClearsWithoutCopy();
        testInsertLineIsNoOpWhenCursorOutsideScrollRegion();
        testDeleteLineDefaultCountShiftsRowsUpAndClearsBottom();
        testDeleteLineExplicitCountClearsMultipleBottomRows();
        testDeleteLineRespectsCustomScrollRegion();
        testDeleteLineIsNoOpWhenCursorOutsideScrollRegion();
        testInsertCharacterShiftsRowRightAndDropsOverflow();
        testDeleteCharacterShiftsRowLeftAndBlanksTrailingCells();
        testEraseCharacterBlanksCellsWithoutShifting();
        testInsertAndDeleteCharacterPreserveShiftedCellAttributes();
        testConcurrentAppendAndResizeDoNotCorruptOrThrow();
        testPrimaryDeviceAttributesRepliesWithXtermCapabilities();
        testPrimaryDeviceAttributesBareFormRepliesTheSame();
        testSecondaryDeviceAttributesRepliesWithXtermVersion();
        testCursorPositionReportRepliesWithCurrentCursorPosition();
        testDeviceStatusReportRepliesOk();
        testUnknownDsrModeProducesNoReply();
        testBracketedPasteModeIsTrackedWithoutLeakingEscapeSequence();
        testMouseTrackingModesAreSwallowedWithoutLeaking();
        testCombinedDecPrivateModesAreAllApplied();
        testReplyWithoutSinkConfiguredDoesNotThrow();
        System.out.println("TERMINAL SCREEN TESTS PASSED");
    }

    private static void testAutowrapAndCoordinateClamping() {
        TerminalScreen screen = new TerminalScreen(2, 4);
        screen.append("ABCDX");
        assertEquals("ABCD\nX", screen.snapshot(200_000).text,
                "autowrap should move additional text onto the next row");

        screen.append(ESC + "[999;999HZ");
        assertEquals("ABCD\nX  Z", screen.snapshot(200_000).text,
                "out-of-range CUP coordinates should clamp to the bottom-right cell");
    }

    private static void testCursorAddressingAndSaveRestore() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        screen.append("abc\r\ndef");
        screen.append(ESC + "[1;2H" + "X");
        screen.append(ESC + "[s" + ESC + "[2;3H" + "Y" + ESC + "[u" + "Z");
        screen.append(ESC + "7" + ESC + "[2;1H" + "Q" + ESC + "8" + "W");
        assertEquals("aXZW\nQeY", screen.snapshot(200_000).text,
                "CUP/save/restore should overwrite in-place and restore the cursor");
    }

    private static void testEraseLineVariants() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append("abcdef" + ESC + "[4G" + ESC + "[1K");
        assertEquals("    ef", screen.snapshot(200_000).text,
                "ESC[1K should blank from line start to cursor");

        screen.append(ESC + "[2G" + ESC + "[0K");
        assertEquals("", screen.snapshot(200_000).text,
                "ESC[0K should blank from cursor to line end");

        screen.append("xyz" + ESC + "[2K");
        assertEquals("", screen.snapshot(200_000).text,
                "ESC[2K should blank the entire current line");
    }

    private static void testEraseDisplayVariants() {
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append("line one\r\nline two\r\nline three");
        screen.append(ESC + "[2;6H" + ESC + "[0J");
        assertEquals("line one\nline ", screen.snapshot(200_000).text,
                "ESC[0J should clear from the cursor to the display end");

        screen.append("restored");
        screen.append(ESC + "[2J");
        assertEquals("\n", screen.snapshot(200_000).text,
                "ESC[2J should clear the visible display while preserving cursor row");

        screen.append("one\r\ntwo\r\nthree\r\nfour\r\nfive");
        screen.append(ESC + "[3J");
        assertEquals("two\nthree\nfour\nfive", screen.snapshot(200_000).text,
                "ESC[3J should clear scrollback without disturbing visible cells");
    }

    private static void testAlternateScreenSwapRestoresPrimary() {
        TerminalScreen screen = new TerminalScreen(4, 16);
        screen.append("shell output\r\nprompt$ ");
        screen.append(ESC + "[?1049h" + "copilot-ui");
        assertEquals("copilot-ui", screen.snapshot(200_000).text,
                "alternate screen should start with a clean buffer");
        screen.append(ESC + "[?1049l");
        assertEquals("shell output\nprompt$ ", screen.snapshot(200_000).text,
                "leaving alternate screen should restore the primary buffer");
    }

    private static void testAlternateScreenHidesPrimaryScrollback() {
        // A short primary screen whose output overflows into scrollback, exactly
        // like a login shell printing a multi-line MOTD before a full-screen TUI
        // (tmux / GitHub Copilot CLI) switches to the alternate screen.
        TerminalScreen screen = new TerminalScreen(3, 8);
        screen.append("L1\r\nL2\r\nL3\r\nL4\r\nL5");
        String primaryBefore = screen.snapshot(200_000).text;
        assertContains(primaryBefore, "L1", "primary output should spill into scrollback");
        assertContains(primaryBefore, "L5", "primary output should keep the latest line");

        screen.append(ESC + "[?1049h" + ESC + "[2J" + ESC + "[H" + "ALT");
        String alt = screen.snapshot(200_000).text;
        assertEquals("ALT", alt,
                "alternate screen must show only its own buffer, not the primary scrollback");

        screen.append(ESC + "[?1049l");
        String primaryAfter = screen.snapshot(200_000).text;
        assertContains(primaryAfter, "L1",
                "leaving the alternate screen should restore the primary scrollback");
        assertContains(primaryAfter, "L5",
                "leaving the alternate screen should restore the primary screen content");
    }

    private static void testScrollRegionAndIndexBehavior() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;3r" + ESC + "[3;1H" + ESC + "D" + "X");
        assertEquals("1\n3\nX\n4", screen.snapshot(200_000).text,
                "index at the bottom of a scroll region should scroll only that region");
        screen.append(ESC + "[2;1H" + ESC + "M" + "Y");
        assertEquals("1\nY\n3\n4", screen.snapshot(200_000).text,
                "reverse index at the top of a region should scroll down within that region");
    }

    private static void testRepeatedSpinnerRepaintStaysOnSingleLine() {
        TerminalScreen screen = new TerminalScreen(4, 32);
        screen.append("◎ Loading: 1 skills…");
        screen.append("\r" + ESC + "[2K◉ Loading: 84 skills…");
        screen.append("\r" + ESC + "[2K○ Loading: 84 skills…");
        assertEquals("○ Loading: 84 skills…", screen.snapshot(200_000).text,
                "spinner repaint should update a single visible line in place");
    }

    private static void testTmuxStyleAlternateScreenRedrawRestoresShell() {
        TerminalScreen screen = new TerminalScreen(6, 24);
        screen.append("shell output\r\nprompt$ ");
        screen.append(ESC + "[?1049h" + ESC + "[2J" + ESC + "[H");
        screen.append("pane1" + ESC + "[1;13Hpane2");
        screen.append(ESC + "[2;1Hleft$ ls" + ESC + "[2;13Hright$ pwd");
        screen.append(ESC + "[3;1Hfile-a" + ESC + "[3;13H/home/u");
        screen.append(ESC + "[6;1H[tmux 1]");
        screen.append(ESC + "[3;1H" + ESC + "[2Kfile-a file-b");
        screen.append(ESC + "[6;1H" + ESC + "[2K[tmux 2]");

        String tmux = screen.snapshot(200_000).text;
        assertContains(tmux, "pane1", "tmux redraw should preserve the left pane");
        assertContains(tmux, "pane2", "tmux redraw should preserve the right pane");
        assertContains(tmux, "left$ ls", "tmux redraw should preserve pane command text");
        assertContains(tmux, "right$ pwd", "tmux redraw should preserve split-pane content");
        assertContains(tmux, "file-a file-b", "tmux redraw should repaint pane content in place");
        assertContains(tmux, "[tmux 2]", "tmux redraw should update the status bar in place");
        assertNotContains(tmux, "[tmux 1]", "tmux redraw should not leave stale status bars behind");

        screen.append(ESC + "[?1049l");
        assertEquals("shell output\nprompt$ ", screen.snapshot(200_000).text,
                "quitting tmux should restore the primary shell screen");
    }

    private static void testCopilotCliStyleFullScreenRepaintStaysStable() {
        TerminalScreen screen = new TerminalScreen(6, 32);
        screen.append(ESC + "[?1049h" + ESC + "[?25l");
        screen.append(ESC + "[2J" + ESC + "[HGitHub Copilot CLI");
        screen.append(ESC + "[2;1HLoading: 1 skills…" + ESC + "[3;1H◎ Synthesizing...");
        screen.append(ESC + "[2;1H" + ESC + "[2KLoading: 84 skills…");
        screen.append(ESC + "[3;1H" + ESC + "[2K○ Ready");
        screen.append(ESC + "[4;1H> explain this diff");

        TerminalScreen.Snapshot snapshot = screen.snapshot(200_000);
        assertFalse(snapshot.cursorVisible, "Copilot CLI full-screen mode should hide the cursor");
        assertContains(snapshot.text, "GitHub Copilot CLI",
                "Copilot CLI title should remain visible after redraws");
        assertContains(snapshot.text, "Loading: 84 skills…",
                "Copilot CLI should show the latest loading state");
        assertContains(snapshot.text, "○ Ready",
                "Copilot CLI should repaint status lines in place");
        assertContains(snapshot.text, "> explain this diff",
                "Copilot CLI prompt should remain on the current screen");
        assertNotContains(snapshot.text, "Loading: 1 skills…",
                "Copilot CLI redraw should not leave stale loading lines behind");
        assertNotContains(snapshot.text, "◎ Synthesizing...",
                "Copilot CLI redraw should replace older transient status text");
    }

    private static void testBasicShellScrollbackAndLineEditing() {
        TerminalScreen screen = new TerminalScreen(4, 40);
        StringBuilder lsOutput = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            lsOutput.append("-rw-r--r-- 1 user group ")
                    .append(i)
                    .append(" Jul 02 00:0")
                    .append(i)
                    .append(" file")
                    .append(i)
                    .append("\r\n");
        }
        screen.append(lsOutput);
        String rendered = screen.snapshot(200_000).text;
        assertContains(rendered, "file1", "scrollback should keep the earliest shell lines");
        assertContains(rendered, "file10", "scrollback should keep the latest shell lines");

        screen.append("prompt$ hell");
        screen.append("\b \b");
        screen.append("o");
        assertContains(screen.snapshot(200_000).text, "prompt$ helo",
                "backspace-space-backspace editing should stay aligned");
    }

    private static void testLongCatScrollbackPreservesEarlyAndLateLines() {
        TerminalScreen screen = new TerminalScreen(4, 32);
        StringBuilder catOutput = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            catOutput.append("line ").append(i).append(" from long cat\r\n");
        }
        screen.append(catOutput);
        String rendered = screen.snapshot(200_000).text;
        assertContains(rendered, "line 1 from long cat",
                "long cat output should preserve the earliest lines in scrollback");
        assertContains(rendered, "line 20 from long cat",
                "long cat output should preserve the latest lines in scrollback");
    }

    private static void testCursorVisibilityModes() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append(ESC + "[?25l");
        assertFalse(screen.snapshot(200_000).cursorVisible, "ESC[?25l should hide the cursor");
        screen.append(ESC + "[?25h");
        assertTrue(screen.snapshot(200_000).cursorVisible, "ESC[?25h should show the cursor");
    }

    private static void testTruecolorStyleOverwrite() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append("A", false, 0x112233, 0x445566);
        screen.append("\rB", true, 0xaabbcc, 0x010203);
        TerminalScreen.Snapshot snapshot = screen.snapshot(200_000);
        assertEquals("B", snapshot.text, "overwriting a cell should update the visible glyph");
        List<TerminalScreen.StyleRun> runs = snapshot.runs;
        assertEquals(1, runs.size(), "single styled cell should produce one run");
        TerminalScreen.StyleRun run = runs.get(0);
        assertTrue(run.bold, "overwritten cell should retain bold style");
        assertEquals(Integer.valueOf(0xaabbcc), run.foregroundRgb,
                "overwritten cell should retain truecolor foreground");
        assertEquals(Integer.valueOf(0x010203), run.backgroundRgb,
                "overwritten cell should retain truecolor background");
    }

    private static void testCursorSitsPastTrailingSpaces() {
        TerminalScreen screen = new TerminalScreen(2, 16);
        screen.append("abc     ");
        TerminalScreen.Snapshot snap = screen.snapshot(200_000);
        assertEquals("abc     ", snap.text,
                "cursor row should keep spaces up to the cursor column");
        assertEquals(8, snap.cursorIndex,
                "cursor should sit past trailing spaces at its true column");
    }

    // Growing the terminal (e.g. rotating the device, or the keyboard closing and
    // freeing up rows) must never shift already-painted rows or leave the cursor
    // pointing at stale content: that is what produces "ghost" text where a
    // redraw appears to land on the wrong line while an old line lingers on
    // screen, untouched, below it.
    private static void testGrowingRowsKeepsContentAndCursorAnchoredAtTop() {
        TerminalScreen screen = new TerminalScreen(3, 10);
        screen.append("line0\nline1\nline2");
        screen.resize(5, 10);
        screen.append("\nNEWLINE");
        assertEquals("line0\nline1\nline2\nNEWLINE", screen.snapshot(200_000).text,
                "growing rows should keep existing lines anchored at the top and"
                        + " continue writing after the last line, not overwrite it");
    }

    // Shrinking must shift the cursor by the same number of rows that get
    // dropped off the top, so it keeps tracking the (now higher-indexed) line it
    // was on and subsequent output continues in the right place instead of
    // clobbering a line that moved.
    private static void testShrinkingRowsKeepsCursorOnItsLine() {
        TerminalScreen screen = new TerminalScreen(5, 10);
        screen.append("line0\nline1\nline2\nline3\nline4");
        screen.resize(3, 10);
        screen.append("\nNEWLINE");
        assertEquals("line2\nline3\nline4\nNEWLINE", screen.snapshot(200_000).text,
                "shrinking rows should keep the cursor tracking its line and"
                        + " scroll older content off rather than overwriting a stale row");
    }

    // CSI L (Insert Line): a blank line is inserted at the cursor row, the rows
    // below it shift down within the scroll region, and whatever falls off the
    // bottom of the region is dropped.
    private static void testInsertLineDefaultCountShiftsRowsDownAndDropsOverflow() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;1H" + ESC + "[L");
        assertEquals("1\n\n2\n3", screen.snapshot(200_000).text,
                "ESC[L should insert one blank line at the cursor row, shifting"
                        + " later rows down and dropping the last row off the bottom");
    }

    private static void testInsertLineExplicitCountDropsMultipleOverflowRows() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;1H" + ESC + "[2L");
        assertEquals("1\n\n\n2", screen.snapshot(200_000).text,
                "ESC[2L should insert two blank lines at the cursor row, shifting"
                        + " remaining content down and dropping rows that fall off the bottom");
    }

    private static void testInsertLineRespectsCustomScrollRegion() {
        TerminalScreen screen = new TerminalScreen(6, 4);
        screen.append("A\r\nB\r\nC\r\nD\r\nE\r\nF");
        // Scroll region rows 2-4 (1-based) => scrollTop=1, scrollBottom=3 (0-based).
        screen.append(ESC + "[2;4r" + ESC + "[3;1H" + ESC + "[L");
        assertEquals("A\nB\n\nC\nE\nF", screen.snapshot(200_000).text,
                "ESC[L inside a custom scroll region should only shift rows within"
                        + " [scrollTop, scrollBottom], leaving rows outside the region untouched");
    }

    private static void testInsertLineAtRegionBottomClearsWithoutCopy() {
        TerminalScreen screen = new TerminalScreen(6, 4);
        screen.append("A\r\nB\r\nC\r\nD\r\nE\r\nF");
        // Scroll region rows 2-4 (1-based) => scrollTop=1, scrollBottom=3 (0-based).
        // Inserting at the very bottom boundary row of the region should simply
        // blank that row, since there is nothing left inside the region to
        // shift down.
        screen.append(ESC + "[2;4r" + ESC + "[4;1H" + ESC + "[L");
        assertEquals("A\nB\nC\n\nE\nF", screen.snapshot(200_000).text,
                "ESC[L at the bottom boundary row of the scroll region should blank"
                        + " that row without disturbing rows above or below the region");
    }

    private static void testInsertLineIsNoOpWhenCursorOutsideScrollRegion() {
        TerminalScreen screen = new TerminalScreen(6, 4);
        screen.append("A\r\nB\r\nC\r\nD\r\nE\r\nF");
        // Scroll region rows 2-4 (1-based) => scrollTop=1, scrollBottom=3 (0-based).
        screen.append(ESC + "[2;4r" + ESC + "[1;1H" + ESC + "[L");
        assertEquals("A\nB\nC\nD\nE\nF", screen.snapshot(200_000).text,
                "ESC[L should be a no-op when the cursor sits outside the active"
                        + " scroll region");
    }

    // CSI M (Delete Line): the cursor row is removed, rows below it shift up
    // within the scroll region, and blank rows are pulled in at the bottom.
    private static void testDeleteLineDefaultCountShiftsRowsUpAndClearsBottom() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;1H" + ESC + "[M");
        assertEquals("1\n3\n4", screen.snapshot(200_000).text,
                "ESC[M should delete the cursor row, shifting later rows up and"
                        + " clearing a blank row at the bottom");
    }

    private static void testDeleteLineExplicitCountClearsMultipleBottomRows() {
        TerminalScreen screen = new TerminalScreen(4, 4);
        screen.append("1\r\n2\r\n3\r\n4");
        screen.append(ESC + "[2;1H" + ESC + "[2M");
        assertEquals("1\n4", screen.snapshot(200_000).text,
                "ESC[2M should delete two rows starting at the cursor row, pulling"
                        + " remaining content up and clearing two blank rows at the bottom");
    }

    private static void testDeleteLineRespectsCustomScrollRegion() {
        TerminalScreen screen = new TerminalScreen(6, 4);
        screen.append("A\r\nB\r\nC\r\nD\r\nE\r\nF");
        // Scroll region rows 2-4 (1-based) => scrollTop=1, scrollBottom=3 (0-based).
        screen.append(ESC + "[2;4r" + ESC + "[3;1H" + ESC + "[M");
        assertEquals("A\nB\nD\n\nE\nF", screen.snapshot(200_000).text,
                "ESC[M inside a custom scroll region should only shift rows within"
                        + " [scrollTop, scrollBottom], leaving rows outside the region untouched");
    }

    private static void testDeleteLineIsNoOpWhenCursorOutsideScrollRegion() {
        TerminalScreen screen = new TerminalScreen(6, 4);
        screen.append("A\r\nB\r\nC\r\nD\r\nE\r\nF");
        // Scroll region rows 2-4 (1-based) => scrollTop=1, scrollBottom=3 (0-based).
        screen.append(ESC + "[2;4r" + ESC + "[6;1H" + ESC + "[M");
        assertEquals("A\nB\nC\nD\nE\nF", screen.snapshot(200_000).text,
                "ESC[M should be a no-op when the cursor sits outside the active"
                        + " scroll region");
    }

    // CSI @ (ICH): blank characters are inserted at the cursor column, shifting
    // the remainder of the row right and dropping whatever falls off the edge.
    private static void testInsertCharacterShiftsRowRightAndDropsOverflow() {
        TerminalScreen screen = new TerminalScreen(2, 4);
        screen.append("ABCD");
        screen.append(ESC + "[1;2H" + ESC + "[@");
        assertEquals("A BC", screen.snapshot(200_000).text,
                "ESC[@ should insert one blank cell at the cursor column, shifting"
                        + " the rest of the row right and dropping the last cell off the edge");
    }

    // CSI P (DCH): characters at the cursor column are removed, the remainder
    // of the row shifts left, and blank cells are pulled in at the row end.
    private static void testDeleteCharacterShiftsRowLeftAndBlanksTrailingCells() {
        TerminalScreen screen = new TerminalScreen(2, 4);
        screen.append("ABCD");
        screen.append(ESC + "[1;2H" + ESC + "[P");
        assertEquals("ACD", screen.snapshot(200_000).text,
                "ESC[P should delete the cell at the cursor column, shifting the"
                        + " rest of the row left and clearing a blank cell at the row end");
    }

    // CSI X (ECH): characters starting at the cursor column are blanked in
    // place, without shifting any other cells.
    private static void testEraseCharacterBlanksCellsWithoutShifting() {
        TerminalScreen screen = new TerminalScreen(2, 4);
        screen.append("ABCD");
        screen.append(ESC + "[1;2H" + ESC + "[2X");
        assertEquals("A  D", screen.snapshot(200_000).text,
                "ESC[2X should blank two cells starting at the cursor column"
                        + " without shifting the surrounding cells");
    }

    private static void testInsertAndDeleteCharacterPreserveShiftedCellAttributes() {
        TerminalScreen screen = new TerminalScreen(1, 4);
        screen.append("A", false, null, null);
        screen.append("B", true, 0xaabbcc, 0x010203);
        screen.append(ESC + "[1;2H" + ESC + "[@");
        TerminalScreen.Snapshot afterInsert = screen.snapshot(200_000);
        assertEquals("A B", afterInsert.text,
                "ESC[@ should insert a blank cell ahead of the existing styled cell,"
                        + " shifting it one column to the right");
        List<TerminalScreen.StyleRun> insertRuns = afterInsert.runs;
        TerminalScreen.StyleRun shiftedRun = insertRuns.get(insertRuns.size() - 1);
        assertTrue(shiftedRun.bold, "the shifted cell should keep its bold attribute after ESC[@");
        assertEquals(Integer.valueOf(0xaabbcc), shiftedRun.foregroundRgb,
                "the shifted cell should keep its truecolor foreground after ESC[@");

        screen.append(ESC + "[1;2H" + ESC + "[P");
        TerminalScreen.Snapshot afterDelete = screen.snapshot(200_000);
        assertEquals("AB", afterDelete.text,
                "ESC[P should delete the blank cell, restoring the styled cell next to"
                        + " the unstyled one");
        List<TerminalScreen.StyleRun> deleteRuns = afterDelete.runs;
        TerminalScreen.StyleRun restoredRun = deleteRuns.get(deleteRuns.size() - 1);
        assertTrue(restoredRun.bold, "the cell should keep its bold attribute after ESC[P shifts it left");
        assertEquals(Integer.valueOf(0x010203), restoredRun.backgroundRgb,
                "the cell should keep its truecolor background after ESC[P shifts it left");
    }

    /**
     * Regression test for issue #54: the SSH reader thread calls append() for every
     * incoming chunk while the UI thread calls resize() on rotation/keyboard toggle.
     * Neither path took a lock, so a resize interleaved with an in-flight append could
     * observe/mutate `active`, `row`, `col`, `scrollTop`/`scrollBottom` inconsistently,
     * throwing (e.g. ArrayIndexOutOfBoundsException from a stale row/col pointing past
     * the just-resized backing arrays) or leaving the model in a corrupted state. This
     * hammers append/resize/snapshot/reset concurrently from multiple threads and
     * requires that no thread ever observes an exception and that snapshot() always
     * returns an internally consistent result (cursorIndex within the rendered text).
     */
    private static void testConcurrentAppendAndResizeDoNotCorruptOrThrow() {
        final TerminalScreen screen = new TerminalScreen(24, 80);
        final int iterations = 10_000;
        final long joinTimeoutMillis = 20_000;
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(3);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);

        Runnable appender = () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < iterations; i++) {
                    // Lots of line feeds force frequent scrollUp()/copyRows() calls,
                    // which read scrollTop/scrollBottom/active without bounds checks -
                    // exactly the state resize() mutates non-atomically.
                    screen.append("line " + i + "\r\nmore tmux-style redraw text\r\n"
                            + ESC + "[2K" + ESC + "[1;1H\r\n\r\n\r\n");
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };

        Runnable resizer = () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < iterations; i++) {
                    // Alternate wildly between tiny and large dimensions to maximize
                    // the window where `active`/scrollTop/scrollBottom disagree with
                    // row/col while another thread is mid-append.
                    int rows = (i % 2 == 0) ? 1 : 60;
                    int cols = (i % 2 == 0) ? 1 : 200;
                    screen.resize(rows, cols);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };

        Runnable snapshotter = () -> {
            ready.countDown();
            await(start);
            try {
                for (int i = 0; i < iterations; i++) {
                    TerminalScreen.Snapshot snapshot = screen.snapshot(4_000);
                    if (snapshot.cursorIndex < 0 || snapshot.cursorIndex > snapshot.text.length()) {
                        throw new AssertionError("snapshot cursorIndex " + snapshot.cursorIndex
                                + " out of bounds for text length " + snapshot.text.length());
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };

        Thread appenderThread = new Thread(appender, "append-thread");
        Thread resizerThread = new Thread(resizer, "resize-thread");
        Thread snapshotThread = new Thread(snapshotter, "snapshot-thread");
        // Daemon so a hang/livelock caused by unsynchronized state (e.g. an infinite
        // scroll loop chasing corrupted scrollTop/scrollBottom) can never keep the
        // whole test process alive; we detect that condition explicitly below via a
        // bounded join instead of relying on the threads to terminate on their own.
        appenderThread.setDaemon(true);
        resizerThread.setDaemon(true);
        snapshotThread.setDaemon(true);
        appenderThread.start();
        resizerThread.start();
        snapshotThread.start();

        await(ready);
        start.countDown();

        joinWithTimeout(appenderThread, joinTimeoutMillis);
        joinWithTimeout(resizerThread, joinTimeoutMillis);
        joinWithTimeout(snapshotThread, joinTimeoutMillis);

        if (appenderThread.isAlive() || resizerThread.isAlive() || snapshotThread.isAlive()) {
            throw new AssertionError("FAILED concurrent append/resize/snapshot did not finish within "
                    + joinTimeoutMillis + "ms; this indicates a livelock/hang caused by the terminal"
                    + " model observing inconsistent state across threads");
        }

        Throwable observed = failure.get();
        if (observed != null) {
            throw new AssertionError("FAILED concurrent append/resize/snapshot corrupted the "
                    + "terminal screen or threw: " + observed, observed);
        }
    }

    // Primary Device Attributes (CSI c / CSI 0c): xterm-256color replies with a
    // VT500-class capability report so probing programs (tmux, vim) don't stall
    // waiting for a response that never arrives.
    private static void testPrimaryDeviceAttributesRepliesWithXtermCapabilities() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[c");
        assertEquals(1, sink.replies.size(), "CSI c should produce exactly one reply");
        assertEquals(ESC + "[?64;1;2;6;9;15;18;21;22c", sink.repliesAsStrings().get(0),
                "CSI c should reply with an xterm-256color-style primary DA response");
    }

    private static void testPrimaryDeviceAttributesBareFormRepliesTheSame() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[0c");
        assertEquals(1, sink.replies.size(), "CSI 0c should produce exactly one reply");
        assertEquals(ESC + "[?64;1;2;6;9;15;18;21;22c", sink.repliesAsStrings().get(0),
                "CSI 0c should reply identically to bare CSI c");
    }

    // Secondary Device Attributes (CSI > c): reports terminal type/firmware/keyboard.
    private static void testSecondaryDeviceAttributesRepliesWithXtermVersion() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[>c");
        assertEquals(1, sink.replies.size(), "CSI > c should produce exactly one reply");
        assertEquals(ESC + "[>41;300;0c", sink.repliesAsStrings().get(0),
                "CSI > c should reply with a plausible xterm secondary DA response");
    }

    // Device Status Report / Cursor Position Report (CSI 6n): reply with the
    // current 1-based cursor row/column so tools like vim can locate the cursor.
    private static void testCursorPositionReportRepliesWithCurrentCursorPosition() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[3;5H");
        screen.append(ESC + "[6n");
        assertEquals(1, sink.replies.size(), "CSI 6n should produce exactly one reply");
        assertEquals(ESC + "[3;5R", sink.repliesAsStrings().get(0),
                "CSI 6n should reply with the current 1-based cursor position");
    }

    // Device Status Report (CSI 5n): reply "terminal OK".
    private static void testDeviceStatusReportRepliesOk() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[5n");
        assertEquals(1, sink.replies.size(), "CSI 5n should produce exactly one reply");
        assertEquals(ESC + "[0n", sink.repliesAsStrings().get(0),
                "CSI 5n should reply with the terminal-OK status");
    }

    private static void testUnknownDsrModeProducesNoReply() {
        TerminalScreen screen = new TerminalScreen(4, 8);
        RecordingReplySink sink = new RecordingReplySink();
        screen.setReplySink(sink);
        screen.append(ESC + "[99n");
        assertEquals(0, sink.replies.size(), "unrecognized DSR modes should not produce a reply");
    }

    // Bracketed paste mode (CSI ?2004h/l): must not leak into the visible grid,
    // and the flag should be tracked so future paste-wrapping logic has
    // somewhere to read it from.
    private static void testBracketedPasteModeIsTrackedWithoutLeakingEscapeSequence() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        assertFalse(screen.isBracketedPasteMode(), "bracketed paste should start disabled");
        screen.append(ESC + "[?2004h");
        assertTrue(screen.isBracketedPasteMode(), "CSI ?2004h should enable bracketed paste tracking");
        assertEquals("", screen.snapshot(200_000).text,
                "CSI ?2004h should be fully consumed, not leaked as visible text");
        screen.append(ESC + "[?2004l");
        assertFalse(screen.isBracketedPasteMode(), "CSI ?2004l should disable bracketed paste tracking");
        assertEquals("", screen.snapshot(200_000).text,
                "CSI ?2004l should be fully consumed, not leaked as visible text");
    }

    // Mouse tracking/reporting modes (CSI ?1000h etc.): these must be silently
    // consumed so remote programs enabling mouse reporting don't cause garbage
    // to render in the terminal grid.
    private static void testMouseTrackingModesAreSwallowedWithoutLeaking() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append(ESC + "[?1000h" + ESC + "[?1002h" + ESC + "[?1006h" + "hi" + ESC + "[?1000l");
        assertEquals("hi", screen.snapshot(200_000).text,
                "mouse tracking mode escapes should be consumed without leaking into the visible text");
    }

    // xterm commonly batches several DEC private modes into a single sequence,
    // e.g. "ESC[?1000;1002;1006h" to enable mouse tracking. Each mode in the
    // batch must be applied/tracked, not just the first.
    private static void testCombinedDecPrivateModesAreAllApplied() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append(ESC + "[?1000;2004;1006h" + "hi");
        assertTrue(screen.isBracketedPasteMode(),
                "bracketed paste mode within a combined mode-set sequence should still be tracked");
        assertEquals("hi", screen.snapshot(200_000).text,
                "combined DEC private mode escapes should be consumed without leaking into the visible text");
        screen.append(ESC + "[?1000;2004;1006l");
        assertFalse(screen.isBracketedPasteMode(),
                "bracketed paste mode within a combined mode-reset sequence should still be tracked");
    }

    // With no reply sink configured (the default for a freshly-constructed
    // TerminalScreen, e.g. in other tests in this file), DA/DSR queries must
    // not throw.
    private static void testReplyWithoutSinkConfiguredDoesNotThrow() {
        TerminalScreen screen = new TerminalScreen(2, 8);
        screen.append(ESC + "[c" + ESC + "[6n" + ESC + "[5n");
    }

    private static final class RecordingReplySink implements TerminalScreen.ReplySink {
        private final List<byte[]> replies = new ArrayList<>();

        @Override
        public void send(byte[] bytes) {
            replies.add(bytes);
        }

        private List<String> repliesAsStrings() {
            List<String> result = new ArrayList<>();
            for (byte[] reply : replies) {
                result.add(new String(reply, StandardCharsets.US_ASCII));
            }
            return result;
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static void joinWithTimeout(Thread t, long timeoutMillis) {
        try {
            t.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining " + t.getName(), e);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED " + message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expectedPart, String message) {
        if (actual == null || !actual.contains(expectedPart)) {
            throw new AssertionError("FAILED " + message + ": expected to find <" + expectedPart + "> in <" + actual + ">");
        }
    }

    private static void assertNotContains(String actual, String unexpectedPart, String message) {
        if (actual != null && actual.contains(unexpectedPart)) {
            throw new AssertionError("FAILED " + message + ": did not expect to find <" + unexpectedPart + "> in <" + actual + ">");
        }
    }

    private static void assertFalse(boolean actual, String message) {
        if (actual) {
            throw new AssertionError("FAILED " + message);
        }
    }

    private static void assertTrue(boolean actual, String message) {
        if (!actual) {
            throw new AssertionError("FAILED " + message);
        }
    }
}
