package com.gymbuddy.data

import com.gymbuddy.domain.persistence.*

class RoomWorkoutProductRepository(
    private val dao:EvidenceDao,
):WorkoutProductRepository{
    override fun loadSelectionSnapshot():WorkoutSelectionSnapshot{
        val active=dao.workoutProductState(ACTIVE_SLOT)?.activeSessionId
        return WorkoutSelectionSnapshot(
            activeSessionId=active,
            preferences=dao.exercisePreferences().map{
                ExercisePreferenceRecord(it.exerciseId,it.favorite,it.lastSelectedAtEpochMs,it.equipmentContextId)
            },
            completions=active?.let(dao::workoutCompletions).orEmpty().map{
                WorkoutExerciseCompletionRecord(it.sessionId,it.exerciseId,it.completedSets,it.completedAtEpochMs)
            },
            equipmentContexts=dao.equipmentContexts().map{
                EquipmentContextRecord(it.contextId,it.baseEquipmentProfileId,it.label,it.updatedAtEpochMs)
            },
        )
    }

    override fun setActiveSession(sessionId:String?){
        require(sessionId==null||sessionId.isNotBlank())
        dao.upsertWorkoutProductState(WorkoutProductStateEntity(ACTIVE_SLOT,sessionId))
    }

    override fun rememberExerciseSelection(record:ExercisePreferenceRecord){
        val current=dao.exercisePreferences().firstOrNull{it.exerciseId==record.exerciseId}
        dao.upsertExercisePreference(
            ExercisePreferenceEntity(
                exerciseId=record.exerciseId,
                favorite=current?.favorite?:record.favorite,
                lastSelectedAtEpochMs=maxOf(current?.lastSelectedAtEpochMs?:0L,record.lastSelectedAtEpochMs),
                equipmentContextId=record.equipmentContextId?:current?.equipmentContextId,
            )
        )
    }

    override fun setFavorite(exerciseId:String,favorite:Boolean){
        require(exerciseId.isNotBlank())
        val current=dao.exercisePreferences().firstOrNull{it.exerciseId==exerciseId}
        dao.upsertExercisePreference(
            ExercisePreferenceEntity(
                exerciseId=exerciseId,
                favorite=favorite,
                lastSelectedAtEpochMs=current?.lastSelectedAtEpochMs?:0L,
                equipmentContextId=current?.equipmentContextId,
            )
        )
    }

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
