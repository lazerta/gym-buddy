# gym-buddy

Private-use, local-first Android biomechanics coach.

## Principles

- **On-device first:** workout monitoring must work in airplane mode.
- **ML for perception; code for reasoning:** MediaPipe produces landmarks; deterministic Kotlin code analyzes movement and form.
- **No backend required.**
- **No LLM required.**
- **No login or multi-user system.**
- **No camera/video upload.**
- **Structured movement telemetry is the source of truth.**
- **Manual exercise selection:** do not spend complexity guessing which exercise is being performed.

## MVP

First supported movement: **Incline Dumbbell Press**.

Pipeline:

```text
FrameSource<MPImage>
  -> MediaPipe Pose Landmarker
  -> landmark smoothing
  -> rep phase state machine
  -> biomechanics metrics
  -> deterministic form rules
  -> live overlay / Android TTS
  -> local persistence
```

Initial metrics:

- rep count
- eccentric / bottom / concentric / lockout phase
- range of motion
- rep tempo
- left/right symmetry
- forearm orientation
- wrist path / lateral drift proxy

## Health data

Health Connect is used as an optional local health-data source for:

- sleep
- weight
- body fat
- resting heart rate
- heart rate
- steps
- calories / activity where available

Health data is not part of the real-time camera critical path.

## Project workspace

Source code lives in this repository. Specs, training samples, build artifacts, and reports are kept separately in the project's Google Drive workspace.


## Testable camera boundary

CameraX must not be the direct dependency of the biomechanics pipeline. The app
depends on a `FrameSource<MPImage>` boundary instead.

```text
CameraXFrameSource ─┐
                    ├─> MediaPipe -> smoothing -> reps -> form rules
SimulatorFrameSource┘
```

The protocol contract lives in `contracts/frame-source/`. The matching frame
server/exporter is implemented in the simulator harness repository.

This lets the same MediaPipe and deterministic Kotlin analysis code run against
either the phone camera or MuJoCo-generated RGB frames.
