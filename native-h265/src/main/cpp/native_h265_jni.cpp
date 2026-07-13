/*
 * Copyright (C) 2026 pzhangleo
 *
 * SPDX-License-Identifier: GPL-2.0-only
 */
#include <jni.h>

#include <cstring>
#include <vector>

#include <x265.h>

struct NativeEncoder {
    const x265_api *api;
    x265_param *param;
    x265_encoder *encoder;
    x265_picture *picture;
    int width;
    int height;
};

static NativeEncoder *fromHandle(jlong handle) {
    return reinterpret_cast<NativeEncoder *>(handle);
}

static jbyteArray copyNals(JNIEnv *env, x265_nal *nals, uint32_t nalCount) {
    size_t totalSize = 0;
    for (uint32_t i = 0; i < nalCount; ++i) {
        totalSize += nals[i].sizeBytes;
    }

    if (totalSize == 0) {
        return nullptr;
    }

    std::vector<jbyte> output(totalSize);
    size_t offset = 0;
    for (uint32_t i = 0; i < nalCount; ++i) {
        std::memcpy(output.data() + offset, nals[i].payload, nals[i].sizeBytes);
        offset += nals[i].sizeBytes;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Codec_nativeIsAvailable(
        JNIEnv *, jclass) {
    return x265_api_get(0) != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeCreate(
        JNIEnv *, jclass, jint width, jint height, jint bitrate, jint fps) {
    if (width <= 0 || height <= 0 || bitrate <= 0 || fps <= 0) {
        return 0;
    }

    const x265_api *api = x265_api_get(0);
    if (api == nullptr) {
        return 0;
    }

    NativeEncoder *state = new NativeEncoder{api, nullptr, nullptr, nullptr, width, height};
    state->param = api->param_alloc();
    if (state->param == nullptr || api->param_default_preset(state->param, "ultrafast", "zerolatency") < 0) {
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

    if (api->param_apply_profile(state->param, "main") < 0) {
        api->param_free(state->param);
        delete state;
        return 0;
    }

    state->encoder = api->encoder_open(state->param);
    state->picture = api->picture_alloc();
    if (state->encoder == nullptr || state->picture == nullptr) {
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
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_ypresto_androidtranscoder_nativeh265_NativeH265Encoder_nativeEncode(
        JNIEnv *env, jclass, jlong handle, jbyteArray frame, jlong pts) {
    NativeEncoder *state = fromHandle(handle);
    if (state == nullptr || state->encoder == nullptr) {
        return nullptr;
    }

    if (frame != nullptr) {
        const jsize expectedSize = state->width * state->height * 3 / 2;
        if (env->GetArrayLength(frame) != expectedSize) {
            return nullptr;
        }

        jbyte *pixels = env->GetByteArrayElements(frame, nullptr);
        state->picture->planes[0] = pixels;
        state->picture->planes[1] = pixels + state->width * state->height;
        state->picture->planes[2] = pixels + state->width * state->height * 5 / 4;
        state->picture->stride[0] = state->width;
        state->picture->stride[1] = state->width / 2;
        state->picture->stride[2] = state->width / 2;
        state->picture->pts = pts;
    }

    x265_nal *nals = nullptr;
    uint32_t nalCount = 0;
    int result = state->api->encoder_encode(
            state->encoder, &nals, &nalCount, frame == nullptr ? nullptr : state->picture, nullptr);
    if (frame != nullptr) {
        env->ReleaseByteArrayElements(frame, static_cast<jbyte *>(state->picture->planes[0]), JNI_ABORT);
    }
    return result < 0 ? nullptr : copyNals(env, nals, nalCount);
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
