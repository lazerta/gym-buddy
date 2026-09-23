package com.gymbuddy.app.controller

enum class WorkoutDay(val label:String){PUSH("Push"),PULL("Pull"),LEGS("Legs")}

data class ExerciseRowUiState(
    val exerciseId:String,
    val displayName:String,
)

enum class CameraReadinessUi { SETTING_UP, READY }

data class CompletedSetUiState(
    val setNumber:Int,
    val reps:Int,
    val actualLoadText:String,
    val focus:String,
)

sealed interface WorkoutUiState {
    data class ExerciseSelection(
        val selectedDay:WorkoutDay,
        val exercises:List<ExerciseRowUiState>,
    ):WorkoutUiState

    data class CameraSetup(
        val exerciseId:String,
        val exerciseName:String,
        val instruction:String,
        val readiness:CameraReadinessUi,
        val setNumber:Int,
    ):WorkoutUiState

    data class ActiveSet(
        val exerciseId:String,
        val exerciseName:String,
        val setNumber:Int,
        val actualLoadText:String,
        val repCount:Int,
        val trackingText:String,
        val cue:String?,
    ):WorkoutUiState

    data class Rest(
        val exerciseId:String,
        val exerciseName:String,
        val completedSetNumber:Int,
        val previousReps:Int,
        val previousActualLoadText:String,
        val focus:String,
        val plannedNextLoadText:String,
        val restStartedAtEpochMs:Long,
    ):WorkoutUiState

    data class Summary(
        val exerciseId:String,
        val exerciseName:String,
        val completedSets:List<CompletedSetUiState>,
        val evidenceSummary:String,
    ):WorkoutUiState
}

val WorkoutUiState.requiresCamera:Boolean
    get()=this is WorkoutUiState.CameraSetup||this is WorkoutUiState.ActiveSet
