package com.gymbuddy.app.runtime

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.domain.lifecycle.SetLifecycleState
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

interface WorkoutRuntimeGateway:AutoCloseable {
    val analysisExecutor:Executor
    fun beginExercise(exerciseId:String)
    fun beginSet(setOrdinal:Int)
    fun endSet()
    fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage>
}
