# Gym Buddy UML Overview v0.6

Final architecture review candidate for a single-user, local-first Android biomechanics coach.

## 1. System Context

```mermaid
flowchart LR
    U[Shawn\nSingle User] --> A[Gym Buddy Android App]
    C[CameraX] --> A
    H[Health Connect] --> A
    A --> T[Android TTS\nOffline voice only]
    A --> D[(Room / SQLite)]

    subgraph OnDevice[On-device personal-data boundary]
      A
      T
      D
    end

    N[No backend\nNo LLM\nNo login\nNo INTERNET permission\nBundled pose model\nNo personal-data export/share\nAirplane-mode workout monitoring] -. constraints .-> A
    A -. no personal-data path .-x G[GitHub / Google Drive]
```

Real Gym Buddy personal data stays on the Android device. TTS uses only a locally installed non-network voice; otherwise cues are visual-only.

## 2. Android Component Architecture

```mermaid
flowchart LR
    UI[Compose UI] --> CAM[CameraX]
    CAM --> FS[Latest-frame Scheduler]
    FS --> POSE[Bundled MediaPipe Pose]
    POSE --> NORM[Coordinate Normalizer]
    NORM --> SIG[Landmark Smoother / Motion Features]
    SIG --> QG[Tracking Quality Gate]
    QG --> ANA[Exercise-specific Analyzer]
    QG --> UI
    ANA --> MET[Biomechanics Metrics]
    MET --> RULES[Deterministic Rule Engine]
    RULES --> CUE[Cue Policy + Arbitrator]
    CUE --> UI
    CUE --> TTS[Offline-only Android TTS]

    ANA --> TQ[Telemetry Ring Buffer\nBest-effort]
    ANA --> DQ[Durable Event Queue\nPriority]
    MET --> DQ
    RULES --> DQ
    CUE --> DQ
    DQ --> BW[Async BatchWriter]
    TQ --> BW
    BW --> REPO[Workout Repository]
    REPO --> DB[(Room)]

    HC[Health Connect Adapter] --> HA[Health Aggregator]
    HA --> REPO
    RW[Retention Worker] --> REPO
    RW --> FILES[App-private no-backup files]
```

**Key rules:** stale frames are dropped; invalid tracking blocks analysis; storage is off the realtime path; durable records use a separate priority lane; retention cleanup runs at startup and periodically.

## 3. Domain / Data Model

```mermaid
classDiagram
    class UserProfile {
      +heightCm
      +preferredUnits
      +activeTrainingPlanId
      +activeTrainingPlanVersion
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
      +notes
    }
    class PlannedSet {
      +setNumber
      +targetLoadPct
      +loadPercentBasis
      +repsMin
      +repsMax
      +rirMin
      +rirMax
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
      +startedAt
      +endedAt
      +trainingPlanId
      +trainingPlanVersion
      +appBuild
      +poseModelVersion
    }
    class ExerciseExecution {
      +exerciseId
      +plannedExerciseId
      +order
      +analyzerVersion
      +metricVersion
      +calibrationVersion
    }
    class ExerciseSet {
      +plannedSetId
      +setNumber
      +loadKg
      +targetReps
      +startedAt
      +endedAt
      +telemetryExpiresAt
    }
    class Rep {
      +repNumber
      +startedAtElapsedNanos
      +endedAtElapsedNanos
      +completionState
    }
    class TelemetrySample {
      +elapsedRealtimeNanos
      +repId nullable
      +phase nullable
      +landmarks
      +trackingConfidence
    }
    class TrackingEvent {
      +elapsedRealtimeNanos
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
      +id
      +ruleId
      +ruleVersion
      +severity
      +confidence
      +startedAtElapsedNanos
      +endedAtElapsedNanos
    }
    class FormEvidence {
      +key
      +observedValue
      +unit
      +comparator
      +thresholdValue
      +textValue
    }
    class CueDelivery {
      +deliveredAtElapsedNanos
      +channel
      +message
    }
    class CompactRepTrace {
      +traceVersion
      +schemaId
      +pointCount
      +normalizedTime
    }
    class TraceChannel {
      +name
      +unit
      +side
      +values
    }

    UserProfile "1" --> "many" TrainingPlan
    TrainingPlan "1" --> "many" TrainingDay
    TrainingDay "1" --> "many" PlannedExercise
    PlannedExercise "1" --> "many" PlannedSet
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
    FormEvent "1" --> "many" FormEvidence
    FormEvent "1" --> "many" CueDelivery
    Rep "1" --> "0..1" CompactRepTrace
    CompactRepTrace "1" --> "many" TraceChannel
    WorkoutSession "many" --> "0..1" DailyContext
    WorkoutSession "many" --> "0..1" DailyHealthSummary
```

