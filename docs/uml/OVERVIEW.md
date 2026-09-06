# Gym Buddy UML Overview v0.4

Post-review architecture candidate for a single-user, local-first Android biomechanics coach.

## 1. System Context

```mermaid
flowchart LR
    U[Shawn\nSingle User] --> A[Gym Buddy Android App]
    C[CameraX] --> A
    H[Health Connect] --> A
    A --> T[Android TTS / Overlay]
    A --> D[(Room / SQLite)]

    subgraph OnDevice[On-device personal-data boundary]
      A
      T
      D
    end

    N[No backend\nNo LLM\nNo login\nNo Gym Buddy cloud data path\nNo INTERNET permission in MVP\nAirplane-mode workout monitoring] -. constraints .-> A
    A -. no personal-data path .-x G[GitHub / Google Drive]
```

Real Gym Buddy personal data stays on the Android device. GitHub contains code/tests/synthetic fixtures; Google Drive contains specs/reports/builds without real telemetry or health records.

## 2. Android Component Architecture

```mermaid
flowchart LR
    UI[Compose UI] --> CAM[CameraX]
    CAM --> FS[Latest-frame Scheduler]
    FS --> POSE[MediaPipe Pose]
    POSE --> NORM[Coordinate Normalizer]
    NORM --> SIG[Landmark Smoother / Motion Features]
    SIG --> QG[Tracking Quality Gate]
    QG --> ANA[Exercise-specific Analyzer]
    QG --> UI
    ANA --> MET[Biomechanics Metrics]
    MET --> RULES[Deterministic Rule Engine]
    RULES --> CUE[Cue Policy + Arbitrator]
    CUE --> UI
    CUE --> TTS[Android TTS]

    ANA --> BUF[Bounded TelemetryBuffer]
    MET --> BUF
    RULES --> BUF
    BUF --> BW[Async BatchWriter]
    BW --> REPO[Workout Repository]
    REPO --> DB[(Room)]

    HC[Health Connect Adapter] --> HA[Health Aggregator]
    HA --> REPO
```

**Key rules:** ML stops at pose perception. Stale camera frames are dropped instead of queued. Invalid tracking/calibration blocks biomechanics analysis and coaching cues. Storage is off the realtime critical path. If persistence falls behind, drop raw telemetry before delaying coaching or losing durable rep/event summaries.

## 3. Domain / Data Model

```mermaid
classDiagram
    class UserProfile {
      +heightCm
      +preferredUnits
    }

    class TrainingPlan {
      +id
      +name
      +version
    }

    class TrainingDay {
      +dayOfWeek
      +displayName
      +order
    }

    class PlannedExercise {
      +exerciseId
      +order
      +targetSets
      +repRangeMin
      +repRangeMax
    }

    class ExerciseDefinition {
      +id
      +displayName
      +analyzerType
      +defaultCameraView
    }

    class CalibrationProfile {
      +exerciseId
      +version
      +cameraView
      +createdAt
    }

    class DailyContext {
      +date
      +caloriesKcal
      +proteinG
      +carbsG
      +fatG
      +preWorkoutCarbsG
      +waterMl
      +sodiumMg
    }

    class DailyHealthSummary {
      +date
      +sleepMinutes
      +sleepQuality
      +restingHeartRateBpm
      +steps
      +activeCaloriesKcal
      +sourceSummary
    }

    class WeeklyBodyComposition {
      +weekStart
      +medianWeightKg
      +weightSampleCount
      +medianBodyFatPct
      +bodyFatSampleCount
      +sourceSummary
    }

    class WorkoutSession {
      +id
      +startedAt
      +endedAt
      +appBuild
      +poseModelVersion
    }

    class ExerciseExecution {
      +id
      +exerciseId
      +order
      +analyzerVersion
      +metricVersion
      +calibrationVersion
    }

    class ExerciseSet {
      +id
      +setNumber
      +load
      +targetReps
    }

    class Rep {
      +id
      +repNumber
      +startedAtMs
      +endedAtMs
      +completionState
    }

    class TelemetrySample {
      +timestampMs
      +repId nullable
      +phase nullable
      +landmarks
      +trackingConfidence
    }

    class TrackingEvent {
      +timestampMs
      +type
      +severity
    }

    class RepMetric {
      +metricType
      +value
      +unit
      +confidence
      +metricVersion
    }

    class FormEvent {
      +ruleId
      +ruleVersion
      +severity
      +confidence
      +evidenceJson
      +cue
    }

    class CompactRepTrace {
      +traceVersion
      +pointCount
      +normalizedTime
      +elbowAngles
      +wristPaths
    }

    UserProfile "1" --> "1" TrainingPlan
    TrainingPlan "1" --> "many" TrainingDay
    TrainingDay "1" --> "many" PlannedExercise
    PlannedExercise "many" --> "1" ExerciseDefinition
    UserProfile "1" --> "many" CalibrationProfile
    UserProfile "1" --> "many" DailyContext
    UserProfile "1" --> "many" DailyHealthSummary
    UserProfile "1" --> "many" WeeklyBodyComposition
    UserProfile "1" --> "many" WorkoutSession
    WorkoutSession "1" --> "many" ExerciseExecution
    ExerciseExecution "many" --> "1" ExerciseDefinition
    ExerciseExecution "1" --> "many" ExerciseSet
    ExerciseSet "1" --> "many" Rep
    ExerciseSet "1" --> "many" TelemetrySample
    ExerciseSet "1" --> "many" TrackingEvent
    Rep "0..1" <-- "many" TelemetrySample
    Rep "1" --> "many" RepMetric
    Rep "1" --> "many" FormEvent
    Rep "1" --> "0..1" CompactRepTrace
    WorkoutSession "many" --> "0..1" DailyContext
    WorkoutSession "many" --> "0..1" DailyHealthSummary
```

