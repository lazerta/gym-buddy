# Gym Buddy UML Overview v0.2

Pre-implementation architecture baseline for a single-user, local-first Android biomechanics coach.

## 1. System Context

```mermaid
flowchart LR
    U[Shawn\nSingle User] --> A[Gym Buddy Android App]
    C[CameraX] --> A
    H[Health Connect] --> A
    A --> T[Android TTS / Overlay]
    A --> D[(Room / SQLite)]

    subgraph OnDevice[On-device boundary]
      A
      T
      D
    end

    N[No backend\nNo LLM\nNo login\nNo video upload\nAirplane-mode workout monitoring] -. constraints .-> A
```

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
    ANA --> DB[(Room)]
    MET --> DB
    RULES --> DB
    HC[Health Connect Adapter] --> DB
```

**Key rules:** ML stops at pose perception. Stale camera frames are dropped instead of queued. Invalid tracking/calibration blocks biomechanics analysis and coaching cues. Multiple form issues are arbitrated into at most one actionable cue at a time.

## 3. Domain / Data Model

```mermaid
classDiagram
    class UserProfile {
      +heightCm
      +currentWeightKg
      +preferredUnits
    }

    class TrainingPlan {
      +id
      +name
      +version
    }

    class ExerciseDefinition {
      +id
      +displayName
      +analyzerType
      +defaultCameraView
    }

    class CalibrationProfile {
      +id
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

    class HealthSample {
      +sourceApp
      +sourceDevice
      +type
      +value
      +unit
      +measuredAt
      +lastSyncedAt
    }

    class WorkoutSession {
      +id
      +exerciseId
      +startedAt
      +endedAt
      +appBuild
      +poseModelVersion
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

    UserProfile "1" --> "1" TrainingPlan
    TrainingPlan "1" --> "many" ExerciseDefinition
    UserProfile "1" --> "many" CalibrationProfile
    UserProfile "1" --> "many" DailyContext
    DailyContext "1" --> "many" HealthSample
    DailyContext "1" --> "many" WorkoutSession
    ExerciseDefinition "1" --> "many" WorkoutSession
    WorkoutSession "1" --> "many" ExerciseSet
    ExerciseSet "1" --> "many" Rep
    ExerciseSet "1" --> "many" TelemetrySample
    ExerciseSet "1" --> "many" TrackingEvent
    Rep "0..1" <-- "many" TelemetrySample
    Rep "1" --> "many" RepMetric
    Rep "1" --> "many" FormEvent
```

**Important correction:** telemetry belongs to the set and may optionally reference a rep. This preserves setup, between-rep movement, partial/failed reps, and tracking-loss periods.

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
        A->>A: update exercise-specific rep state
        A->>A: calculate movement metrics
        A->>R: metrics + phase + recent history
        R-->>Q: candidate FormEvents
        Q-->>U: max one prioritized overlay/TTS cue
        A-->>DB: telemetry + rep boundaries + provenance
        R-->>DB: metrics + rule evidence + FormEvents
      end
    end
```

## 5. Health Connect Sequence

```mermaid
sequenceDiagram
    participant SRC as RENPHO / Watch / Health Apps
    participant HC as Health Connect
    participant G as Gym Buddy
    participant DB as Room

    SRC->>HC: Sync records
    G->>HC: Read permitted date range
    HC-->>G: sleep / weight / body fat / HR / steps
    G->>G: normalize while preserving source + timestamps
    G->>DB: cache DailyContext + HealthSamples

    Note over G,DB: Health Connect is never in the realtime camera critical path.
    Note over G,DB: Missing/stale/denied health data must not block workout coaching.
```

## 6. Realtime Processing Pipeline

```mermaid
flowchart LR
    C[CameraX] --> F[Latest-frame Scheduler]
    F --> P[MediaPipe Pose]
    P --> N[Coordinate Normalizer]
    N --> S[Landmark Smoother]
    S --> G{Tracking Quality Gate}
    G -- Invalid --> X[No biomechanics analysis\nShow camera/tracking guidance]
    G -- Valid --> A[Exercise-specific Analyzer]
    A --> M[Biomechanics Metrics]
    M --> R[Deterministic Rules]
    R --> Q[Cue Arbitration]
    Q --> O[Overlay / TTS]
    A --> DB[(Local Persistence)]
    M --> DB
    R --> DB
```

Normalization owns rotation, mirroring, left/right body identity, coordinate space, timestamps, and units. Backpressure is bounded: stale frames are dropped to protect realtime latency.

## 7. Persistence / Data Lifecycle

```mermaid
flowchart TD
    F[Camera Frame] --> P[Pose Inference]
    F -->|debug mode only| V[Debug Video]
    F -->|default| D1[Discard raw frame]
    P --> T[Telemetry Samples]
    T --> DB[(Room / SQLite)]
    M[Rep Metrics] --> DB
    E[Form Events + Evidence] --> DB
    TE[Tracking Events] --> DB
    H[Health Context Cache] --> DB
    V --> LF[Local File Storage]

    T -. bounded retention / downsampling .-> DB
```

Default retention policy: raw camera frames are discarded; debug video is explicit opt-in only; small rep metrics/form events are long-term; high-frequency telemetry must use bounded retention, downsampling, or compaction.

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
-> Room
```

Initial signals: rep count; exercise-specific phases; ROM; tempo; left/right symmetry; joint/forearm orientation; wrist-path/lateral-drift proxy; tracking confidence; rule evidence.

## Architectural Invariants

- Android only, one user.
- Local-first and airplane-mode capable for workout monitoring.
- No backend, LLM, login, or automatic video upload.
- Exercise selection is manual.
- ML is perception only; reasoning is deterministic code.
- Tracking quality can veto analysis.
- Exercise analyzers own exercise-specific state machines.
- Cue arbitration prevents voice spam.
- Telemetry and algorithm provenance are retained so old sessions can be re-analyzed.
- Health samples retain provenance and timestamps.
- Personal data never belongs in GitHub; only synthetic/anonymized fixtures may be committed.
