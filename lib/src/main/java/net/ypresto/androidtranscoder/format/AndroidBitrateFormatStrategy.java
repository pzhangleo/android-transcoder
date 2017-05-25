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
package net.ypresto.androidtranscoder.format;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import net.ypresto.androidtranscoder.format.MediaFormatExtraConstants;
import net.ypresto.androidtranscoder.format.MediaFormatStrategy;

public class AndroidBitrateFormatStrategy implements MediaFormatStrategy {
    private static final String TAG = "720pFormatStrategy";
    private static final int LONGER_LENGTH = 1280;
    private static final int SHORTER_LENGTH = 720;
    private static final int DEFAULT_BITRATE = 2500 * 1000; // From Nexus 4 Camera in 720p
    private int mBitRate = DEFAULT_BITRATE;
    private int mOriBitrate = 0;
    private String mMaskFilePath;

    public AndroidBitrateFormatStrategy(int bitRate, String maskFilePath) {
        mOriBitrate = bitRate;
        mMaskFilePath = maskFilePath;
    }

    @Override
    public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
        String videoMime = inputFormat.getString(MediaFormat.KEY_MIME);
        int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        Log.d(TAG, "video info mime:" + videoMime + "width:" + width + "height:" + height);
        int longer, shorter, outWidth, outHeight;
        if (width >= height) {
            if (Math.abs(((float) LONGER_LENGTH / SHORTER_LENGTH - (float) width / height)) > 0.01f) {
                longer = width;
                shorter = height;
            } else {
                longer = LONGER_LENGTH;
                shorter = SHORTER_LENGTH;
            }
            outWidth = longer;
            outHeight = shorter;
        } else {
            if (Math.abs(((float) LONGER_LENGTH / SHORTER_LENGTH - (float) height / width)) > 0.01f) {
                shorter = width;
                longer = height;
            } else {
                shorter = SHORTER_LENGTH;
                longer = LONGER_LENGTH;
            }
            outWidth = shorter;
            outHeight = longer;
        }
        if (longer * 9 != shorter * 16) {
            Log.d(TAG,"This video is not 16:9, So it is transcode with. (" + width + "x" + height + ")");
        } else {
            Log.d(TAG,"This video is 16:9, So it is transcode with. (" + outWidth + "x" + outHeight + ")");
        }
        if (shorter <= SHORTER_LENGTH && mOriBitrate < mBitRate && mOriBitrate != 0
                && MediaFormatExtraConstants.MIMETYPE_VIDEO_AVC.equals(videoMime)) {
            Log.d(TAG,"This video is less or equal to 720p, pass-through. (" + width + "x" + height + ")" + " bitrate: " + mOriBitrate);
            return null;
        }
        if (mOriBitrate < mBitRate && mOriBitrate != 0) {
            mBitRate = mOriBitrate;
        }
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight);
        // From Nexus 4 Camera in 720p
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (mMaskFilePath != null) {
            format.setString(MediaFormatExtraConstants.KEY_MASK_PATH, mMaskFilePath);
        }
        return format;
    }

    @Override
    public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
        String audioMemType = inputFormat.getString(MediaFormat.KEY_MIME);
        int channalCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        Log.d(TAG,"audio info mime: " + audioMemType +", channalcount: " + channalCount + ", samplerate: " + sampleRate);
        if (MediaFormatExtraConstants.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(audioMemType)) {//如果是aac，则不需要对音频单独处理
            Log.d(TAG,"This audio mimetype is " + audioMemType + ", pass-through");
            return null;
        } else {
            Log.d(TAG,"This audio mimetype is "+ audioMemType + ", need transcode");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormatExtraConstants.MIMETYPE_AUDIO_AAC, sampleRate, channalCount);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128 * 1024);
            return format;
        }
    }
}
