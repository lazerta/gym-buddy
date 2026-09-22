package com.gymbuddy.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities=[WorkoutSessionEntity::class,ExerciseExecutionEntity::class,SetEntity::class,AnalysisContextEntity::class,RepEvidenceEntity::class,RepSignalEvidenceEntity::class,RepMetricEvidenceEntity::class,FormObservationEntity::class,CueEventEntity::class,CueResponseEntity::class,TrackingQualitySummaryEntity::class,SetSummaryEntity::class],
    version=1,exportSchema=true
)
abstract class GymBuddyDatabase:RoomDatabase(){abstract fun evidenceDao():EvidenceDao}
