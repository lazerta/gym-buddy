package com.gymbuddy.app.ui

import androidx.camera.core.Preview
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gymbuddy.app.controller.WorkoutDay
import com.gymbuddy.app.controller.WorkoutUiState
import com.gymbuddy.app.controller.requiresCamera

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
){
    LaunchedEffect(state.requiresCamera){
        onCameraNeededChanged(state.requiresCamera)
    }

    MaterialTheme{
        when(state){
            is WorkoutUiState.ExerciseSelection->ExerciseSelectionScreen(
                state=state,
                onSelectDay=onSelectDay,
                onSelectExercise=onSelectExercise,
            )
            is WorkoutUiState.CameraSetup->CameraSetupScreen(
                state=state,
                onPreviewSurfaceAvailable=onPreviewSurfaceAvailable,
            )
            is WorkoutUiState.ActiveSet->ActiveSetScreen(state,onEndSet)
            is WorkoutUiState.Rest->RestScreen(
                state=state,
                onNextLoadChange=onNextLoadChange,
                onNextSet=onNextSet,
                onFinishExercise=onFinishExercise,
            )
            is WorkoutUiState.Summary->ExerciseSummaryScreen(
                state=state,
                onAskChatGpt=onAskChatGpt,
                onReturnToExercises=onReturnToExercises,
            )
        }
    }
}
