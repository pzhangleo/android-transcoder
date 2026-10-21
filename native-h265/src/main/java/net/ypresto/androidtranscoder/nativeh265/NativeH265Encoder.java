/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.ypresto.androidtranscoder.nativeh265;

/**
 * Low-level x265 encoder for tightly packed 8-bit I420 frames.
 *
 * The returned data is Annex-B HEVC. A null result means that x265 has not produced a packet yet,
 * which is normal because the encoder may buffer frames. An actual failure throws instead of
 * returning null, so a caller can never mistake an error for "please feed me more frames".
 */
public final class NativeH265Encoder implements AutoCloseable {
    private long nativeHandle;

    /**
     * @param width   frame width, must be even
     * @param height  frame height, must be even
     * @param bitrate target bitrate in bits per second
     * @param fps     frame rate numerator
     * @throws IllegalArgumentException when the parameters cannot be represented in I420
     * @throws IllegalStateException    when the native x265 library is unavailable
     */
    public NativeH265Encoder(int width, int height, int bitrate, int fps) {
        if (!NativeH265Codec.isAvailable()) {
            throw new IllegalStateException("x265 native codec is unavailable");
        }
        nativeHandle = nativeCreate(width, height, bitrate, fps);
        if (nativeHandle == 0) {
            throw new IllegalArgumentException(
                    "Unable to create x265 encoder for " + width + "x" + height);
        }
    }

    /**
     * Encodes one I420 frame. The frame size must be {@code width * height * 3 / 2}.
     *
     * @throws IllegalArgumentException when the frame does not match the configured size
     */
    public synchronized byte[] encode(byte[] i420Frame, long presentationTimeUs) {
        checkOpen();
        if (i420Frame == null) {
            throw new NullPointerException("i420Frame");
        }
        return nativeEncode(nativeHandle, i420Frame, presentationTimeUs);
    }

    /** Drains one delayed packet, or returns null when the encoder is empty. */
    public synchronized byte[] drain() {
        checkOpen();
        return nativeEncode(nativeHandle, null, 0);
    }

    /** Number of frames accepted by the encoder so far; useful for diagnostics. */
    public synchronized int framesIn() {
        checkOpen();
        return nativeFramesIn(nativeHandle);
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0) {
            nativeClose(nativeHandle);
            nativeHandle = 0;
        }
    }

    private void checkOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Encoder is closed");
        }
    }

    private static native long nativeCreate(int width, int height, int bitrate, int fps);

    private static native byte[] nativeEncode(long handle, byte[] i420Frame, long pts);

    private static native int nativeFramesIn(long handle);

    private static native void nativeClose(long handle);
}
