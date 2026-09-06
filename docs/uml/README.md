# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.3 — architecture freeze candidate**

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
- No backend, LLM, account system, or Gym Buddy cloud data path exists.
- Exercise selection is explicit; the app does not spend complexity guessing the exercise.
- Coordinate normalization happens before exercise-specific reasoning.
- The realtime path uses bounded/latest-frame processing; stale frames must not accumulate.
- Tracking/calibration quality gates can block movement analysis and coaching cues.
- Cue arbitration prevents multiple rules from spamming the user.
- Persist structured telemetry, derived metrics, algorithm provenance and rule evidence rather than only opaque scores.
- Raw video is not persisted by default.
- Android backup/cloud backup of Gym Buddy personal data must be disabled.
- Debug media must remain in app-private/no-backup storage unless the user explicitly exports it.

## Domain invariants

- `TelemetrySample` belongs to an `ExerciseSet` and may optionally reference a recognized `Rep`.
- Setup motion, partial/failed reps, between-rep motion and tracking-loss periods must not be discarded merely because no complete rep exists.
- `ExerciseAnalyzer` behavior is exercise-specific; shared math/signal-processing infrastructure stays generic.
- Historical results store `poseModelVersion`, `analyzerVersion`, `metricVersion`, `ruleVersion`, `calibrationVersion`, and app build provenance where relevant.
- Health values retain source, measurement time and sync time so duplicates/stale values can be reconciled deterministically.
- Weight/body-fat are consolidated into one `WeeklyBodyComposition` snapshot per week; prefer the weekly median of valid measurements.

## Data retention policy

- Raw camera frames: discard immediately after processing.
- High-frequency pose/movement telemetry: retain **7 days**, then delete.
- Tracking/calibration debug events: retain **7 days**, then delete.
- Debug video: OFF by default; if explicitly enabled, keep at most **7 days** in app-private/no-backup storage.
- Rep metrics, form events, rule evidence, set/workout summaries, and compact/downsampled rep traces: retain long-term.
- Daily sleep/nutrition summaries may be retained long-term.
- Weight/body-fat: retain only a weekly compact snapshot, preferably the weekly median.
- Avoid duplicating fine-grained Health Connect history long-term when it can be queried from Health Connect directly.

## Privacy / repository boundary

Real personal data belongs **only on the Android device**.

- GitHub: source code, architecture, tests, synthetic/anonymized fixtures only.
- Google Drive: specs, architecture, reports without personal telemetry, and build artifacts only.
- Android device: Health Connect cache, workout telemetry, metrics, form events, calibration, summaries, and optional debug media.

Do **not** commit or upload real health data, telemetry exports, workout recordings, sleep/weight history, nutrition logs, or debug video to GitHub or Google Drive.

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
