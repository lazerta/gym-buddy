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

class Step3CallbackIsolationTest {
    @Test fun delayedAnalysisReadCannotOverwriteADifferentExecutionSummary() {
        val runtime=Runtime()
        val c=WorkoutController(runtime)
        complete(c)
        val old=runtime.reads.single()
        c.returnToSelection();complete(c)
        old.second(listOf(analysis(old.first)))
        check((c.uiState.value as WorkoutUiState.Summary).savedAnalyses.isEmpty()) {
            "An earlier execution's asynchronous analysis contaminated the current summary"
        }
    }
    @Test fun delayedAnalysisAppendCannotOverwriteADifferentExecutionSummary() {
        val runtime=Runtime()
        val c=WorkoutController(runtime)
        complete(c)
        c.recordExternalGptAnalysis("Review",emptyList())
        c.returnToSelection();complete(c)
        val before=runtime.reads.size
        runtime.appendCallback!!(true)
        runtime.reads.drop(before).forEach { (id,callback)->callback(listOf(analysis(id))) }
        check((c.uiState.value as WorkoutUiState.Summary).savedAnalyses.isEmpty()) {
            "An earlier execution's append completion contaminated the current summary"
        }
    }
    @Test fun recoveredExecutionRetainsPriorWorkoutCompletionCount() {
        val runtime=Runtime(rest=RestCheckpoint(
            WorkoutSessionRecord("session",1),ExerciseExecutionRecord("exec-rest","session","dumbbell_lateral_raise",1),
            SetRecord("rest-set","exec-rest",1,2),8,"Repeat the same setup.",null,1000))
        runtime.selection=WorkoutSelectionSnapshot("session",completions=listOf(
            WorkoutExerciseCompletionRecord("session","dumbbell_lateral_raise",3,900)))
        val c=WorkoutController(runtime)
        c.finishExercise()
        check(runtime.completedCount==4) { "Recreation replaced three prior completed sets with one" }
    }
    @Test fun lateFrameCannotChangeACompletedSummary() {
        val runtime=Runtime(); val c=WorkoutController(runtime);complete(c)
        val before=c.uiState.value
        active(c,99)
        check(c.uiState.value==before)
    }
    private fun complete(c:WorkoutController) {
        c.selectExercise("dumbbell_lateral_raise");active(c,8);c.endSet();c.finishExercise()
    }
    private fun active(c:WorkoutController,n:Int)=c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(
        CameraGuidanceAction.CAMERA_READY,SetLifecycleState.ACTIVE_SET,TrackingQualityState.OBSERVABLE,n,null))
    private fun analysis(id:String)=GptAnalysisRecord("a-$id",id,1,"model",1000,setOf(id),"Stored review")
    private class Runtime(val rest:RestCheckpoint?=null):WorkoutRuntimeGateway {
        override val analysisExecutor=Executor { it.run() }
        var selection=WorkoutSelectionSnapshot()
        var n=0
        var completedCount=0
        val reads=mutableListOf<Pair<String,(List<GptAnalysisRecord>)->Unit>>()
        var appendCallback:((Boolean)->Unit)?=null
        override fun beginExercise(exerciseId:String){n++}
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?)=Unit
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit) {
            val s=SetRecord("set-$n","exec-$n",1,2)
            onCompleted(CompletedSetContext(WorkoutSessionRecord("session",0),
                ExerciseExecutionRecord("exec-$n","session","dumbbell_lateral_raise",1),s,
                SetSummary(s.setId,500,8,0,0,1000)))
        }
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage> {}
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)=Unit
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(rest)
        override fun clearRestCheckpoint()=Unit
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(selection)
        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit) {
            completedCount=completedSets;onCompleted(true)
        }
        override fun loadGptAnalyses(setId:String,onLoaded:(List<GptAnalysisRecord>)->Unit){reads+=setId to onLoaded}
        override fun appendGptAnalysis(record:GptAnalysisRecord,onCompleted:(Boolean)->Unit){appendCallback=onCompleted}
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=Unit
        override fun close()=Unit
    }
}
