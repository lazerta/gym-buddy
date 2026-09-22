package com.gymbuddy.domain.movement

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import kotlin.math.sqrt

enum class NormalizationUnknownReason {
    MISSING_TORSO_ANCHORS,
    INVALID_BODY_SCALE,
    INVALID_BODY_BASIS,
}

data class BodyLocalLandmark(
    val landmarkId: PoseLandmarkId,
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double?,
    val presence: Double?,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }
}

data class NormalizedPose(
    val candidateIndex: Int,
    val bodyScale: Double,
    val landmarks: Map<PoseLandmarkId, BodyLocalLandmark>,
    val worldLandmarks: Map<PoseLandmarkId, BodyLocalLandmark> = emptyMap(),
) {
    init {
        require(candidateIndex >= 0)
        require(bodyScale.isFinite() && bodyScale > 0.0)
        require(landmarks.all { (id, lm) -> id == lm.landmarkId })
        require(worldLandmarks.all { (id, lm) -> id == lm.landmarkId })
    }
}

sealed interface CoordinateNormalizationResult {
    data class Valid(val pose: NormalizedPose) : CoordinateNormalizationResult
    data class Unknown(val reason: NormalizationUnknownReason) : CoordinateNormalizationResult
}

class CoordinateNormalizer(
    private val minimumBodyScale: Double = 1e-5,
) {
    init {
        require(minimumBodyScale.isFinite() && minimumBodyScale > 0.0)
    }

    fun normalize(candidate: PoseSubjectCandidate): CoordinateNormalizationResult {
        val normalizedAnchors = anchors(candidate.normalizedLandmarks)
            ?: return CoordinateNormalizationResult.Unknown(
                NormalizationUnknownReason.MISSING_TORSO_ANCHORS
            )
        val basis = basis(normalizedAnchors)
            ?: return CoordinateNormalizationResult.Unknown(
                NormalizationUnknownReason.INVALID_BODY_BASIS
            )
        if (basis.scale < minimumBodyScale) {
            return CoordinateNormalizationResult.Unknown(
                NormalizationUnknownReason.INVALID_BODY_SCALE
            )
        }

        val local = transform(candidate.normalizedLandmarks, basis)
        val world = anchors(candidate.worldLandmarks)?.let { worldAnchors ->
            basis(worldAnchors)?.takeIf { it.scale >= minimumBodyScale }?.let { worldBasis ->
                transform(candidate.worldLandmarks, worldBasis)
            }
        }.orEmpty()

        return CoordinateNormalizationResult.Valid(
            NormalizedPose(
                candidateIndex = candidate.candidateIndex,
                bodyScale = basis.scale,
                landmarks = local,
                worldLandmarks = world,
            )
        )
    }

    private fun transform(
        landmarks: Map<PoseLandmarkId, PoseLandmarkObservation>,
        basis: BodyBasis,
    ): Map<PoseLandmarkId, BodyLocalLandmark> = landmarks.mapValues { (id, lm) ->
        val relative = Vec3(
            lm.position.x - basis.center.x,
            lm.position.y - basis.center.y,
            lm.position.z - basis.center.z,
        )
        BodyLocalLandmark(
            landmarkId = id,
            x = dot(relative, basis.xAxis) / basis.scale,
            y = dot(relative, basis.yAxis) / basis.scale,
            z = relative.z / basis.scale,
            visibility = lm.visibility,
            presence = lm.presence,
        )
    }

    private fun anchors(
        landmarks: Map<PoseLandmarkId, PoseLandmarkObservation>,
    ): TorsoAnchors? {
        val leftShoulder = landmarks[PoseLandmarkId.LEFT_SHOULDER]?.position ?: return null
        val rightShoulder = landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.position ?: return null
        val leftHip = landmarks[PoseLandmarkId.LEFT_HIP]?.position ?: return null
        val rightHip = landmarks[PoseLandmarkId.RIGHT_HIP]?.position ?: return null
        return TorsoAnchors(leftShoulder, rightShoulder, leftHip, rightHip)
    }

    private fun basis(anchors: TorsoAnchors): BodyBasis? {
        val shoulderMid = midpoint(anchors.leftShoulder, anchors.rightShoulder)
        val hipMid = midpoint(anchors.leftHip, anchors.rightHip)
        val center = midpoint(shoulderMid, hipMid)
        val xRaw = Vec3(
            anchors.rightShoulder.x - anchors.leftShoulder.x,
            anchors.rightShoulder.y - anchors.leftShoulder.y,
            0.0,
        )
        val torsoRaw = Vec3(
            hipMid.x - shoulderMid.x,
            hipMid.y - shoulderMid.y,
            0.0,
        )
        val scale = norm(torsoRaw)
        val xAxis = normalize(xRaw) ?: return null
        val yProjected = torsoRaw - xAxis * dot(torsoRaw, xAxis)
        val yAxis = normalize(yProjected) ?: return null
        return BodyBasis(center, xAxis, yAxis, scale)
    }
}

private data class TorsoAnchors(
    val leftShoulder: PoseCoordinate3d,
    val rightShoulder: PoseCoordinate3d,
    val leftHip: PoseCoordinate3d,
    val rightHip: PoseCoordinate3d,
)

private data class BodyBasis(
    val center: PoseCoordinate3d,
    val xAxis: Vec3,
    val yAxis: Vec3,
    val scale: Double,
)

private data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(value: Double) = Vec3(x * value, y * value, z * value)
}

private fun midpoint(a: PoseCoordinate3d, b: PoseCoordinate3d) = PoseCoordinate3d(
    (a.x + b.x) / 2.0,
    (a.y + b.y) / 2.0,
    (a.z + b.z) / 2.0,
)

private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
private fun norm(v: Vec3) = sqrt(dot(v, v))
private fun normalize(v: Vec3): Vec3? {
    val n = norm(v)
    if (!n.isFinite() || n <= 1e-12) return null
    return Vec3(v.x / n, v.y / n, v.z / n)
}
