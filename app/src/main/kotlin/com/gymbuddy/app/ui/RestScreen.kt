package com.gymbuddy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymbuddy.app.controller.WorkoutUiState
import com.gymbuddy.domain.persistence.LoadBasis
import kotlinx.coroutines.delay

@Composable
fun RestScreen(
    state:WorkoutUiState.Rest,
    onNextLoadChange:(String)->Unit,
    onNextSet:()->Unit,
    onFinishExercise:()->Unit,
    onNextLoadUnitChange:(String)->Unit={},
    onNextLoadBasisChange:(LoadBasis)->Unit={},
){
    var now by remember(state.restStartedAtEpochMs){mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(state.restStartedAtEpochMs){
        while(true){now=System.currentTimeMillis();delay(1000)}
    }
    val elapsed=((now-state.restStartedAtEpochMs).coerceAtLeast(0L))/1000L
    Column(
        modifier=Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),
    ){
        Text(String.format("%02d:%02d",elapsed/60,elapsed%60),fontSize=64.sp)
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
        OutlinedTextField(
            modifier=Modifier.fillMaxWidth(),
            value=state.plannedNextLoadUnit.orEmpty(),
            onValueChange=onNextLoadUnitChange,
            label={Text("Unit (optional)")},
            placeholder={Text("lb, kg, stack level…")},
            singleLine=true,
        )
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf(LoadBasis.TOTAL,LoadBasis.PER_IMPLEMENT,LoadBasis.PER_SIDE,LoadBasis.STACK,LoadBasis.BODYWEIGHT,LoadBasis.UNKNOWN)
                .forEach{basis->
                    TextButton(onClick={onNextLoadBasisChange(basis)}){
                        Text(if(state.plannedNextLoadBasis==basis)"[${basisLabel(basis)}]" else basisLabel(basis))
                    }
                }
        }
        Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Button(modifier=Modifier.weight(1f),onClick=onNextSet){Text("Next Set")}
            OutlinedButton(modifier=Modifier.weight(1f),onClick=onFinishExercise){Text("Finish Exercise")}
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
