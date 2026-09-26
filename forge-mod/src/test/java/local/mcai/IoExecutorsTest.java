package local.mcai;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class IoExecutorsTest {
    private void waitFor(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private Runnable block(final CountDownLatch entered, final CountDownLatch release) {
        return new Runnable() {
            @Override public void run() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            }
        };
    }
    @Test public void blockedSocialDoesNotBlockOtherLanes() throws Exception {
        try (IoExecutors io = new IoExecutors()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), other = new CountDownLatch(3);
            assertTrue(io.execute(IoExecutors.Lane.SOCIAL, block(entered, release))); waitFor(entered);
            for (IoExecutors.Lane lane : new IoExecutors.Lane[] {IoExecutors.Lane.CONTROL, IoExecutors.Lane.OBSERVATION, IoExecutors.Lane.RESULTS}) {
                assertTrue(io.execute(lane, new Runnable() { @Override public void run() { other.countDown(); } }));
            }
            waitFor(other); assertEquals(1, release.getCount()); release.countDown();
        }
    }
    @Test public void sameLaneIsBoundedAndNeverRunsOnCaller() throws Exception {
        try (IoExecutors io = new IoExecutors()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
            assertTrue(io.execute(IoExecutors.Lane.CONTROL, block(entered, release))); waitFor(entered);
            assertTrue(io.execute(IoExecutors.Lane.CONTROL, new Runnable() { @Override public void run() { second.countDown(); } }));
            assertFalse(io.execute(IoExecutors.Lane.CONTROL, new Runnable() { @Override public void run() { fail("Ran rejected job on caller"); } }));
            assertEquals(1, second.getCount()); release.countDown(); waitFor(second);
        }
    }
    @Test public void workerIsReusedAndIsDaemon() throws Exception {
        try (IoExecutors io = new IoExecutors()) {
            AtomicReference<Thread> first = new AtomicReference<Thread>(), second = new AtomicReference<Thread>();
            CountDownLatch a = new CountDownLatch(1), b = new CountDownLatch(1);
            io.execute(IoExecutors.Lane.SOCIAL, new Runnable() { @Override public void run() { first.set(Thread.currentThread()); a.countDown(); } });
            waitFor(a);
            io.execute(IoExecutors.Lane.SOCIAL, new Runnable() { @Override public void run() { second.set(Thread.currentThread()); b.countDown(); } });
            waitFor(b); assertSame(first.get(), second.get()); assertTrue(first.get().isDaemon());
        }
    }
    @Test public void closeInterruptsActiveDropsQueuedAndRejectsNewWork() throws Exception {
        IoExecutors io = new IoExecutors();
        try {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), queued = new CountDownLatch(1);
            io.execute(IoExecutors.Lane.OBSERVATION, block(entered, release)); waitFor(entered);
            io.execute(IoExecutors.Lane.OBSERVATION, new Runnable() { @Override public void run() { queued.countDown(); } });
            io.close(); assertTrue(io.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(1, queued.getCount());
            assertFalse(io.execute(IoExecutors.Lane.OBSERVATION, new Runnable() { @Override public void run() { fail("Closed executor accepted work"); } }));
        } finally { io.close(); }
    }
}
