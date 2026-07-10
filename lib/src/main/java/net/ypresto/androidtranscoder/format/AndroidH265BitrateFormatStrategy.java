package net.ypresto.androidtranscoder.format;

import android.os.Build;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

/**
 * Creates an H.265/HEVC video output format with the requested bitrate.
 * HEVC in an MP4 container requires MediaMuxer support from API 24.
 */
public class AndroidH265BitrateFormatStrategy implements MediaFormatStrategy {
    private static final int LONGER_LENGTH = 1280;
    private static final int SHORTER_LENGTH = 720;
    private static final int DEFAULT_BITRATE = 2_500 * 1000;

    private final int mVideoBitrate;

    public AndroidH265BitrateFormatStrategy() {
        this(DEFAULT_BITRATE);
    }

    public AndroidH265BitrateFormatStrategy(int videoBitrate) {
        if (videoBitrate <= 0) {
            throw new IllegalArgumentException("Video bitrate must be greater than zero.");
        }
        mVideoBitrate = videoBitrate;
    }

    @Override
    public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new OutputFormatUnavailableException("H.265 MP4 output requires Android 7.0 (API 24) or later.");
        }

        int inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int width = inputWidth;
        int height = inputHeight;
        int shorter = Math.min(inputWidth, inputHeight);
        if (shorter > SHORTER_LENGTH && inputWidth >= inputHeight && isSixteenByNine(inputWidth, inputHeight)) {
            width = LONGER_LENGTH;
            height = SHORTER_LENGTH;
        } else if (shorter > SHORTER_LENGTH && inputHeight > inputWidth && isSixteenByNine(inputHeight, inputWidth)) {
            width = SHORTER_LENGTH;
            height = LONGER_LENGTH;
        }
        width &= ~1;
        height &= ~1;

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormatExtraConstants.MIMETYPE_VIDEO_HEVC, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return format;
    }

    @Override
    public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
        return null;
    }

    private static boolean isSixteenByNine(int longer, int shorter) {
        return longer * 9 == shorter * 16;
    }
}
