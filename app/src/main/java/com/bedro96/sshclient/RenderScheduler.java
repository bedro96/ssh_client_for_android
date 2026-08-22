package com.bedro96.sshclient;

/**
 * Coalesces bursts of "new output arrived" notifications onto a single fixed-rate render
 * pass per tick (issue #64).
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
     * <p>If a render pass is already pending, this is a no-op (the pending pass will pick up
     * this update's content -- and anything else that arrives before it fires -- because
     * {@code renderAction} always re-reads the current, authoritative state rather than a
     * snapshot captured at schedule time). Otherwise, this schedules exactly one render pass to
     * run after {@code periodMillis}.
     *
     * @return {@code true} if this call scheduled a fresh render pass, {@code false} if one was
     *         already pending and this call was coalesced into it.
     */
    boolean onOutputAvailable() {
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

    /** Test/diagnostic hook: whether a render pass is currently pending. */
    boolean isRenderScheduled() {
        synchronized (lock) {
            return scheduled;
        }
    }
}
