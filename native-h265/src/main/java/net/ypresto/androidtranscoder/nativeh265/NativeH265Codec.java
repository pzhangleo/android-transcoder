/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.ypresto.androidtranscoder.nativeh265;

/**
 * Entry point for the optional GPLv2 native H.265 codec provider.
 *
 * This module links the GPLv2 x265 encoder. It is intentionally separate from
 * the Apache-2.0 library module.
 */
public final class NativeH265Codec {
    private static final boolean NATIVE_LIBRARY_LOADED = loadNativeLibrary();

    private NativeH265Codec() {
    }

    /**
     * Returns whether the x265 encoder is linked and loadable.
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
