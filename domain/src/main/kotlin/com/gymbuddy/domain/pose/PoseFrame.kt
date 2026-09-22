package com.gymbuddy.domain.pose

enum class PoseFrameSource {
    CAMERA,
    SIMULATOR,
    VIDEO,
}

enum class PoseLandmarkId(val wireName: String) {
    NOSE("nose"),
    LEFT_EYE_INNER("left_eye_inner"),
    LEFT_EYE("left_eye"),
    LEFT_EYE_OUTER("left_eye_outer"),
    RIGHT_EYE_INNER("right_eye_inner"),
    RIGHT_EYE("right_eye"),
    RIGHT_EYE_OUTER("right_eye_outer"),
    LEFT_EAR("left_ear"),
    RIGHT_EAR("right_ear"),
    MOUTH_LEFT("mouth_left"),
    MOUTH_RIGHT("mouth_right"),
    LEFT_SHOULDER("left_shoulder"),
    RIGHT_SHOULDER("right_shoulder"),
    LEFT_ELBOW("left_elbow"),
    RIGHT_ELBOW("right_elbow"),
    LEFT_WRIST("left_wrist"),
    RIGHT_WRIST("right_wrist"),
    LEFT_PINKY("left_pinky"),
    RIGHT_PINKY("right_pinky"),
    LEFT_INDEX("left_index"),
    RIGHT_INDEX("right_index"),
    LEFT_THUMB("left_thumb"),
    RIGHT_THUMB("right_thumb"),
    LEFT_HIP("left_hip"),
    RIGHT_HIP("right_hip"),
    LEFT_KNEE("left_knee"),
    RIGHT_KNEE("right_knee"),
    LEFT_ANKLE("left_ankle"),
    RIGHT_ANKLE("right_ankle"),
    LEFT_HEEL("left_heel"),
    RIGHT_HEEL("right_heel"),
    LEFT_FOOT_INDEX("left_foot_index"),
    RIGHT_FOOT_INDEX("right_foot_index"),
}

data class PoseCoordinate3d(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "pose coordinates must be finite"
        }
    }
}

data class PoseLandmarkObservation(
    val landmarkId: PoseLandmarkId,
    val position: PoseCoordinate3d,
    val visibility: Double?,
    val presence: Double?,
) {
    init {
        visibility?.let {
            require(it.isFinite() && it in 0.0..1.0) {
                "visibility must be null or within [0, 1]"
            }
        }
        presence?.let {
            require(it.isFinite() && it in 0.0..1.0) {
                "presence must be null or within [0, 1]"
            }
        }
    }
}

data class PoseSubjectCandidate(
    val candidateIndex: Int,
    val normalizedLandmarks: Map<PoseLandmarkId, PoseLandmarkObservation>,
    val worldLandmarks: Map<PoseLandmarkId, PoseLandmarkObservation> = emptyMap(),
    val confidence: Double? = null,
) {
    init {
        require(candidateIndex >= 0) { "candidateIndex must be >= 0" }
        confidence?.let {
            require(it.isFinite() && it in 0.0..1.0) {
                "confidence must be null or within [0, 1]"
            }
        }
        require(normalizedLandmarks.all { (id, landmark) -> id == landmark.landmarkId }) {
            "normalized landmark map keys must match landmark ids"
        }
        require(worldLandmarks.all { (id, landmark) -> id == landmark.landmarkId }) {
            "world landmark map keys must match landmark ids"
        }
    }

    fun normalized(landmarkId: PoseLandmarkId): PoseLandmarkObservation? =
        normalizedLandmarks[landmarkId]

    fun world(landmarkId: PoseLandmarkId): PoseLandmarkObservation? =
        worldLandmarks[landmarkId]
}

data class PoseFrame(
    val frameId: Long,
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val source: PoseFrameSource,
    val candidates: List<PoseSubjectCandidate>,
) {
    init {
        require(frameId >= 0L) { "frameId must be >= 0" }
        require(timestampUs >= 0L) { "timestampUs must be >= 0" }
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(candidates.map { it.candidateIndex }.distinct().size == candidates.size) {
            "candidate indexes must be unique within a frame"
        }
    }
}

class PoseFrameTimestampGuard {
    private var lastTimestampUs: Long? = null

    fun accept(frame: PoseFrame): PoseFrame {
        val previous = lastTimestampUs
        require(previous == null || frame.timestampUs > previous) {
            "PoseFrame timestamps must be strictly increasing within a stream"
        }
        lastTimestampUs = frame.timestampUs
        return frame
    }

    fun reset() {
        lastTimestampUs = null
    }
}
