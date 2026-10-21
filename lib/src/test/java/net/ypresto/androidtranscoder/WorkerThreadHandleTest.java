package net.ypresto.androidtranscoder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for the cancel/worker-thread coordination.
 *
 * <p>They cover the two ways a cancelled task used to reach into the next one: the interrupt was
 * sent to a thread reference captured before the worker left, and the interrupt status is a flag on
 * the thread that a pooled worker carries into whatever it runs next.
 */
public class WorkerThreadHandleTest {

    @Test(timeout = 5000)
    public void interruptWhileAttachedReachesTheWorker() {
        WorkerThreadHandle handle = new WorkerThreadHandle();
        final CountDownLatch attached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interruptedInsideTask = new CountDownLatch(1);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                handle.attach(Thread.currentThread());
                attached.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // Expected: the interrupt under test arrives here.
                    interruptedInsideTask.countDown();
                }
                handle.detach();
            }
        }, "handle-test-worker");
        thread.start();

        try {
            assertTrue(awaitQuietly(attached));
            assertTrue(handle.interrupt());
            assertTrue(awaitQuietly(interruptedInsideTask));
        } finally {
            release.countDown();
            joinQuietly(thread);
        }
    }

    /**
     * The regression: the canceller used to keep the thread reference and interrupt it later. Once
     * the worker has detached, that interrupt must not be sent at all, because the thread may
     * already be running somebody else's task.
     */
    @Test(timeout = 5000)
    public void interruptAfterDetachIsNotSent() {
        WorkerThreadHandle handle = new WorkerThreadHandle();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Never started: the task was cancelled while still queued.
            }
        }, "handle-test-detached");
        thread.start();
        joinQuietly(thread);

        handle.attach(thread);
        handle.detach();

        assertFalse(handle.interrupt());
        assertFalse(thread.isInterrupted());
    }

    @Test(timeout = 5000)
    public void interruptOnAPendingTaskIsANoOp() {
        WorkerThreadHandle handle = new WorkerThreadHandle();
        assertFalse(handle.interrupt());
    }

    /**
     * The interrupt status survives the task, and a pooled thread runs the next task with the same
     * {@link Thread} object. Detaching must clear the status so the next task does not start life
     * interrupted.
     */
    @Test(timeout = 5000)
    public void detachClearsTheInterruptStatus() throws Exception {
        WorkerThreadHandle handle = new WorkerThreadHandle();
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptedAfterDetach = new AtomicBoolean(true);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                handle.attach(Thread.currentThread());
                attached.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // The interrupt under test arrives here.
                }
                handle.detach();
                interruptedAfterDetach.set(Thread.currentThread().isInterrupted());
            }
        }, "handle-test-detach-clears");
        thread.start();

        try {
            assertTrue(awaitQuietly(attached));
            assertTrue(handle.interrupt());
            release.countDown();
            joinQuietly(thread);
            assertFalse("the worker thread must leave the task without a pending interrupt",
                    interruptedAfterDetach.get());
        } finally {
            release.countDown();
            joinQuietly(thread);
        }
    }

    /**
     * End-to-end shape of the reported bug on a real single-thread executor: cancel a running task
     * with interruption, and check that the task scheduled after it is not interrupted.
     */
    @Test(timeout = 10000)
    public void cancelDoesNotInterruptTheNextTaskOnAReusedThread() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final WorkerThreadHandle handleOfA = new WorkerThreadHandle();
        final CountDownLatch aStarted = new CountDownLatch(1);
        final CountDownLatch aFinished = new CountDownLatch(1);
        final AtomicInteger strayInterrupts = new AtomicInteger();

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    handleOfA.attach(Thread.currentThread());
                    aStarted.countDown();
                    try {
                        // Cancelled from the test thread below.
                        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                    } catch (InterruptedException e) {
                        // Cancellation: the expected end of task A.
                    } finally {
                        handleOfA.detach();
                        aFinished.countDown();
                    }
                }
            });
            assertTrue(awaitQuietly(aStarted));
            assertTrue(handleOfA.interrupt());
            assertTrue(awaitQuietly(aFinished));

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (Thread.currentThread().isInterrupted()) {
                        strayInterrupts.incrementAndGet();
                    }
                }
            });
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse("task B must not inherit task A's interrupt", strayInterrupts.get() > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
