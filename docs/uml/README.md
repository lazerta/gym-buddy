# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.8 — ARCHITECTURE FROZEN / FINAL**

Final multi-pass review result: **P0 = 0, architecture-level P1 = 0**. Remaining choices belong to SPEC, acceptance criteria, tests, and implementation.

## Diagrams

1. `01-system-context.puml` — local-only boundary, bundled pose model, offline TTS, no network permission/export.
2. `02-android-components.puml` — ordered serialized realtime analysis, temporal reset, atomic rep finalization, persistence lanes.
3. `03-domain-model.puml` — versioned plans, multi-exercise workouts, resistance/config snapshots, structured form evidence, compact traces.
4. `04-live-analysis-sequence.puml` — frame-to-cue flow, out-of-order result rejection, atomic rep durability, close barrier semantics.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion and compact aggregation.
6. `06-realtime-processing-pipeline.puml` — latest-frame ingestion, ordered result gate, confidence-aware signal processing, priority persistence.
7. `07-persistence-data-lifecycle.puml` — 7-day short-lived data, atomic rep bundles, revisioned form events, local retention/recovery.
8. `08-camera-setup-state.puml` — setup validation, tracking degradation/recovery, temporal reset and safe finalization.
9. `09-incline-db-press-state-machine.puml` — MVP rep-state semantics for Incline Dumbbell Press.

## Architectural invariants

- Android only, single user.
- Workout monitoring works in airplane mode.
- MVP SHALL NOT request `android.permission.INTERNET`.
- MediaPipe pose model is bundled with the APK; no runtime model download.
- No backend, LLM, account/login, Gym Buddy cloud path, or personal-data export/share in MVP.
- TTS uses only an installed offline-capable voice; otherwise feedback is visual-only.
- ML is perception only; deterministic code owns movement interpretation and coaching.
- Health Connect is optional context and never part of the realtime camera critical path.
- Exercise selection is explicit.
- Camera ingestion is latest-frame/bounded; stale frames never accumulate.
- Inference callbacks are not trusted to complete in order: `OrderedResultGate` discards timestamps `<= lastProcessedTimestamp`.
- All stateful biomechanics processing runs on one serialized temporal lane per active set.
- Signal processing is confidence-aware; invalid landmarks do not contaminate filter state as valid observations.
- Significant tracking/setup/view discontinuities reset every stateful temporal consumer through `TemporalResetCoordinator`.
- Tracking quality can veto biomechanics analysis and coaching.
- Detection (`FormEvent` + `FormEvidence`) and delivery (`CueDelivery`) are separate facts.
- A continuous form problem is one stateful episode, not one database row per frame.
- Persistence never blocks realtime coaching.
- Short-lived pose/debug telemetry is bounded, chunked and best-effort.
- Higher-value persistence commands are sequence-ordered and reserved from raw-telemetry pressure.
- `RepFinalizationBundle` is the atomic durability unit for Rep + final metrics + compact trace + rep-scoped finalizations.
- `FormEvent.eventRevision` prevents stale upserts from overwriting newer event state.
- `SetCloseBarrier(highWatermark)` is reserved/non-droppable; a set becomes saved/completed only after all required priority commands through the high-water mark are durable in Room.
- Android cloud backup of Gym Buddy personal data is disabled.
- Debug media is app-private/no-backup and expires after 7 days.
- Retention cleanup and interrupted-session recovery run locally.

## Domain invariants

- `TrainingPlan -> TrainingDay -> PlannedExercise -> PlannedSet`; planned exercises reference reusable `ExerciseDefinition`s.
- Published plan versions are immutable once referenced by a workout.
- Planned load percentage supports a range plus nullable `loadPercentBasis`; undefined bases are never guessed.
- `WorkoutSession` is one gym visit and may contain many `ExerciseExecution`s.
- `ExerciseExecution` snapshots pipeline/analyzer/metric/rule-set/cue-policy/calibration versions.
- Exercise configuration such as `benchAngleDeg` is captured with `ExerciseParameterSnapshot`.
- Actual resistance semantics are explicit via `ResistanceSnapshot` rather than a bare load number (`PER_HAND`, `TOTAL_EXTERNAL`, `MACHINE_STACK`, `ASSISTANCE`, `BODYWEIGHT`, etc.).
- `ExerciseSet` stores both wall-clock start time and a monotonic runtime origin; durable realtime event times are offsets from set start.
- `ExerciseSet.telemetryExpiresAt` is non-null from creation: initialize to `startedAt + 7 days`, then update to `endedAt + 7 days` on normal close.
- `FormEvent.scope=REP` requires a rep and finalizes at the rep boundary; `scope=SET` may span reps.
- `CompactRepTrace` is a generic long-term reinterpretation artifact (`schemaId + TraceChannel[]`), not a promise of raw/full-fidelity replay.
- Fine-grained Health Connect records are processed in memory and not retained long-term.
- `DailyHealthSummary` is compact daily context; `WeeklyBodyComposition` stores weekly median weight/body-fat plus sample counts.
- Missing health data never blocks training.

## Data retention policy

- Raw camera frames: discard immediately.
- `PoseObservationChunk` + tracking/calibration debug events: set-scoped short-lived data, logical expiry at `telemetryExpiresAt`, physically purged locally.
- Debug video: OFF by default; at most 7 days in app-private/no-backup storage.
- `RepFinalizationBundle` results, set-scoped FormEvents/evidence, CueDelivery, set/workout summaries: long-term.
- Daily nutrition context and `DailyHealthSummary`: long-term when present.
- Weight/body-fat: one `WeeklyBodyComposition` per week, preferably medians + sample counts.
- Fine-grained Health Connect records: aggregate in memory; never durable Gym Buddy history.

## MVP implementation target

First exercise: **Incline Dumbbell Press**.

```text
CameraX
-> latest-frame scheduler
-> bundled MediaPipe Pose
-> OrderedResultGate
-> serialized analysis lane
-> coordinate normalization
-> confidence-aware signal processing
-> tracking quality gate
-> InclineDumbbellPressAnalyzer
-> biomechanics metrics
-> deterministic rule engine + episode tracker
-> cue arbitration
-> overlay / offline-only TTS

short-lived pose/debug data -> sampler/chunker -> best-effort telemetry buffer
rep finalizations / revisioned form events / delivered cues -> priority persistence queue
-> serialized writer -> Room
```

Exact FPS, persistence sampling rate, chunk size/codec, confidence thresholds, tracking-gap tolerance, state-machine hysteresis/dwell, exercise-specific thresholds, cue cooldowns, queue capacities, Room encodings, Health Connect aggregation/timezone rules, and crash-loss tolerances belong to the SPEC/test layer.
