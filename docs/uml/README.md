# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.2 — pre-implementation review baseline**

## Diagrams

1. `01-system-context.puml` — system boundary and external dependencies.
2. `02-android-components.puml` — Android runtime component structure.
3. `03-domain-model.puml` — persistent domain/data model.
4. `04-live-analysis-sequence.puml` — frame-to-cue realtime analysis flow.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion flow.
6. `06-realtime-processing-pipeline.puml` — latency/backpressure, normalization, quality gate and cue arbitration.
7. `07-persistence-data-lifecycle.puml` — local storage, retention, compaction and debug-video policy.

## Architectural invariants

- Workout monitoring is local-first and must work in airplane mode.
- ML is restricted to perception. MediaPipe converts camera frames into landmarks; deterministic code owns movement interpretation and coaching decisions.
- Health Connect is contextual input, never part of the realtime camera critical path.
- No backend, LLM, account system, or cloud upload is required.
- Exercise selection is explicit; the app does not spend complexity guessing the exercise.
- Coordinate normalization happens before exercise-specific reasoning.
- The realtime path uses bounded/latest-frame processing; stale frames must not accumulate.
- Tracking/calibration quality gates can block movement analysis and coaching cues.
- Cue arbitration prevents multiple rules from spamming the user.
- Persist structured telemetry, derived metrics, algorithm provenance and rule evidence rather than only opaque scores.
- Raw video is not persisted by default.

## Domain invariants

- `TelemetrySample` belongs to an `ExerciseSet` and may optionally reference a recognized `Rep`.
- Setup motion, partial/failed reps, between-rep motion and tracking-loss periods must not be discarded merely because no complete rep exists.
- `ExerciseAnalyzer` behavior is exercise-specific; shared math/signal-processing infrastructure stays generic.
- Historical results store `poseModelVersion`, `analyzerVersion`, `metricVersion`, `ruleVersion`, `calibrationVersion`, and app build provenance where relevant.
- Health values retain source, measurement time and sync time so duplicates/stale values can be reconciled deterministically.

## Privacy / repository boundary

Do **not** commit personal health data, real telemetry exports, or workout recordings to this repository. Real user data belongs only in the local Android database or the private project Drive workspace. GitHub test fixtures must be synthetic or explicitly anonymized.

> Repository visibility is currently a separate operational concern. Until it is private, treat GitHub as public and commit no personal data.

## MVP implementation target

First exercise: **Incline Dumbbell Press**.

The first implementation must prove this path:

```text
CameraX
-> latest-frame scheduler
-> MediaPipe Pose
-> coordinate normalization
-> landmark smoothing
-> tracking quality gate
-> InclineDumbbellPressAnalyzer
-> biomechanics metrics
-> deterministic form rules
-> cue arbitration
-> overlay/TTS
-> local Room persistence
```

Health Connect integration comes after the realtime camera path is proven and must not block it.
