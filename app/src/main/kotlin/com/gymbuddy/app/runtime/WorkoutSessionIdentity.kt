package com.gymbuddy.app.runtime

import java.util.UUID

internal data class ExerciseIdentity(
    val sessionId:String,
    val executionId:String,
)

internal data class StartedExerciseIdentity(
    val sessionStartedAtUs:Long,
    val executionStartedAtUs:Long,
    val sessionStartedAtEpochMs:Long,
    val executionStartedAtEpochMs:Long,
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
    var sessionStartedAtEpochMs:Long?=null
        private set
    var executionStartedAtEpochMs:Long?=null
        private set

    fun beginExercise(exerciseId:String):ExerciseIdentity{
        require(exerciseId.isNotBlank())
        val session=sessionId?:idFactory().also{sessionId=it}
        val execution=idFactory()
        executionId=execution
        executionStartedAtUs=null
        executionStartedAtEpochMs=null
        return ExerciseIdentity(session,execution)
    }

    fun resume(
        sessionId:String,
        sessionStartedAtUs:Long,
        executionId:String,
        executionStartedAtUs:Long,
        sessionStartedAtEpochMs:Long=0L,
        executionStartedAtEpochMs:Long=0L,
    ){
        require(sessionId.isNotBlank())
        require(executionId.isNotBlank())
        require(sessionStartedAtUs>=0L)
        require(executionStartedAtUs>=0L)
        require(sessionStartedAtEpochMs>=0L)
        require(executionStartedAtEpochMs>=0L)
        this.sessionId=sessionId
        this.executionId=executionId
        this.sessionStartedAtUs=sessionStartedAtUs
        this.executionStartedAtUs=executionStartedAtUs
        this.sessionStartedAtEpochMs=sessionStartedAtEpochMs
        this.executionStartedAtEpochMs=executionStartedAtEpochMs
    }

    fun ensureStarted(
        firstFrameTimestampUs:Long,
        wallClockEpochMs:Long=0L,
    ):StartedExerciseIdentity{
        require(firstFrameTimestampUs>=0L)
        require(wallClockEpochMs>=0L)
        requireNotNull(sessionId){"beginExercise or resume must be called first"}
        requireNotNull(executionId){"beginExercise or resume must be called first"}

        val sessionStartUs=sessionStartedAtUs
            ?:firstFrameTimestampUs.also{sessionStartedAtUs=it}
        val executionStartUs=executionStartedAtUs
            ?:firstFrameTimestampUs.also{executionStartedAtUs=it}
        val sessionStartEpoch=sessionStartedAtEpochMs
            ?:wallClockEpochMs.also{sessionStartedAtEpochMs=it}
        val executionStartEpoch=executionStartedAtEpochMs
            ?:wallClockEpochMs.also{executionStartedAtEpochMs=it}

        return StartedExerciseIdentity(
            sessionStartUs,
            executionStartUs,
            sessionStartEpoch,
            executionStartEpoch,
        )
    }
}
