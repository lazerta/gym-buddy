package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymbuddy.app.controller.WorkoutUiState
import com.gymbuddy.domain.persistence.*
import android.os.SystemClock
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

@Composable
fun RestScreen(
    state:WorkoutUiState.Rest,
    onNextLoadChange:(String)->Unit,
    onNextSet:()->Unit,
    onFinishExercise:()->Unit,
    onNextLoadUnitChange:(String)->Unit={},
    onNextLoadBasisChange:(LoadBasis)->Unit={},
    onNextResistanceKindChange:(ResistanceKind)->Unit={},
    onNextMeasurementModeChange:(LoadMeasurementMode)->Unit={},
    onRetryRestSave:()->Unit={},
){
    val timer=remember(state.restStartedAtEpochMs,state.timerAnchor){
        state.timerAnchor?:RestTimerAnchor.restore(state.restStartedAtEpochMs,null,
            System.currentTimeMillis(),SystemClock.elapsedRealtime(),null)
    }
    var now by remember(timer){mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(timer){
        while(true){now=SystemClock.elapsedRealtime();delay(1000)}
    }
    val elapsed=timer.elapsedMs(now)/1000L
    var showDetails by rememberSaveable{mutableStateOf(false)}
    val canContinue=!state.busy&&!state.savingLoad&&!state.loadSaveFailed
    Column(
        modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),
    ){
        Text(String.format("%02d:%02d",elapsed/60,elapsed%60),modifier=Modifier.testTag("rest-timer"),fontSize=64.sp)
        if(timer.estimated)Text("Rest time estimated after restart.",style=MaterialTheme.typography.caption)
        Text(
            "Set ${state.completedSetNumber}: ${state.previousReps} reps · Load ${state.previousActualLoadText}",
            style=MaterialTheme.typography.h6,
        )
        Text("Focus: ${state.focus}",style=MaterialTheme.typography.h6)
        if(state.savingLoad)Text("Saving changes…")
        state.errorMessage?.let{Text(it,color=MaterialTheme.colors.error)}
        if(state.loadSaveFailed)TextButton(onClick=onRetryRestSave){Text("Retry save")}
        OutlinedTextField(
            modifier=Modifier.fillMaxWidth().testTag("next-load"),
            value=state.plannedNextLoadText,
            onValueChange=onNextLoadChange,
            label={Text("Next-set load")},
            singleLine=true,
            enabled=!state.busy,
        )
        OutlinedTextField(
            modifier=Modifier.fillMaxWidth(),
            value=state.plannedNextLoadUnit.orEmpty(),
            onValueChange=onNextLoadUnitChange,
            label={Text("Unit (optional)")},
            placeholder={Text("lb, kg, stack level…")},
            singleLine=true,
            enabled=!state.busy,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf(LoadBasis.TOTAL,LoadBasis.PER_IMPLEMENT,LoadBasis.PER_SIDE,LoadBasis.STACK,LoadBasis.BODYWEIGHT,LoadBasis.UNKNOWN)
                .forEach{basis->
                    TextButton(enabled=!state.busy,onClick={onNextLoadBasisChange(basis)}){
                        Text(if(state.plannedNextLoadBasis==basis)"[${basisLabel(basis)}]" else basisLabel(basis))
                    }
                }
        }
        TextButton(onClick={showDetails=!showDetails}){Text(if(showDetails)"Hide load details" else "Load details")}
        if(showDetails){
            Text("Resistance kind",style=MaterialTheme.typography.subtitle2)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                ResistanceKind.entries.forEach{kind->
                    TextButton(enabled=!state.busy,onClick={onNextResistanceKindChange(kind)}){
                        Text(choiceLabel(kind.name,kind==state.plannedNextResistanceKind))
                    }
                }
            }
            Text("Measurement mode",style=MaterialTheme.typography.subtitle2)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                LoadMeasurementMode.entries.forEach{mode->
                    TextButton(enabled=!state.busy,onClick={onNextMeasurementModeChange(mode)}){
                        Text(choiceLabel(mode.name,mode==state.plannedNextMeasurementMode))
                    }
                }
            }
            Text("Unknown stays unknown. The next set carries this plan; it is not a measured load.",
                style=MaterialTheme.typography.caption)
        }
        Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Button(modifier=Modifier.weight(1f),enabled=canContinue,onClick=onNextSet){Text("Next Set")}
            OutlinedButton(modifier=Modifier.weight(1f),enabled=canContinue,onClick=onFinishExercise){Text("Finish Exercise")}
        }
    }
}

private fun basisLabel(basis:LoadBasis)=when(basis){
    LoadBasis.TOTAL->"Total"
    LoadBasis.PER_IMPLEMENT->"Per implement"
    LoadBasis.PER_SIDE->"Per side"
    LoadBasis.STACK->"Stack"
    LoadBasis.BODYWEIGHT->"Bodyweight"
    LoadBasis.UNKNOWN->"Unknown"
}

private fun choiceLabel(name:String,selected:Boolean):String{
    val label=name.lowercase(java.util.Locale.US).replace('_',' ')
    return if(selected)"[$label]" else label
}
