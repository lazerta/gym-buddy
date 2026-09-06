# Gym Buddy UML Overview v0.3

Architecture freeze candidate for a single-user, local-first Android biomechanics coach.

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

    N[No backend\nNo LLM\nNo login\nNo Gym Buddy cloud data path\nAirplane-mode workout monitoring] -. constraints .-> A
    A -. no automatic personal-data export .-x G[GitHub / Google Drive]
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
      +sleepMinutes
      +sleepQuality
    }

    class WeeklyBodyComposition {
      +weekStart
      +weightKg
      +bodyFatPct
      +aggregation
      +sourceSummary
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
    UserProfile "1" --> "many" WeeklyBodyComposition
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

Telemetry belongs to the set and may optionally reference a rep. Weight/body-fat are not retained daily in Gym Buddy: consolidate valid measurements into one `WeeklyBodyComposition` snapshot, preferably using the weekly median.

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
    G->>DB: cache only what Gym Buddy needs
    G->>G: weekly consolidate weight/body-fat
    G->>DB: store WeeklyBodyComposition

    Note over G,DB: Health Connect is never in the realtime camera critical path.
    Note over G,DB: Fine-grained Health Connect history should not be duplicated permanently.
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
    F -->|default| D1[Discard immediately]
    P --> T[High-frequency Telemetry]
    T -->|7 days| DB[(Room / SQLite)]
    TE[Tracking / Calibration Debug Events] -->|7 days| DB
    M[Rep Metrics] -->|long-term| DB
    E[Form Events + Evidence] -->|long-term| DB
    S[Daily Sleep / Nutrition Summary] -->|long-term| DB
    W[Weekly Body Composition] -->|long-term| DB
    H[Fine-grained Health Connect Records] -->|temporary cache only| DB
    V -->|max 7 days| LF[App-private no-backup storage]
```

### Retention policy

| Data | Retention |
|---|---|
| Raw camera frame | discard immediately |
| High-frequency pose/movement telemetry | 7 days |
| Tracking/calibration debug events | 7 days |
| Debug video | OFF by default; max 7 days if enabled |
| Compact/downsampled rep trace | long-term if useful |
| Rep metrics / form events / rule evidence | long-term |
| Set/workout summaries | long-term |
| Sleep/nutrition summaries | long-term |
| Weight/body fat | one weekly snapshot; prefer weekly median |
| Fine-grained Health Connect history | do not permanently duplicate |

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
-> Room
```

Initial signals: rep count; exercise-specific phases; ROM; tempo; left/right symmetry; joint/forearm orientation; wrist-path/lateral-drift proxy; tracking confidence; rule evidence.

## Architectural Invariants

- Android only, one user.
- Real personal data stays on the Android device only.
- Local-first and airplane-mode capable for workout monitoring.
- No backend, LLM, login, or Gym Buddy cloud data path.
- Android backup/cloud backup of Gym Buddy personal data is disabled.
- Debug media remains app-private/no-backup unless explicitly exported.
- Exercise selection is manual.
- ML is perception only; reasoning is deterministic code.
- Tracking quality can veto analysis.
- Exercise analyzers own exercise-specific state machines.
- Cue arbitration prevents voice spam.
- Raw/high-frequency debugging data has a 7-day window; compact historical data is retained long-term.
- Weight/body-fat is stored weekly, not daily.
- GitHub and Google Drive never contain real Gym Buddy personal data.
