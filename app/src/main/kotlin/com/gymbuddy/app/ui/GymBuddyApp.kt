package com.gymbuddy.app.ui

import androidx.camera.core.Preview
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gymbuddy.app.controller.*
import com.gymbuddy.domain.persistence.*

@Composable
fun GymBuddyApp(
    state:WorkoutUiState,
    onSelectDay:(WorkoutDay)->Unit,
    onSelectExercise:(String)->Unit,
    onPreviewSurfaceAvailable:(Preview.SurfaceProvider)->Unit,
    onCameraNeededChanged:(Boolean)->Unit,
    onEndSet:()->Unit,
    onNextLoadChange:(String)->Unit,
    onNextSet:()->Unit,
    onFinishExercise:()->Unit,
    onAskChatGpt:()->Unit,
    onReturnToExercises:()->Unit,
    onResetCalibration:()->Unit={},
    onOpenOtherExercise:()->Unit={},
    onCloseOtherExercise:()->Unit={},
    onSearchChange:(String)->Unit={},
    onSelectOtherExercise:(String)->Unit=onSelectExercise,
    onBeginSubstitution:(String)->Unit={},
    onToggleFavorite:(String)->Unit={},
    onEditEquipment:(String)->Unit={},
    onEquipmentLabelChange:(String)->Unit={},
    onSaveEquipmentContext:()->Unit={},
    onStartNewWorkout:()->Unit={},
    onNextLoadUnitChange:(String)->Unit={},
    onNextLoadBasisChange:(LoadBasis)->Unit={},
    onNextResistanceKindChange:(ResistanceKind)->Unit={},
    onNextMeasurementModeChange:(LoadMeasurementMode)->Unit={},
    onRetryRestSave:()->Unit={},
    onSaveExternalAnalysis:(String,List<String>,String,(Boolean)->Unit)->Unit={_,_,_,done->done(false)},
){
    LaunchedEffect(state.requiresCamera){onCameraNeededChanged(state.requiresCamera)}
    MaterialTheme{
        when(state){
            is WorkoutUiState.ExerciseSelection->ExerciseSelectionScreen(
                state=state,
                onSelectDay=onSelectDay,
                onSelectExercise=onSelectExercise,
                onOpenOtherExercise=onOpenOtherExercise,
                onCloseOtherExercise=onCloseOtherExercise,
                onSearchChange=onSearchChange,
                onSelectOtherExercise=onSelectOtherExercise,
                onBeginSubstitution=onBeginSubstitution,
                onToggleFavorite=onToggleFavorite,
                onEditEquipment=onEditEquipment,
                onEquipmentLabelChange=onEquipmentLabelChange,
                onSaveEquipmentContext=onSaveEquipmentContext,
                onStartNewWorkout=onStartNewWorkout,
            )
            is WorkoutUiState.CameraSetup->CameraSetupScreen(state,onPreviewSurfaceAvailable)
            is WorkoutUiState.ActiveSet->ActiveSetScreen(state,onEndSet)
            is WorkoutUiState.Rest->RestScreen(
                state=state,
                onNextLoadChange=onNextLoadChange,
                onNextSet=onNextSet,
                onFinishExercise=onFinishExercise,
                onNextLoadUnitChange=onNextLoadUnitChange,
                onNextLoadBasisChange=onNextLoadBasisChange,
                onNextResistanceKindChange=onNextResistanceKindChange,
                onNextMeasurementModeChange=onNextMeasurementModeChange,
                onRetryRestSave=onRetryRestSave,
            )
            is WorkoutUiState.Summary->ExerciseSummaryScreen(
                state=state,
                onAskChatGpt=onAskChatGpt,
                onResetCalibration=onResetCalibration,
                onReturnToExercises=onReturnToExercises,
                onSaveExternalAnalysis=onSaveExternalAnalysis,
            )
        }
    }
}
