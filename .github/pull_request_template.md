## What changed

<!-- One or two sentences. Say which module: lib / native-h265 / example. -->

## Why

<!-- The failing case or the measurement. "Faster" is not a reason by itself. -->

## Verification

<!-- Tick what you actually ran. Device reports matter for codec changes. -->

- [ ] `./gradlew :lib:testDebugUnitTest :lib:lintDebug`
- [ ] `./gradlew :native-h265:testDebugUnitTest :native-h265:assembleDebug`
- [ ] Output MP4 played on a real device (name the model and Android version)
- [ ] `ffprobe -show_streams -show_packets` checked for duration, key frames and rotation

## Risk

- Affected devices / API levels:
- Licence impact (only if `native-h265` or a new native dependency is touched):
