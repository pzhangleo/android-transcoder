package net.ypresto.androidtranscoder.format;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

/**
 * Targets a bitrate (and, when needed, a frame size bounded by 1280x720) without ever up-scaling:
 * a video that is already smaller than the target keeps its own resolution.
 */
public class AndroidBitrateFormatStrategy implements MediaFormatStrategy {
    private static final String TAG = "BitrateStrategy";
    private static final int LONGER_LENGTH = 1280;
    private static final int SHORTER_LENGTH = 720;
    private static final int DEFAULT_BITRATE = 2500 * 1000; // From Nexus 4 Camera in 720p

    private final int mRequestedBitrate;

    public AndroidBitrateFormatStrategy(int bitRate) {
        mRequestedBitrate = bitRate > 0 ? bitRate : DEFAULT_BITRATE;
    }

    @Override
    public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
        String videoMime = inputFormat.getString(MediaFormat.KEY_MIME);
        int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int inputBitrate = inputFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
        int[] outSize = resolveOutputSize(width, height);
        int outWidth = outSize[0];
        int outHeight = outSize[1];

        // Never spend more bits than the source already has: inflating a 1.5 Mbps clip to a
        // "2.5 Mbps" target only wastes CPU and storage.
        int outputBitrate = (inputBitrate > 0 && inputBitrate < mRequestedBitrate)
                ? inputBitrate : mRequestedBitrate;

        boolean noResize = outWidth == width && outHeight == height;
        boolean noBitrateIncrease = inputBitrate <= 0 || outputBitrate >= inputBitrate;
        if (MediaFormatExtraConstants.MIMETYPE_VIDEO_AVC.equals(videoMime)
                && noResize && noBitrateIncrease) {
            Log.d(TAG, "Already within the target (" + width + "x" + height + ", "
                    + inputBitrate + " bps). Pass-through.");
            return null;
        }

        Log.d(TAG, "Transcoding " + width + "x" + height + " (" + inputBitrate + " bps) -> "
                + outWidth + "x" + outHeight + " (" + outputBitrate + " bps)");
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormatExtraConstants.MIMETYPE_VIDEO_AVC, outWidth, outHeight);
        format.setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return format;
    }

    /**
     * Fits the input into a 1280x720 bounding box while preserving the aspect ratio.
     * Never up-scales, always returns even dimensions.
     *
     * @return {width, height} in the same orientation as the input.
     */
    static int[] resolveOutputSize(int width, int height) {
        int longer = Math.max(width, height);
        int shorter = Math.min(width, height);
        if (longer <= 0 || shorter <= 0) {
            return new int[]{width, height};
        }
        if (longer <= LONGER_LENGTH && shorter <= SHORTER_LENGTH) {
            return new int[]{width, height};
        }

        double scale = Math.min((double) LONGER_LENGTH / longer, (double) SHORTER_LENGTH / shorter);
        int outLonger = Math.max(2, (int) Math.round(longer * scale)) & ~1;
        int outShorter = Math.max(2, (int) Math.round(shorter * scale)) & ~1;
        if (width >= height) {
            return new int[]{outLonger, outShorter};
        }
        return new int[]{outShorter, outLonger};
    }

    @Override
    public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
        String audioMimeType = inputFormat.getString(MediaFormat.KEY_MIME);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (MediaFormatExtraConstants.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(audioMimeType)) {
            // AAC is already the output codec; pass it through instead of re-encoding.
            Log.d(TAG, "Audio is already " + audioMimeType + ", pass-through");
            return null;
        }
        Log.d(TAG, "Transcoding audio from " + audioMimeType + " to AAC");
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormatExtraConstants.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128 * 1024);
        return format;
    }
}