A workout may contain many exercises. Exercise-specific analyzer/calibration versions belong to `ExerciseExecution`. Full telemetry is short-lived; `CompactRepTrace` is the small long-term movement artifact.

## 4. Live Workout Analysis Sequence

```mermaid
sequenceDiagram
    participant U as User
    participant C as CameraX
    participant F as Frame Scheduler
    participant P as MediaPipe
    participant N as Normalize + Smooth
    participant G as Quality Gate
    participant A as Exercise Analyzer
    participant R as Rules
    participant Q as Cue Arbitrator
    participant B as TelemetryBuffer
    participant W as BatchWriter
    participant DB as Room

    U->>C: Start selected exercise set
    loop realtime
      C->>F: frame + monotonic timestamp
      F->>P: newest eligible frame
      P-->>N: landmarks + confidence
      N-->>G: canonical MovementFrame
      alt tracking/calibration invalid
        G-->>U: setup / visibility guidance
      else valid
        G-->>A: valid MovementFrame
        A->>A: update exercise-specific rep state + metrics
        A->>R: metrics + phase + recent history
        R-->>Q: candidate FormEvents
        Q-->>U: max one prioritized overlay/TTS cue
        A-->>B: offer telemetry / rep transitions / metrics
        R-->>B: offer evidence / FormEvents
      end
    end

    loop asynchronous persistence
      B->>W: drain bounded batch
      W->>DB: transaction
    end
```

Persistence never blocks coaching. The buffer is bounded; under pressure, raw telemetry is dropped before durable summaries/events.

## 5. Health Connect Sequence

```mermaid
sequenceDiagram
    participant SRC as RENPHO / Watch / Health Apps
    participant HC as Health Connect
    participant G as Gym Buddy Adapter
    participant A as Health Aggregator
    participant DB as Room

    SRC->>HC: Optional upstream sync
    G->>HC: Read permitted date range
    HC-->>G: sleep / weight / body fat / resting HR / steps / active calories
    G->>G: normalize + deduplicate in memory
    G->>A: normalized records
    A->>A: create DailyHealthSummary
    A->>A: weekly median weight/body-fat + sample counts
    A->>DB: persist compact summaries only
    A->>A: discard fine-grained imported records

    Note over G,DB: Health Connect is optional and never in the realtime camera critical path.
```

## 6. Realtime Processing Pipeline

