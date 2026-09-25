package com.gymbuddy.app.runtime

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.domain.evidence.SetCoachingSummary
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Executor

data class WorkoutRuntimeSnapshot(
    val cameraGuidance:CameraGuidanceAction,
    val lifecycleState:SetLifecycleState,
    val trackingState:TrackingQualityState,
    val repCount:Int,
    val cueText:String?,
)

data class CompletedSetContext(
    val session:WorkoutSessionRecord,
    val execution:ExerciseExecutionRecord,
    val set:SetRecord,
    val summary:SetSummary?=null,
    val coachingSummary:SetCoachingSummary?=null,
){
    init{require(summary==null||summary.setId==set.setId)}
}

interface WorkoutRuntimeGateway:AutoCloseable {
    val analysisExecutor:Executor

    fun beginExercise(exerciseId:String)
    fun beginExercise(request:ExerciseStartRequest){beginExercise(request.actualExerciseId)}
    fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)

    fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?=null)
    fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?,plannedLoad:LoadSnapshot?){beginSet(setOrdinal,actualLoad)}

    fun endSet(onCompleted:(CompletedSetContext?)->Unit)
    fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage>

    fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)
    fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)
    fun loadActiveSetRecovery(onLoaded:(ActiveSetRecovery?)->Unit){onLoaded(null)}
    fun markActiveSetInterrupted(setId:String,recoveredAtEpochMs:Long,committedReps:Int)=Unit
    fun clearRestCheckpoint()

    fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit){onLoaded(WorkoutSelectionSnapshot())}
    fun setExerciseFavorite(exerciseId:String,favorite:Boolean,onCompleted:(Boolean)->Unit={}){onCompleted(false)}
    fun rememberEquipmentContext(record:EquipmentContextRecord,onCompleted:(Boolean)->Unit={}){onCompleted(false)}
    fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit={}){
        rememberEquipmentContext(record,onCompleted)
    }
    fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit={}){onCompleted(false)}
    fun startNewWorkout(onCompleted:(Boolean)->Unit={}){onCompleted(false)}

    fun resetPersonalCalibration(exerciseId:String,onCompleted:(Boolean)->Unit={}){onCompleted(false)}
    fun clearPersonalCalibration(onCompleted:(Boolean)->Unit={}){onCompleted(false)}

    fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)
    fun appendGptAnalysis(record:GptAnalysisRecord,onCompleted:(Boolean)->Unit={}){onCompleted(false)}
    fun loadGptAnalyses(setId:String,onLoaded:(List<GptAnalysisRecord>)->Unit){onLoaded(emptyList())}
}
