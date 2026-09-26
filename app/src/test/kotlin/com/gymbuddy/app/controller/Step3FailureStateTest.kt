package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.*
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Executor
import org.junit.Test

class Step3FailureStateTest {
    @Test fun failedFavoriteDoesNotAppearSaved(){
        val r=Runtime();val c=WorkoutController(r)
        c.toggleFavorite("dumbbell_lateral_raise")
        check(!(c.uiState.value as WorkoutUiState.ExerciseSelection).exercises.last().favorite)
        r.favorite!!(false)
        check(!(c.uiState.value as WorkoutUiState.ExerciseSelection).exercises.last().favorite)
        check((c.uiState.value as WorkoutUiState.ExerciseSelection).errorMessage!=null)
        c.toggleFavorite("dumbbell_lateral_raise");r.favorite!!(true)
        check((c.uiState.value as WorkoutUiState.ExerciseSelection).exercises.last().favorite)
    }
    @Test fun failedEquipmentDoesNotBecomeAnAnalysisContext(){
        val r=Runtime();val c=WorkoutController(r)
        c.editEquipment("dumbbell_lateral_raise");c.updateEquipmentLabel("Bench A");c.saveEquipmentContext()
        r.equipment!!(false)
        check((c.uiState.value as WorkoutUiState.ExerciseSelection).equipmentEditorExerciseId!=null)
        c.selectExercise("dumbbell_lateral_raise")
        check(r.request!!.equipmentContext==null)
    }
    @Test fun failedCompletionRetainsRestAndRetriesWithoutDoubleCounting(){
        val r=Runtime();val c=WorkoutController(r);rest(c)
        r.finishSuccess=false;c.finishExercise()
        check(c.uiState.value is WorkoutUiState.Rest)
        check((c.uiState.value as WorkoutUiState.Rest).errorMessage!=null)
        check(r.clears==0)
        r.finishSuccess=true;c.finishExercise();c.finishExercise()
        check((c.uiState.value as WorkoutUiState.Summary).completedSets.size==1)
        check(r.completionCalls==2) // one failed transaction and one success
    }
    @Test fun failedNewWorkoutRetainsCompletionBadges(){
        val r=Runtime();r.selection=WorkoutSelectionSnapshot("session",completions=listOf(
            WorkoutExerciseCompletionRecord("session","dumbbell_lateral_raise",3,1000)))
        val c=WorkoutController(r);c.startNewWorkout()
        check((c.uiState.value as WorkoutUiState.ExerciseSelection).exercises.last().completedSets==3)
        check((c.uiState.value as WorkoutUiState.ExerciseSelection).errorMessage!=null)
    }
    @Test fun failedNextSetKeepsEditablePlanForRetry(){
        val r=Runtime();val c=WorkoutController(r);rest(c)
        c.updateNextLoad("25");r.prepareSuccess=false;c.nextSet()
        check((c.uiState.value as WorkoutUiState.Rest).plannedNextLoadText=="25")
        c.updateNextLoad("30");r.prepareSuccess=true;c.nextSet()
        check((c.uiState.value as WorkoutUiState.CameraSetup).setNumber==2)
        check(r.nextPlanned!!.value==30.0&&r.nextActual!!.value==30.0)
        check(r.nextPlanned!!.source==LoadSource.PLANNED&&r.nextActual!!.source==LoadSource.USER_ENTERED)
    }
    private fun rest(c:WorkoutController){
        c.selectExercise("dumbbell_lateral_raise")
        c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(CameraGuidanceAction.CAMERA_READY,
            SetLifecycleState.ACTIVE_SET,TrackingQualityState.OBSERVABLE,8,null))
        c.endSet()
    }
    private class Runtime:WorkoutRuntimeGateway {
        override val analysisExecutor=Executor{it.run()}
        var favorite:((Boolean)->Unit)?=null
        var equipment:((Boolean)->Unit)?=null
        var request:ExerciseStartRequest?=null
        var selection=WorkoutSelectionSnapshot()
        var finishSuccess=true
        var prepareSuccess=true
        var clears=0
        var completionCalls=0
        var nextActual:LoadSnapshot?=null
        var nextPlanned:LoadSnapshot?=null
        override fun beginExercise(exerciseId:String)=Unit
        override fun beginExercise(request:ExerciseStartRequest){this.request=request}
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?)=Unit
        override fun prepareSet(ordinal:Int,actual:LoadSnapshot?,planned:LoadSnapshot?,onCompleted:(Boolean)->Unit){
            nextActual=actual;nextPlanned=planned;onCompleted(prepareSuccess)
        }
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(selection)
        override fun setExerciseFavorite(exerciseId:String,favorite:Boolean,onCompleted:(Boolean)->Unit){this.favorite=onCompleted}
        override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){equipment=onCompleted}
        override fun startNewWorkout(onCompleted:(Boolean)->Unit)=onCompleted(false)
        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit){completionCalls++;onCompleted(finishSuccess)}
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit){
            onCompleted(CompletedSetContext(WorkoutSessionRecord("session",0),
                ExerciseExecutionRecord("exec","session","dumbbell_lateral_raise",1),
                SetRecord("set","exec",1,2),SetSummary("set",500,8,0,0,1000)))
        }
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage>{}
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)=Unit
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(null)
        override fun clearRestCheckpoint(){clears++}
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=Unit
        override fun close()=Unit
    }
}
