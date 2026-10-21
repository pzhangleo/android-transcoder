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

// Refer for example: https://gist.github.com/wobbals/3990442
// Refer for preferred parameters: https://developer.apple.com/library/ios/documentation/networkinginternet/conceptual/streamingmediaguide/UsingHTTPLiveStreaming/UsingHTTPLiveStreaming.html#//apple_ref/doc/uid/TP40008332-CH102-SW8
// Refer for available keys: (ANDROID ROOT)/media/libstagefright/ACodec.cpp
public class MediaFormatPresets {
    private static final int LONGER_LENGTH_960x540 = 960;

    private MediaFormatPresets() {
    }

    // preset similar to iOS SDK's AVAssetExportPreset960x540
    @Deprecated
    public static MediaFormat getExportPreset960x540() {
        return createPreset960x540(960, 540);
    }

    /**
     * Preset similar to iOS SDK's AVAssetExportPreset960x540.
     * Note that encoding resolutions of this preset are not supported in all devices e.g. Nexus 4.
     * On unsupported device encoded video stream will be broken without any exception.
     *
     * @param originalWidth  Input video width.
     * @param originalHeight Input video height.
     * @return MediaFormat instance, or null if pass through.
     */
    public static MediaFormat getExportPreset960x540(int originalWidth, int originalHeight) {
        int[] size = calculateScaledSize(originalWidth, originalHeight, LONGER_LENGTH_960x540);
        if (size == null) return null; // don't upscale
        return createPreset960x540(size[0], size[1]);
    }

    private static MediaFormat createPreset960x540(int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5500 * 1000);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return format;
    }

    /**
     * Scales the input so that its longer side equals {@code targetLongerLength}, keeping the
     * aspect ratio. Pure integer/double math so it can be unit tested on the JVM.
     *
     * @return {width, height} in the input orientation, or null when no scaling is required.
     * @throws OutputFormatUnavailableException when the scaled shorter side is not an integer,
     *                                          because non-integer scaling cannot be expressed in
     *                                          a valid {@link MediaFormat}.
     */
    static int[] calculateScaledSize(int originalWidth, int originalHeight, int targetLongerLength) {
        int longerLength = Math.max(originalWidth, originalHeight);
        int shorterLength = Math.min(originalWidth, originalHeight);
        if (longerLength <= targetLongerLength) return null; // don't upscale

        int residue = targetLongerLength * shorterLength % longerLength;
        if (residue != 0) {
            double ambiguousShorter = (double) targetLongerLength * shorterLength / longerLength;
            throw new OutputFormatUnavailableException(String.format(
                    "Could not fit to integer, original: (%d, %d), scaled: (%d, %f)",
                    longerLength, shorterLength, targetLongerLength, ambiguousShorter));
        }

        int scaledShorter = targetLongerLength * shorterLength / longerLength;
        if (originalWidth >= originalHeight) {
            return new int[]{targetLongerLength, scaledShorter};
        }
        return new int[]{scaledShorter, targetLongerLength};
    }
}