```mermaid
flowchart LR
    C[CameraX] --> F[Latest-frame Scheduler]
    F --> P[MediaPipe Pose]
    P --> N[Coordinate Normalizer]
    N --> S[Landmark Smoother]
    S --> G{Tracking Quality Gate}
    G -- Invalid --> X[No biomechanics analysis\nShow setup/tracking guidance]
    G -- Valid --> A[Exercise-specific Analyzer]
    A --> M[Biomechanics Metrics]
    M --> R[Deterministic Rules]
    R --> Q[Cue Arbitration]
    Q --> O[Overlay / TTS]

    A --> B[Bounded TelemetryBuffer]
    M --> B
    R --> B
    B --> W[Async BatchWriter]
    W --> DB[(Room)]
```

Normalization owns rotation, mirroring, left/right body identity, coordinate space, timestamps and units. Backpressure is bounded at both camera ingestion and storage. Neither stale frames nor database writes may accumulate into realtime latency.

## 7. Persistence / Data Lifecycle

```mermaid
flowchart TD
    F[Camera Frame] --> P[Pose Inference]
    F -->|debug mode only| V[Debug Video]
    F -->|default| D1[Discard immediately]
    P --> T[High-frequency Telemetry]
    T -->|7 days| DB[(Room / SQLite)]
    TE[Tracking / Calibration Debug Events] -->|7 days| DB
    M[Rep Metrics] -->|long-term| DB
    E[Form Events + Evidence] -->|long-term| DB
    CT[CompactRepTrace] -->|long-term| DB
    N[Daily Nutrition Context] -->|long-term| DB
    DH[DailyHealthSummary] -->|long-term| DB
    W[WeeklyBodyComposition] -->|long-term| DB
    H[Fine-grained Health Connect Records] -->|aggregate in memory then discard| AGG[Health Aggregator]
    AGG --> DH
    AGG --> W
    V -->|max 7 days| LF[App-private no-backup storage]
```

### Retention policy

| Data | Retention |
|---|---|
| Raw camera frame | discard immediately |
| High-frequency pose/movement telemetry | 7 days |
| Tracking/calibration debug events | 7 days |
| Debug video | OFF by default; max 7 days if enabled |
| CompactRepTrace | long-term |
| Rep metrics / form events / rule evidence | long-term |
| Set/workout summaries | long-term |
| Daily nutrition / DailyHealthSummary | long-term when present |
| Weight/body fat | one weekly snapshot; prefer medians + sample counts |
| Fine-grained Health Connect records | aggregate in memory; do not persist long-term |

Full-fidelity historical re-analysis is available only inside the 7-day telemetry window. After that, old sessions support compact-trace/metric-level reinterpretation, not reconstruction of every original landmark frame.

Android cloud backup of Gym Buddy personal data must be disabled. Debug media stays in app-private/no-backup storage unless explicitly exported by the user.

## MVP Scope

First exercise: **Incline Dumbbell Press**.

```text
CameraX
-> latest-frame scheduler
-> MediaPipe Pose
-> coordinate normalization
-> landmark smoothing
-> tracking quality gate
-> InclineDumbbellPressAnalyzer
-> biomechanics metrics
-> deterministic rules
-> cue arbitration
-> overlay/TTS

side path:
analysis outputs
-> bounded TelemetryBuffer
-> asynchronous BatchWriter
-> Room
```

Initial signals: rep count; exercise-specific phases; ROM; tempo; left/right symmetry; joint/forearm orientation; wrist-path/lateral-drift proxy; tracking confidence; rule evidence.

## Architectural Invariants

- Android only, one user.
- Real personal data stays on the Android device only.
- Local-first and airplane-mode capable for workout monitoring.
- No backend, LLM, login, or Gym Buddy cloud data path.
- MVP SHALL NOT request `android.permission.INTERNET`.
- Android backup/cloud backup of Gym Buddy personal data is disabled.
- Debug media remains app-private/no-backup unless explicitly exported.
- Exercise selection is manual.
- ML is perception only; reasoning is deterministic code.
- Tracking quality can veto analysis.
- Exercise analyzers own exercise-specific state machines.
- Cue arbitration prevents voice spam.
- Persistence cannot block the realtime coaching path.
- Raw/high-frequency debugging data has a 7-day window; compact historical data is retained long-term.
- Weight/body-fat is stored weekly, not daily.
- GitHub and Google Drive never contain real Gym Buddy personal data.
