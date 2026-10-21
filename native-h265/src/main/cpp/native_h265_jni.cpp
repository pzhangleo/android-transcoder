/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
#include <jni.h>

#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <vector>

#include <x265.h>

#define LOG_TAG "NativeH265"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct NativeEncoder {
    const x265_api *api;
    x265_param *param;
    x265_encoder *encoder;
    x265_picture *picture;
    int width;
    int height;
    int framesIn;
    // Reusable scratch buffers. Allocating a 3 MB I420 buffer and a fresh output array per frame
    // (as the first version did) hammers the allocator at 30 fps for no reason.
    std::vector<uint8_t> yuv;
    std::vector<jbyte> packet;
};

static NativeEncoder *fromHandle(jlong handle) {
    return reinterpret_cast<NativeEncoder *>(handle);
}

/** Copies every NAL unit of the encoder output into one reusable byte array. */
static jbyteArray copyNals(JNIEnv *env, NativeEncoder *state, x265_nal *nals, uint32_t nalCount) {
    size_t totalSize = 0;
    for (uint32_t i = 0; i < nalCount; ++i) {
        totalSize += nals[i].sizeBytes;
    }
    if (totalSize == 0) {
        return nullptr;
    }

    state->packet.resize(totalSize);
    size_t offset = 0;
    for (uint32_t i = 0; i < nalCount; ++i) {
        std::memcpy(state->packet.data() + offset, nals[i].payload, nals[i].sizeBytes);
        offset += nals[i].sizeBytes;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(totalSize));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(totalSize), state->packet.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Codec_nativeIsAvailable(
        JNIEnv *, jclass) {
    return x265_api_get(0) != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeCreate(
        JNIEnv *env, jclass, jint width, jint height, jint bitrate, jint fps) {
    if (width <= 0 || height <= 0 || bitrate <= 0 || fps <= 0) {
        LOGE("nativeCreate: invalid arguments %dx%d @%d bps, %d fps", width, height, bitrate, fps);
        return 0;
    }
    if ((width & 1) != 0 || (height & 1) != 0) {
        // I420 cannot represent odd dimensions. Reject loudly instead of failing later.
        LOGE("nativeCreate: odd dimensions %dx%d are not representable in I420", width, height);
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "x265 requires even width and height for I420 input");
        return 0;
    }

    const x265_api *api = x265_api_get(0);
    if (api == nullptr) {
        LOGE("nativeCreate: x265_api_get returned null");
        return 0;
    }

    NativeEncoder *state = new NativeEncoder{api, nullptr, nullptr, nullptr, width, height, 0, {}, {}};
    state->param = api->param_alloc();
    if (state->param == nullptr || api->param_default_preset(state->param, "ultrafast", "zerolatency") < 0) {
        LOGE("nativeCreate: could not allocate/initialize x265_param");
        delete state;
        return 0;
    }

    state->param->sourceWidth = width;
    state->param->sourceHeight = height;
    state->param->internalCsp = X265_CSP_I420;
    state->param->fpsNum = fps;
    state->param->fpsDenom = 1;
    state->param->rc.bitrate = bitrate / 1000;
    state->param->rc.rateControlMode = X265_RC_ABR;
    state->param->bRepeatHeaders = 1;
    state->param->bAnnexB = 1;
    state->param->bframes = 0;
    state->param->keyframeMax = fps * 3;
    state->param->keyframeMin = fps;
    state->param->logLevel = X265_LOG_ERROR;

    if (api->param_apply_profile(state->param, "main") < 0) {
        LOGE("nativeCreate: param_apply_profile(main) failed");
        api->param_free(state->param);
        delete state;
        return 0;
    }

    state->encoder = api->encoder_open(state->param);
    state->picture = state->encoder != nullptr ? api->picture_alloc() : nullptr;
    if (state->encoder == nullptr || state->picture == nullptr) {
        LOGE("nativeCreate: encoder_open/picture_alloc failed");
        if (state->picture != nullptr) {
            api->picture_free(state->picture);
        }
        if (state->encoder != nullptr) {
            api->encoder_close(state->encoder);
        }
        api->param_free(state->param);
        delete state;
        return 0;
    }
    api->picture_init(state->param, state->picture);
    state->yuv.resize(static_cast<size_t>(width) * height * 3 / 2);
    state->packet.reserve(static_cast<size_t>(width) * height / 2);
    LOGI("x265 encoder created: %dx%d, %d bps, %d fps", width, height, bitrate, fps);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeEncode(
        JNIEnv *env, jclass, jlong handle, jbyteArray frame, jlong pts) {
    NativeEncoder *state = fromHandle(handle);
    if (state == nullptr || state->encoder == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "x265 encoder handle is closed");
        return nullptr;
    }

    if (frame != nullptr) {
        const jsize expectedSize = static_cast<jsize>(state->yuv.size());
        const jsize actualSize = env->GetArrayLength(frame);
        if (actualSize != expectedSize) {
            // The old code returned null here, which the Java layer read as "no packet yet", so
            // every frame was silently dropped and the only symptom was an empty output file.
            LOGE("nativeEncode: frame is %d bytes, expected %d for %dx%d", actualSize, expectedSize,
                 state->width, state->height);
            char message[192];
            snprintf(message, sizeof(message),
                     "I420 frame size mismatch: got %d bytes, expected %d for %dx%d",
                     actualSize, expectedSize, state->width, state->height);
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
            return nullptr;
        }

        env->GetByteArrayRegion(frame, 0, expectedSize,
                                reinterpret_cast<jbyte *>(state->yuv.data()));
        uint8_t *base = state->yuv.data();
        state->picture->planes[0] = base;
        state->picture->planes[1] = base + state->width * state->height;
        state->picture->planes[2] = base + state->width * state->height * 5 / 4;
        state->picture->stride[0] = state->width;
        state->picture->stride[1] = state->width / 2;
        state->picture->stride[2] = state->width / 2;
        state->picture->pts = pts;
        state->framesIn++;
    }

    x265_nal *nals = nullptr;
    uint32_t nalCount = 0;
    int result = state->api->encoder_encode(
            state->encoder, &nals, &nalCount, frame == nullptr ? nullptr : state->picture, nullptr);
    if (result < 0) {
        LOGE("nativeEncode: encoder_encode failed (%d) after %d frames", result, state->framesIn);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "x265 encoder_encode failed");
        return nullptr;
    }
    return copyNals(env, state, nals, nalCount);
}

extern "C" JNIEXPORT jint JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeFramesIn(
        JNIEnv *, jclass, jlong handle) {
    NativeEncoder *state = fromHandle(handle);
    return state == nullptr ? 0 : state->framesIn;
}

extern "C" JNIEXPORT void JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeClose(
        JNIEnv *, jclass, jlong handle) {
    NativeEncoder *state = fromHandle(handle);
    if (state == nullptr) {
        return;
    }
    if (state->picture != nullptr) {
        state->api->picture_free(state->picture);
    }
    if (state->encoder != nullptr) {
        state->api->encoder_close(state->encoder);
    }
    if (state->param != nullptr) {
        state->api->param_free(state->param);
    }
    delete state;
}
