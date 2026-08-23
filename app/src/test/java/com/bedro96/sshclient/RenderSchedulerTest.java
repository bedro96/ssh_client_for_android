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
        testStartSchedulesAContinuousHeartbeatWithoutAnyOutputNotifications();
        testHeartbeatKeepsRenderingOnEveryTickWithNoOutputAtAll();
        testHeartbeatTickIsAtTheConfiguredPeriodEveryTime();
        testStopEndsTheHeartbeatSoNoFurtherTicksAreScheduled();
        testStartIsIdempotentWhileAlreadyRunning();
        testStartAfterStopResumesTheHeartbeat();
        testOnOutputAvailableIsANoOpWhileTheHeartbeatIsRunning();
        testOnOutputAvailableResumesItsOwnSchedulingAfterTheHeartbeatStops();
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

    // --- issue #65: continuous >=10Hz repaint heartbeat, independent of onOutputAvailable() ---

    private static void testOnOutputAvailableIsANoOpWhileTheHeartbeatIsRunning() {
        // Once the heartbeat (issue #65) is running, it already guarantees a render within one
        // tick unconditionally, so onOutputAvailable() scheduling its own separate render on top
        // would just double the effective repaint rate for no benefit -- confirmed as a real
        // finding in rubber-duck review of this fix.
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        boolean scheduledFresh = scheduler.onOutputAvailable();

        assertTrue(!scheduledFresh, "onOutputAvailable() must be a no-op while the heartbeat is already running");
        assertEquals(1, poster.postCount,
                "only the heartbeat's own tick may be posted; onOutputAvailable() must not add a second one");
    }

    private static void testOnOutputAvailableResumesItsOwnSchedulingAfterTheHeartbeatStops() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        scheduler.stop();
        boolean scheduledFresh = scheduler.onOutputAvailable();

        assertTrue(scheduledFresh,
                "once the heartbeat is stopped (e.g. before any session has connected), onOutputAvailable() "
                        + "must resume its own on-demand coalescing so pre-connect local messages still render");
    }

    private static void testStartSchedulesAContinuousHeartbeatWithoutAnyOutputNotifications() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();

        assertEquals(1, poster.postCount, "start() must schedule the first heartbeat tick immediately");
        assertEquals(0, renderCount.count, "the render action must not run until the posted tick fires");
    }

    private static void testHeartbeatKeepsRenderingOnEveryTickWithNoOutputAtAll() {
        // This is the actual fix for issue #65: a multi-pane tmux session where one pane never
        // triggers onOutputAvailable() must still keep getting repainted, because the heartbeat
        // re-renders unconditionally on every tick, not only in response to notifications.
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        for (int tick = 1; tick <= 5; tick++) {
            poster.fireAll();
            assertEquals(tick, renderCount.count,
                    "tick " + tick + ": the render action must run exactly once per elapsed heartbeat tick, "
                            + "with zero onOutputAvailable() calls in between");
        }
        assertEquals(6, poster.postCount,
                "start() posts the first tick, then each of the 5 fired ticks must self-perpetuate "
                        + "exactly one more (1 initial + 5 self-scheduled = 6)");
    }

    private static void testHeartbeatTickIsAtTheConfiguredPeriodEveryTime() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        long period = 16L;
        RenderScheduler scheduler = new RenderScheduler(poster, period, renderCount);

        scheduler.start();
        poster.fireAll();
        poster.fireAll();
        poster.fireAll();

        for (long delay : poster.delaysMillis) {
            assertEquals(period, delay,
                    "issue #65 requires every self-perpetuating heartbeat tick to use the configured "
                            + "period (16ms in production, well under the 100ms/10Hz floor the issue requires)");
        }
    }

    private static void testStopEndsTheHeartbeatSoNoFurtherTicksAreScheduled() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        poster.fireAll();
        poster.fireAll();
        scheduler.stop();
        poster.fireAll(); // nothing pending; must be a no-op

        int postCountAtStop = poster.postCount;
        int renderCountAtStop = renderCount.count;
        poster.fireAll();
        poster.fireAll();

        assertEquals(postCountAtStop, poster.postCount,
                "stop() must prevent any further heartbeat ticks from being scheduled");
        assertEquals(renderCountAtStop, renderCount.count,
                "stop() must prevent any further render passes from running");
    }

    private static void testStartIsIdempotentWhileAlreadyRunning() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        scheduler.start();
        scheduler.start();

        assertEquals(1, poster.postCount,
                "calling start() while already running must not schedule extra, overlapping heartbeat loops");
    }

    private static void testStartAfterStopResumesTheHeartbeat() {
        FakePoster poster = new FakePoster();
        Counter renderCount = new Counter();
        RenderScheduler scheduler = new RenderScheduler(poster, 16L, renderCount);

        scheduler.start();
        poster.fireAll();
        scheduler.stop();
        scheduler.start();
        poster.fireAll();

        assertEquals(2, renderCount.count, "restarting after a stop() must resume rendering on each tick");
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
