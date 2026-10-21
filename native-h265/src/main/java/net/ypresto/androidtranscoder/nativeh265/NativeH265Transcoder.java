/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.ypresto.androidtranscoder.nativeh265;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Software HEVC fallback for API 21+. Video is decoded by Android and encoded by x265, audio is
 * copied without re-encoding.
 *
 * <p>The pipeline is synchronous; run it on a worker thread and keep the input descriptor open
 * until the call returns. Pass a {@link ProgressListener} to receive rough progress updates.</p>
 */
public final class NativeH265Transcoder {
    private static final String TAG = "NativeH265Transcoder";
    private static final int TIMEOUT_US = 10_000;
    private static final int MAX_DRAIN_LOOPS = 500;
    /**
     * x265 emits VPS/SPS/PPS before the first frame. If that does not happen within this many
     * samples something is badly wrong, so fail instead of buffering the whole movie in RAM.
     */
    private static final int MAX_PENDING_SAMPLES = 120;
    private static final String MIME_HEVC = "video/hevc";

    // HEVC NAL unit types that carry parameter sets.
    private static final int NAL_VPS = 32;
    private static final int NAL_SPS = 33;
    private static final int NAL_PPS = 34;
    /** Annex-B start code, required in front of every NAL unit that goes into {@code csd-0}. */
    private static final byte[] START_CODE = {0, 0, 0, 1};

    private NativeH265Transcoder() {
    }

    /** Rough progress callback, invoked on the calling thread. */
    public interface ProgressListener {
        /**
         * @param progress value in [0.0, 1.0]; 1.0 is reported once the video track is written.
         */
        void onProgress(double progress);
    }

    public static void transcode(FileDescriptor input, String outputPath, int bitrate)
            throws IOException, InterruptedException {
        transcode(input, outputPath, bitrate, null);
    }

