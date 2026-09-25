package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.*
import com.gymbuddy.domain.evidence.SetCoachingSummary
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class WorkoutControllerStep3Test {
    @Test fun substitutionPreservesPlannedAndActualExercise(){
        val runtime=Runtime()
        val c=WorkoutController(runtime,clock=WorkoutClock{1_000})
        c.beginSubstitution("incline_dumbbell_press")
        c.updateExerciseSearch("lateral")
        val selection=c.uiState.value as WorkoutUiState.ExerciseSelection
        assertEquals("dumbbell_lateral_raise",selection.searchResults.single().exerciseId)
        c.selectOtherExercise("dumbbell_lateral_raise")
        assertEquals("dumbbell_lateral_raise",runtime.start!!.actualExerciseId)
        assertEquals("incline_dumbbell_press",runtime.start!!.plannedExerciseId)
    }

    @Test fun completionBadgesAreScopedToActiveWorkout(){
        val runtime=Runtime(selection=WorkoutSelectionSnapshot(
            activeSessionId="workout-a",
            completions=listOf(WorkoutExerciseCompletionRecord("workout-a","incline_dumbbell_press",3,100)),
        ))
        val c=WorkoutController(runtime,clock=WorkoutClock{1_000})
        val before=c.uiState.value as WorkoutUiState.ExerciseSelection
        assertEquals(3,before.exercises.single{it.exerciseId=="incline_dumbbell_press"}.completedSets)
        c.startNewWorkout()
        val after=c.uiState.value as WorkoutUiState.ExerciseSelection
        assertEquals(0,after.exercises.single{it.exerciseId=="incline_dumbbell_press"}.completedSets)
    }

    @Test fun equipmentContextFollowsSelectedExerciseIntoRuntime(){
        val runtime=Runtime()
        val c=WorkoutController(runtime,clock=WorkoutClock{12_345})
        c.editEquipment("smith_machine_squat")
        c.updateEquipmentLabel("Smith A")
        c.saveEquipmentContext()
        c.selectExercise("smith_machine_squat")
        assertEquals("Smith A",runtime.start!!.equipmentContext!!.label)
        assertEquals(runtime.savedEquipment.contextId,runtime.start!!.equipmentContext!!.contextId)
    }

    @Test fun plannedAndActualLoadSemanticsRemainSeparate(){
        val runtime=Runtime()
        val c=WorkoutController(runtime,clock=WorkoutClock{5_000})
        c.selectExercise("dumbbell_lateral_raise")
        c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(CameraGuidanceAction.CAMERA_READY,SetLifecycleState.ACTIVE_SET,TrackingQualityState.OBSERVABLE,8,null))
        c.endSet()
        c.updateNextLoad("25")
        c.updateNextLoadUnit("lb")
        c.updateNextLoadBasis(LoadBasis.PER_IMPLEMENT)
        c.nextSet()
        assertEquals(LoadSource.USER_ENTERED,runtime.lastActual!!.source)
        assertEquals(LoadSource.PLANNED,runtime.lastPlanned!!.source)
        assertEquals(LoadBasis.PER_IMPLEMENT,runtime.lastActual!!.basis)
        assertEquals(25.0,runtime.lastPlanned!!.value,0.0)
    }

    @Test fun activeCameraGuidanceRemainsOnActiveScreen(){
        val runtime=Runtime()
        val c=WorkoutController(runtime)
        c.selectExercise("dumbbell_lateral_raise")
        c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(CameraGuidanceAction.CAMERA_READY,SetLifecycleState.ACTIVE_SET,TrackingQualityState.OBSERVABLE,2,null))
        c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(CameraGuidanceAction.MOVE_LEFT,SetLifecycleState.CAMERA_GUIDANCE,TrackingQualityState.PAUSED,2,null))
        val state=c.uiState.value as WorkoutUiState.ActiveSet
        assertEquals("Move phone left.",state.cameraInstruction)
        assertEquals("Tracking paused",state.trackingText)
    }

    private class Runtime(
        var selection:WorkoutSelectionSnapshot=WorkoutSelectionSnapshot(),
    ):WorkoutRuntimeGateway{
        override val analysisExecutor=Executor{it.run()}
        var start:ExerciseStartRequest?=null
        lateinit var savedEquipment:EquipmentContextRecord
        var setOrdinal=1
        var lastActual:LoadSnapshot?=null
        var lastPlanned:LoadSnapshot?=null
        override fun beginExercise(exerciseId:String){start=ExerciseStartRequest(exerciseId,exerciseId)}
        override fun beginExercise(request:ExerciseStartRequest){start=request}
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?){this.setOrdinal=setOrdinal;lastActual=actualLoad}
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?,plannedLoad:LoadSnapshot?){this.setOrdinal=setOrdinal;lastActual=actualLoad;lastPlanned=plannedLoad}
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit){
            val actual=start?.actualExerciseId?:"dumbbell_lateral_raise"
            val set=SetRecord("set-$setOrdinal","exec",setOrdinal,100,lastActual,1_000,lastPlanned)
            onCompleted(CompletedSetContext(WorkoutSessionRecord("session",10,900),ExerciseExecutionRecord("exec","session",actual,20,920,start?.plannedExerciseId,start?.equipmentContext?.contextId),set,SetSummary(set.setId,200,8,0,0,5_000),SetCoachingSummary()))
        }
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage>{}
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)=Unit
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(null)
        override fun clearRestCheckpoint()=Unit
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(selection)
        override fun setExerciseFavorite(exerciseId:String,favorite:Boolean,onCompleted:(Boolean)->Unit)=onCompleted(true)
        override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){savedEquipment=record;onCompleted(true)}
        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit)=onCompleted(true)
        override fun startNewWorkout(onCompleted:(Boolean)->Unit){selection=selection.copy(activeSessionId=null,completions=emptyList());onCompleted(true)}
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=onResult(Result.success("{}"))
        override fun close()=Unit
    }
}
