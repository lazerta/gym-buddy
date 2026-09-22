package com.gymbuddy.app

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate

data class PoseAdapterLandmark(
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double?,
    val presence: Double?,
)

object MediaPipePoseFrameMapper {
    fun map(
        frameId: Long,
        timestampUs: Long,
        width: Int,
        height: Int,
        source: PoseFrameSource,
        normalizedPoses: List<List<PoseAdapterLandmark>>,
        worldPoses: List<List<PoseAdapterLandmark>>,
    ): PoseFrame {
        val candidates = normalizedPoses.mapIndexed { candidateIndex, normalized ->
            PoseSubjectCandidate(
                candidateIndex = candidateIndex,
                normalizedLandmarks = mapLandmarks(normalized),
                worldLandmarks = mapLandmarks(worldPoses.getOrNull(candidateIndex).orEmpty()),
                confidence = null,
            )
        }

        return PoseFrame(
            frameId = frameId,
            timestampUs = timestampUs,
            width = width,
            height = height,
            source = source,
            candidates = candidates,
        )
    }

    private fun mapLandmarks(
        input: List<PoseAdapterLandmark>,
    ): Map<PoseLandmarkId, PoseLandmarkObservation> = buildMap {
        PoseLandmarkId.entries.forEachIndexed { index, landmarkId ->
            val raw = input.getOrNull(index) ?: return@forEachIndexed
            if (!raw.x.isFinite() || !raw.y.isFinite() || !raw.z.isFinite()) {
                return@forEachIndexed
            }
            put(
                landmarkId,
                PoseLandmarkObservation(
                    landmarkId = landmarkId,
                    position = PoseCoordinate3d(raw.x, raw.y, raw.z),
                    visibility = raw.visibility.asConfidenceOrNull(),
                    presence = raw.presence.asConfidenceOrNull(),
                ),
            )
        }
    }

    private fun Double?.asConfidenceOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in 0.0..1.0 }
}

object PoseFrameWireEncoder {
    fun encode(frame: PoseFrame): Map<String, Any?> = mapOf(
        "mediapipe_timestamp_ms" to frame.timestampUs / 1_000L,
        "pose_count" to frame.candidates.size,
        "landmarks" to frame.candidates.map { candidate ->
            orderedWireLandmarks(candidate.normalizedLandmarks)
        },
        "world_landmarks" to frame.candidates.map { candidate ->
            orderedWireLandmarks(candidate.worldLandmarks)
        },
    )

    private fun orderedWireLandmarks(
        landmarks: Map<PoseLandmarkId, PoseLandmarkObservation>,
    ): List<Map<String, Any?>> = PoseLandmarkId.entries.mapNotNull { id ->
        landmarks[id]?.let(::landmarkToWireMap)
    }

    private fun landmarkToWireMap(
        landmark: PoseLandmarkObservation,
    ): Map<String, Any?> = buildMap {
        put("landmark_id", landmark.landmarkId.wireName)
        put("x", landmark.position.x)
        put("y", landmark.position.y)
        put("z", landmark.position.z)
        landmark.visibility?.let { put("visibility", it) }
        landmark.presence?.let { put("presence", it) }
    }
}
