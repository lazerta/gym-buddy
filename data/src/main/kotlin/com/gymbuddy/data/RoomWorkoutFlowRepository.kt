package com.gymbuddy.data

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
    override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
        val set=requireNotNull(dao.set(checkpoint.completedSetId))
        requireNotNull(dao.setSummary(set.setId)){"completed set must have a summary before rest is saved"}
        dao.upsertWorkoutFlowState(
            WorkoutFlowStateEntity(
                checkpointId=ACTIVE_CHECKPOINT,
                completedSetId=set.setId,
                focus=checkpoint.focus,
                plannedNextLoadValue=checkpoint.plannedNextLoad?.value,
                plannedNextLoadUnit=checkpoint.plannedNextLoad?.unit,
                restStartedAtEpochMs=checkpoint.restStartedAtEpochMs,
            )
        )
    }

    override fun loadRestCheckpoint():RestCheckpoint?{
        val state=dao.workoutFlowState(ACTIVE_CHECKPOINT)?:return null
        val set=requireNotNull(dao.set(state.completedSetId))
        val summary=requireNotNull(dao.setSummary(set.setId))
        val execution=requireNotNull(dao.execution(set.executionId))
        val session=requireNotNull(dao.session(execution.sessionId))
        return RestCheckpoint(
            session=WorkoutSessionRecord(session.sessionId,session.startedAtUs),
            execution=ExerciseExecutionRecord(
                execution.executionId,
                execution.sessionId,
                execution.exerciseId,
                execution.startedAtUs,
            ),
            completedSet=SetRecord(
                set.setId,
                set.executionId,
                set.setOrdinal,
                set.startedAtUs,
                loadSnapshot(set.actualLoadValue,set.actualLoadUnit),
            ),
            previousReps=summary.completedReps,
            focus=state.focus,
            plannedNextLoad=loadSnapshot(state.plannedNextLoadValue,state.plannedNextLoadUnit),
            restStartedAtEpochMs=state.restStartedAtEpochMs,
        )
    }

    override fun clearRestCheckpoint(){
        dao.deleteWorkoutFlowState(ACTIVE_CHECKPOINT)
    }

    private fun loadSnapshot(value:Double?,unit:String?):LoadSnapshot?{
        require(value!=null||unit==null)
        return value?.let{LoadSnapshot(it,unit)}
    }

    companion object {
        private const val ACTIVE_CHECKPOINT="active"
    }
}
