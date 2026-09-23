package com.gymbuddy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymbuddy.app.controller.WorkoutDay
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun ExerciseSelectionScreen(
    state:WorkoutUiState.ExerciseSelection,
    onSelectDay:(WorkoutDay)->Unit,
    onSelectExercise:(String)->Unit,
){
    Column(
        modifier=Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),
    ){
        Text("Gym Buddy",style=MaterialTheme.typography.h4)
        TabRow(selectedTabIndex=WorkoutDay.entries.indexOf(state.selectedDay)){
            WorkoutDay.entries.forEach{day->
                Tab(
                    selected=day==state.selectedDay,
                    onClick={onSelectDay(day)},
                    text={Text(day.label)},
                )
            }
        }
        if(state.exercises.isEmpty()){
            Text("No supported exercises in this day yet.")
        }else{
            state.exercises.forEach{exercise->
                Card(
                    modifier=Modifier
                        .fillMaxWidth()
                        .clickable{onSelectExercise(exercise.exerciseId)},
                    elevation=4.dp,
                ){
                    Row(Modifier.fillMaxWidth().padding(20.dp)){
                        Text(exercise.displayName,style=MaterialTheme.typography.h6)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
