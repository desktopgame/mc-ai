package local.mcai;

import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-owned bounded lanes. A slow Social request cannot occupy a control worker. */
public final class IoExecutors implements AutoCloseable {
    public enum Lane { SOCIAL, OBSERVATION, CONTROL, RESULTS }
    private final EnumMap<Lane, ThreadPoolExecutor> pools = new EnumMap<Lane, ThreadPoolExecutor>(Lane.class);

    public IoExecutors() {
        for (final Lane lane : Lane.values()) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(1), new ThreadFactory() {
                        @Override public Thread newThread(Runnable task) {
                            Thread thread = new Thread(task, "mc-ai-" + lane.name().toLowerCase(java.util.Locale.ROOT));
                            thread.setDaemon(true); return thread;
                        }
                    }, new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            pools.put(lane, pool);
        }
    }

    /** Never blocks or executes network I/O on the submitting game thread. */
    public boolean execute(Lane lane, Runnable task) {
        try { pools.get(lane).execute(task); return true; }
        catch (RejectedExecutionException rejected) { return false; }
    }

    @Override public void close() {
        for (ThreadPoolExecutor pool : pools.values()) { pool.shutdownNow(); }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (ThreadPoolExecutor pool : pools.values()) {
            if (!pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) { return false; }
        }
        return true;
    }
}
