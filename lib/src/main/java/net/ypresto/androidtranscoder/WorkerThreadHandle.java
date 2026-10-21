/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder;

/**
 * Hands a single task's worker thread to the cancelling thread, so that
 * {@link #interrupt()} can interrupt only that task and never the task that the same pooled
 * thread runs next.
 *
 * <p>Reading the thread reference and interrupting it are two separate steps. If they are not
 * atomic, the following interleaving is possible: the canceller reads the thread of task A, A
 * finishes and the executor immediately starts task B on that very thread, and only then does the
 * interrupt arrive. Task B would observe a stray {@link InterruptedException} and report a failure
 * that has nothing to do with it. Interrupting inside the same monitor that {@link #detach()}
 * takes closes that window: either the worker is still attached, and the interrupt is delivered
 * while it is inside the task, or it has already detached and no interrupt is sent at all.
 *
 * <p>{@link #detach()} also clears a pending interrupt status, because an interrupt is a flag on
 * the {@link Thread} object and the thread outlives the task. This makes the guarantee independent
 * of what the surrounding executor does with the thread between tasks.
 *
 * <p>One instance belongs to exactly one task; never share it with another task, or the isolation
 * between tasks disappears again.
 */
final class WorkerThreadHandle {
    private Thread mThread;

    /** Called by the worker when it starts executing the owning task. */
    synchronized void attach(Thread thread) {
        mThread = thread;
    }

    /**
     * Called by the worker from a {@code finally} block, i.e. from the attached thread itself, so
     * that the cleared interrupt status belongs to the worker and not to the caller.
     */
    synchronized void detach() {
        mThread = null;
        Thread.interrupted();
    }

    /**
     * Interrupts the worker if it is still running the owning task.
     *
     * @return {@code true} if an interrupt was sent, {@code false} if the worker had already
     * detached (the task is finishing anyway, and interrupting whatever runs next would be wrong).
     */
    synchronized boolean interrupt() {
        Thread thread = mThread;
        if (thread == null) return false;
        thread.interrupt();
        return true;
    }
}
