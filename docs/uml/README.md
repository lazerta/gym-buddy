# Gym Buddy UML

Architecture source of truth for the private-use, local-first Android biomechanics coach.

## Version

**v0.6 — final architecture review candidate**

## Diagrams

1. `01-system-context.puml` — local-only boundary, bundled pose model, offline TTS and no-export policy.
2. `02-android-components.puml` — realtime components, priority persistence lanes, health aggregation and retention enforcement.
3. `03-domain-model.puml` — plan/set targets, multi-exercise workouts, structured rule evidence, delivered cues and generic compact traces.
4. `04-live-analysis-sequence.puml` — frame-to-cue flow with separate detection/delivery and async persistence.
5. `05-health-connect-sequence.puml` — optional Health Connect ingestion and compact aggregation.
6. `06-realtime-processing-pipeline.puml` — independent camera/storage backpressure and durable feedback observability.
7. `07-persistence-data-lifecycle.puml` — 7-day expiry semantics, local cleanup, compact history and no-export policy.

## Architectural invariants

- Android only, single user.
- Workout monitoring works fully in airplane mode.
- MVP SHALL NOT request `android.permission.INTERNET`.
- MediaPipe pose model is bundled with the APK; no runtime model download.
- No backend, LLM, account/login system, Gym Buddy cloud path, or personal-data export/share feature exists in MVP.
- TTS uses only an installed voice that does not require a network connection; otherwise feedback remains visual-only.
- ML is perception only. Deterministic code owns movement interpretation and coaching decisions.
- Health Connect is optional context and never part of the realtime camera critical path.
- Exercise selection is explicit.
- Coordinate normalization happens before exercise-specific reasoning.
- Camera ingestion uses latest-frame/backpressure semantics; stale frames do not accumulate.
- Tracking/calibration quality can veto analysis and coaching.
- Rule detection and cue delivery are separate facts.
- Cue arbitration prevents feedback spam; `CueDelivery` exists only when feedback is actually shown/spoken.
- Persistence is outside the realtime coaching path.
- Short-lived telemetry uses a bounded best-effort ring buffer.
- Durable rep summaries, compact traces, final metrics, structured `FormEvidence`, `FormEvent`s and `CueDelivery` records use a separate priority queue.
- Android backup/cloud backup of Gym Buddy personal data is disabled.
- Debug media stays in app-private/no-backup storage.
- Retention cleanup runs on startup and periodically without network access.

## Domain invariants

- `TrainingPlan -> TrainingDay -> PlannedExercise -> PlannedSet`; exercises reference reusable `ExerciseDefinition`s.
- `PlannedSet` supports per-set load %, rep range and RIR range. `loadPercentBasis` records what the percentage means when known; it remains null when the source plan does not define the denominator.
- Plan definitions are versioned. `UserProfile` points to the active plan; `WorkoutSession` snapshots the plan id/version used that day.
- `WorkoutSession` may contain many `ExerciseExecution`s.
- Exercise-specific provenance belongs to `ExerciseExecution`.
- `ExerciseExecution.plannedExerciseId` and `ExerciseSet.plannedSetId` are optional for ad-hoc work.
- `ExerciseSet` stores wall-clock start/end and `telemetryExpiresAt`; short-lived children use that expiry anchor.
- Realtime duration timestamps use monotonic elapsed-time values, not wall clock.
- `TelemetrySample` belongs to `ExerciseSet` and may optionally reference a `Rep`.
- `FormEvent` records a persisted detected issue; `FormEvidence[]` stores structured observed/threshold facts.
- `CueDelivery` records only feedback actually delivered to the user. Suppressed issues have no delivery record.
- `CompactRepTrace` is generic: `schemaId + TraceChannel[]` on a small normalized time axis.
- Full-fidelity re-analysis is available only inside the 7-day telemetry window; older sessions support compact-trace/metric-level reinterpretation.
- Fine-grained Health Connect records are in-memory aggregation inputs, not permanent Gym Buddy data.
- `DailyHealthSummary` stores compact daily health context when available.
- `WeeklyBodyComposition` stores weekly median weight/body-fat plus sample counts.
- Missing health data never blocks workout monitoring.

## Data retention policy

- Raw camera frames: discard immediately.
- High-frequency pose/movement telemetry: expires at **7 days**.
- Tracking/calibration debug events: expires at **7 days**.
- Debug video: OFF by default; expires at **7 days** in app-private/no-backup storage.
- Expired data is excluded from normal reads; startup/periodic local cleanup physically purges it.
- `CompactRepTrace`, rep metrics, form events/evidence, delivered cues, set/workout summaries: long-term.
- Daily nutrition context and `DailyHealthSummary`: long-term when present.
- Weight/body-fat: one `WeeklyBodyComposition` per week, preferably medians plus sample counts.
- Fine-grained Health Connect history: aggregate in memory; do not persist long-term.

## Privacy / repository boundary

Real personal data belongs **only on the Android device**.

- GitHub: source code, architecture, tests, synthetic/anonymized fixtures only.
- Google Drive: specs, architecture, reports without personal telemetry, and build artifacts only.
- Android device: workout/health summaries, short-lived telemetry, metrics, form evidence/events, delivered cues, calibration, compact traces and optional debug media.

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

persistence:
raw telemetry -> TelemetryRingBuffer
rep summary / CompactRepTrace / final metrics / FormEvent+FormEvidence / CueDelivery -> DurableEventQueue
both -> async BatchWriter -> Room
```

Health Connect integration comes after the realtime camera path is proven and must not block it.
