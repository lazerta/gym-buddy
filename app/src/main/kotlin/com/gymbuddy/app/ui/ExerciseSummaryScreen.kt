package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun ExerciseSummaryScreen(
    state:WorkoutUiState.Summary,
    onAskChatGpt:()->Unit,
    onReturnToExercises:()->Unit,
    onResetCalibration:()->Unit={},
    onSaveExternalAnalysis:(String,List<String>,String,(Boolean)->Unit)->Unit={_,_,_,done->done(false)},
){
    var editorOpen by rememberSaveable(state.exerciseId){mutableStateOf(false)}
    var analysisText by rememberSaveable(state.exerciseId){mutableStateOf("")}
    var recommendationsText by rememberSaveable(state.exerciseId){mutableStateOf("")}
    var modelLabel by rememberSaveable(state.exerciseId){mutableStateOf("external-chatgpt (version unspecified)")}
    var saving by remember{mutableStateOf(false)}
    var saveError by remember{mutableStateOf(false)}
    Column(
        modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
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
        if(state.cueResponseEvidence.isNotEmpty()){
            Text("Cue responses",style=MaterialTheme.typography.subtitle1)
            state.cueResponseEvidence.forEach{Text(it)}
            Text("Observed response is not proof that a cue caused the change.",style=MaterialTheme.typography.caption)
        }
        if(state.savedAnalyses.isNotEmpty()){
            Text("Saved analyses",style=MaterialTheme.typography.subtitle1)
            state.savedAnalyses.forEach{analysis->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(analysis.modelLabel,style=MaterialTheme.typography.caption)
                        Text(analysis.summary)
                        analysis.recommendations.forEach{Text(it,style=MaterialTheme.typography.body2)}
                    }
                }
            }
        }
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onAskChatGpt){Text("Ask ChatGPT")}
        TextButton(onClick={editorOpen=!editorOpen},enabled=!saving){Text("Save external analysis")}
        if(editorOpen){
            Text("Optional local record, attached to this exercise's completed sets. No network request is made.",
                style=MaterialTheme.typography.caption)
            OutlinedTextField(modifier=Modifier.fillMaxWidth().testTag("external-analysis-text"),
                value=analysisText,onValueChange={analysisText=it.take(20_000)},enabled=!saving,
                label={Text("Returned interpretation")})
            OutlinedTextField(modifier=Modifier.fillMaxWidth(),value=recommendationsText,
                onValueChange={recommendationsText=it.take(20_000)},enabled=!saving,
                label={Text("Recommendations (one per line)")})
            OutlinedTextField(modifier=Modifier.fillMaxWidth(),value=modelLabel,
                onValueChange={modelLabel=it.take(200)},enabled=!saving,label={Text("Model / version, if known")})
            if(saveError)Text("Analysis was not saved. Your draft is retained; retry.",color=MaterialTheme.colors.error)
            Button(enabled=!saving&&analysisText.isNotBlank()&&modelLabel.isNotBlank(),onClick={
                saving=true;saveError=false
                onSaveExternalAnalysis(analysisText.trim(),recommendationsText.lines().map{it.trim()}.filter{it.isNotEmpty()},modelLabel.trim()){ok->
                    saving=false;saveError=!ok
                    if(ok){editorOpen=false;analysisText="";recommendationsText=""}
                }
            }){Text(if(saving)"Saving…" else "Save analysis")}
        }
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onResetCalibration){Text("Reset calibration")}
        Button(modifier=Modifier.fillMaxWidth(),onClick=onReturnToExercises){Text("Back to Exercises")}
    }
}
