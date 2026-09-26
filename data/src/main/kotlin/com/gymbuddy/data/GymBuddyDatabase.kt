package com.gymbuddy.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities=[
        WorkoutSessionEntity::class,ExerciseExecutionEntity::class,SetEntity::class,AnalysisContextEntity::class,
        RepEvidenceEntity::class,RepSignalEvidenceEntity::class,RepMetricEvidenceEntity::class,RepPhaseEvidenceEntity::class,
        InvalidAttemptEvidenceEntity::class,FormObservationEntity::class,CueEventEntity::class,CueResponseEntity::class,
        CueDeliveryEntity::class,TrackingQualitySummaryEntity::class,SetSummaryEntity::class,WorkoutFlowStateEntity::class,
        InterruptedSetEntity::class,PersonalCalibrationProfileEntity::class,PersonalCalibrationProfileHistoryEntity::class,
        ExercisePreferenceEntity::class,EquipmentContextEntity::class,WorkoutExerciseCompletionEntity::class,
        WorkoutProductStateEntity::class,GptAnalysisEntity::class,
    ],
    version=9,
    exportSchema=true,
)
abstract class GymBuddyDatabase:RoomDatabase(){abstract fun evidenceDao():EvidenceDao}
