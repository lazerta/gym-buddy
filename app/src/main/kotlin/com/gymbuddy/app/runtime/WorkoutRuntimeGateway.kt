package com.gymbuddy.app.runtime

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
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
)

interface WorkoutRuntimeGateway:AutoCloseable {
    val analysisExecutor:Executor
    fun beginExercise(exerciseId:String)
    fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)
    fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?=null)
    fun endSet(onCompleted:(CompletedSetContext?)->Unit)
    fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage>
    fun saveRestCheckpoint(checkpoint:RestCheckpointDraft)
    fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)
    fun clearRestCheckpoint()
    fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)
}
