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

Input compatibility ultimately depends on codecs installed on the device. The library is designed around MP4/H.264 input and output; test the target resolutions, bitrates, profiles, and devices used by your app.

## Add The Library

This fork does not publish the historical JCenter artifact. Include the local module, or publish `:lib` to a Maven repository you control.

```groovy
dependencies {
    implementation project(':lib')
}
```

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
- `createExportPreset960x540Strategy()` for a 960x540 export preset.

Returning `null` from a custom strategy's `createVideoOutputFormat()` or `createAudioOutputFormat()` requests pass-through for that track. When every available track is pass-through, the library rejects the request because no transcoding work is needed.

## Example App

The `example` module is a Jetpack Compose demonstration app. It lets you choose a video, monitor progress, cancel a running job, and open the completed output file.

```bash
./gradlew :example:assembleDebug
```

The implementation is in `example/src/main/java/net/ypresto/androidtranscoder/example/TranscoderActivity.kt`.

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
