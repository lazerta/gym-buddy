package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class Step3SaveOrderingTest {
    @Test fun pendingLatestEditCannotAdvanceOrFinish(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextLoad("25");c.nextSet();c.finishExercise()
        assertTrue((c.uiState.value as WorkoutUiState.Rest).savingLoad)
        assertEquals(0,r.prepares);assertEquals(0,r.finishes)
        r.ack(0,true);c.nextSet();assertEquals(1,r.prepares)
    }
    @Test fun olderSuccessCannotHideNewerFailure(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextLoad("25");c.updateNextLoad("30")
        r.ack(1,false);r.ack(0,true)
        val shown=c.uiState.value as WorkoutUiState.Rest
        assertEquals("30",shown.plannedNextLoadText);assertTrue(shown.loadSaveFailed)
        c.nextSet();assertEquals(0,r.prepares)
    }
    @Test fun olderFailureCannotOverwriteNewerSuccess(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextLoad("25");c.updateNextLoad("30")
        r.ack(1,true);r.ack(0,false)
        val shown=c.uiState.value as WorkoutUiState.Rest
        assertEquals("30",shown.plannedNextLoadText);assertFalse(shown.loadSaveFailed)
        assertNull(shown.errorMessage)
    }
    @Test fun failedEditRetainsDraftAndExplicitRetryCommitsIt(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextLoad("25");r.ack(0,false)
        assertEquals(20.0,r.rest.plannedNextLoad!!.value,0.0)
        assertTrue((c.uiState.value as WorkoutUiState.Rest).loadSaveFailed)
        c.retryRestSave();r.ack(1,true)
        assertEquals(25.0,r.rest.plannedNextLoad!!.value,0.0)
        assertFalse((c.uiState.value as WorkoutUiState.Rest).loadSaveFailed)
        assertEquals("25",(WorkoutController(r).uiState.value as WorkoutUiState.Rest).plannedNextLoadText)
    }
    @Test fun kindAndMeasurementArePreservedThroughPlanAndRecreation(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextResistanceKind(ResistanceKind.EXTERNAL_LOAD);r.ack(0,true)
        c.updateNextMeasurementMode(LoadMeasurementMode.ADDED_LOAD);r.ack(1,true)
        c.updateNextLoadUnit("kg");r.ack(2,true)
        c.updateNextLoadBasis(LoadBasis.PER_SIDE);r.ack(3,true)
        val recovered=WorkoutController(r)
        val rest=recovered.uiState.value as WorkoutUiState.Rest
        assertEquals(ResistanceKind.EXTERNAL_LOAD,rest.plannedNextResistanceKind)
        assertEquals(LoadMeasurementMode.ADDED_LOAD,rest.plannedNextMeasurementMode)
        recovered.nextSet()
        assertEquals(LoadSource.PLANNED,r.planned!!.source)
        assertEquals(LoadSource.CARRIED_FROM_PLAN,r.actual!!.source)
        assertEquals(r.planned!!.copy(source=LoadSource.CARRIED_FROM_PLAN),r.actual)
        assertEquals(ResistanceKind.UNKNOWN,r.rest.completedSet.actualLoad!!.resistanceKind)
    }
    @Test fun lateSaveCallbackCannotMutateClosedController(){
        val r=Runtime();val c=WorkoutController(r)
        c.updateNextLoad("25");val before=c.uiState.value;c.close();r.ack(0,false)
        assertEquals(before,c.uiState.value)
    }
    @Test fun equipmentAtoBWaitsForAcknowledgement(){
        val r=Runtime();r.recover=false
        val a=EquipmentContextRecord("A","dumbbell-generic","A")
        r.selection=WorkoutSelectionSnapshot(preferences=listOf(ExercisePreferenceRecord(ID,equipmentContextId="A")),equipmentContexts=listOf(a))
        val c=WorkoutController(r)
        c.editEquipment(ID);c.updateEquipmentLabel("B");c.saveEquipmentContext()
        assertTrue((c.uiState.value as WorkoutUiState.ExerciseSelection).busy)
        c.selectExercise(ID);assertNull(r.start)
        r.equipment!!.second(true);c.selectExercise(ID)
        assertEquals(r.equipment!!.first,r.start!!.equipmentContext)
    }
    @Test fun failedEquipmentWriteKeepsOldContextAndEditorWithError(){
        val r=Runtime();r.recover=false
        val a=EquipmentContextRecord("A","dumbbell-generic","A")
        r.selection=WorkoutSelectionSnapshot(preferences=listOf(ExercisePreferenceRecord(ID,equipmentContextId="A")),equipmentContexts=listOf(a))
        val c=WorkoutController(r);c.editEquipment(ID);c.updateEquipmentLabel("B");c.saveEquipmentContext()
        r.equipment!!.second(false)
        val ui=c.uiState.value as WorkoutUiState.ExerciseSelection
        assertNotNull(ui.errorMessage);assertEquals(ID,ui.equipmentEditorExerciseId)
        c.selectExercise(ID);assertEquals(a,r.start!!.equipmentContext)
    }
    private class Runtime:WorkoutRuntimeGateway {
        override val analysisExecutor=Executor{it.run()}
        var recover=true
        var selection=WorkoutSelectionSnapshot()
        var rest=RestCheckpoint(WorkoutSessionRecord("s",0),ExerciseExecutionRecord("e","s",ID,0),
            SetRecord("set","e",1,0,LoadSnapshot(20.0,"lb")),2,"Repeat the same setup.",
            LoadSnapshot(20.0,"lb",source=LoadSource.PLANNED),1000,clockAnchor=RestClockAnchor(500,"boot"))
        val writes=mutableListOf<Pair<RestCheckpointDraft,(Boolean)->Unit>>()
        var equipment:Pair<EquipmentContextRecord,(Boolean)->Unit>?=null
        var start:ExerciseStartRequest?=null
        var prepares=0;var finishes=0
        var planned:LoadSnapshot?=null;var actual:LoadSnapshot?=null
        fun ack(n:Int,ok:Boolean){
            val (draft,done)=writes[n]
            if(ok)rest=rest.copy(plannedNextLoad=draft.plannedNextLoad,clockAnchor=draft.clockAnchor)
            done(ok)
        }
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)=saveRestCheckpoint(checkpoint){}
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft,onCompleted:(Boolean)->Unit){writes+=checkpoint to onCompleted}
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(if(recover)rest else null)
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(selection)
        override fun prepareSet(ordinal:Int,actual:LoadSnapshot?,planned:LoadSnapshot?,onCompleted:(Boolean)->Unit){
            prepares++;this.actual=actual;this.planned=planned;onCompleted(true)
        }
        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit){finishes++;onCompleted(true)}
        override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){equipment=record to onCompleted}
        override fun beginExercise(exerciseId:String)=Unit
        override fun beginExercise(request:ExerciseStartRequest){start=request}
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?)=Unit
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit)=Unit
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage>{}
        override fun clearRestCheckpoint()=Unit
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=Unit
        override fun close()=Unit
    }
    companion object{private const val ID="dumbbell_lateral_raise"}
}
