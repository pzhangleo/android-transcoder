# native-h265

Optional native H.265 integration module for `android-transcoder`. It is licensed separately under
GPL-2.0-only; the main `:lib` module remains Apache 2.0. The module targets API 21 or later because
current Android NDK releases no longer support building native code for API 18.

The module links **x265** (pinned upstream commit, fetched as a SHA-256 verified source archive) and
exposes:

- `NativeH265Codec` — availability probe for the linked x265 build.
- `NativeH265Encoder` — raw I420 to Annex-B HEVC encoding with reusable native buffers.
- `NativeH265Transcoder` — the complete fallback pipeline:
  `MediaExtractor -> MediaCodec (decode) -> x265 (encode) -> MediaMuxer`, audio copied as-is,
  rotation hint preserved, progress reported through a callback.

It does not bundle an H.265 decoder and it does not replace `:lib`'s `MediaCodec` pipeline; call it
explicitly when the device cannot encode HEVC in hardware.

## Build

```bash
./gradlew :native-h265:assembleDebug
```

Requirements: NDK `27.0.12077973` and CMake `3.22.1` (both pinned in `build.gradle`). The x265
source archive is downloaded once and verified against `URL_HASH SHA256`; a `git` checkout is no
longer required (the previous `FetchContent GIT_REPOSITORY` failed on machines or CI images without
`git` or behind a strict proxy).

Networks that cannot reach `github.com` can pre-seed the archive instead:

```bash
curl -L -o native-h265/third_party/x265-07295ba.tar.gz \
  https://github.com/videolan/x265/archive/07295ba7ab551bb9c1580fdaee3200f1b45711b7.tar.gz
shasum -a 256 native-h265/third_party/x265-07295ba.tar.gz
# expected: 1c72293fad0517f2eb24e19c3bc9edd988a7304bac897e372915a6052d7d253e
```

The build prefers that file when it exists, so it is fully offline afterwards. `third_party/` is
git-ignored on purpose: the hash in `CMakeLists.txt` is the source of truth.

ABIs are limited to `arm64-v8a` and `x86_64`. x265 is compiled from source, so every extra ABI adds
build time and about 2 MB of packaged `.so`; 32-bit devices are excluded because a software HEVC
encoder is not usable there and Google Play requires 64-bit.

## Transcode API

Run the synchronous call on a worker thread and keep the input descriptor open until it returns.

```java
NativeH265Transcoder.transcode(
        inputFileDescriptor,
        outputFile.getAbsolutePath(),
        8_000_000,
        progress -> Log.d(TAG, "progress=" + progress));
```

Guarantees:

- Every sample is timestamped. Frames queued before the muxer could start keep their original PTS,
  and frames drained from x265 at the end continue the timeline.
- Sync samples (BLA/IDR/CRA) are flagged as key frames, so the MP4 has a usable sample table and
  seeking works.
- Encoder dimensions come from the decoded `Image` crop rect, so padded resolutions (1088 for a
  1080p stream) and odd sizes no longer cause silent frame drops.
- A failed transcode deletes the partial output file.
- Cancelling is done by interrupting the worker thread; the call throws `InterruptedException`.

## Licensing

This module is GPL-2.0-only. See `LICENSE` and `NOTICE`. Linking x265 into a distributed APK makes
the GPL apply to the whole application, so a closed-source app needs a commercial x265 license from
MulticoreWare, or must use the `MediaCodec` HEVC path in `:lib` instead. x265 and HEVC may impose
additional license and patent obligations; review them before distribution.
