package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymbuddy.app.controller.WorkoutUiState
import kotlinx.coroutines.delay

@Composable
fun RestScreen(
    state:WorkoutUiState.Rest,
    onNextLoadChange:(String)->Unit,
    onNextSet:()->Unit,
    onFinishExercise:()->Unit,
){
    var now by remember(state.restStartedAtEpochMs){mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(state.restStartedAtEpochMs){
        while(true){
            now=System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed=((now-state.restStartedAtEpochMs).coerceAtLeast(0L))/1000L
    val minutes=elapsed/60
    val seconds=elapsed%60

    Column(
        modifier=Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(20.dp),
    ){
        Text(
            String.format("%02d:%02d",minutes,seconds),
            fontSize=64.sp,
        )
        Text(
            "Set ${state.completedSetNumber}: ${state.previousReps} reps · Load ${state.previousActualLoadText}",
            style=MaterialTheme.typography.h6,
        )
        Text("Focus: ${state.focus}",style=MaterialTheme.typography.h6)
        OutlinedTextField(
            modifier=Modifier.fillMaxWidth(),
            value=state.plannedNextLoadText,
            onValueChange=onNextLoadChange,
            label={Text("Next-set load")},
            singleLine=true,
        )
        Row(
            modifier=Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
        ){
            Button(
                modifier=Modifier.weight(1f),
                onClick=onNextSet,
            ){Text("Next Set")}
            OutlinedButton(
                modifier=Modifier.weight(1f),
                onClick=onFinishExercise,
            ){Text("Finish Exercise")}
        }
    }
}
