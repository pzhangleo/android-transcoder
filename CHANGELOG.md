## Unreleased — correctness, performance and build hygiene

Native H.265 pipeline (`:native-h265`)
- Every sample now carries `BUFFER_FLAG_KEY_FRAME` for BLA/IDR/CRA pictures. Previously all samples
  were written with `flags = 0`, so the MP4 had no sync samples and seeking/playback could fail.
- Timestamps are preserved. Frames buffered before the muxer could start keep their own PTS, and the
  frames drained from x265 at the end continue the timeline instead of being stamped with 0.
- `Image` objects are closed in a `finally` block; a non-`YUV_420_888` output format now raises a
  descriptive error instead of leaking the decoder buffer pool and stalling.
- Encoder dimensions come from `Image.getCropRect()`, odd sizes are rejected with an exception, and a
  frame-size mismatch throws instead of silently dropping every frame.
- Rotation metadata is copied to the output via `setOrientationHint()`.
- VPS/SPS/PPS are accumulated across packets with a bounded pending buffer, instead of requiring all
  three in one packet and risking unbounded memory growth.
- `csd-0` is written as an Annex-B byte stream again: every parameter set carries its `00 00 00 01`
  start code and the order is always VPS/SPS/PPS. Concatenating the bare payloads left the muxer
  parsing zero parameter sets, so the video track could fail to start.
- An empty end-of-stream buffer from the decoder is treated as a normal end of stream instead of a
  decoder failure. Previously the last `getOutputImage() == null` aborted an already encoded
  transcode and deleted the finished file.
- The partial output file is deleted when transcoding fails.
- Plane copies use bulk row copies and a single reused frame buffer (was one `ByteBuffer.get()` per
  byte plus a fresh 3 MB array per frame).
- JNI writes into a reusable native buffer with `GetByteArrayRegion` instead of
  `GetByteArrayElements`, and reuses its output buffer.
- Progress callbacks are supported through an optional `ProgressListener`.
- Build: x265 is fetched as a hash-verified archive (no `git` clone, supports a pre-seeded local
  copy), `ndkVersion` is pinned, ABIs are limited to `arm64-v8a`/`x86_64`, and debug builds compile
  x265 at `-O3` instead of `-O0`.

Library (`:lib`)
- Removed the per-frame `glFinish()` in the GL frame loop, which serialised decode/render/encode.
- Passing a format strategy that is pass-through for every track now performs a remux instead of
  throwing "No transcoding is necessary".
- Cancelling a `Future` that has not started yet now still delivers exactly one
  `onTranscodeCanceled()`, and the input stream is always closed (fixed a file-descriptor leak).
- Cancellation and completion share one atomic state machine, so `cancel()` is terminal the moment it
  returns: `isDone()` turns true immediately, `get()` throws `CancellationException`, the input is
  closed and exactly one terminal callback fires. A cancelled-but-still-running task can no longer
  report `onTranscodeCompleted()`.
- `cancel(true)` no longer leaks its interrupt into the next job. The worker thread reference used to
  be captured before interrupting, so a task that finished in between could pass the interrupt on to
  the task the same pooled thread had just started, which then failed with a stray
  `InterruptedException`. Interrupting now happens through `WorkerThreadHandle`, which is guarded by
  the same monitor that the worker takes when it detaches, and detaching clears the interrupt status
  so the pooled thread cannot carry it into the next task.
- Progress callbacks are dropped if the task reached a terminal state before the posted callback ran.
  The state was only checked on the worker thread, so a queued progress callback could still run
  after `onTranscodeCanceled()` and overwrite newer UI state.
- A non-baseline H.264 output profile is logged instead of rejected.
- `AndroidBitrateFormatStrategy` never up-scales, scales non-16:9 sources inside a 1280x720 box, and
  no longer carries state between calls. An unknown source bitrate (`KEY_BIT_RATE` missing) now forces
  a re-encode instead of being treated as "already within the target".
- Codecs are released before their EGL surfaces; failures during cleanup no longer throw `Error` from
  a `finally` block.
- `QueuedMuxer` caps its pending buffer at 32 MB instead of growing until OOM.
- Fixed the audio overflow buffer, which could throw `BufferOverflowException` or drop samples.
- Removed dead code (`MediaCodecListCompat`, `Android16By9FormatStrategy`,
  `Android720pFormatStrategyV2`) and the unused `AudioTrackTranscoder.mInputFormat`.

Tests and tooling
- Replaced the broken `androidTest` sources (they did not compile: no test dependencies, removed
  `android.test` APIs) with JVM unit tests covering sizing rules, Annex-B/key-frame parsing and the
  cancel/worker-thread coordination (17 tests in `:lib`, 7 in `:native-h265`).
- `lint` passes for both modules; long log tags fixed.
- GitHub Actions (`ci.yml`) runs three checks on every pull request: wrapper validation, `:lib`
  assemble + unit tests + lint, and a full `:native-h265` + `:example` build with the x265 compile,
  the native unit tests, a `.so` packaging assertion and the debug APK as a downloadable artifact.
- README documents the GPL implications of bundling x265 and the performance trade-offs.

## 0.2.0
- Experimental audio transcoding support. (Thanks @aaron112)
- Fix transcode does not run on Huawei Ascend P7. (Thanks @spiritedRunning)
- Fix race condition caused by not closing output before callback. (Thanks @ryanwilliams83)


## 0.1.10
- `Future` support. (Thanks @MaiKambayashi)

## 0.1.X
- Stability updates. (Thanks @ozyozyo)

## 0.1.0
- First release.
