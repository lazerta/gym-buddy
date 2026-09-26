package com.gymbuddy.app

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymbuddy.app.controller.*
import com.gymbuddy.app.runtime.*
import com.gymbuddy.app.ui.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.frames.FrameConsumer
import com.google.mediapipe.framework.image.MPImage
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered production Composables with the real Controller. The gateway is
 * controlled here; real runtime/Room failure cases run in Step3ReviewE2EActivity. */
@RunWith(AndroidJUnit4::class)
class Step3ComposeRepairTest {
    @get:Rule val compose=createComposeRule()
    @Test fun favoriteEquipmentOutsideDayAndQueryOpensOneVisibleEditor(){equipmentFrom("favorite")}
    @Test fun recentEquipmentOutsideDayAndQueryOpensOneVisibleEditor(){equipmentFrom("recent")}
    private fun equipmentFrom(section:String){
        val c=WorkoutController(Gateway(),WorkoutDay.LEGS)
        c.openOtherExercise();c.updateExerciseSearch("squat")
        compose.setContent{
            val state by c.uiState.collectAsState()
            MaterialTheme{ExerciseSelectionScreen(state as WorkoutUiState.ExerciseSelection,
                c::selectDay,c::selectExercise,onEditEquipment=c::editEquipment,
                onEquipmentLabelChange=c::updateEquipmentLabel,onSaveEquipmentContext=c::saveEquipmentContext)}
        }
        compose.onNodeWithTag("$section-dumbbell_lateral_raise-equipment").performScrollTo().performClick()
        compose.onAllNodesWithTag("equipment-editor").assertCountEquals(1)
        compose.onNodeWithTag("equipment-label").performScrollTo().assertIsDisplayed().performTextInput("Bench B")
        compose.onNodeWithText("Save",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle{
            val state=c.uiState.value as WorkoutUiState.ExerciseSelection
            assertNull(state.equipmentEditorExerciseId)
            assertEquals("Bench B",state.favoriteExercises.single().equipmentLabel)
        }
        c.close()
    }
    @Test fun failedAndPendingRestSavesDisableAdvancingButPermitRetry(){
        var state by mutableStateOf(WorkoutUiState.Rest("dumbbell_lateral_raise","Raise",1,2,"20","Repeat the same setup.",
            "25",System.currentTimeMillis(),savingLoad=true))
        var retries=0
        compose.setContent{MaterialTheme{RestScreen(state,{}, {}, {},onRetryRestSave={retries++})}}
        compose.onNodeWithText("Next Set").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Finish Exercise").assertIsNotEnabled()
        compose.runOnIdle{state=state.copy(savingLoad=false,loadSaveFailed=true,errorMessage="Changes were not saved.")}
        compose.onNodeWithText("Retry save").performScrollTo().performClick()
        compose.runOnIdle{assertEquals(1,retries);state=state.copy(loadSaveFailed=false,errorMessage=null)}
        compose.onNodeWithText("Next Set").performScrollTo().assertIsEnabled()
    }
    @Test fun returnedAnalysisDraftSurvivesFailureAndCanBeRetriedFromSummary(){
        var calls=0
        val summaries=mutableListOf<String>()
        compose.setContent{MaterialTheme{
            ExerciseSummaryScreen(WorkoutUiState.Summary("raise","Raise",emptyList(),"Steady"),{}, {},
                onSaveExternalAnalysis={text,_,_,done->summaries+=text;calls++;done(calls>1)})
        }}
        compose.onNodeWithText("Save external analysis").performScrollTo().performClick()
        compose.onNodeWithTag("external-analysis-text").performScrollTo().performTextInput("Maintain this load.")
        compose.onNodeWithText("Save analysis").performScrollTo().performClick()
        compose.onNodeWithTag("external-analysis-text").assertTextContains("Maintain this load.")
        compose.onNodeWithText("Save analysis").performScrollTo().performClick()
        compose.onNodeWithTag("external-analysis-text").assertDoesNotExist()
        compose.runOnIdle{assertEquals(listOf("Maintain this load.","Maintain this load."),summaries)}
    }
    private class Gateway:WorkoutRuntimeGateway{
        override val analysisExecutor=Executor{it.run()}
        var snapshot=WorkoutSelectionSnapshot(preferences=listOf(ExercisePreferenceRecord(
            "dumbbell_lateral_raise",favorite=true,lastSelectedAtEpochMs=100)))
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(snapshot)
        override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){
            snapshot=snapshot.copy(equipmentContexts=listOf(record),preferences=listOf(snapshot.preferences.single().copy(equipmentContextId=record.contextId)))
            onCompleted(true)
        }
        override fun beginExercise(exerciseId:String)=Unit
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?)=Unit
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit)=onCompleted(null)
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage>{it.image.close()}
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)=Unit
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(null)
        override fun clearRestCheckpoint()=Unit
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=onResult(Result.failure(UnsupportedOperationException()))
        override fun close()=Unit
    }
}
