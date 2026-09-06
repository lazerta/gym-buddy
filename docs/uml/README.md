# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.4 — post-review architecture candidate**

## Diagrams

1. `01-system-context.puml` — system boundary and external dependencies.
2. `02-android-components.puml` — Android runtime component structure.
3. `03-domain-model.puml` — persistent domain/data model.
4. `04-live-analysis-sequence.puml` — frame-to-cue realtime analysis flow.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion and aggregation flow.
6. `06-realtime-processing-pipeline.puml` — latency/backpressure, normalization, quality gate, cue arbitration and async persistence.
7. `07-persistence-data-lifecycle.puml` — local storage, retention, compact replay and debug-video policy.

## Architectural invariants

- Workout monitoring is local-first and must work in airplane mode.
- ML is restricted to perception. MediaPipe converts camera frames into landmarks; deterministic code owns movement interpretation and coaching decisions.
- Health Connect is optional context and never part of the realtime camera critical path.
- No backend, LLM, account system, or Gym Buddy cloud data path exists.
- MVP SHALL NOT request `android.permission.INTERNET`.
- Exercise selection is explicit; the app does not spend complexity guessing the exercise.
- Coordinate normalization happens before exercise-specific reasoning.
- The realtime path uses bounded/latest-frame processing; stale camera frames must not accumulate.
- Tracking/calibration quality gates can block movement analysis and coaching cues.
- Cue arbitration prevents multiple rules from spamming the user.
- Persistence is outside the realtime coaching critical path through a bounded non-blocking buffer and asynchronous batch writer.
- Under storage pressure, raw/high-frequency telemetry may be dropped before durable rep summaries, metrics, rule evidence or FormEvents.
- Android backup/cloud backup of Gym Buddy personal data must be disabled.
- Debug media must remain in app-private/no-backup storage unless the user explicitly exports it.

## Domain invariants

- `WorkoutSession` represents one gym visit/session and contains one or more `ExerciseExecution` records.
- Exercise-specific provenance (`analyzerVersion`, `metricVersion`, `calibrationVersion`) belongs to `ExerciseExecution`, not to the entire workout.
- `TrainingPlan -> TrainingDay -> PlannedExercise -> ExerciseDefinition`; an `ExerciseDefinition` is reusable and independent of any one training-plan version.
- `TelemetrySample` belongs to an `ExerciseSet` and may optionally reference a recognized `Rep`.
- Setup motion, partial/failed reps, between-rep motion and tracking-loss periods remain representable without forcing them into a completed rep.
- `ExerciseAnalyzer` behavior is exercise-specific; shared math/signal-processing infrastructure stays generic.
- `CompactRepTrace` is the long-term small replay/trend artifact after full telemetry expires.
- Full-fidelity re-analysis is guaranteed only while high-frequency telemetry remains inside the 7-day window; older data supports compact/metric-level reinterpretation only.
- Health Connect fine-grained records are aggregation inputs, not permanent Gym Buddy records.
- `DailyHealthSummary` stores compact daily sleep/activity/readiness context when available.
- `WeeklyBodyComposition` stores one weekly weight/body-fat snapshot, preferably medians plus sample counts.
- Missing Health Connect data never blocks workout monitoring.

## Data retention policy

- Raw camera frames: discard immediately after processing.
- High-frequency pose/movement telemetry: retain **7 days**, then delete.
- Tracking/calibration debug events: retain **7 days**, then delete.
- Debug video: OFF by default; if explicitly enabled, keep at most **7 days** in app-private/no-backup storage.
- `CompactRepTrace`, rep metrics, form events, rule evidence, set/workout summaries: retain long-term.
- Daily nutrition context and `DailyHealthSummary`: retain long-term when present.
- Weight/body-fat: retain only one `WeeklyBodyComposition` snapshot per week, preferably weekly medians with sample counts.
- Fine-grained Health Connect history: aggregate in memory and do not permanently duplicate in Gym Buddy.

## Privacy / repository boundary

Real personal data belongs **only on the Android device**.

- GitHub: source code, architecture, tests, synthetic/anonymized fixtures only.
- Google Drive: specs, architecture, reports without personal telemetry, and build artifacts only.
- Android device: workout/health summaries, telemetry, metrics, form events, calibration, compact traces, and optional debug media.

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

side path:
analysis outputs
-> bounded TelemetryBuffer
-> asynchronous BatchWriter
-> Room
```

Health Connect integration comes after the realtime camera path is proven and must not block it.
