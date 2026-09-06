# Gym Buddy UML Overview

This page renders directly on GitHub using Mermaid.

## 1. System Context

```mermaid
flowchart LR
    U[Shawn\nSingle User] --> A[Gym Buddy Android App]
    C[CameraX] --> A
    H[Health Connect] --> A
    A --> T[Android TTS / Overlay]
    A --> D[(Room / SQLite)]

    subgraph AppBoundary[On-device boundary]
      A
      T
      D
    end

    N[No backend\nNo LLM\nNo login\nNo video upload] -. architectural constraint .-> A
```

## 2. Android Component Architecture

```mermaid
flowchart LR
    UI[Jetpack Compose UI] --> CAM[CameraX]
    CAM --> POSE[MediaPipe Pose Landmarker]
    POSE --> SIG[Signal Processor\nSmoothing / velocity / trajectory]
    SIG --> ANA[Exercise Analyzer\nIncline DB Press first]
    ANA --> FSM[Rep State Machine]
    ANA --> MET[Biomechanics Metrics]
    FSM --> RULES[Deterministic Rule Engine]
    MET --> RULES
    RULES --> CUE[Cue Engine]
    CUE --> UI
    CUE --> TTS[Android TTS]
    ANA --> DB[(Room Database)]
    RULES --> DB
    HC[Health Connect Adapter] --> DB

    POSE:::ml
    classDef ml stroke-dasharray:5 5;
```

**Rule:** ML is used for perception only. Movement reasoning and coaching decisions are deterministic Kotlin code.

## 3. Domain / Data Model

```mermaid
classDiagram
    class UserProfile {
      +heightCm
      +currentWeightKg
    }

    class DailyContext {
      +date
      +sleepMinutes
      +sleepQuality
      +weightKg
      +bodyFatPct
      +restingHeartRate
      +steps
      +calories
      +proteinG
      +carbsG
      +fatG
    }

    class WorkoutSession {
      +id
      +startedAt
      +endedAt
      +exerciseId
    }

    class ExerciseSet {
      +setNumber
      +load
      +targetReps
    }

    class Rep {
      +repNumber
      +startedAt
      +endedAt
      +phaseQuality
    }

    class PoseSample {
      +timestampMs
      +landmarks
      +confidence
      +phase
    }

    class RepMetric {
      +metricType
      +value
      +unit
    }

    class FormEvent {
      +ruleId
      +ruleVersion
      +severity
      +confidence
      +cue
    }

    UserProfile "1" --> "many" DailyContext
    DailyContext "1" --> "many" WorkoutSession
    WorkoutSession "1" --> "many" ExerciseSet
    ExerciseSet "1" --> "many" Rep
    Rep "1" --> "many" PoseSample
    Rep "1" --> "many" RepMetric
    Rep "1" --> "many" FormEvent
```

## 4. Live Workout Analysis Sequence

```mermaid
sequenceDiagram
    participant U as User
    participant C as CameraX
    participant P as MediaPipe Pose
    participant S as Signal Processor
    participant A as Exercise Analyzer
    participant R as Rule Engine
    participant Q as Cue Engine
    participant DB as Room

    U->>C: Start Incline DB Press set
    loop Every camera frame
      C->>P: Frame + timestamp
      P-->>S: Landmarks + confidence
      S-->>A: Smoothed landmarks / motion features
      A->>A: Update rep phase
      A->>A: Calculate ROM / tempo / symmetry / angles
      A->>R: Metrics + phase + recent history
      R-->>Q: FormEvent only if persistent + confident
      Q-->>U: Overlay / short TTS cue
      A-->>DB: Pose / rep telemetry
      R-->>DB: Rule evidence + FormEvent
    end
```

### Realtime cue gate

```mermaid
flowchart LR
    E[Potential error] --> C{Confidence high?}
    C -- No --> X[Ignore]
    C -- Yes --> P{Persists long enough?}
    P -- No --> X
    P -- Yes --> S{Severity meaningful?}
    S -- No --> X
    S -- Yes --> D{Cue cooldown expired?}
    D -- No --> X
    D -- Yes --> Q[Emit one short correction]
```

## 5. Health Connect Sequence

```mermaid
sequenceDiagram
    participant SRC as RENPHO / Watch / Health Apps
    participant HC as Health Connect
    participant G as Gym Buddy
    participant DB as Room

    SRC->>HC: Sync health records
    G->>HC: Read permitted records for date range
    HC-->>G: Sleep / weight / body fat / HR / steps
    G->>G: Normalize into DailyContext
    G->>DB: Persist local context

    Note over G,DB: Health data never enters the realtime camera critical path.
    Note over G,DB: If Health Connect is unavailable, workout coaching still works.
```

## MVP Scope

The first exercise is **Incline Dumbbell Press**. The first implementation must prove:

```text
CameraX
-> MediaPipe Pose
-> landmark smoothing
-> rep phase state machine
-> biomechanics metrics
-> deterministic form rules
-> overlay / TTS
-> Room persistence
```

Initial form/quality signals:

- rep count
- eccentric / bottom / concentric / lockout phase
- ROM
- rep tempo
- left/right symmetry
- forearm orientation
- wrist-path / lateral-drift proxy
- confidence and persistence gating for cues

## Architectural Invariants

- Android only.
- One user.
- Local-first and airplane-mode capable.
- No backend required.
- No LLM required.
- No login.
- No video upload.
- Exercise selection is manual.
- Raw video is not persisted by default.
- Structured movement telemetry is the source of truth.
- Personal health/workout recordings do not belong in GitHub; only synthetic/anonymized fixtures may be committed.
