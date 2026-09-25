package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun ActiveSetScreen(
    state:WorkoutUiState.ActiveSet,
    onEndSet:()->Unit,
){
    Column(
        modifier=Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.SpaceBetween,
    ){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Text(
                "${state.exerciseName} · Set ${state.setNumber} · Load ${state.actualLoadText}",
                style=MaterialTheme.typography.subtitle1,
            )
            Text(state.trackingText,style=MaterialTheme.typography.body2)
            state.cameraInstruction?.let{
                Spacer(Modifier.height(8.dp))
                Text(it,fontSize=24.sp)
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Text(state.repCount.toString(),fontSize=112.sp)
            state.cue?.let{
                Spacer(Modifier.padding(8.dp))
                Text(it,fontSize=28.sp)
            }
        }
        Button(modifier=Modifier.fillMaxWidth(),onClick=onEndSet){Text("End Set")}
    }
}
