package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun ExerciseSummaryScreen(
    state:WorkoutUiState.Summary,
    onAskChatGpt:()->Unit,
    onReturnToExercises:()->Unit,
){
    Column(
        modifier=Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),
    ){
        Text(state.exerciseName,style=MaterialTheme.typography.h4)
        state.completedSets.forEach{set->
            Card(modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){
                    Text("Set ${set.setNumber}: ${set.reps} reps · Load ${set.actualLoadText}")
                    Text(set.focus,style=MaterialTheme.typography.body2)
                }
            }
        }
        Text(state.evidenceSummary,style=MaterialTheme.typography.h6)
        OutlinedButton(
            modifier=Modifier.fillMaxWidth(),
            onClick=onAskChatGpt,
        ){Text("Ask ChatGPT")}
        Button(
            modifier=Modifier.fillMaxWidth(),
            onClick=onReturnToExercises,
        ){Text("Back to Exercises")}
    }
}
