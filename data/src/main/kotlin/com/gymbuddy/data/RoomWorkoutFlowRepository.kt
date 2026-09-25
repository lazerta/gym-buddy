package com.gymbuddy.data

import com.gymbuddy.domain.persistence.ActiveSetRecovery
import com.gymbuddy.domain.persistence.CompletedSetRecord
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadBasis
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.LoadSource
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
                plannedNextLoadBasis=LoadBasis.UNKNOWN.name,
                plannedNextLoadSource=LoadSource.UNKNOWN.name,
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
                plannedNextLoadBasis=checkpoint.plannedNextLoad?.basis?.name?:LoadBasis.UNKNOWN.name,
                plannedNextLoadSource=checkpoint.plannedNextLoad?.source?.name?:LoadSource.UNKNOWN.name,
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
                execution.plannedExerciseId,
                execution.equipmentContextId,
            ),
            completedSet=set.toRecord(),
            previousReps=summary.completedReps,
            focus=state.focus,
            plannedNextLoad=loadSnapshot(
                state.plannedNextLoadValue,state.plannedNextLoadUnit,
                state.plannedNextLoadBasis,state.plannedNextLoadSource,
            ),
            restStartedAtEpochMs=state.restStartedAtEpochMs,
            completedSets=completedHistory(execution,set.setOrdinal).map { result ->
                if(result.set.setId==set.setId)result.copy(focus=state.focus) else result
            },
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
                execution.plannedExerciseId,
                execution.equipmentContextId,
            ),
            set=set.toRecord(),
            committedReps=committed,
            finalized=summary!=null,
            endedAtEpochMs=summary?.endedAtEpochMs?:0L,
            completedSets=completedHistory(execution,set.setOrdinal),
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

    // Only durably completed sets from this execution belong in its recovered
    // summary. Repeated executions of the same exercise remain independent.
    private fun completedHistory(
        execution:ExerciseExecutionEntity,
        throughOrdinal:Int,
    ):List<CompletedSetRecord> = dao.completedSetIdsForSessionExercise(
        execution.sessionId,execution.exerciseId,
    ).map { id -> requireNotNull(dao.set(id)) }
        .filter { it.executionId==execution.executionId && it.setOrdinal<=throughOrdinal }
        .sortedBy { it.setOrdinal }
        .map { set ->
            val summary=requireNotNull(dao.setSummary(set.setId))
            CompletedSetRecord(set.toRecord(),summary.completedReps)
        }

    private fun SetEntity.toRecord()=SetRecord(
        setId,
        executionId,
        setOrdinal,
        startedAtUs,
        loadSnapshot(actualLoadValue,actualLoadUnit,actualLoadBasis,actualLoadSource),
        startedAtEpochMs,
        loadSnapshot(plannedLoadValue,plannedLoadUnit,plannedLoadBasis,plannedLoadSource),
    )

    private fun loadSnapshot(
        value:Double?,unit:String?,basis:String=LoadBasis.UNKNOWN.name,source:String=LoadSource.UNKNOWN.name,
    ):LoadSnapshot?{
        require(value!=null||unit==null)
        return value?.let{LoadSnapshot(it,unit,LoadBasis.valueOf(basis),LoadSource.valueOf(source))}
    }

    companion object {
        private const val ACTIVE_CHECKPOINT="active"
        private const val STATE_ACTIVE_SET="ACTIVE_SET"
        private const val STATE_REST="REST"
        private const val ACTIVE_FOCUS="Active set in progress."
    }
}
