/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.ypresto.androidtranscoder.nativeh265;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Software HEVC fallback for API 21+. Video is decoded by Android and encoded
 * by x265. Audio is copied without re-encoding.
 */
public final class NativeH265Transcoder {
    private static final int TIMEOUT_US = 10_000;
    private static final int MAX_DRAIN_LOOPS = 100;

    private NativeH265Transcoder() {
    }

    public static void transcode(FileDescriptor input, String outputPath, int bitrate)
            throws IOException, InterruptedException {
        if (!NativeH265Codec.isAvailable()) {
            throw new IOException("x265 native codec is unavailable");
        }

        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaCodec decoder = null;
        NativeH265Encoder encoder = null;
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(input);
            audioExtractor.setDataSource(input);
            int videoTrack = selectTrack(videoExtractor, true);
            if (videoTrack < 0) {
                throw new IOException("No video track found");
            }
            videoExtractor.selectTrack(videoTrack);

            MediaFormat inputFormat = videoExtractor.getTrackFormat(videoTrack);
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            frameRate = Math.max(1, Math.min(frameRate, 60));

            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            encoder = new NativeH265Encoder(width, height, bitrate, frameRate);
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int audioTrack = selectTrack(audioExtractor, false);
            if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack);
            }

            VideoOutput output = new VideoOutput(muxer, width, height, frameRate);
            if (audioTrack >= 0) {
                output.audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            }

            decodeAndEncode(videoExtractor, decoder, encoder, output);
            if (!output.started) {
                throw new IOException("x265 produced no video output");
            }
            copyAudio(audioExtractor, output, audioTrack);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                }
                decoder.release();
            }
            if (encoder != null) {
                encoder.close();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static void decodeAndEncode(MediaExtractor extractor, MediaCodec decoder,
                                        NativeH265Encoder encoder, VideoOutput output)
            throws IOException, InterruptedException {
        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frameIndex = 0;

        while (!outputDone) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
                    int size = extractor.readSampleData(buffer, 0);
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
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            Image image = Build.VERSION.SDK_INT >= 21 ? decoder.getOutputImage(outputIndex) : null;
            if (image != null && image.getFormat() == ImageFormat.YUV_420_888) {
                byte[] i420 = toI420(image);
                byte[] packet = encoder.encode(i420, info.presentationTimeUs);
                if (packet != null) {
                    output.writeVideo(packet, info.presentationTimeUs);
                }
                image.close();
                frameIndex++;
            }
            decoder.releaseOutputBuffer(outputIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputDone = true;
            }
        }

        for (int i = 0; i < MAX_DRAIN_LOOPS; i++) {
            byte[] packet = encoder.drain();
            if (packet == null) {
                break;
            }
            output.writeVideo(packet, 0);
        }
        if (frameIndex == 0) {
            throw new IOException("Decoder produced no YUV frames");
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

    private static byte[] toI420(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] result = new byte[width * height * 3 / 2];
        copyPlane(image.getPlanes()[0], width, height, result, 0, 1);
        copyPlane(image.getPlanes()[1], width / 2, height / 2, result, width * height, 1);
        copyPlane(image.getPlanes()[2], width / 2, height / 2, result,
                width * height + width * height / 4, 1);
        return result;
    }

    private static void copyPlane(Image.Plane plane, int width, int height,
                                  byte[] output, int outputOffset, int outputPixelStride) {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowStride;
            for (int col = 0; col < width; col++) {
                int source = rowOffset + col * pixelStride;
                output[outputOffset + row * width * outputPixelStride + col * outputPixelStride]
                        = buffer.get(source);
            }
        }
    }

    private static final class VideoOutput {
        private final MediaMuxer muxer;
        private final int width;
        private final int height;
        private final int frameRate;
        private final List<byte[]> pendingPackets = new ArrayList<>();
        private int audioTrack = -1;
        private int videoTrack = -1;
        private boolean started;

        private VideoOutput(MediaMuxer muxer, int width, int height, int frameRate) {
            this.muxer = muxer;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }

        private void writeVideo(byte[] packet, long pts) throws IOException {
            if (!started) {
                pendingPackets.add(packet);
                List<byte[]> parameterSets = findParameterSets(packet);
                if (parameterSets.size() >= 3) {
                    MediaFormat format = MediaFormat.createVideoFormat("video/hevc", width, height);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(join(parameterSets)));
                    videoTrack = muxer.addTrack(format);
                    muxer.start();
                    started = true;
                    for (byte[] pending : pendingPackets) {
                        writeAnnexB(pending, 0);
                    }
                    pendingPackets.clear();
                }
                return;
            }
            writeAnnexB(packet, pts);
        }

        private void writeAnnexB(byte[] packet, long pts) {
            ByteBuffer buffer = ByteBuffer.wrap(packet);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, packet.length, pts, 0);
            muxer.writeSampleData(videoTrack, buffer, info);
        }

        private static List<byte[]> findParameterSets(byte[] packet) {
            List<byte[]> result = new ArrayList<>();
            int start = 0;
            while (start < packet.length) {
                int next = findStartCode(packet, start + 3);
                int end = next < 0 ? packet.length : next;
                int code = packet[start + 2] == 1 ? 3 : 4;
                if (start + code < end) {
                    int type = (packet[start + code] & 0x7e) >> 1;
                    if (type == 32 || type == 33 || type == 34) {
                        byte[] nal = new byte[end - start];
                        System.arraycopy(packet, start, nal, 0, nal.length);
                        result.add(nal);
                    }
                }
                start = next < 0 ? packet.length : next;
            }
            return result;
        }

        private static int findStartCode(byte[] data, int offset) {
            for (int i = offset; i + 3 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0
                        && (data[i + 2] == 1 || (data[i + 2] == 0 && data[i + 3] == 1))) {
                    return i;
                }
            }
            return -1;
        }

        private static byte[] join(List<byte[]> parts) {
            int size = 0;
            for (byte[] part : parts) {
                size += part.length;
            }
            byte[] result = new byte[size];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, result, offset, part.length);
                offset += part.length;
            }
            return result;
        }
    }
}
