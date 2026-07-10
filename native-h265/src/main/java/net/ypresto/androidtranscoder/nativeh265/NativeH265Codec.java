/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.ypresto.androidtranscoder.nativeh265;

/**
 * Entry point for the optional GPLv2 native H.265 codec provider.
 *
 * The repository intentionally does not bundle FFmpeg, x265, or another codec
 * implementation. A provider must be linked into this module before native
 * H.265 encoding is enabled.
 */
public final class NativeH265Codec {
    private static final boolean NATIVE_LIBRARY_LOADED = loadNativeLibrary();

    private NativeH265Codec() {
    }

    /**
     * Returns whether the linked native provider can encode H.265.
     */
    public static boolean isAvailable() {
        return NATIVE_LIBRARY_LOADED && nativeIsAvailable();
    }

    private static boolean loadNativeLibrary() {
        try {
            System.loadLibrary("native_h265");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    private static native boolean nativeIsAvailable();
}
