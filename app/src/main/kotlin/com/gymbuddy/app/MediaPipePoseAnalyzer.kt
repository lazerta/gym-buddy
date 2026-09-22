package com.gymbuddy.app

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.frames.FrameAnalyzer
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket

class MediaPipePoseAnalyzer(
    context: Context,
    modelAssetPath: String = "pose_landmarker_lite.task",
    minPoseDetectionConfidence: Float = 0.5f,
    minPosePresenceConfidence: Float = 0.5f,
    minTrackingConfidence: Float = 0.5f,
    maxPoses: Int = 4,
) : FrameAnalyzer<MPImage, PoseFrame>, AutoCloseable {

    private val landmarker: PoseLandmarker

    init {
        require(maxPoses > 0) { "maxPoses must be > 0" }

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .build()

        val options = PoseLandmarker
            .PoseLandmarkerOptions
            .builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(maxPoses)
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setOutputSegmentationMasks(false)
            .build()

        landmarker = PoseLandmarker.createFromOptions(
            context.applicationContext,
            options,
        )
    }

    override fun analyze(frame: FramePacket<MPImage>): PoseFrame {
        try {
            val result = landmarker.detectForVideo(
                frame.image,
                frame.mediaPipeTimestampMs,
            )

            return MediaPipePoseFrameMapper.map(
                frameId = frame.frameId,
                timestampUs = frame.timestampUs,
                width = frame.width,
                height = frame.height,
                source = frame.origin.toPoseFrameSource(),
                normalizedPoses = result.landmarks().map { pose ->
                    pose.map(::normalizedLandmark)
                },
                worldPoses = result.worldLandmarks().map { pose ->
                    pose.map(::worldLandmark)
                },
            )
        } finally {
            frame.image.close()
        }
    }

    override fun close() {
        landmarker.close()
    }

    private fun normalizedLandmark(
        landmark: NormalizedLandmark,
    ): PoseAdapterLandmark = PoseAdapterLandmark(
        x = landmark.x().toDouble(),
        y = landmark.y().toDouble(),
        z = landmark.z().toDouble(),
        visibility = landmark.visibility().map { it.toDouble() }.orElse(null),
        presence = landmark.presence().map { it.toDouble() }.orElse(null),
    )

    private fun worldLandmark(
        landmark: Landmark,
    ): PoseAdapterLandmark = PoseAdapterLandmark(
        x = landmark.x().toDouble(),
        y = landmark.y().toDouble(),
        z = landmark.z().toDouble(),
        visibility = landmark.visibility().map { it.toDouble() }.orElse(null),
        presence = landmark.presence().map { it.toDouble() }.orElse(null),
    )

    private fun FrameOrigin.toPoseFrameSource(): PoseFrameSource = when (this) {
        FrameOrigin.CAMERA -> PoseFrameSource.CAMERA
        FrameOrigin.SIMULATOR -> PoseFrameSource.SIMULATOR
        FrameOrigin.VIDEO -> PoseFrameSource.VIDEO
    }
}
