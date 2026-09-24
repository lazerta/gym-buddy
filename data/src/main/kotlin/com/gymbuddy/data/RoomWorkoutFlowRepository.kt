package com.gymbuddy.data

import com.gymbuddy.domain.persistence.ActiveSetRecovery
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutFlowRepository
import com.gymbuddy.domain.persistence.WorkoutSessionRecord

class RoomWorkoutFlowRepository(
    private val dao:EvidenceDao,
):WorkoutFlowRepository{
    override fun saveActiveSetCheckpoint(setId:String){
        requireNotNull(dao.set(setId))
        dao.upsertWorkoutFlowState(
            WorkoutFlowStateEntity(
                checkpointId=ACTIVE_CHECKPOINT,
                completedSetId=setId,
                state=STATE_ACTIVE_SET,
                focus=ACTIVE_FOCUS,
                plannedNextLoadValue=null,
                plannedNextLoadUnit=null,
                restStartedAtEpochMs=0L,
            )
        )
    }

    override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
        val set=requireNotNull(dao.set(checkpoint.completedSetId))
        requireNotNull(dao.setSummary(set.setId)){"completed set must have a summary before rest is saved"}
        dao.upsertWorkoutFlowState(
            WorkoutFlowStateEntity(
                checkpointId=ACTIVE_CHECKPOINT,
                completedSetId=set.setId,
                state=STATE_REST,
                focus=checkpoint.focus,
                plannedNextLoadValue=checkpoint.plannedNextLoad?.value,
                plannedNextLoadUnit=checkpoint.plannedNextLoad?.unit,
                restStartedAtEpochMs=checkpoint.restStartedAtEpochMs,
            )
        )
    }

    override fun loadRestCheckpoint():RestCheckpoint?{
        val state=dao.workoutFlowState(ACTIVE_CHECKPOINT)?:return null
        if(state.state!=STATE_REST)return null
        val set=requireNotNull(dao.set(state.completedSetId))
        val summary=requireNotNull(dao.setSummary(set.setId))
        val execution=requireNotNull(dao.execution(set.executionId))
        val session=requireNotNull(dao.session(execution.sessionId))
        return RestCheckpoint(
            session=WorkoutSessionRecord(
                session.sessionId,session.startedAtUs,session.startedAtEpochMs
            ),
            execution=ExerciseExecutionRecord(
                execution.executionId,
                execution.sessionId,
                execution.exerciseId,
                execution.startedAtUs,
                execution.startedAtEpochMs,
            ),
            completedSet=set.toRecord(),
            previousReps=summary.completedReps,
            focus=state.focus,
            plannedNextLoad=loadSnapshot(state.plannedNextLoadValue,state.plannedNextLoadUnit),
            restStartedAtEpochMs=state.restStartedAtEpochMs,
        )
    }

    override fun loadActiveSetRecovery():ActiveSetRecovery?{
        val state=dao.workoutFlowState(ACTIVE_CHECKPOINT)?:return null
        if(state.state!=STATE_ACTIVE_SET)return null
        val set=requireNotNull(dao.set(state.completedSetId))
        val execution=requireNotNull(dao.execution(set.executionId))
        val session=requireNotNull(dao.session(execution.sessionId))
        val summary=dao.setSummary(set.setId)
        val committed=summary?.completedReps?:dao.repsForSet(set.setId).size
        return ActiveSetRecovery(
            session=WorkoutSessionRecord(
                session.sessionId,session.startedAtUs,session.startedAtEpochMs
            ),
            execution=ExerciseExecutionRecord(
                execution.executionId,
                execution.sessionId,
                execution.exerciseId,
                execution.startedAtUs,
                execution.startedAtEpochMs,
            ),
            set=set.toRecord(),
            committedReps=committed,
            finalized=summary!=null,
        )
    }

    override fun markInterruptedSet(setId:String,recoveredAtEpochMs:Long,committedReps:Int){
        require(recoveredAtEpochMs>=0L)
        require(committedReps>=0)
        requireNotNull(dao.set(setId))
        require(dao.setSummary(setId)==null){"finalized set cannot be marked interrupted"}
        require(dao.repsForSet(setId).size==committedReps){"committed rep count mismatch"}
        dao.markInterruptedAndClearFlow(
            InterruptedSetEntity(setId,recoveredAtEpochMs,committedReps),
            ACTIVE_CHECKPOINT,
        )
    }

    override fun clearRestCheckpoint(){
        dao.deleteWorkoutFlowState(ACTIVE_CHECKPOINT)
    }

    private fun SetEntity.toRecord()=SetRecord(
        setId,
        executionId,
        setOrdinal,
        startedAtUs,
        loadSnapshot(actualLoadValue,actualLoadUnit),
        startedAtEpochMs,
    )

    private fun loadSnapshot(value:Double?,unit:String?):LoadSnapshot?{
        require(value!=null||unit==null)
        return value?.let{LoadSnapshot(it,unit)}
    }

    companion object {
        private const val ACTIVE_CHECKPOINT="active"
        private const val STATE_ACTIVE_SET="ACTIVE_SET"
        private const val STATE_REST="REST"
        private const val ACTIVE_FOCUS="Active set in progress."
    }
}
