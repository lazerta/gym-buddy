package com.gymbuddy.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises native MPImage/Bitmap ownership, including the pre-inference
 * rejection paths that cannot be validated by a fake image or a mock analyzer. */
internal class FrameOwnershipIntegrationSuite(private val context:Context) {
    fun stoppedAnalyzerReleasesRejectedFrame() {
        val pose=MediaPipePoseAnalyzer(context)
        try {
            val analyzer=ProductionFrameAnalyzer(poseAnalyzer=pose,processorFactory={error("No processor expected")})
            analyzer.finishSet(1_000L)
            withFrame { bitmap,frame ->
                check(runCatching { analyzer.analyze(frame) }.exceptionOrNull() is IllegalStateException)
                check(bitmap.isRecycled) { "Stopped analyzer retained a rejected frame's native bitmap" }
            }
        } finally { pose.close() }
    }

    fun failedObservationContextReleasesFrame() {
        val pose=MediaPipePoseAnalyzer(context)
        try {
            val analyzer=ProductionFrameAnalyzer(poseAnalyzer=pose,
                observationContextProvider={error("Injected observation-context failure")},
                processorFactory={error("No processor expected")})
            withFrame { bitmap,frame ->
                check(runCatching { analyzer.analyze(frame) }.exceptionOrNull()?.message==
                    "Injected observation-context failure")
                check(bitmap.isRecycled) { "Pre-inference context failure retained the frame bitmap" }
            }
        } finally { pose.close() }
    }

    fun idleAndClosedRuntimeReleaseUnusedFrames() {
        val runtime=DefaultWorkoutRuntime(context)
        val executor=runtime.analysisExecutor as ExecutorService
        try {
            withFrame { bitmap,frame ->
                runtime.frameConsumer { error("No active set should emit a snapshot") }.onFrame(frame)
                check(bitmap.isRecycled) { "Runtime without an active analyzer retained an unused frame" }
            }
            runtime.close()
            check(executor.awaitTermination(10,TimeUnit.SECONDS))
            withFrame { bitmap,frame ->
                runtime.frameConsumer { error("Closed runtime emitted a snapshot") }.onFrame(frame)
                check(bitmap.isRecycled) { "Closed runtime retained a late camera frame" }
            }
        } finally {
            runtime.close()
            check(executor.awaitTermination(10,TimeUnit.SECONDS))
        }
    }

    fun successfulNativeInferenceReleasesFrame() {
        val pose=MediaPipePoseAnalyzer(context)
        try {
            withFrame { bitmap,frame ->
                val result=pose.analyze(frame)
                check(result.timestampUs==frame.timestampUs)
                check(bitmap.isRecycled) { "Native inference ownership control did not release its bitmap" }
            }
        } finally { pose.close() }
    }

    private fun withFrame(block:(Bitmap,FramePacket<MPImage>)->Unit) {
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val image=BitmapImageBuilder(bitmap).build()
        val frame=FramePacket(1L,1_000L,64,64,FrameOrigin.VIDEO,image)
        try { block(bitmap,frame) }
        finally { if(!bitmap.isRecycled)image.close() }
    }
}
