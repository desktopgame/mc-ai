package local.mcai;

/**
 * Pure terminal-poll cursor for one binding (daemonEpoch, session). `afterSequence` only ever moves
 * forward within a binding, and a binding change must reset it so a new session's first eventSequence
 * (which restarts at 1) is not skipped. Minecraft-independent and unit-testable.
 */
public final class TerminalPollCursor {
    private static final long POLL_INTERVAL_NANOS = 1000000000L;
    private int cursor;
    private long nextPollNanos;

    public int afterSequence() { return cursor; }

    public long nextPollNanos() { return nextPollNanos; }

    public boolean due(long now) { return now >= nextPollNanos; }

    public void schedule(long now) { nextPollNanos = now + POLL_INTERVAL_NANOS; }

    /** Records the highest observed eventSequence so already-seen events are not fetched again. */
    public void observe(int eventSequence) {
        if (eventSequence > cursor) { cursor = eventSequence; }
    }

    /** Drops all binding state. Must be called on world/session change so a new session starts at 0. */
    public void reset() {
        cursor = 0;
        nextPollNanos = 0;
    }
}
