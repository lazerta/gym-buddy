package com.gymbuddy.app

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.gymbuddy.frames.FrameAnalyzer
import com.gymbuddy.frames.FramePacket

data class PosePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double?,
    val presence: Double?,
) {
    fun toWireMap(): Map<String, Any?> = buildMap {
        put("x", x)
        put("y", y)
        put("z", z)
        visibility?.let {
            put("visibility", it)
        }
        presence?.let {
            put("presence", it)
        }
    }
}

data class PoseAnalysis(
    val mediaPipeTimestampMs: Long,
    val normalizedPoses: List<List<PosePoint>>,
    val worldPoses: List<List<PosePoint>>,
) {
    fun toWireMap(): Map<String, Any?> = mapOf(
        "mediapipe_timestamp_ms" to mediaPipeTimestampMs,
        "pose_count" to normalizedPoses.size,
        "landmarks" to normalizedPoses.map { pose ->
            pose.map {
                it.toWireMap()
            }
        },
        "world_landmarks" to worldPoses.map { pose ->
            pose.map {
                it.toWireMap()
            }
        },
    )
}

class MediaPipePoseAnalyzer(
    context: Context,
    modelAssetPath: String =
        "pose_landmarker_lite.task",
    minPoseDetectionConfidence: Float = 0.5f,
    minPosePresenceConfidence: Float = 0.5f,
    minTrackingConfidence: Float = 0.5f,
) : FrameAnalyzer<MPImage, PoseAnalysis>, AutoCloseable {

    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .build()

        val options = PoseLandmarker
            .PoseLandmarkerOptions
            .builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(
                minPoseDetectionConfidence
            )
            .setMinPosePresenceConfidence(
                minPosePresenceConfidence
            )
            .setMinTrackingConfidence(
                minTrackingConfidence
            )
            .setOutputSegmentationMasks(false)
            .build()

        landmarker = PoseLandmarker.createFromOptions(
            context.applicationContext,
            options,
        )
    }

    override fun analyze(
        frame: FramePacket<MPImage>,
    ): PoseAnalysis {
        try {
            val result = landmarker.detectForVideo(
                frame.image,
                frame.mediaPipeTimestampMs,
            )

            return PoseAnalysis(
                mediaPipeTimestampMs = result.timestampMs(),
                normalizedPoses = result.landmarks().map { pose ->
                    pose.map {
                        normalizedPoint(it)
                    }
                },
                worldPoses = result.worldLandmarks().map { pose ->
                    pose.map {
                        worldPoint(it)
                    }
                },
            )
        } finally {
            frame.image.close()
        }
    }

    override fun close() {
        landmarker.close()
    }

    private fun normalizedPoint(
        landmark: NormalizedLandmark,
    ): PosePoint {
        return PosePoint(
            x = landmark.x().toDouble(),
            y = landmark.y().toDouble(),
            z = landmark.z().toDouble(),
            visibility = landmark.visibility()
                .map {
                    it.toDouble()
                }
                .orElse(null),
            presence = landmark.presence()
                .map {
                    it.toDouble()
                }
                .orElse(null),
        )
    }

    private fun worldPoint(
        landmark: Landmark,
    ): PosePoint {
        return PosePoint(
            x = landmark.x().toDouble(),
            y = landmark.y().toDouble(),
            z = landmark.z().toDouble(),
            visibility = landmark.visibility()
                .map {
                    it.toDouble()
                }
                .orElse(null),
            presence = landmark.presence()
                .map {
                    it.toDouble()
                }
                .orElse(null),
        )
    }
}
