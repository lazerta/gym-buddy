package com.gymbuddy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymbuddy.app.controller.*

@Composable
fun ExerciseSelectionScreen(
    state:WorkoutUiState.ExerciseSelection,
    onSelectDay:(WorkoutDay)->Unit,
    onSelectExercise:(String)->Unit,
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
){
    Column(
        modifier=Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Gym Buddy",style=MaterialTheme.typography.h4)
            TextButton(onClick=onStartNewWorkout){Text("New workout")}
        }
        TabRow(selectedTabIndex=WorkoutDay.entries.indexOf(state.selectedDay)){
            WorkoutDay.entries.forEach{day->
                Tab(selected=day==state.selectedDay,onClick={onSelectDay(day)},text={Text(day.label)})
            }
        }
        if(state.exercises.isEmpty()){
            Text("No supported exercises in this day yet.")
        }else{
            state.exercises.forEach{exercise->
                ExerciseRow(
                    exercise=exercise,
                    onSelect={onSelectExercise(exercise.exerciseId)},
                    onSubstitute={onBeginSubstitution(exercise.exerciseId)},
                    onFavorite={onToggleFavorite(exercise.exerciseId)},
                    onEquipment={onEditEquipment(exercise.exerciseId)},
                )
                if(state.equipmentEditorExerciseId==exercise.exerciseId){
                    EquipmentEditor(
                        value=state.equipmentLabelInput,
                        onValueChange=onEquipmentLabelChange,
                        onSave=onSaveEquipmentContext,
                    )
                }
            }
        }

        if(!state.otherExerciseOpen){
            OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onOpenOtherExercise){
                Text("Other exercise")
            }
        }else{
            Divider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(
                    state.substitutionForExerciseId?.let{"Choose substitute"}?:"Other exercise",
                    style=MaterialTheme.typography.h6,
                )
                TextButton(onClick=onCloseOtherExercise){Text("Close")}
            }
            OutlinedTextField(
                modifier=Modifier.fillMaxWidth(),
                value=state.searchQuery,
                onValueChange=onSearchChange,
                label={Text("Search exercises")},
                singleLine=true,
            )
            if(state.favoriteExercises.isNotEmpty()){
                Text("Favorites",style=MaterialTheme.typography.subtitle1)
                state.favoriteExercises.forEach{exercise->
                    CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment)
                }
            }
            if(state.recentExercises.isNotEmpty()){
                Text("Recent",style=MaterialTheme.typography.subtitle1)
                state.recentExercises.forEach{exercise->
                    CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment)
                }
            }
            Text("Results",style=MaterialTheme.typography.subtitle1)
            state.searchResults.forEach{exercise->
                CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment)
                if(state.equipmentEditorExerciseId==exercise.exerciseId){
                    EquipmentEditor(
                        value=state.equipmentLabelInput,
                        onValueChange=onEquipmentLabelChange,
                        onSave=onSaveEquipmentContext,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise:ExerciseRowUiState,
    onSelect:()->Unit,
    onSubstitute:()->Unit,
    onFavorite:()->Unit,
    onEquipment:()->Unit,
){
    Card(modifier=Modifier.fillMaxWidth(),elevation=4.dp){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(
                Modifier.fillMaxWidth().clickable(onClick=onSelect),
                horizontalArrangement=Arrangement.SpaceBetween,
            ){
                Column{
                    Text(exercise.displayName,style=MaterialTheme.typography.h6)
                    if(exercise.completedSets>0)Text("Completed · ${exercise.completedSets} sets")
                    exercise.equipmentLabel?.let{Text("Equipment: $it",style=MaterialTheme.typography.body2)}
                }
                Text(if(exercise.favorite)"★" else "☆")
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick=onSubstitute){Text("Substitute")}
                TextButton(onClick=onEquipment){Text("Equipment")}
                TextButton(onClick=onFavorite){Text(if(exercise.favorite)"Unfavorite" else "Favorite")}
            }
        }
    }
}

@Composable
private fun CompactExerciseRow(
    exercise:ExerciseRowUiState,
    onSelect:(String)->Unit,
    onFavorite:(String)->Unit,
    onEquipment:(String)->Unit,
){
    Card(modifier=Modifier.fillMaxWidth(),elevation=2.dp){
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement=Arrangement.SpaceBetween,
        ){
            Column(Modifier.weight(1f).clickable{onSelect(exercise.exerciseId)}){
                Text(exercise.displayName)
                exercise.equipmentLabel?.let{Text("Equipment: $it",style=MaterialTheme.typography.caption)}
            }
            TextButton(onClick={onEquipment(exercise.exerciseId)}){Text("Equipment")}
            TextButton(onClick={onFavorite(exercise.exerciseId)}){Text(if(exercise.favorite)"★" else "☆")}
        }
    }
}

@Composable
private fun EquipmentEditor(
    value:String,
    onValueChange:(String)->Unit,
    onSave:()->Unit,
){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(
            modifier=Modifier.weight(1f),
            value=value,
            onValueChange=onValueChange,
            label={Text("Equipment label")},
            placeholder={Text("e.g. Smith A or Incline bench")},
            singleLine=true,
        )
        Button(onClick=onSave){Text("Save")}
    }
}
