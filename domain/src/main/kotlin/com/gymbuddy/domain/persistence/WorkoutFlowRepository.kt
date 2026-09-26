package com.gymbuddy.domain.persistence

import com.gymbuddy.domain.evidence.SetCoachingSummary

data class LoadSnapshot(
    val value:Double,
    val unit:String?=null,
    val basis:LoadBasis=LoadBasis.UNKNOWN,
    val source:LoadSource=LoadSource.UNKNOWN,
    val resistanceKind:ResistanceKind=ResistanceKind.UNKNOWN,
    val measurementMode:LoadMeasurementMode=LoadMeasurementMode.UNKNOWN,
){
    init{
        require(value.isFinite()){"load value must be finite"}
        require(value>=0.0){"load value must be >= 0"}
        require(unit==null||unit.isNotBlank()){"load unit must not be blank"}
    }
}

data class RestCheckpointDraft(
    val completedSetId:String,
    val focus:String,
    val plannedNextLoad:LoadSnapshot?,
    val restStartedAtEpochMs:Long,
    val clockAnchor:RestClockAnchor?=null,
){
    init{
        require(completedSetId.isNotBlank())
        require(focus.isNotBlank())
        require(restStartedAtEpochMs>=0L)
    }
}

/** Durable completed results carried across runtime recreation. */
data class CompletedSetRecord(
    val set:SetRecord,
    val reps:Int,
    val focus:String="Repeat the same setup.",
    val coachingSummary:SetCoachingSummary?=null,
) { init { require(reps>=0); require(focus.isNotBlank()) } }

data class RestCheckpoint(
    val session:WorkoutSessionRecord,
    val execution:ExerciseExecutionRecord,
    val completedSet:SetRecord,
    val previousReps:Int,
    val focus:String,
    val plannedNextLoad:LoadSnapshot?,
    val restStartedAtEpochMs:Long,
    val completedSets:List<CompletedSetRecord> = emptyList(),
    val clockAnchor:RestClockAnchor?=null,
){
    init{
        require(execution.sessionId==session.sessionId)
        require(completedSet.executionId==execution.executionId)
        require(previousReps>=0)
        require(focus.isNotBlank())
        require(restStartedAtEpochMs>=0L)
    }
}

data class ActiveSetRecovery(
    val session:WorkoutSessionRecord,
    val execution:ExerciseExecutionRecord,
    val set:SetRecord,
    val committedReps:Int,
    val finalized:Boolean,
    val endedAtEpochMs:Long=0L,
    val completedSets:List<CompletedSetRecord> = emptyList(),
){
    init{
        require(execution.sessionId==session.sessionId)
        require(set.executionId==execution.executionId)
        require(committedReps>=0)
    }
}

interface WorkoutFlowRepository {
    fun saveActiveSetCheckpoint(setId:String)
    fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)
    fun loadRestCheckpoint():RestCheckpoint?
    fun loadActiveSetRecovery():ActiveSetRecovery?
    fun markInterruptedSet(setId:String,recoveredAtEpochMs:Long,committedReps:Int)
    fun clearRestCheckpoint()
}
