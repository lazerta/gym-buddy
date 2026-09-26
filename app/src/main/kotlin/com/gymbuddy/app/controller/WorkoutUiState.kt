package com.gymbuddy.app.controller

import com.gymbuddy.domain.persistence.LoadBasis
import com.gymbuddy.domain.persistence.LoadSource

enum class WorkoutDay(val label:String){PUSH("Push"),PULL("Pull"),LEGS("Legs")}

data class ExerciseRowUiState(
    val exerciseId:String,
    val displayName:String,
    val completedSets:Int=0,
    val favorite:Boolean=false,
    val recent:Boolean=false,
    val equipmentLabel:String?=null,
)

data class GptAnalysisUiState(
    val analysisId:String,
    val modelLabel:String,
    val summary:String,
    val createdAtEpochMs:Long,
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
        val otherExerciseOpen:Boolean=false,
        val searchQuery:String="",
        val searchResults:List<ExerciseRowUiState> = emptyList(),
        val recentExercises:List<ExerciseRowUiState> = emptyList(),
        val favoriteExercises:List<ExerciseRowUiState> = emptyList(),
        val substitutionForExerciseId:String?=null,
        val equipmentEditorExerciseId:String?=null,
        val equipmentLabelInput:String="",
        val errorMessage:String?=null,
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
        val cameraInstruction:String?=null,
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
        val plannedNextLoadUnit:String?=null,
        val plannedNextLoadBasis:LoadBasis=LoadBasis.UNKNOWN,
        val plannedNextLoadSource:LoadSource=LoadSource.PLANNED,
        val errorMessage:String?=null,
        val busy:Boolean=false,
    ):WorkoutUiState

    data class Summary(
        val exerciseId:String,
        val exerciseName:String,
        val completedSets:List<CompletedSetUiState>,
        val evidenceSummary:String,
        val recurringEvidence:List<String> = emptyList(),
        val savedAnalyses:List<GptAnalysisUiState> = emptyList(),
    ):WorkoutUiState
}

val WorkoutUiState.requiresCamera:Boolean
    get()=this is WorkoutUiState.CameraSetup||this is WorkoutUiState.ActiveSet
