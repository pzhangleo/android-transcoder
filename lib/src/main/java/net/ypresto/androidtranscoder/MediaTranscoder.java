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

import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.ypresto.androidtranscoder.engine.MediaTranscoderEngine;
import net.ypresto.androidtranscoder.format.MediaFormatPresets;
import net.ypresto.androidtranscoder.format.MediaFormatStrategy;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MediaTranscoder {
    private static final String TAG = "MediaTranscoder";
    private static final int MAXIMUM_THREAD = 1; // TODO
    /**
     * Progress notifications closer than this to the previous one are dropped, so a fast transcode
     * cannot flood the listener's Looper with hundreds of near-identical updates.
     */
    private static final double PROGRESS_EPSILON = 0.001;
    private static volatile MediaTranscoder sMediaTranscoder;
    private ThreadPoolExecutor mExecutor;

    private MediaTranscoder() {
        mExecutor = new ThreadPoolExecutor(
                0, MAXIMUM_THREAD, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "MediaTranscoder-Worker");
                    }
                });
    }

    public static MediaTranscoder getInstance() {
        if (sMediaTranscoder == null) {
            synchronized (MediaTranscoder.class) {
                if (sMediaTranscoder == null) {
                    sMediaTranscoder = new MediaTranscoder();
                }
            }
        }
        return sMediaTranscoder;
    }

    /**
     * Transcodes video file asynchronously.
     * Audio track will be kept unchanged.
     *
     * @param inFileDescriptor FileDescriptor for input.
     * @param outPath          File path for output.
     * @param listener         Listener instance for callback.
     * @deprecated Use {@link #transcodeVideo(FileDescriptor, String, MediaFormatStrategy, MediaTranscoder.Listener)} which accepts output video format.
     */
    @Deprecated
    public Future<Void> transcodeVideo(final FileDescriptor inFileDescriptor, final String outPath, final Listener listener) {
        return transcodeVideo(inFileDescriptor, outPath, new MediaFormatStrategy() {
            @Override
            public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
                return MediaFormatPresets.getExportPreset960x540();
            }

            @Override
            public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
                return null;
            }
        }, listener);
    }

    /**
     * Transcodes video file asynchronously.
     * Audio track will be kept unchanged.
     *
     * @param inPath            File path for input.
     * @param outPath           File path for output.
     * @param outFormatStrategy Strategy for output video format.
     * @param listener          Listener instance for callback.
     * @throws IOException if input file could not be read.
     */
    public Future<Void> transcodeVideo(final String inPath, final String outPath, final MediaFormatStrategy outFormatStrategy, final Listener listener) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(inPath);
        final FileDescriptor inFileDescriptor;
        try {
            inFileDescriptor = fileInputStream.getFD();
        } catch (IOException e) {
            closeQuietly(fileInputStream);
            throw e;
        }
        // The stream must stay open for the whole transcode and is closed by the task itself, so a
        // cancelled-before-start or failed job can no longer leak the file descriptor.
        return startTask(inFileDescriptor, outPath, outFormatStrategy, listener, fileInputStream);
    }

    /**
     * Transcodes video file asynchronously.
     * Audio track will be kept unchanged.
     *
     * @param inFileDescriptor  FileDescriptor for input.
     * @param outPath           File path for output.
     * @param outFormatStrategy Strategy for output video format.
     * @param listener          Listener instance for callback.
     */
    public Future<Void> transcodeVideo(final FileDescriptor inFileDescriptor, final String outPath, final MediaFormatStrategy outFormatStrategy, final Listener listener) {
        return startTask(inFileDescriptor, outPath, outFormatStrategy, listener, null);
    }

    private Future<Void> startTask(FileDescriptor inFileDescriptor, String outPath,
                                   MediaFormatStrategy outFormatStrategy, Listener listener,
                                   Closeable inputToClose) {
        Looper looper = Looper.myLooper();
        if (looper == null) looper = Looper.getMainLooper();
        TranscodeTask task = new TranscodeTask(inFileDescriptor, outPath, outFormatStrategy,
                listener, new Handler(looper), inputToClose);
        // NOTE: execute() (not submit()) on purpose: a plain FutureTask cancelled while still queued
        // never runs its Callable, so nothing would deliver the terminal callback. TranscodeTask
        // keeps the state itself, so cancel() is complete the moment it returns even if this runnable
        // is never executed (the executor may be shut down first).
        mExecutor.execute(task);
        return task;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            Log.e(TAG, "Can't close input stream: ", e);
        }
    }

    public interface Listener {
        /**
         * Called to notify progress.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onTranscodeProgress(double progress);

        /**
         * Called when transcode completed.
         */
        void onTranscodeCompleted();

        /**
         * Called when transcode canceled.
         */
        void onTranscodeCanceled();

        /**
         * Called when transcode failed.
         *
         * @param exception Exception thrown from {@link MediaTranscoderEngine#transcodeVideo(String, MediaFormatStrategy)}.
         *                  Note that it IS NOT {@link java.lang.Throwable}. This means {@link java.lang.Error} won't be caught.
         */
        void onTranscodeFailed(Exception exception);
    }

    /**
     * A minimal Future backed by the transcode task itself. It exists so that cancelling a task
     * that has not started yet still produces exactly one terminal callback.
     */
    private static final class TranscodeTask implements Runnable, Future<Void> {
        private static final int STATE_PENDING = 0;
        private static final int STATE_RUNNING = 1;
        private static final int STATE_COMPLETED = 2;
        private static final int STATE_CANCELLED = 3;
        private static final int STATE_FAILED = 4;

        private final FileDescriptor mInFileDescriptor;
        private final String mOutPath;
        private final MediaFormatStrategy mFormatStrategy;
        private final Listener mListener;
        private final Handler mHandler;
        private final Closeable mInputToClose;
        private final CountDownLatch mDone = new CountDownLatch(1);
        /**
         * Single source of truth for the task state. Cancellation and completion used to be tracked
         * by a separate {@code mCancelled} flag plus the latch, which allowed states that contradict
         * each other: a cancelled queued task that was not done yet, or a running task that reported
         * completion to the listener while {@link #get()} threw {@link CancellationException}.
         */
        private final AtomicInteger mState = new AtomicInteger(STATE_PENDING);
        private final AtomicBoolean mTerminalSignalSent = new AtomicBoolean();
        /** The input may be closed by {@link #cancel} or by the worker; close it exactly once. */
        private final AtomicBoolean mInputClosed = new AtomicBoolean();
        /**
         * Owns the worker thread for this task only. Interrupting through this handle cannot hit
         * the next task that the pooled thread picks up. See {@link WorkerThreadHandle}.
         */
        private final WorkerThreadHandle mWorker = new WorkerThreadHandle();
        private volatile Exception mFailure;
        private volatile double mLastProgress = -1;

        private TranscodeTask(FileDescriptor inFileDescriptor, String outPath,
                              MediaFormatStrategy formatStrategy, Listener listener,
                              Handler handler, Closeable inputToClose) {
            mInFileDescriptor = inFileDescriptor;
            mOutPath = outPath;
            mFormatStrategy = formatStrategy;
            mListener = listener;
            mHandler = handler;
            mInputToClose = inputToClose;
        }

        @Override
        public void run() {
            mWorker.attach(Thread.currentThread());
            try {
                // A cancel() while this task was still queued already produced the terminal
                // callback and released the input, so there is nothing left to do here.
                if (!mState.compareAndSet(STATE_PENDING, STATE_RUNNING)) {
                    return;
                }
                transcode();
            } finally {
                mWorker.detach();
                closeInput();
                mDone.countDown();
            }
        }

        private void transcode() {
            Exception caughtException = null;
            try {
                MediaTranscoderEngine engine = new MediaTranscoderEngine();
                engine.setProgressCallback(new MediaTranscoderEngine.ProgressCallback() {
                    @Override
                    public void onProgress(final double progress) {
                        if (mState.get() != STATE_RUNNING) {
                            // Cancelled or finished: no further progress for this task.
                            return;
                        }
                        if (progress >= 0 && progress < 1.0
                                && Math.abs(progress - mLastProgress) < PROGRESS_EPSILON) {
                            return;
                        }
                        mLastProgress = progress;
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                // The check above runs on the worker, but this runnable runs later
                                // on the listener's Looper. Between the two, cancel() can claim the
                                // terminal signal and post onTranscodeCanceled(), which the Looper
                                // may then deliver before this queued progress — leaving a stale
                                // progress callback arriving after the task ended. Re-check at
                                // delivery time so no progress can follow the terminal callback.
                                if (mTerminalSignalSent.get()) {
                                    return;
                                }
                                mListener.onTranscodeProgress(progress);
                            }
                        });
                    }
                });
                engine.setDataSource(mInFileDescriptor);
                engine.transcodeVideo(mOutPath, mFormatStrategy);
            } catch (IOException e) {
                Log.w(TAG, "Transcode failed: input file (fd: " + mInFileDescriptor
                        + ") not found or could not open output file ('" + mOutPath + "') .", e);
                caughtException = e;
            } catch (InterruptedException e) {
                Log.i(TAG, "Cancel transcode video file.", e);
                caughtException = e;
            } catch (RuntimeException e) {
                Log.e(TAG, "Fatal error while transcoding, this might be invalid format or bug in engine or Android.", e);
                caughtException = e;
            }

            mFailure = caughtException;
            if (caughtException == null) {
                // Loses the race against cancel(): in that case the listener already received
                // onTranscodeCanceled() and must not also receive onTranscodeCompleted().
                if (mState.compareAndSet(STATE_RUNNING, STATE_COMPLETED)) {
                    signalCompleted();
                }
            } else if (mState.compareAndSet(STATE_RUNNING, STATE_FAILED)) {
                signalFailed(caughtException);
            }
        }

        private boolean claimTerminalSignal() {
            return mTerminalSignalSent.compareAndSet(false, true);
        }

        private void signalCompleted() {
            if (!claimTerminalSignal()) return;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onTranscodeCompleted();
                }
            });
        }

        private void signalCanceled() {
            if (!claimTerminalSignal()) return;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onTranscodeCanceled();
                }
            });
        }

        private void signalFailed(final Exception exception) {
            if (!claimTerminalSignal()) return;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onTranscodeFailed(exception);
                }
            });
        }

        /**
         * Cancels the transcode. The future reaches a terminal state immediately: {@link #isDone()}
         * turns true, {@link #get()} throws {@link CancellationException}, and the listener receives
         * exactly one {@link Listener#onTranscodeCanceled()} — even if the task never left the
         * executor queue. With {@code mayInterruptIfRunning == false} the worker may still run to
         * completion; its result is discarded, so the output file can end up usable.
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            for (;;) {
                int state = mState.get();
                if (state == STATE_CANCELLED || state == STATE_COMPLETED || state == STATE_FAILED) {
                    // Already finished, in whichever way: nothing left to cancel.
                    return false;
                }
                if (mState.compareAndSet(state, STATE_CANCELLED)) {
                    break;
                }
            }
            // Cancellation is terminal right now, whether the task is still queued or already
            // running. Deferring it to the worker left get() blocked on a task that might never run
            // (shutdownNow() drops queued tasks) and kept the input descriptor open meanwhile.
            closeInput();
            signalCanceled();
            mDone.countDown();
            if (mayInterruptIfRunning) {
                // Do NOT capture the thread and interrupt it afterwards: the worker can finish and
                // the same pooled thread can start the next task in between, and that task would be
                // the one getting interrupted. The handle interrupts only while this task still
                // owns the thread.
                mWorker.interrupt();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return mState.get() == STATE_CANCELLED;
        }

        @Override
        public boolean isDone() {
            return mDone.getCount() == 0;
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            mDone.await();
            return awaitResult();
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (!mDone.await(timeout, unit)) {
                throw new TimeoutException();
            }
            return awaitResult();
        }

        private Void awaitResult() throws ExecutionException {
            int state = mState.get();
            if (state == STATE_CANCELLED) throw new CancellationException();
            if (state == STATE_FAILED) throw new ExecutionException(mFailure);
            return null;
        }

        private void closeInput() {
            if (mInputClosed.compareAndSet(false, true)) {
                closeQuietly(mInputToClose);
            }
        }
    }
}
