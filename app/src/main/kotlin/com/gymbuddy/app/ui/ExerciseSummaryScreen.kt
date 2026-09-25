package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun ExerciseSummaryScreen(
    state:WorkoutUiState.Summary,
    onAskChatGpt:()->Unit,
    onReturnToExercises:()->Unit,
    onResetCalibration:()->Unit={},
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
        if(state.recurringEvidence.isNotEmpty()){
            Text("Recurring evidence",style=MaterialTheme.typography.subtitle1)
            state.recurringEvidence.forEach{Text("• $it")}
        }
        if(state.savedAnalyses.isNotEmpty()){
            Text("Saved analyses",style=MaterialTheme.typography.subtitle1)
            state.savedAnalyses.forEach{analysis->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(analysis.modelLabel,style=MaterialTheme.typography.caption)
                        Text(analysis.summary)
                    }
                }
            }
        }
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onAskChatGpt){Text("Ask ChatGPT")}
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onResetCalibration){Text("Reset calibration")}
        Button(modifier=Modifier.fillMaxWidth(),onClick=onReturnToExercises){Text("Back to Exercises")}
    }
}
