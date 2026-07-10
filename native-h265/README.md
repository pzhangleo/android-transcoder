# native-h265

This is an optional native H.265 integration module for `android-transcoder`.
It is licensed separately under GPL-2.0-only. The main `:lib` module remains Apache 2.0.
The native module targets API 21 or later because current Android NDK releases no
longer support building native code for API 18.

The repository contains only the JNI boundary and a buildable stub. It does not
bundle FFmpeg, x265, libde265, or prebuilt native codec binaries. A codec provider
must be added under this module together with its exact source, build configuration,
license files, and third-party notices before enabling native H.265 encoding.

`NativeH265Codec.isAvailable()` returns `false` until such a provider is linked.
This prevents the main library from silently claiming software H.265 support that
is not actually present in the APK.

## Build

```bash
./gradlew :native-h265:assembleDebug
```

## Licensing

This module is GPL-2.0-only. See `LICENSE` and `NOTICE`. Codec providers added to
the module may impose additional license and patent obligations.
