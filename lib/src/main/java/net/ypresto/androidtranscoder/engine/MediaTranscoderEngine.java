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
package net.ypresto.androidtranscoder.engine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import net.ypresto.androidtranscoder.format.MediaFormatStrategy;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Internal engine, do not use this directly.
 */
// TODO: treat encrypted data
public class MediaTranscoderEngine {
    private static final String TAG = "MediaTranscoderEngine";
    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private FileDescriptor mInputFileDescriptor;
    private TrackTranscoder mVideoTrackTranscoder;
    private TrackTranscoder mAudioTrackTranscoder;
    private MediaExtractor mExtractor;
    private MediaMuxer mMuxer;
    private volatile double mProgress;
    private ProgressCallback mProgressCallback;
    private long mDurationUs;

    /**
     * Do not use this constructor unless you know what you are doing.
     */
    public MediaTranscoderEngine() {
    }

    public void setDataSource(FileDescriptor fileDescriptor) {
        mInputFileDescriptor = fileDescriptor;
    }

    public ProgressCallback getProgressCallback() {
        return mProgressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
    }

    /**
     * NOTE: This method is thread safe.
     */
    public double getProgress() {
        return mProgress;
    }

    /**
     * Run video transcoding. Blocks current thread.
     * Audio data will not be transcoded; original stream will be wrote to output file.
     *
     * @param outputPath     File path to output transcoded video file.
     * @param formatStrategy Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcodeVideo(String outputPath, MediaFormatStrategy formatStrategy) throws IOException, InterruptedException {
        if (outputPath == null) {
            throw new NullPointerException("Output path cannot be null.");
        }
        if (mInputFileDescriptor == null) {
            throw new IllegalStateException("Data source is not set.");
        }
        try {
            // NOTE: use single extractor to keep from running out audio track fast.
            mExtractor = new MediaExtractor();
            mExtractor.setDataSource(mInputFileDescriptor);
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMetadata();
            setupTrackTranscoders(formatStrategy);
            runPipelines();
            mMuxer.stop();
        } finally {
            try {
                if (mVideoTrackTranscoder != null) {
                    mVideoTrackTranscoder.release();
                    mVideoTrackTranscoder = null;
                }
                if (mAudioTrackTranscoder != null) {
                    mAudioTrackTranscoder.release();
                    mAudioTrackTranscoder = null;
                }
                if (mExtractor != null) {
                    mExtractor.release();
                    mExtractor = null;
                }
            } catch (RuntimeException e) {
                // Too fatal to make alive the app, because it may leak native resources.
                //noinspection ThrowFromFinallyBlock
                throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
            }
            try {
                if (mMuxer != null) {
                    mMuxer.release();
                    mMuxer = null;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to release muxer.", e);
            }
        }
    }

    private void setupMetadata() throws IOException {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        try {
            mediaMetadataRetriever.setDataSource(mInputFileDescriptor);

            String rotationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            try {
                mMuxer.setOrientationHint(Integer.parseInt(rotationString));
            } catch (NumberFormatException e) {
                // skip
            }

            // TODO: parse ISO 6709
            // String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            // mMuxer.setLocation(Integer.getInteger(rotationString, 0));

            try {
                mDurationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
            } catch (NumberFormatException e) {
                mDurationUs = -1;
            }
        } finally {
            mediaMetadataRetriever.release();
        }
        Log.d(TAG, "Duration (us): " + mDurationUs);
    }

    private void setupTrackTranscoders(MediaFormatStrategy formatStrategy) {
        if (formatStrategy == null) {
            throw new NullPointerException("Format strategy cannot be null.");
        }
        MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndOptionalAudioTrack(mExtractor);
        final boolean hasAudioTrack = trackResult.mAudioTrackIndex >= 0;
        MediaFormat videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
        MediaFormat audioOutputFormat = hasAudioTrack
                ? formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat) : null;

        if (videoOutputFormat == null && (!hasAudioTrack || audioOutputFormat == null)) {
            throw new InvalidOutputFormatException("MediaFormatStrategy returned pass-through for all input tracks. No transcoding is necessary.");
        }
        QueuedMuxer queuedMuxer = new QueuedMuxer(mMuxer, new QueuedMuxer.FormatListener() {
            @Override
            public void onDetermineOutputFormat(MediaFormat videoFormat, MediaFormat audioFormat) {
                MediaFormatValidator.validateVideoOutputFormat(videoFormat);
                if (audioFormat != null) {
                    MediaFormatValidator.validateAudioOutputFormat(audioFormat);
                }
            }
        }, hasAudioTrack);

        if (videoOutputFormat == null) {
            mVideoTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
        } else {
            mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, videoOutputFormat, queuedMuxer);
        }
        mVideoTrackTranscoder.setup();
        if (hasAudioTrack) {
            if (audioOutputFormat == null) {
                mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
            } else {
                mAudioTrackTranscoder = new AudioTrackTranscoder(mExtractor, trackResult.mAudioTrackIndex, audioOutputFormat, queuedMuxer);
            }
            mAudioTrackTranscoder.setup();
        }
        mExtractor.selectTrack(trackResult.mVideoTrackIndex);
        if (hasAudioTrack) {
            mExtractor.selectTrack(trackResult.mAudioTrackIndex);
        }
    }

    private void runPipelines() throws InterruptedException {
        long loopCount = 0;
        if (mDurationUs <= 0) {
            double progress = PROGRESS_UNKNOWN;
            mProgress = progress;
            if (mProgressCallback != null) mProgressCallback.onProgress(progress); // unknown
        }
        while (!arePipelinesFinished()) {
            checkCancellation();
            boolean stepped = mVideoTrackTranscoder.stepPipeline();
            if (mAudioTrackTranscoder != null) {
                stepped |= mAudioTrackTranscoder.stepPipeline();
            }
            loopCount++;
            if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                notifyProgress(calculateProgress());
            }
            if (!stepped) {
                Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
            }
        }
        if (mDurationUs > 0) {
            notifyProgress(1.0);
        }
    }

    private boolean arePipelinesFinished() {
        return mVideoTrackTranscoder.isFinished()
                && (mAudioTrackTranscoder == null || mAudioTrackTranscoder.isFinished());
    }

    private double calculateProgress() {
        double videoProgress = getTrackProgress(mVideoTrackTranscoder);
        if (mAudioTrackTranscoder == null) {
            return videoProgress;
        }
        return (videoProgress + getTrackProgress(mAudioTrackTranscoder)) / 2.0;
    }

    private double getTrackProgress(TrackTranscoder transcoder) {
        return transcoder.isFinished() ? 1.0
                : Math.min(1.0, (double) transcoder.getWrittenPresentationTimeUs() / mDurationUs);
    }

    private void notifyProgress(double progress) {
        mProgress = progress;
        if (mProgressCallback != null) {
            mProgressCallback.onProgress(progress);
        }
    }

    private static void checkCancellation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Transcoding was canceled.");
        }
    }

    public interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
