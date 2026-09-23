package com.gymbuddy.domain.persistence

data class LoadSnapshot(
    val value:Double,
    val unit:String?=null,
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
){
    init{
        require(completedSetId.isNotBlank())
        require(focus.isNotBlank())
        require(restStartedAtEpochMs>=0L)
    }
}

data class RestCheckpoint(
    val session:WorkoutSessionRecord,
    val execution:ExerciseExecutionRecord,
    val completedSet:SetRecord,
    val previousReps:Int,
    val focus:String,
    val plannedNextLoad:LoadSnapshot?,
    val restStartedAtEpochMs:Long,
){
    init{
        require(execution.sessionId==session.sessionId)
        require(completedSet.executionId==execution.executionId)
        require(previousReps>=0)
        require(focus.isNotBlank())
        require(restStartedAtEpochMs>=0L)
    }
}

interface WorkoutFlowRepository {
    fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)
    fun loadRestCheckpoint():RestCheckpoint?
    fun clearRestCheckpoint()
}
