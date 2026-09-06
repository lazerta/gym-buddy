# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.7 — final architecture review candidate**

## Diagrams

1. `01-system-context.puml` — local-only boundary, bundled pose model, offline TTS, no network permission/export.
2. `02-android-components.puml` — realtime components, best-effort telemetry lane, priority persistence lane, health aggregation, retention.
3. `03-domain-model.puml` — versioned plan/set targets, multi-exercise workouts, set-scoped form events, structured evidence, generic compact traces.
4. `04-live-analysis-sequence.puml` — realtime flow, detection-vs-delivery, async priority persistence, crash-safe set expiry.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion and compact aggregation.
6. `06-realtime-processing-pipeline.puml` — independent camera/storage backpressure and priority persistence.
7. `07-persistence-data-lifecycle.puml` — 7-day expiry semantics, local purge, long-term compact history.

## Architectural invariants

- Android only, single user.
- Workout monitoring works in airplane mode.
- MVP SHALL NOT request `android.permission.INTERNET`.
- MediaPipe pose model is bundled with the APK; no runtime model download.
- No backend, LLM, account/login, Gym Buddy cloud path, or personal-data export/share in MVP.
- TTS uses only an installed voice that does not require network; otherwise visual-only feedback.
- ML is perception only; deterministic code owns movement interpretation and coaching.
- Health Connect is optional context and never in the realtime camera critical path.
- Exercise selection is explicit.
- Coordinate normalization precedes exercise-specific reasoning.
- Camera ingestion is latest-frame/bounded; stale frames never accumulate.
- Tracking/calibration quality can veto biomechanics analysis and coaching.
- Detection (`FormEvent` + `FormEvidence`) and delivery (`CueDelivery`) are separate facts.
- Persistence never blocks realtime coaching.
- Raw telemetry uses a bounded best-effort `TelemetryRingBuffer`.
- Higher-value records use a bounded `PriorityPersistenceQueue` with reserved capacity and drain before raw telemetry.
- `PriorityPersistenceQueue` is not durable storage; durability begins only after Room commit.
- Android cloud backup of Gym Buddy personal data is disabled.
- Debug media is app-private/no-backup and expires after 7 days.
- Retention cleanup runs locally on startup and periodically.

## Domain invariants

- `TrainingPlan -> TrainingDay -> PlannedExercise -> PlannedSet`; planned exercises reference reusable `ExerciseDefinition`s.
- `PlannedSet` supports per-set load %, rep range and RIR range. `loadPercentBasis` is recorded only when the source defines it; otherwise it remains null rather than guessed.
- Plan definitions are versioned. `UserProfile` points to the active plan; `WorkoutSession` snapshots the plan/version used that day.
- `WorkoutSession` represents one gym visit and may contain many `ExerciseExecution`s.
- Exercise-specific analyzer/metric/calibration versions belong to `ExerciseExecution`.
- `ExerciseExecution.plannedExerciseId` and `ExerciseSet.plannedSetId` are optional for ad-hoc work.
- `ExerciseSet.telemetryExpiresAt` is non-null from creation: initialize to `startedAt + 7 days`; on normal close update to `endedAt + 7 days`.
- Realtime duration/event timestamps use monotonic elapsed-time values, while wall-clock `Instant`s are used for history and retention.
- `TelemetrySample` belongs to `ExerciseSet` and may optionally reference `Rep`.
- `FormEvent` belongs to `ExerciseSet` and may optionally reference `Rep`, so issues can exist during setup/top position, partial attempts, or unrecognized reps.
- `FormEvidence[]` stores structured observed/threshold facts; `CueDelivery` records only feedback actually delivered.
- `CompactRepTrace` is generic: `schemaId + TraceChannel[]` on a small normalized time axis.
- Full-fidelity re-analysis exists only during the 7-day telemetry window; older sessions support compact-trace/metric-level reinterpretation.
- Fine-grained Health Connect data is processed in memory and not retained long-term.
- `DailyHealthSummary` is compact daily health context; `WeeklyBodyComposition` stores weekly median weight/body-fat plus sample counts.
- Missing health data never blocks training.

## Data retention policy

- Raw camera frames: discard immediately.
- High-frequency pose/movement telemetry: expires no later than the set's 7-day `telemetryExpiresAt`.
- Tracking/calibration debug events: same set-scoped expiry.
- Debug video: OFF by default; 7-day expiry in app-private/no-backup storage.
- Expired short-lived data is excluded from normal reads immediately; startup/periodic cleanup physically purges it.
- `CompactRepTrace`, rep metrics, form events/evidence, cue deliveries, set/workout summaries: long-term.
- Daily nutrition context and `DailyHealthSummary`: long-term when present.
- Weight/body-fat: one `WeeklyBodyComposition` per week, preferably medians + sample counts.
- Fine-grained Health Connect records: aggregate in memory; never durable Gym Buddy history.

## Privacy / repository boundary

Real personal data belongs **only on the Android device**.

- GitHub: source code, architecture, tests, synthetic/anonymized fixtures only.
- Google Drive: specs, architecture, non-personal reports, build artifacts only.
- Android device: workout/health summaries, short-lived telemetry, metrics, form evidence/events, delivered cues, calibration, compact traces, optional debug media.

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
-> deterministic rules
-> cue arbitration
-> overlay / offline-only TTS

persistence:
raw telemetry -> TelemetryRingBuffer
rep summaries / CompactRepTrace / final metrics / FormEvent+FormEvidence / CueDelivery -> PriorityPersistenceQueue
both -> async BatchWriter -> Room
```

Exact queue capacities, cleanup cadence, Health Connect aggregation windows, exercise-specific compact trace channels, rule thresholds and cue cooldowns belong to the SPEC/test layer, not UML.
