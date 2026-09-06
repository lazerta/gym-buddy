# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.5 — final review candidate**

## Diagrams

1. `01-system-context.puml` — local-only boundary and external dependencies.
2. `02-android-components.puml` — Android runtime components, priority persistence lanes, Health Connect aggregation and retention enforcement.
3. `03-domain-model.puml` — persistent domain/data model including plan/set targets, multi-exercise workouts and generic compact traces.
4. `04-live-analysis-sequence.puml` — frame-to-cue realtime flow with async priority persistence.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion and compact aggregation.
6. `06-realtime-processing-pipeline.puml` — camera/storage backpressure, normalization, quality gate and cue arbitration.
7. `07-persistence-data-lifecycle.puml` — local storage, 7-day cleanup, compact history and debug-media policy.

## Architectural invariants

- Android only, single user.
- Workout monitoring is local-first and must work in airplane mode.
- MVP SHALL NOT request `android.permission.INTERNET`.
- The MediaPipe pose model is bundled with the APK; no runtime model download is required.
- No backend, LLM, account/login system, Gym Buddy cloud path, or personal-data export/share feature exists in MVP.
- ML is restricted to perception. Deterministic code owns movement interpretation and coaching decisions.
- Health Connect is optional context and never part of the realtime camera critical path.
- Exercise selection is explicit; the app does not guess the exercise.
- Coordinate normalization happens before exercise-specific reasoning.
- Camera ingestion uses bounded/latest-frame processing; stale frames do not accumulate.
- Tracking/calibration quality gates can block movement analysis and coaching cues.
- Cue arbitration prevents multiple rules from spamming the user.
- TTS may use only an installed voice that does not require a network connection; if none is available, feedback remains visual-only.
- Persistence is outside the realtime coaching critical path.
- Short-lived raw telemetry uses a bounded best-effort ring buffer.
- Durable rep summaries, `CompactRepTrace`, final metrics, rule evidence and `FormEvent`s use a separate priority queue with reserved capacity and are drained before raw telemetry.
- Android backup/cloud backup of Gym Buddy personal data is disabled.
- Debug media stays in app-private/no-backup storage and is deleted after at most 7 days.
- Retention cleanup runs on app startup and periodically, locally and without network access.

## Domain invariants

- `TrainingPlan -> TrainingDay -> PlannedExercise -> PlannedSet`; `PlannedExercise` references a reusable `ExerciseDefinition`.
- `PlannedSet` can represent per-set load percentages, rep ranges and RIR ranges; training targets are not hard-coded into analyzers.
- Training-plan definitions are versioned; `UserProfile` points to the active plan, while `WorkoutSession` snapshots the plan id/version used for that workout.
- `WorkoutSession` represents one gym visit and may contain many `ExerciseExecution`s.
- Exercise-specific provenance (`analyzerVersion`, `metricVersion`, `calibrationVersion`) belongs to `ExerciseExecution`.
- `ExerciseExecution.plannedExerciseId` and `ExerciseSet.plannedSetId` are optional so ad-hoc work remains valid.
- `TelemetrySample` belongs to `ExerciseSet` and may optionally reference a recognized `Rep`.
- Realtime duration timestamps use monotonic elapsed-time values, not wall-clock time.
- `CompactRepTrace` is generic: `schemaId + TraceChannel[]`, with a fixed small normalized time axis. It is not press-specific.
- Full-fidelity re-analysis is available only during the 7-day raw telemetry window; older sessions support compact-trace/metric-level reinterpretation.
- Fine-grained Health Connect records are in-memory aggregation inputs, not permanent Gym Buddy records.
- `DailyHealthSummary` stores compact daily sleep/activity context when available.
- `WeeklyBodyComposition` stores weekly median weight/body-fat plus sample counts.
- Missing Health Connect data never blocks workout monitoring.

## Data retention policy

- Raw camera frames: discard immediately.
- High-frequency pose/movement telemetry: retain **7 days**, then delete.
- Tracking/calibration debug events: retain **7 days**, then delete.
- Debug video: OFF by default; if enabled, retain at most **7 days** in app-private/no-backup storage.
- `CompactRepTrace`, rep metrics, form events, rule evidence, set/workout summaries: retain long-term.
- Daily nutrition context and `DailyHealthSummary`: retain long-term when present.
- Weight/body-fat: one `WeeklyBodyComposition` per week, preferably medians plus sample counts.
- Fine-grained Health Connect history: aggregate in memory and do not persist long-term.

## Privacy / repository boundary

Real personal data belongs **only on the Android device**.

- GitHub: source code, architecture, tests, synthetic/anonymized fixtures only.
- Google Drive: specs, architecture, reports without personal telemetry, and build artifacts only.
- Android device: workout/health summaries, short-lived telemetry, metrics, form events, calibration, compact traces and optional debug media.

Do **not** commit or upload real health data, telemetry exports, workout recordings, sleep/weight history, nutrition logs, or debug video to GitHub or Google Drive.

## MVP implementation target

First exercise: **Incline Dumbbell Press**.

```text
CameraX
-> latest-frame scheduler
-> bundled MediaPipe Pose
-> coordinate normalization
-> landmark smoothing
-> tracking quality gate
-> InclineDumbbellPressAnalyzer
-> biomechanics metrics
-> deterministic form rules
-> cue arbitration
-> overlay / offline-only TTS

persistence side path:
raw telemetry -> bounded TelemetryRingBuffer
rep summary / compact trace / metrics / events -> DurableEventQueue
both -> asynchronous BatchWriter -> Room
```

Health Connect integration comes after the realtime camera path is proven and must not block it.
