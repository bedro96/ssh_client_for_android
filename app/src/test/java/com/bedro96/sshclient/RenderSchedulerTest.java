package com.bedro96.sshclient;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link RenderScheduler}, the pure-logic coalescing seam behind issue #64 ("Terminal:
 * coalesce screen refresh to a fixed, faster rate for smoother redraw"). {@code MainActivity}
 * extends {@code android.app.Activity} and cannot be loaded on the host JVM (see
 * ConfigChangeGeometryTest/SshResizeRaceTest), so -- exactly like {@link TerminalCursorRenderer}
 * -- the actual scheduling *decision* logic lives in this small, dependency-free class and is
 * exercised directly here with a fake {@link RenderScheduler.Poster} standing in for
 * {@code Handler.postDelayed}.
 */
public final class RenderSchedulerTest {

    private RenderSchedulerTest() { }

    public static void main(String[] args) {
        testFirstUpdateSchedulesARenderPass();
        testUpdatesWhileScheduledAreCoalescedIntoTheSameSchedule();
        testTickRunsRenderActionExactlyOnceRegardlessOfHowManyUpdatesArrivedBeforeIt();
        testAfterTickFiresANewUpdateSchedulesAFreshRenderPass();
        testRenderActionAlwaysSeesLatestStateSinceItReReadsRatherThanUsingASnapshot();
        testSchedulesAtTheConfiguredPeriod();
        testConstructorRejectsNullPosterOrRenderAction();
        testConstructorRejectsNegativePeriod();
        System.out.println("RENDER SCHEDULER TESTS PASSED");
    }

    private static void testFirstUpdateSchedulesARenderPass() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        boolean scheduledFresh = scheduler.onOutputAvailable();

        assertTrue(scheduledFresh, "the first call with nothing pending must schedule a fresh render pass");
        assertEquals(1, poster.postCount, "exactly one postDelayed call must have been made");
        assertTrue(scheduler.isRenderScheduled(), "scheduler must report a pending render after scheduling");
        assertEquals(0, renderCount.count, "the render action must not run until the posted tick fires");
    }

    private static void testUpdatesWhileScheduledAreCoalescedIntoTheSameSchedule() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        boolean first = scheduler.onOutputAvailable();
        boolean second = scheduler.onOutputAvailable();
        boolean third = scheduler.onOutputAvailable();

        assertTrue(first, "first call schedules");
        assertTrue(!second, "second call while pending must be coalesced (no new schedule)");
        assertTrue(!third, "third call while pending must also be coalesced");
        assertEquals(1, poster.postCount,
                "three rapid updates between ticks must still result in exactly one postDelayed call");
    }

    private static void testTickRunsRenderActionExactlyOnceRegardlessOfHowManyUpdatesArrivedBeforeIt() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.onOutputAvailable();
        scheduler.onOutputAvailable();
        scheduler.onOutputAvailable();
        poster.fireAll();

        assertEquals(1, renderCount.count,
                "multiple rapid updates coalesced into one schedule must produce exactly one render pass");
        assertTrue(!scheduler.isRenderScheduled(), "flag must clear once the tick has fired");
    }

    private static void testAfterTickFiresANewUpdateSchedulesAFreshRenderPass() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.onOutputAvailable();
        poster.fireAll();
        boolean scheduledAgain = scheduler.onOutputAvailable();
        poster.fireAll();

        assertTrue(scheduledAgain, "once the pending tick has fired, a new update must schedule again");
        assertEquals(2, poster.postCount, "two separate ticks means two separate postDelayed calls");
        assertEquals(2, renderCount.count, "each of the two ticks must have run the render action once");
    }

    private static void testRenderActionAlwaysSeesLatestStateSinceItReReadsRatherThanUsingASnapshot() {
        // Simulates TerminalScreen's authoritative buffer: renderAction reads the *current*
        // value of `latestContent` whenever it actually runs, not a value captured at the time
        // onOutputAvailable() happened to be called. This is what guarantees no content is
        // lost/stale even though most onOutputAvailable() calls during a burst are no-ops.
        FakePoster poster = new FakePoster();
        final StringBuilder observedAtRenderTime = new StringBuilder();
        final StringBuilder latestContent = new StringBuilder();
        Runnable renderAction = new Runnable() {
            @Override public void run() {
                observedAtRenderTime.setLength(0);
                observedAtRenderTime.append(latestContent.toString());
            }
        };
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderAction);

        scheduler.onOutputAvailable();
        latestContent.append("chunk-1");
        scheduler.onOutputAvailable(); // coalesced, but more content has since arrived
        latestContent.append("chunk-2");
        scheduler.onOutputAvailable(); // coalesced again, still more content

        poster.fireAll();

        assertEquals("chunk-1chunk-2", observedAtRenderTime.toString(),
                "the single coalesced render pass must observe ALL content that arrived before it fired, "
                        + "not just whatever existed at the moment the first onOutputAvailable() call scheduled it");
    }

    private static void testSchedulesAtTheConfiguredPeriod() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        long period = 16L;
        RenderScheduler scheduler = new RenderScheduler(poster, period, renderCount);

        scheduler.onOutputAvailable();

        assertEquals(1, poster.delaysMillis.size(), "expected exactly one recorded postDelayed call");
        assertEquals(period, poster.delaysMillis.get(0).longValue(),
                "issue #64 requires the coalesced render period to be the configured tick period "
                        + "(~16ms / ~60Hz in production -- exactly half the conservative ~33ms / ~30Hz baseline)");
    }

    private static void testConstructorRejectsNullPosterOrRenderAction() {
        boolean threwForNullPoster = false;
        try {
            new RenderScheduler(null, 16L, new Counter());
        } catch (NullPointerException expected) {
            threwForNullPoster = true;
        }
        assertTrue(threwForNullPoster, "constructor must reject a null Poster");

        boolean threwForNullRenderAction = false;
        try {
            new RenderScheduler(new FakePoster(), 16L, null);
        } catch (NullPointerException expected) {
            threwForNullRenderAction = true;
        }
        assertTrue(threwForNullRenderAction, "constructor must reject a null renderAction");
    }

    private static void testConstructorRejectsNegativePeriod() {
        boolean threw = false;
        try {
            new RenderScheduler(new FakePoster(), -1L, new Counter());
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "constructor must reject a negative period");
    }

    /** Fake {@link RenderScheduler.Poster} that records and can synchronously fire posted work. */
    private static final class FakePoster implements RenderScheduler.Poster {
        private final List<Runnable> pending = new ArrayList<>();
        private final List<Long> delaysMillis = new ArrayList<>();
        private int postCount;

        @Override public void postDelayed(Runnable runnable, long delayMillis) {
            postCount++;
            delaysMillis.add(delayMillis);
            pending.add(runnable);
        }

        void fireAll() {
            List<Runnable> toRun = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : toRun) {
                r.run();
            }
        }
    }

    /** Simple {@code Runnable} that counts how many times it ran, standing in for renderTermBuffer(). */
    private static final class Counter implements Runnable {
        private int count;

        @Override public void run() {
            count++;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
