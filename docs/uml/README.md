# Gym Buddy UML

This folder is the architecture source of truth before implementation starts.

## Diagrams

1. `01-system-context.puml` — system boundary and external dependencies.
2. `02-android-components.puml` — Android runtime component structure.
3. `03-domain-model.puml` — persistent domain/data model.
4. `04-live-analysis-sequence.puml` — frame-to-cue realtime analysis flow.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion flow.

## Architectural invariants

- Workout monitoring is local-first and must work in airplane mode.
- ML is restricted to perception. MediaPipe converts camera frames into landmarks; deterministic code owns movement interpretation and coaching decisions.
- Health Connect is contextual input, never part of the realtime camera critical path.
- No backend, LLM, account system, or cloud upload is required.
- Exercise selection is explicit; the app does not spend complexity guessing the exercise.
- Persist structured movement telemetry and rule evidence rather than only opaque scores.
- Raw video is not persisted by default.

## Privacy / repository boundary

Do **not** commit personal health or workout recordings to this repository. Real user data belongs only in the local Android database or the private project data workspace. Test fixtures in GitHub must be synthetic or explicitly anonymized.

## MVP implementation target

First exercise: **Incline Dumbbell Press**.

The first implementation must prove this path:

```text
CameraX -> MediaPipe Pose -> smoothing -> rep state machine
-> biomechanics metrics -> deterministic rules -> overlay/TTS -> Room
```
