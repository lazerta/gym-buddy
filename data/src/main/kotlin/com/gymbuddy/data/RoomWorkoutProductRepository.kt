package com.gymbuddy.data

import com.gymbuddy.domain.persistence.*

class RoomWorkoutProductRepository(
    private val dao:EvidenceDao,
):WorkoutProductRepository{
    override fun loadSelectionSnapshot():WorkoutSelectionSnapshot=dao.readProductSnapshot()

    override fun setActiveSession(sessionId:String?){
        require(sessionId==null||sessionId.isNotBlank())
        dao.upsertWorkoutProductState(WorkoutProductStateEntity(ACTIVE_SLOT,sessionId))
    }

    override fun rememberExerciseSelection(record:ExercisePreferenceRecord)=dao.rememberSelection(record)
    override fun setFavorite(exerciseId:String,favorite:Boolean)=dao.changeFavorite(exerciseId,favorite)

    override fun rememberEquipmentContext(record:EquipmentContextRecord){
        dao.upsertEquipmentContext(
            EquipmentContextEntity(
                record.contextId,record.baseEquipmentProfileId,record.label,record.updatedAtEpochMs
            )
        )
    }

    override fun markExerciseCompleted(record:WorkoutExerciseCompletionRecord){
        dao.upsertWorkoutExerciseCompletion(
            WorkoutExerciseCompletionEntity(
                record.sessionId,record.exerciseId,record.completedSets,record.completedAtEpochMs
            )
        )
    }

    override fun clearWorkoutCompletion(sessionId:String){
        require(sessionId.isNotBlank())
        dao.deleteWorkoutCompletions(sessionId)
    }

    companion object { private const val ACTIVE_SLOT="active" }
}
