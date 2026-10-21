package net.ypresto.androidtranscoder.format;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pure-JVM tests for the export preset maths. The previous version of this test lived in
 * androidTest with no test dependencies at all, so it never compiled.
 */
public class MediaFormatPresetsTest {

    @Test
    public void doesNotUpscale() {
        assertNull(MediaFormatPresets.calculateScaledSize(960, 540, 960));
        assertNull(MediaFormatPresets.calculateScaledSize(540, 960, 960));
        assertNull(MediaFormatPresets.calculateScaledSize(480, 320, 960));
    }

    @Test
    public void keepsAspectRatioForLandscape() {
        assertArrayEquals(new int[]{960, 720},
                MediaFormatPresets.calculateScaledSize(1024, 768, 960));
        assertArrayEquals(new int[]{960, 540},
                MediaFormatPresets.calculateScaledSize(1920, 1080, 960));
        assertArrayEquals(new int[]{960, 540},
                MediaFormatPresets.calculateScaledSize(1280, 720, 960));
    }

    @Test
    public void keepsAspectRatioForPortrait() {
        assertArrayEquals(new int[]{720, 960},
                MediaFormatPresets.calculateScaledSize(768, 1024, 960));
        assertArrayEquals(new int[]{540, 960},
                MediaFormatPresets.calculateScaledSize(1080, 1920, 960));
        assertArrayEquals(new int[]{540, 960},
                MediaFormatPresets.calculateScaledSize(720, 1280, 960));
    }

    @Test(expected = OutputFormatUnavailableException.class)
    public void throwsWhenTheScaledSideIsNotAnInteger() {
        MediaFormatPresets.calculateScaledSize(1024, 769, 960);
    }
}