`PlannedSet` can preserve load %, rep and RIR prescriptions without guessing the percentage basis. `FormEvent` means detected issue; `FormEvidence[]` contains structured evidence; `CueDelivery` exists only for feedback actually delivered. `ExerciseSet.telemetryExpiresAt` anchors 7-day short-lived data cleanup.

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
    participant T as Telemetry Queue
    participant D as Durable Queue
    participant W as BatchWriter
    participant DB as Room

    U->>C: Start selected exercise set
    loop realtime
      C->>F: frame + monotonic timestamp
      F->>P: newest eligible frame
      P-->>N: landmarks + confidence
      N-->>G: canonical MovementFrame
      alt tracking invalid
        G-->>U: setup / visibility guidance
      else valid
        G-->>A: valid MovementFrame
        A->>A: update rep state + metrics
        A->>R: metrics + phase + history
        R-->>Q: FormEvent + structured FormEvidence
        R-->>D: persist detected issue + evidence
        alt cue selected
          Q-->>U: one prioritized visual / offline-TTS cue
          Q-->>D: CueDelivery
        end
        A-->>T: best-effort raw telemetry
        opt rep completed
          A-->>D: rep summary + CompactRepTrace + final metrics
        end
      end
    end

    loop async durable persistence
      D->>W: durable batch first
      W->>DB: transaction
    end
    loop async telemetry persistence
      T->>W: telemetry batch
      W->>DB: transaction
    end
```

Detection and delivery are distinct. The realtime path never waits for Room.

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
    A->>A: build DailyHealthSummary
    A->>A: weekly medians + sample counts
    A->>DB: persist compact summaries only
    A->>A: discard fine-grained imported records
```

Health Connect is optional and never blocks workout monitoring.

## 6. Realtime Processing Pipeline

```mermaid
flowchart LR
    C[CameraX] --> F[Latest-frame Scheduler]
    F --> P[Bundled MediaPipe Pose]
    P --> N[Coordinate Normalizer]
    N --> S[Landmark Smoother]
    S --> G{Tracking Quality Gate}
    G -- Invalid --> X[Setup/tracking guidance only]
    G -- Valid --> A[Exercise-specific Analyzer]
    A --> M[Biomechanics Metrics]
    M --> R[Deterministic Rules]
    R --> Q[Cue Arbitration]
    Q --> O[Overlay / Offline TTS]

    A --> T[Telemetry Ring Buffer]
    A --> D[Durable Event Queue]
    M --> D
    R --> D
    Q --> D
    D --> W[Async BatchWriter]
    T --> W
    W --> DB[(Room)]
```

Backpressure exists independently at camera ingestion and storage. Durable rep/evidence/cue records are prioritized over raw telemetry.

## 7. Persistence / Data Lifecycle

```mermaid
flowchart TD
    F[Camera Frame] --> P[Pose Inference]
    F -->|debug mode only| V[Debug Video]
    F -->|default| D1[Discard immediately]
    P --> T[High-frequency Telemetry]
    T -->|expires via ExerciseSet.telemetryExpiresAt| DB[(Room / SQLite)]
    TE[Tracking / Calibration Debug Events] -->|same expiry| DB
    M[Rep Metrics] -->|long-term| DB
    E[FormEvent + FormEvidence] -->|long-term| DB
    CD[CueDelivery] -->|long-term| DB
    CT[CompactRepTrace + TraceChannels] -->|long-term| DB
    N[Daily Nutrition Context] -->|long-term| DB
    DH[DailyHealthSummary] -->|long-term| DB
    BC[WeeklyBodyComposition] -->|long-term| DB
    H[Fine-grained Health Connect Records] -->|aggregate in memory| AGG[Health Aggregator]
    AGG --> DH
    AGG --> BC
    V -->|7-day expiry| LF[App-private no-backup storage]
    RW[Retention Worker] -->|startup + periodic purge| DB
    RW -->|purge expired debug media| LF
```

### Retention policy

| Data | Retention |
|---|---|
| Raw camera frame | discard immediately |
| High-frequency pose/movement telemetry | expires 7 days after set close |
| Tracking/calibration debug events | same 7-day set expiry |
| Debug video | OFF by default; 7-day expiry |
| CompactRepTrace + TraceChannel[] | long-term |
| Rep metrics / FormEvent / FormEvidence / CueDelivery | long-term |
| Set/workout summaries | long-term |
| Daily nutrition / DailyHealthSummary | long-term when present |
| Weight/body fat | weekly medians + sample counts |
| Fine-grained Health Connect records | aggregate in memory; not durable |

Expired data is excluded from normal reads once its expiry passes; local startup/periodic cleanup physically purges it.

## MVP Scope

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
rep summary / CompactRepTrace / final metrics / FormEvent+FormEvidence / CueDelivery -> DurableEventQueue
both -> async BatchWriter -> Room
```

## Architectural Invariants

- Android only, one user.
- Real personal data stays on the Android device only.
- No backend, LLM, login, INTERNET permission, cloud path, or personal-data export/share in MVP.
- Pose model bundled in APK.
- Android cloud backup disabled.
- Debug media stays app-private/no-backup and expires after 7 days.
- Offline TTS voice only; visual fallback otherwise.
- ML is perception only; reasoning is deterministic code.
- Tracking quality can veto analysis.
- Exercise analyzers own exercise-specific state machines.
- Detection, evidence and cue delivery are separately observable.
- Persistence cannot block realtime coaching.
- Retention cleanup is explicit and enforced.
- Raw/high-frequency data has a 7-day window; compact history remains.
- Weight/body-fat is weekly, not daily.
- GitHub and Google Drive never contain real Gym Buddy personal data.
