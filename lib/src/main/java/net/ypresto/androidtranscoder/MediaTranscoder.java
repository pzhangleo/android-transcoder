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
        // NOTE: execute() (not submit()) on purpose. A FutureTask cancelled while still queued never
        // runs its Callable, so the listener would never learn about the cancellation and the caller
        // would wait forever. This task always runs and converts a pre-start cancellation into
        // exactly one onTranscodeCanceled() callback.
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
        private final FileDescriptor mInFileDescriptor;
        private final String mOutPath;
        private final MediaFormatStrategy mFormatStrategy;
        private final Listener mListener;
        private final Handler mHandler;
        private final Closeable mInputToClose;
        private final CountDownLatch mDone = new CountDownLatch(1);
        private final AtomicBoolean mTerminalSignalSent = new AtomicBoolean();
        private volatile boolean mCancelled;
        private volatile Exception mFailure;
        private volatile Thread mWorkerThread;
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
            mWorkerThread = Thread.currentThread();
            try {
                if (mCancelled) {
                    signalCanceled();
                    return;
                }
                transcode();
            } finally {
                mWorkerThread = null;
                closeQuietly(mInputToClose);
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
                        if (progress >= 0 && progress < 1.0
                                && Math.abs(progress - mLastProgress) < PROGRESS_EPSILON) {
                            return;
                        }
                        mLastProgress = progress;
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
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
                signalCompleted();
            } else if (mCancelled) {
                signalCanceled();
            } else {
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

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return false;
            mCancelled = true;
            Thread worker = mWorkerThread;
            if (mayInterruptIfRunning && worker != null) {
                worker.interrupt();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return mCancelled;
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
            if (mCancelled) throw new CancellationException();
            Exception failure = mFailure;
            if (failure != null) throw new ExecutionException(failure);
            return null;
        }
    }
}
