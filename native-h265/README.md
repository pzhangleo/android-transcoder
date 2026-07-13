# native-h265

This is an optional native H.265 integration module for `android-transcoder`.
It is licensed separately under GPL-2.0-only. The main `:lib` module remains Apache 2.0.
The native module targets API 21 or later because current Android NDK releases no
longer support building native code for API 18.

The module links x265 3.4 from its pinned upstream commit during the native build.
It exposes codec availability through `NativeH265Codec` and raw I420 encoding
through `NativeH265Encoder`; the full
MediaExtractor/MediaMuxer fallback pipeline is being connected separately.
The module does not bundle FFmpeg or an H.265 decoder. Android `MediaCodec` is
used for input decoding until a software decoder is required.

## Build

```bash
./gradlew :native-h265:assembleDebug
```

The first native build downloads x265 from its upstream Git repository. A
network connection is therefore required unless the Gradle/CMake source cache
has already been populated.

## Transcode API

Add this module as a dependency and run the synchronous operation on a worker
thread. Keep the input descriptor open until the call returns.

```java
NativeH265Transcoder.transcode(
        inputFileDescriptor,
        outputFile.getAbsolutePath(),
        8_000_000);
```

The implementation decodes the first video track with Android `MediaCodec`,
encodes I420 frames with x265, copies the first audio track, and writes an MP4
with `MediaMuxer`. It currently targets 8-bit YUV420 input and does not report
progress callbacks.

## Licensing

This module is GPL-2.0-only. See `LICENSE` and `NOTICE`. x265 and HEVC may impose
additional license and patent obligations; review them before distribution.
