package com.gymbuddy.app.runtime

import java.util.UUID

internal data class ExerciseIdentity(
    val sessionId:String,
    val executionId:String,
)

internal data class StartedExerciseIdentity(
    val sessionStartedAtUs:Long,
    val executionStartedAtUs:Long,
)

internal class WorkoutSessionIdentity(
    private val idFactory:()->String={UUID.randomUUID().toString()},
){
    var sessionId:String?=null
        private set
    var executionId:String?=null
        private set
    var sessionStartedAtUs:Long?=null
        private set
    var executionStartedAtUs:Long?=null
        private set

    fun beginExercise(exerciseId:String):ExerciseIdentity{
        require(exerciseId.isNotBlank())
        val session=sessionId?:idFactory().also{sessionId=it}
        val execution=idFactory()
        executionId=execution
        executionStartedAtUs=null
        return ExerciseIdentity(session,execution)
    }

    fun resume(
        sessionId:String,
        sessionStartedAtUs:Long,
        executionId:String,
        executionStartedAtUs:Long,
    ){
        require(sessionId.isNotBlank())
        require(executionId.isNotBlank())
        require(sessionStartedAtUs>=0L)
        require(executionStartedAtUs>=sessionStartedAtUs)
        this.sessionId=sessionId
        this.executionId=executionId
        this.sessionStartedAtUs=sessionStartedAtUs
        this.executionStartedAtUs=executionStartedAtUs
    }

    fun ensureStarted(firstFrameTimestampUs:Long):StartedExerciseIdentity{
        require(firstFrameTimestampUs>=0L)
        requireNotNull(sessionId){"beginExercise or resume must be called first"}
        requireNotNull(executionId){"beginExercise or resume must be called first"}
        val sessionStart=sessionStartedAtUs?:firstFrameTimestampUs.also{sessionStartedAtUs=it}
        val executionStart=executionStartedAtUs?:firstFrameTimestampUs.also{executionStartedAtUs=it}
        return StartedExerciseIdentity(sessionStart,executionStart)
    }
}
