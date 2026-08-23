package com.bedro96.sshclient;

/**
 * Coalesces bursts of "new output arrived" notifications onto a single fixed-rate render
 * pass per tick (issue #64), and separately offers a continuous, unconditional repaint
 * heartbeat (issue #65) as a robustness backstop against any missed/delayed notification.
 *
 * <p>Before this existed, {@code SshConnectionService}'s reader thread posted
 * {@code notifyScreenUpdated()} to the UI thread for literally every incoming SSH read chunk
 * (up to 1024 bytes each), and {@code MainActivity.onScreenUpdated()} answered every single one
 * of those with an immediate, synchronous {@code renderTermBuffer()} -- a full
 * {@code SpannableStringBuilder} rebuild plus {@code setText()}. In a chatty session (tmux
 * status-line refreshes, a build log scrolling by, etc) that can fire many times per second,
 * which is wasteful and can look jittery rather than smooth.
 *
 * <p>The fix: track whether a render pass is already scheduled. While one is pending, further
 * "output arrived" notifications are no-ops -- they don't need to do anything, because
 * {@code TerminalScreen} already accumulates every incoming byte into its authoritative buffer
 * regardless of when the UI repaints, and the render action re-reads that live buffer fresh
 * every time it actually runs (it is not handed a stale snapshot). Once the pending render
 * fires, the "scheduled" flag is cleared so the next notification schedules a fresh one.
 *
 * <p>The render period is fixed at construction (see {@code MainActivity}'s
 * {@code RENDER_PERIOD_MILLIS}, ~16ms / ~60Hz -- exactly half the period of a conservative
 * ~33ms / ~30Hz baseline), so screen updates repaint at a steady, predictable cadence no matter
 * how bursty the incoming SSH traffic is.
 *
 * <p>This class deliberately has no {@code android.*} imports (the actual posting mechanism is
 * abstracted behind {@link Poster}, backed by {@code Handler.postDelayed} in production) so it
 * can be exercised directly on the host JVM in {@code run-tests.sh}, consistent with
 * {@link TerminalCursorRenderer}. {@code MainActivity} (which cannot be loaded outside an
 * Android runtime) is the thin, harder-to-unit-test layer that wires a real {@code Handler} and
 * the real {@code renderTermBuffer()} call into this scheduler.
 */
final class RenderScheduler {

    /** Abstraction over {@code Handler.postDelayed(Runnable, long)}, for host-JVM testability. */
    interface Poster {
        void postDelayed(Runnable runnable, long delayMillis);
    }

    private final Poster poster;
    private final long periodMillis;
    private final Runnable renderAction;

    /** Guards {@link #scheduled}; also serializes scheduling decisions across callers. */
    private final Object lock = new Object();
    private boolean scheduled;

    /** Guards {@link #heartbeatRunning}/{@link #heartbeatGeneration}; separate from {@link #lock}
     * since the heartbeat and the on-demand coalescing scheme are independent mechanisms that
     * may be used together. */
    private final Object heartbeatLock = new Object();
    private boolean heartbeatRunning;
    /** Incremented on every {@link #start()}. Lets an in-flight tick posted by a previous
     * generation (e.g. one already posted right before a stop()+start() pair) detect that it is
     * stale and do nothing, instead of "reviving" and running alongside the new generation's own
     * tick chain -- which would otherwise silently double the effective repaint rate. */
    private long heartbeatGeneration;

    RenderScheduler(Poster poster, long periodMillis, Runnable renderAction) {
        if (poster == null || renderAction == null) {
            throw new NullPointerException("poster and renderAction must not be null");
        }
        if (periodMillis < 0) {
            throw new IllegalArgumentException("periodMillis must be >= 0");
        }
        this.poster = poster;
        this.periodMillis = periodMillis;
        this.renderAction = renderAction;
    }

    /**
     * Called whenever new output has arrived and the screen may need repainting.
     *
     * <p>If the continuous heartbeat (see {@link #start()}) is currently running, this is
     * always a no-op: the heartbeat already guarantees a render within one tick unconditionally,
     * so scheduling a second, separate render here would just double the effective repaint rate
     * for no benefit (found in rubber-duck review of issue #65). Otherwise -- e.g. for a
     * locally-generated message shown before any session has connected and started the
     * heartbeat -- this falls back to its original on-demand coalescing: if a render pass is
     * already pending, this is a no-op (the pending pass will pick up this update's content --
     * and anything else that arrives before it fires -- because {@code renderAction} always
     * re-reads the current, authoritative state rather than a snapshot captured at schedule
     * time); otherwise it schedules exactly one render pass to run after {@code periodMillis}.
     *
     * @return {@code true} if this call scheduled a fresh render pass, {@code false} if the
     *         heartbeat is already covering repaints, or a render was already pending and this
     *         call was coalesced into it.
     */
    boolean onOutputAvailable() {
        synchronized (heartbeatLock) {
            if (heartbeatRunning) {
                return false;
            }
        }
        synchronized (lock) {
            if (scheduled) {
                return false;
            }
            scheduled = true;
        }
        poster.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    scheduled = false;
                }
                renderAction.run();
            }
        }, periodMillis);
        return true;
    }

    /**
     * Starts a continuous, self-perpetuating repaint heartbeat: {@code renderAction} runs
     * unconditionally every {@code periodMillis} for as long as the heartbeat is running,
     * independent of {@link #onOutputAvailable()} (issue #65). This is the guarantee that the
     * on-demand coalescing scheme above cannot make on its own: if any particular "output
     * arrived" notification is ever missed, delayed, or simply never fires for some region of
     * the terminal (e.g. an idle tmux pane in a multi-pane layout), the heartbeat still
     * repaints the whole screen from the terminal model's current, authoritative state at a
     * fixed cadence, so nothing can go stale indefinitely.
     *
     * <p>Idempotent: calling this while already running has no effect (does not start a second,
     * overlapping tick loop).
     */
    void start() {
        long generation;
        synchronized (heartbeatLock) {
            if (heartbeatRunning) {
                return;
            }
            heartbeatRunning = true;
            generation = ++heartbeatGeneration;
        }
        scheduleHeartbeatTick(generation);
    }

    /**
     * Stops the heartbeat. A tick already posted before this call may still fire once (it was
     * already handed to {@link Poster}), but it is tagged with the generation active when it was
     * scheduled and will find that generation stale on arrival, so it neither runs
     * {@code renderAction} nor schedules a further tick -- even if {@link #start()} is called
     * again before that stale tick fires.
     */
    void stop() {
        synchronized (heartbeatLock) {
            heartbeatRunning = false;
        }
    }

    private void scheduleHeartbeatTick(final long generation) {
        poster.postDelayed(new Runnable() {
            @Override public void run() {
                boolean stillCurrent;
                synchronized (heartbeatLock) {
                    stillCurrent = heartbeatRunning && generation == heartbeatGeneration;
                }
                if (!stillCurrent) {
                    return;
                }
                renderAction.run();
                // Re-check after running renderAction in case stop()/start() was called
                // re-entrantly from within it; schedule the next tick only if this generation
                // is still the current one.
                synchronized (heartbeatLock) {
                    stillCurrent = heartbeatRunning && generation == heartbeatGeneration;
                }
                if (stillCurrent) {
                    scheduleHeartbeatTick(generation);
                }
            }
        }, periodMillis);
    }

    /** Test/diagnostic hook: whether a render pass is currently pending. */
    boolean isRenderScheduled() {
        synchronized (lock) {
            return scheduled;
        }
    }
}
