package com.gymbuddy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.testTag
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
    val editorRequester=remember{BringIntoViewRequester()}
    LaunchedEffect(state.equipmentEditorExerciseId){
        if(state.equipmentEditorExerciseId!=null)editorRequester.bringIntoView()
    }
    Column(
        modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Gym Buddy",style=MaterialTheme.typography.h4)
            TextButton(enabled=!state.busy,onClick=onStartNewWorkout){Text("New workout")}
        }
        state.errorMessage?.let{Text(it,color=MaterialTheme.colors.error)}
        if(state.busy)Text("Saving…")
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
                    enabled=!state.busy,
                )
            }
        }

        if(!state.otherExerciseOpen){
            OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=!state.busy,onClick=onOpenOtherExercise){
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
                    CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment,!state.busy,"favorite")
                }
            }
            if(state.recentExercises.isNotEmpty()){
                Text("Recent",style=MaterialTheme.typography.subtitle1)
                state.recentExercises.forEach{exercise->
                    CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment,!state.busy,"recent")
                }
            }
            Text("Results",style=MaterialTheme.typography.subtitle1)
            state.searchResults.forEach{exercise->
                CompactExerciseRow(exercise,onSelectOtherExercise,onToggleFavorite,onEditEquipment,!state.busy,"result")
            }
        }
        // The action can originate in Favorites, Recent or Results, regardless
        // of the active day/query. Never gate editor placement on those lists.
        state.equipmentEditorExerciseId?.let{id->
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(editorRequester).testTag("equipment-editor")){
                Text("Equipment",style=MaterialTheme.typography.h6)
                EquipmentEditor(state.equipmentLabelInput,onEquipmentLabelChange,onSaveEquipmentContext,!state.busy)
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
    enabled:Boolean=true,
){
    Card(modifier=Modifier.fillMaxWidth(),elevation=4.dp){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(
                Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onSelect),
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
                TextButton(enabled=enabled,onClick=onSubstitute){Text("Substitute")}
                TextButton(enabled=enabled,onClick=onEquipment){Text("Equipment")}
                TextButton(enabled=enabled,onClick=onFavorite){Text(if(exercise.favorite)"Unfavorite" else "Favorite")}
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
    enabled:Boolean,
    section:String,
){
    Card(modifier=Modifier.fillMaxWidth(),elevation=2.dp){
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement=Arrangement.SpaceBetween,
        ){
            Column(Modifier.weight(1f).clickable(enabled=enabled){onSelect(exercise.exerciseId)}){
                Text(exercise.displayName)
                exercise.equipmentLabel?.let{Text("Equipment: $it",style=MaterialTheme.typography.caption)}
            }
            TextButton(modifier=Modifier.testTag("$section-${exercise.exerciseId}-equipment"),enabled=enabled,onClick={onEquipment(exercise.exerciseId)}){Text("Equipment")}
            TextButton(enabled=enabled,onClick={onFavorite(exercise.exerciseId)}){Text(if(exercise.favorite)"★" else "☆")}
        }
    }
}

@Composable
private fun EquipmentEditor(
    value:String,
    onValueChange:(String)->Unit,
    onSave:()->Unit,
    enabled:Boolean=true,
){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(
            modifier=Modifier.weight(1f).testTag("equipment-label"),
            enabled=enabled,
            value=value,
            onValueChange=onValueChange,
            label={Text("Equipment label")},
            placeholder={Text("e.g. Smith A or Incline bench")},
            singleLine=true,
        )
        Button(enabled=enabled,onClick=onSave){Text("Save")}
    }
}
