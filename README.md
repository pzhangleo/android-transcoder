# Android Transcoder

A hardware-accelerated MP4 video transcoder for Android, implemented in Java on top of `MediaExtractor`, `MediaCodec`, and `MediaMuxer`. It is intended for apps that need on-device H.264 video conversion without bundling a native FFmpeg binary.

## Capabilities

- Transcodes the first video track with Android hardware codecs when available.
- Copies the audio track by default, with experimental audio transcoding support in selected strategies.
- Supports video-only input files as well as video files with audio.
- Reports progress, completion, cancellation, and failures through one listener.
- Produces an MP4 file at a caller-provided output path.

## Requirements

The library requires Android API 18 (Android 4.3) or later. The Compose example app requires API 21 or later, but this does not change the library module's API 18 minimum.

To build the repository, use JDK 17 or later, Android SDK 35, and the Gradle
wrapper included in this repository (Gradle 8.9). Android Studio's embedded JDK
17 is sufficient.

Input compatibility ultimately depends on codecs installed on the device. The library is designed around MP4/H.264 input and output; test the target resolutions, bitrates, profiles, and devices used by your app.

## Add The Library

This fork does not publish the historical JCenter artifact. Include the local module, or publish `:lib` to a Maven repository you control.

```groovy
dependencies {
    implementation project(':lib')
}
```

If the project is consumed from a separate Gradle build, copy or publish the
`:lib` module and add it as a normal Android library dependency. Do not add
`:native-h265` unless the GPLv2 licensing and native codec requirements are
acceptable for the application.

## Transcode A Video

Open the source through a `ContentResolver`, create an output file, and retain the returned `Future` when the caller needs cancellation. Close the `ParcelFileDescriptor` in every terminal callback; the transcoder reads from its `FileDescriptor` asynchronously.

```java
final ParcelFileDescriptor input = getContentResolver()
        .openFileDescriptor(inputUri, "r");
final File outputFile = new File(getExternalFilesDir(null), "transcoded.mp4");

Future<Void> future = MediaTranscoder.getInstance().transcodeVideo(
        input.getFileDescriptor(),
        outputFile.getAbsolutePath(),
        MediaFormatStrategyPresets.createAndroid720pStrategy(),
        new MediaTranscoder.Listener() {
            @Override
            public void onTranscodeProgress(double progress) {
                // progress is in [0.0, 1.0]; a negative value means unknown.
            }

            @Override
            public void onTranscodeCompleted() {
                closeInput();
                // The output file is ready to share or play.
            }

            @Override
            public void onTranscodeCanceled() {
                closeInput();
            }

            @Override
            public void onTranscodeFailed(Exception exception) {
                closeInput();
                // Report the error and remove any partial output if appropriate.
            }

            private void closeInput() {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        });
```

Call `future.cancel(true)` to request cancellation. The listener reports cancellation through `onTranscodeCanceled()`.

To open an output file outside your app on Android 7.0 and later, use a `FileProvider` content URI and grant read permission instead of using `Uri.fromFile()`.

## Format Strategies

`MediaFormatStrategyPresets` provides ready-made strategies:

- `createAndroid720pStrategy()` or `createAndroid720pStrategy(int bitrate)` for 720p-oriented H.264 output.
- `createAndroidBitrateFormatStrategy(int bitrate)` for bitrate-oriented output.
- `createAndroidH265BitrateFormatStrategy(int bitrate)` for H.265/HEVC output on Android API 24 and later.
- `createExportPreset960x540Strategy()` for a 960x540 export preset.

Returning `null` from a custom strategy's `createVideoOutputFormat()` or `createAudioOutputFormat()` requests pass-through for that track. When every available track is pass-through, the library rejects the request because no transcoding work is needed.

### H.265 Compatibility

`createAndroidH265BitrateFormatStrategy()` uses Android's `MediaCodec` encoder
and requests an HEVC video track. It is hardware/vendor codec support, not a
software fallback. The strategy requires API 24 or later because the output is
an HEVC track in an MP4 muxer. Devices may still reject HEVC because the
required encoder or profile is unavailable; handle `onTranscodeFailed()` and
fall back to H.264 when the target device cannot encode HEVC.

The optional `:native-h265` module is a separate integration boundary and is not
automatically used by `:lib`. Its current implementation is a buildable JNI
stub, so `NativeH265Codec.isAvailable()` remains `false` until a real codec
provider is linked. Adding FFmpeg, x265, or another provider requires separate
source, binary, patent, and license review.

## Example App

The `example` module is a Jetpack Compose demonstration app. It lets you choose a video, monitor progress, cancel a running job, and open the completed output file.

```bash
./gradlew :example:assembleDebug
```

The implementation is in `example/src/main/java/net/ypresto/androidtranscoder/example/TranscoderActivity.kt`.

The example's H.265 option is disabled below API 24. On unsupported devices,
choose H.264 or provide an application-specific native codec integration.

## Project Layout

- `lib`: Apache 2.0 Android library, minimum API 18.
- `example`: Jetpack Compose demonstration app, minimum API 21.
- `native-h265`: optional GPL-2.0-only JNI/CMake module, minimum API 21.

## Optional Native H.265 Module

The `native-h265` module is a separate GPL-2.0-only integration boundary targeting API 21+. The main `:lib` module remains Apache 2.0. The native module currently contains a JNI stub only; it does not bundle FFmpeg, x265, libde265, or prebuilt codec binaries. Add the chosen codec provider and its complete license/source notices before enabling software H.265 encoding.

```bash
./gradlew :native-h265:assembleDebug
```

The module is intentionally independent from the Apache-2.0 library. It must
not be described or distributed as part of `:lib` without preserving this
license boundary and the notices for every native dependency.

## Limitations

- Hardware codec availability and behavior vary by device. Validate the codecs and parameters required by your product on real devices.
- The output MP4 is not optimized for progressive network playback. Use a fast-start tool if the `moov` atom must be placed at the beginning of the file.
- Encrypted media, streaming output, and arbitrary camera/container formats are not supported.
- Codec and muxer failures can surface as `RuntimeException`; handle the failure callback and clean up partial output files.

## Further Reading

- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Grafika](https://github.com/google/grafika)
- [Original implementation notes (Japanese)](https://qiita.com/yuya_presto/items/d48e29c89109b746d000)

## License

The `lib` module and the existing project code are licensed under the Apache
License 2.0. See the repository `LICENSE` and `NOTICE` files for the complete
terms and attribution notices.

The independent `native-h265` module is licensed under GPL-2.0-only. See
`native-h265/LICENSE`, `native-h265/NOTICE`, and `native-h265/README.md`. Codec
providers added to that module may impose additional license and patent
obligations.

```
Copyright (C) 2014-2016 Yuya Tanaka

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