    public static void transcode(FileDescriptor input, String outputPath, int bitrate,
                                 ProgressListener listener)
            throws IOException, InterruptedException {
        if (!NativeH265Codec.isAvailable()) {
            throw new IOException("x265 native codec is unavailable");
        }
        if (outputPath == null) {
            throw new NullPointerException("outputPath cannot be null");
        }

        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaMuxer muxer = null;
        VideoOutput output = null;
        boolean completed = false;
        try {
            int rotation = 0;
            long durationUs = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(input);
                rotation = readIntMetadata(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
                durationUs = readIntMetadata(retriever,
                        MediaMetadataRetriever.METADATA_KEY_DURATION, 0) * 1000L;
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not read input metadata; continuing without rotation/duration.", e);
            } finally {
                retriever.release();
            }

            videoExtractor.setDataSource(input);
            audioExtractor.setDataSource(input);
            int videoTrack = selectTrack(videoExtractor, true);
            if (videoTrack < 0) {
                throw new IOException("No video track found");
            }
            videoExtractor.selectTrack(videoTrack);

            MediaFormat inputFormat = videoExtractor.getTrackFormat(videoTrack);
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            frameRate = Math.max(1, Math.min(frameRate, 60));

            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) {
                // Portrait clips are stored landscape plus a rotation hint. Without writing it the
                // output plays sideways, because the native path re-encodes raw pixels.
                muxer.setOrientationHint(rotation);
            }

            int audioTrack = selectTrack(audioExtractor, false);
            if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack);
            }

            output = new VideoOutput(muxer, frameRate);
            if (audioTrack >= 0) {
                output.audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            }

            decodeAndEncode(videoExtractor, decoder, output, bitrate, frameRate, durationUs, listener);
            if (!output.started) {
                throw new IOException("x265 produced no video output");
            }
            copyAudio(audioExtractor, output, audioTrack);
            if (listener != null) {
                listener.onProgress(1.0);
            }
            completed = true;
        } finally {
            // Release order matters: codec first, then the muxer, then the extractors.
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                }
                decoder.release();
            }
            if (output != null) {
                output.finish();
            }
            if (muxer != null) {
                muxer.release();
            }
            videoExtractor.release();
            audioExtractor.release();
            if (!completed) {
                // A failed transcode must not leave a 0-byte or truncated MP4 behind: the caller
                // would otherwise see "transcode failed" next to a file that looks valid.
                deleteQuietly(outputPath);
            }
        }
    }

    private static int readIntMetadata(MediaMetadataRetriever retriever, int key, int fallback) {
        String value = retriever.extractMetadata(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void deleteQuietly(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete partial output file: " + path);
        }
    }

    private static void decodeAndEncode(MediaExtractor extractor, MediaCodec decoder,
                                        VideoOutput output, int bitrate, int frameRate,
                                        long durationUs, ProgressListener listener)
            throws IOException, InterruptedException {
        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        NativeH265Encoder encoder = null;
        byte[] i420 = null;
        long lastPtsUs = 0;
        long frameDurationUs = 1_000_000L / Math.max(1, frameRate);
        int decodedFrames = 0;

        try {
            while (!outputDone) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("HEVC transcode cancelled");
                }
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex < 0) {
                    // INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED
                    continue;
                }

                boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                Image image = null;
                try {
                    image = decoder.getOutputImage(outputIndex);
                    if (image == null) {
                        // A codec may emit a separate, empty EOS buffer after the last frame. That
                        // is a normal end of stream, not a decoder failure: failing here threw away
                        // an already completed transcode and deleted the output file.
                        if (endOfStream) {
                            outputDone = true;
                            continue;
                        }
                        throw new IOException("Decoder returned no output Image. The device may only"
                                + " support surface output for " + decoder.getName());
                    }
                    if (image.getFormat() != ImageFormat.YUV_420_888) {
                        throw new IOException("Unsupported decoder output color format 0x"
                                + Integer.toHexString(image.getFormat())
                                + "; YUV_420_888 (flexible YUV) is required");
                    }
                    Rect crop = image.getCropRect();
                    if (crop == null) {
                        crop = new Rect(0, 0, image.getWidth(), image.getHeight());
                    }
                    // I420 needs even dimensions. The crop rect is authoritative because decoders
                    // pad the coded size (e.g. 1088 for a 1080p stream).
                    int width = crop.width() & ~1;
                    int height = crop.height() & ~1;
                    if (width <= 0 || height <= 0) {
                        throw new IOException("Invalid crop rect " + crop);
                    }
                    int frameSize = width * height * 3 / 2;
                    if (i420 == null || i420.length != frameSize) {
                        i420 = new byte[frameSize];
                    }
                    if (encoder == null) {
                        encoder = new NativeH265Encoder(width, height, bitrate, frameRate);
                        output.setVideoSize(width, height);
                    }
                    toI420(image, crop, width, height, i420);
                    long ptsUs = info.presentationTimeUs;
                    byte[] packet = encoder.encode(i420, ptsUs);
                    if (packet != null) {
                        output.writeVideo(packet, ptsUs);
                    }
                    lastPtsUs = ptsUs;
                    decodedFrames++;
                    if (listener != null && durationUs > 0) {
                        listener.onProgress(Math.min(1.0, (double) ptsUs / durationUs));
                    }
                } finally {
                    // Closing the Image is mandatory: without it the decoder's buffer pool is
                    // exhausted after a few dozen frames and decoding stalls forever.
                    if (image != null) {
                        image.close();
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
                if (endOfStream) {
                    outputDone = true;
                }
            }

            if (decodedFrames == 0) {
                throw new IOException("Decoder produced no YUV frames");
            }

            // Drain frames still buffered inside x265. Their timestamps must continue the timeline;
            // writing 0 for all of them (the previous behaviour) corrupts the end of the file.
            long drainPtsUs = lastPtsUs;
            for (int i = 0; i < MAX_DRAIN_LOOPS; i++) {
                byte[] packet = encoder.drain();
                if (packet == null) {
                    break;
                }
                drainPtsUs += frameDurationUs;
                output.writeVideo(packet, drainPtsUs);
            }
        } finally {
            if (encoder != null) {
                encoder.close();
            }
        }
    }

    private static void copyAudio(MediaExtractor extractor, VideoOutput output, int track)
            throws IOException {
        if (track < 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (Thread.interrupted()) {
                throw new IOException("HEVC transcode cancelled during audio copy");
            }
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                return;
            }
            buffer.position(0);
            buffer.limit(size);
            info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
            output.muxer.writeSampleData(output.audioTrack, buffer, info);
            extractor.advance();
        }
    }

    private static int selectTrack(MediaExtractor extractor, boolean video) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(video ? "video/" : "audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Copies one plane of a YUV_420_888 {@link Image} into a tightly packed I420 buffer.
     * Uses a bulk row copy whenever the plane is not interleaved, which is the usual case.
     */
    private static void copyPlane(ByteBuffer planeBuffer, int rowStride, int pixelStride,
                                  int srcLeft, int srcTop, int width, int height,
                                  byte[] dst, int dstOffset) {
        ByteBuffer src = planeBuffer.duplicate();
        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                src.position((srcTop + row) * rowStride + srcLeft);
                src.get(dst, dstOffset + row * width, width);
            }
        } else {
            for (int row = 0; row < height; row++) {
                int srcRowStart = (srcTop + row) * rowStride + srcLeft * pixelStride;
                int dstRowStart = dstOffset + row * width;
                for (int col = 0; col < width; col++) {
                    dst[dstRowStart + col] = src.get(srcRowStart + col * pixelStride);
                }
            }
        }
    }

    private static void toI420(Image image, Rect crop, int width, int height, byte[] dst) {
        Image.Plane[] planes = image.getPlanes();
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        copyPlane(planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                crop.left, crop.top, width, height, dst, 0);
        copyPlane(planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                crop.left / 2, crop.top / 2, uvWidth, uvHeight, dst, width * height);
        copyPlane(planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(),
                crop.left / 2, crop.top / 2, uvWidth, uvHeight, dst,
                width * height + uvWidth * uvHeight);
    }

    /**
     * Builds {@code csd-0} for the HEVC track. Android expects an Annex-B byte stream there: every
     * parameter set prefixed with 00 00 00 01, exactly as in the elementary stream. Concatenating
     * the bare NAL payloads (the previous behaviour) leaves the muxer parsing zero parameter sets,
     * so the video track never starts and the MP4 is unusable.
     *
     * @param parameterSets VPS, SPS and PPS payloads in that order; {@code null} entries are skipped.
     */
    static byte[] toAnnexBParameterSets(byte[][] parameterSets) {
        int size = 0;
        for (byte[] nal : parameterSets) {
            if (nal != null) {
                size += START_CODE.length + nal.length;
            }
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] nal : parameterSets) {
            if (nal == null) continue;
            System.arraycopy(START_CODE, 0, result, offset, START_CODE.length);
            offset += START_CODE.length;
            System.arraycopy(nal, 0, result, offset, nal.length);
            offset += nal.length;
        }
        return result;
    }

    /** Splits an Annex-B buffer into NAL units as {@code {offset, length}} pairs. */
    static List<int[]> splitNals(byte[] data) {
        List<int[]> nals = new ArrayList<>();
        int startCode = indexOfStartCode(data, 0);
        while (startCode >= 0) {
            int nalStart = startCode + 3;
            int nextStartCode = indexOfStartCode(data, nalStart);
            int nalEnd = nextStartCode < 0 ? data.length : nextStartCode;
            // A 4-byte start code (00 00 00 01) is matched at its second zero, so that extra zero
            // belongs to the next start code rather than to this NAL unit.
            if (nextStartCode > nalStart && data[nextStartCode - 1] == 0) {
                nalEnd = nextStartCode - 1;
            }
            if (nalEnd > nalStart) {
                nals.add(new int[]{nalStart, nalEnd - nalStart});
            }
            startCode = nextStartCode;
        }
        return nals;
    }

    private static int indexOfStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int nalType(byte[] data, int[] nal) {
        return (data[nal[0]] & 0x7e) >> 1;
    }

    /**
     * True for BLA/IDR/CRA pictures. Those must be flagged as sync samples, otherwise the MP4
     * contains no key frames at all and players cannot seek, or even start decoding reliably.
     */
    static boolean isKeyFrame(byte[] packet) {
        for (int[] nal : splitNals(packet)) {
            int type = nalType(packet, nal);
            if (type >= 16 && type <= 21) {
                return true;
            }
        }
        return false;
    }

    private static final class PendingSample {
        private final byte[] data;
        private final long ptsUs;

        private PendingSample(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    private static final class VideoOutput {
        private static final int MAX_PENDING_BYTES = 64 * 1024 * 1024;

        private final MediaMuxer muxer;
        private final int frameRate;
        private final List<PendingSample> pending = new ArrayList<>();
        /** VPS, SPS and PPS payloads, indexed by {@code type - NAL_VPS} so csd-0 keeps that order. */
        private final byte[][] parameterSets = new byte[3][];
        private int parameterSetCount;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        private int width;
        private int height;
        private long pendingBytes;
        private int audioTrack = -1;
        private int videoTrack = -1;
        private boolean started;

        private VideoOutput(MediaMuxer muxer, int frameRate) {
            this.muxer = muxer;
            this.frameRate = frameRate;
        }

        private void setVideoSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void writeVideo(byte[] packet, long ptsUs) throws IOException {
            if (!started) {
                collectParameterSets(packet);
                if (parameterSetCount < 3) {
                    if (pending.size() >= MAX_PENDING_SAMPLES || pendingBytes >= MAX_PENDING_BYTES) {
                        throw new IOException("x265 did not emit VPS/SPS/PPS within "
                                + pending.size() + " samples; refusing to buffer more");
                    }
                    pending.add(new PendingSample(packet, ptsUs));
                    pendingBytes += packet.length;
                    return;
                }
                startMuxer();
                flushPending();
            }
            writeSample(packet, ptsUs);
        }

        /** Parameter sets may be spread over several packets, so accumulate them by NAL type. */
        private void collectParameterSets(byte[] packet) {
            for (int[] nal : splitNals(packet)) {
                int type = nalType(packet, nal);
                int slot = type - NAL_VPS;
                if (slot < 0 || slot > 2 || parameterSets[slot] != null) {
                    continue;
                }
                // Keep the payload only; the start code is put back when csd-0 is assembled.
                byte[] copy = new byte[nal[1]];
                System.arraycopy(packet, nal[0], copy, 0, nal[1]);
                parameterSets[slot] = copy;
                parameterSetCount++;
            }
        }

        private void startMuxer() {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_HEVC, width, height);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(toAnnexBParameterSets(parameterSets)));
            videoTrack = muxer.addTrack(format);
            muxer.start();
            started = true;
            Log.i(TAG, "Muxer started: " + width + "x" + height + ", "
                    + pending.size() + " pending sample(s)");
        }

        private void flushPending() {
            for (PendingSample sample : pending) {
                writeSample(sample.data, sample.ptsUs);
            }
            pending.clear();
            pendingBytes = 0;
        }

        private void writeSample(byte[] packet, long ptsUs) {
            int flags = isKeyFrame(packet) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            bufferInfo.set(0, packet.length, ptsUs, flags);
            muxer.writeSampleData(videoTrack, ByteBuffer.wrap(packet), bufferInfo);
        }

        /** Stops the muxer when it was actually started; safe to call on any path. */
        private void finish() {
            if (!started) {
                return;
            }
            started = false;
            try {
                muxer.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to stop the muxer.", e);
            }
        }
    }
}
