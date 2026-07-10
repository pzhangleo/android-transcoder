/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Codec_nativeIsAvailable(
        JNIEnv *, jclass) {
    // A real GPLv2 codec provider must replace this module's stub implementation.
    return JNI_FALSE;
}
