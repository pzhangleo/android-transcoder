package net.ypresto.androidtranscoder.engine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.FileDescriptor;
import java.io.IOException;

// TODO: treat encrypted data
public class MediaTranscoderEngine {
    private static final String TAG = "MediaTranscoderEngine";
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private FileDescriptor mInputFileDescriptor;
    private TrackTranscoder mVideoTrackTranscoder;
    private TrackTranscoder mAudioTrackTranscoder;
    private MediaExtractor mExtractor;
    private MediaMuxer mMuxer;
    private ProgressCallback mProgressCallback;
    private long mDurationUs;

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
     * Run video transcoding. Blocks current thread.
     * Audio data will not be transcoded; original stream will be wrote to output file.
     *
     * @param outputPath  File path to output transcoded video file.
     * @param videoFormat Output video format.
     * @throws IOException when input or output file could not be opened.
     */
    public void transcodeVideo(String outputPath, MediaFormat videoFormat) throws IOException {
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
            setupTrackTranscoders(videoFormat);
            mMuxer.start();
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
        Log.d(TAG, "Duration (us): " + mDurationUs);
    }

    private void setupTrackTranscoders(MediaFormat outputFormat) {
        MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
        mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, outputFormat, mMuxer);
        mVideoTrackTranscoder.setup();
        mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mAudioTrackIndex, mMuxer);
        mAudioTrackTranscoder.setup();
        mVideoTrackTranscoder.determineFormat();
        mAudioTrackTranscoder.determineFormat();
        mVideoTrackTranscoder.addTrackToMuxer();
        mAudioTrackTranscoder.addTrackToMuxer();
        mExtractor.selectTrack(trackResult.mVideoTrackIndex);
        mExtractor.selectTrack(trackResult.mAudioTrackIndex);
    }

    private void runPipelines() {
        long loopCount = 0;
        if (mDurationUs <= 0 && mProgressCallback != null) {
            mProgressCallback.onProgress(-1.0); // unknown
        }
        while (!(mVideoTrackTranscoder.isFinished() && mAudioTrackTranscoder.isFinished())) {
            boolean stepped = mVideoTrackTranscoder.stepPipeline()
                    || mAudioTrackTranscoder.stepPipeline();
            loopCount++;
            if (mDurationUs > 0 && mProgressCallback != null && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                double videoProgress = mVideoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mVideoTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                double audioProgress = mAudioTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mAudioTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                mProgressCallback.onProgress((videoProgress + audioProgress) / 2.0);
            }
            if (!stepped) {
                try {
                    Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                } catch (InterruptedException e) {
                    // nothing to do
                }
            }
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