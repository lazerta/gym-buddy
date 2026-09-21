# FrameSource contract

This package is intentionally framework-independent. The Android app should
adapt both real CameraX frames and simulator frames into the same
`FrameSource<T>` abstraction.

```text
CameraXFrameSource<MPImage> ─┐
                            ├─> FrameAnalysisLoop<MPImage, GymBuddyResult>
SimulatorFrameSource<MPImage>┘
                                      │
                                      ▼
                                MediaPipe Pose
                                      │
                                      ▼
                            deterministic biomechanics
```

## Camera path

The Android implementation should wrap CameraX `ImageAnalysis` and construct
the same image type used by the MediaPipe analyzer (normally `MPImage`).
Camera timestamps become `FramePacket.timestampUs`.

## Simulator path

`SimulatorFrameSource` consumes the harness protocol:

- `GET /v1/session`
- `GET /v1/frames/{frame_id}`
- `POST /v1/results`

The app **must not** use `/v1/ground-truth/{frame_id}`. That endpoint belongs
to the harness/oracle side only.

A concrete Android transport can use OkHttp/Retrofit (or another HTTP client)
to implement `SimulatorTransport`; a `FrameDecoder<MPImage>` can decode JPEG
bytes and build the MediaPipe image object.

The same `FrameAnalyzer<MPImage, Result>` must be used for CameraX and
simulator input.

## Timestamp rule

`timestampUs` is authoritative and must be echoed unchanged in the POST result
envelope. Convert to milliseconds only at the MediaPipe API boundary with
`frame.mediaPipeTimestampMs`.

The harness rejects mismatched timestamps.
