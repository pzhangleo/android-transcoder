package net.ypresto.androidtranscoder.nativeh265;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Tests for the Annex-B parsing that drives parameter-set collection and the MP4 sync-sample flag.
 * Both were broken before: every sample was written with flags=0, so the output MP4 carried no
 * key frame at all.
 */
public class NativeH265TranscoderTest {

    /** Builds one HEVC NAL unit: 2-byte header with the given type, then payload bytes. */
    private static byte[] nal(int type, int payloadLength) {
        byte[] nal = new byte[2 + payloadLength];
        nal[0] = (byte) ((type << 1) & 0x7e);
        nal[1] = 0x01;
        for (int i = 0; i < payloadLength; i++) {
            nal[2 + i] = (byte) (0x10 + i);
        }
        return nal;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static final byte[] START_CODE_3 = {0x00, 0x00, 0x01};
    private static final byte[] START_CODE_4 = {0x00, 0x00, 0x00, 0x01};

    @Test
    public void splitsMixedStartCodes() {
        byte[] stream = concat(START_CODE_4, nal(32, 3), START_CODE_3, nal(33, 5),
                START_CODE_4, nal(34, 2));
        List<int[]> nals = NativeH265Transcoder.splitNals(stream);
        assertEquals(3, nals.size());
        assertEquals(5, nals.get(0)[1]);   // 2-byte NAL header + 3 payload bytes
        assertEquals(7, nals.get(1)[1]);   // no stray zero left over from the next 4-byte start code
        assertEquals(4, nals.get(2)[1]);
    }

    @Test
    public void detectsParameterSetTypes() {
        byte[] stream = concat(START_CODE_4, nal(32, 1), START_CODE_3, nal(33, 1),
                START_CODE_3, nal(34, 1));
        List<int[]> nals = NativeH265Transcoder.splitNals(stream);
        assertEquals(3, nals.size());
        // VPS/SPS/PPS are 32/33/34 and must not be mistaken for key frames.
        for (int[] n : nals) {
            int type = (stream[n[0]] & 0x7e) >> 1;
            assertTrue("type " + type, type >= 32 && type <= 34);
        }
    }

    @Test
    public void idrIsAKeyFrame() {
        // 19 = IDR_W_RADL, 20 = IDR_N_LP, 21 = CRA
        for (int type : new int[]{19, 20, 21}) {
            byte[] packet = concat(START_CODE_4, nal(32, 1), START_CODE_3, nal(33, 1),
                    START_CODE_3, nal(34, 1), START_CODE_4, nal(type, 8));
            assertTrue("type " + type, NativeH265Transcoder.isKeyFrame(packet));
        }
    }

    @Test
    public void trailFrameIsNotAKeyFrame() {
        // 1 = TRAIL_R, 0 = TRAIL_N
        for (int type : new int[]{0, 1}) {
            byte[] packet = concat(START_CODE_4, nal(type, 8));
            assertFalse("type " + type, NativeH265Transcoder.isKeyFrame(packet));
        }
    }

    @Test
    public void handlesEmptyAndGarbageInput() {
        assertTrue(NativeH265Transcoder.splitNals(new byte[0]).isEmpty());
        assertTrue(NativeH265Transcoder.splitNals(new byte[]{1, 2, 3, 4}).isEmpty());
        assertFalse(NativeH265Transcoder.isKeyFrame(new byte[0]));
    }
}
