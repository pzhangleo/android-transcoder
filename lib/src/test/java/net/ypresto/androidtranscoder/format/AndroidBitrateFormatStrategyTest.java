package net.ypresto.androidtranscoder.format;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-JVM tests for the sizing rules. These cover the two bugs the previous implementation had:
 * 16:9 clips below 720p were up-scaled to 1280x720, and non-16:9 clips were never scaled at all.
 */
public class AndroidBitrateFormatStrategyTest {

    @Test
    public void downscales1080pTo720p() {
        assertArrayEquals(new int[]{1280, 720},
                AndroidBitrateFormatStrategy.resolveOutputSize(1920, 1080));
        assertArrayEquals(new int[]{1280, 720},
                AndroidBitrateFormatStrategy.resolveOutputSize(3840, 2160));
    }

    @Test
    public void downscalesPortraitKeepingOrientation() {
        assertArrayEquals(new int[]{720, 1280},
                AndroidBitrateFormatStrategy.resolveOutputSize(1080, 1920));
        assertArrayEquals(new int[]{720, 1280},
                AndroidBitrateFormatStrategy.resolveOutputSize(2160, 3840));
    }

    @Test
    public void neverUpscales() {
        assertArrayEquals(new int[]{640, 360},
                AndroidBitrateFormatStrategy.resolveOutputSize(640, 360));
        assertArrayEquals(new int[]{1280, 720},
                AndroidBitrateFormatStrategy.resolveOutputSize(1280, 720));
        assertArrayEquals(new int[]{360, 640},
                AndroidBitrateFormatStrategy.resolveOutputSize(360, 640));
    }

    @Test
    public void scalesNonSixteenByNineInsideTheBoundingBox() {
        // 4:3 1024x768 must become 960x720 instead of being left at the original size.
        assertArrayEquals(new int[]{960, 720},
                AndroidBitrateFormatStrategy.resolveOutputSize(1024, 768));
        // A very wide clip is bounded by the longer side.
        assertArrayEquals(new int[]{1280, 534},
                AndroidBitrateFormatStrategy.resolveOutputSize(2560, 1068));
    }

    @Test
    public void alwaysReturnsEvenDimensions() {
        int[][] inputs = {
                {1919, 1079}, {1921, 1081}, {1001, 999}, {3841, 2161},
        };
        for (int[] input : inputs) {
            int[] out = AndroidBitrateFormatStrategy.resolveOutputSize(input[0], input[1]);
            assertEquals("width of " + input[0] + "x" + input[1], 0, out[0] & 1);
            assertEquals("height of " + input[0] + "x" + input[1], 0, out[1] & 1);
        }
    }

    @Test
    public void leavesDegenerateInputUntouched() {
        assertArrayEquals(new int[]{0, 0},
                AndroidBitrateFormatStrategy.resolveOutputSize(0, 0));
    }
}
