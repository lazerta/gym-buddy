package com.gymbuddy.domain.tracking

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class PrimarySubjectLockState {
    LOCKED,
    TEMPORARILY_OCCLUDED,
    TARGET_AMBIGUOUS,
    TARGET_LOST,
    REACQUIRING,
}

data class PrimarySubjectLockConfig(
    val minCandidateLandmarks: Int = 4,
    val minCandidateObservability: Double = 0.10,
    val acquisitionMinScore: Double = 0.58,
    val lockedMinScore: Double = 0.56,
    val reacquireMinScore: Double = 0.66,
    val minIdentityMargin: Double = 0.12,
    val shortOcclusionGraceUs: Long = 450_000L,
    val lostAfterUs: Long = 1_500_000L,
    val reacquireDwellFrames: Int = 2,
    val maxSingleFrameScaleRatio: Double = 1.35,
) {
    init {
        require(minCandidateLandmarks >= 1)
        require(minCandidateObservability in 0.0..1.0)
        require(acquisitionMinScore in 0.0..1.0)
        require(lockedMinScore in 0.0..1.0)
        require(reacquireMinScore in 0.0..1.0)
        require(minIdentityMargin in 0.0..1.0)
        require(shortOcclusionGraceUs >= 0)
        require(lostAfterUs >= shortOcclusionGraceUs)
        require(reacquireDwellFrames >= 1)
        require(maxSingleFrameScaleRatio >= 1.0)
    }
}

data class PrimarySubjectLockResult(
    val state: PrimarySubjectLockState,
    val targetCandidateIndex: Int?,
    val targetScore: Double?,
    val identityMargin: Double?,
    val candidateCount: Int,
) {
    val isIdentitySafe: Boolean
        get() = state == PrimarySubjectLockState.LOCKED
}

class PrimarySubjectLock(
    private val config: PrimarySubjectLockConfig = PrimarySubjectLockConfig(),
) {
    private var fingerprint: SubjectFingerprint? = null
    private var lastMatchedTimestampUs: Long? = null
    private var lastCenter: Vec2? = null
    private var lastVelocity: Vec2 = Vec2(0.0, 0.0)
    private var previousCenterTimestampUs: Long? = null
    private var reacquireCandidate: SubjectFingerprint? = null
    private var reacquireFrames: Int = 0
    private var state: PrimarySubjectLockState = PrimarySubjectLockState.TARGET_LOST

    fun reset() {
        fingerprint = null
        lastMatchedTimestampUs = null
        lastCenter = null
        lastVelocity = Vec2(0.0, 0.0)
        previousCenterTimestampUs = null
        reacquireCandidate = null
        reacquireFrames = 0
        state = PrimarySubjectLockState.TARGET_LOST
    }

    fun update(frame: PoseFrame): PrimarySubjectLockResult {
        val features = frame.candidates.mapNotNull { candidate ->
            CandidateFeatures.from(candidate)?.takeIf {
                it.landmarkCount >= config.minCandidateLandmarks &&
                    it.observability >= config.minCandidateObservability
            }
        }

        val currentFingerprint = fingerprint
        if (currentFingerprint == null) {
            return acquireInitial(frame, features)
        }

        if (features.isEmpty()) {
            return handleMissing(frame.timestampUs, frame.candidates.size)
        }

        val ranked = features
            .map { it to scoreLockedCandidate(it, currentFingerprint, frame.timestampUs) }
            .sortedByDescending { it.second }
        val best = ranked[0]
        val secondScore = ranked.getOrNull(1)?.second
        val margin = secondScore?.let { best.second - it } ?: best.second

        val threshold = if (
            state == PrimarySubjectLockState.TARGET_LOST ||
            state == PrimarySubjectLockState.REACQUIRING ||
            state == PrimarySubjectLockState.TARGET_AMBIGUOUS
        ) {
            config.reacquireMinScore
        } else {
            config.lockedMinScore
        }

        if (best.second < threshold) {
            clearReacquisition()
            state = if (isWithinLostWindow(frame.timestampUs)) {
                PrimarySubjectLockState.TARGET_AMBIGUOUS
            } else {
                PrimarySubjectLockState.TARGET_LOST
            }
            return result(state, null, best.second, margin, frame.candidates.size)
        }

        if (secondScore != null && margin < config.minIdentityMargin) {
            clearReacquisition()
            state = PrimarySubjectLockState.TARGET_AMBIGUOUS
            return result(state, null, best.second, margin, frame.candidates.size)
        }

        val needsReacquisition = state != PrimarySubjectLockState.LOCKED
        if (needsReacquisition) {
            return handleReacquisition(frame, best.first, best.second, margin)
        }

        lockTo(frame.timestampUs, best.first)
        return result(
            PrimarySubjectLockState.LOCKED,
            best.first.candidate.candidateIndex,
            best.second,
            margin,
            frame.candidates.size,
        )
    }

    private fun acquireInitial(
        frame: PoseFrame,
        features: List<CandidateFeatures>,
    ): PrimarySubjectLockResult {
        if (features.isEmpty()) {
            state = PrimarySubjectLockState.TARGET_LOST
            return result(state, null, null, null, frame.candidates.size)
        }

        val ranked = features
            .map { it to acquisitionScore(it) }
            .sortedByDescending { it.second }
        val best = ranked[0]
        val secondScore = ranked.getOrNull(1)?.second
        val margin = secondScore?.let { best.second - it } ?: best.second

        if (best.second < config.acquisitionMinScore) {
            state = PrimarySubjectLockState.TARGET_LOST
            return result(state, null, best.second, margin, frame.candidates.size)
        }

        if (secondScore != null && margin < config.minIdentityMargin) {
            state = PrimarySubjectLockState.TARGET_AMBIGUOUS
            return result(state, null, best.second, margin, frame.candidates.size)
        }

        fingerprint = best.first.toFingerprint()
        lockTo(frame.timestampUs, best.first)
        return result(
            PrimarySubjectLockState.LOCKED,
            best.first.candidate.candidateIndex,
            best.second,
            margin,
            frame.candidates.size,
        )
    }

    private fun handleMissing(
        timestampUs: Long,
        candidateCount: Int,
    ): PrimarySubjectLockResult {
        clearReacquisition()
        val elapsed = elapsedSinceMatch(timestampUs)
        state = when {
            elapsed <= config.shortOcclusionGraceUs ->
                PrimarySubjectLockState.TEMPORARILY_OCCLUDED
            elapsed <= config.lostAfterUs ->
                PrimarySubjectLockState.REACQUIRING
            else -> PrimarySubjectLockState.TARGET_LOST
        }
        return result(state, null, null, null, candidateCount)
    }

    private fun handleReacquisition(
        frame: PoseFrame,
        candidate: CandidateFeatures,
        score: Double,
        margin: Double,
    ): PrimarySubjectLockResult {
        val previous = reacquireCandidate
        val sameCandidate = previous != null &&
            fingerprintSimilarity(previous, candidate.toFingerprint()) >= 0.88

        reacquireFrames = if (sameCandidate) reacquireFrames + 1 else 1
        reacquireCandidate = candidate.toFingerprint()

        if (reacquireFrames < config.reacquireDwellFrames) {
            state = PrimarySubjectLockState.REACQUIRING
            return result(
                state,
                candidate.candidate.candidateIndex,
                score,
                margin,
                frame.candidates.size,
            )
        }

        lockTo(frame.timestampUs, candidate)
        clearReacquisition()
        return result(
            PrimarySubjectLockState.LOCKED,
            candidate.candidate.candidateIndex,
            score,
            margin,
            frame.candidates.size,
        )
    }

    private fun lockTo(timestampUs: Long, candidate: CandidateFeatures) {
        val priorCenter = lastCenter
        val priorTimestamp = previousCenterTimestampUs
        if (priorCenter != null && priorTimestamp != null && timestampUs > priorTimestamp) {
            val dtSeconds = (timestampUs - priorTimestamp) / 1_000_000.0
            val measuredVelocity = (candidate.center - priorCenter) / dtSeconds
            lastVelocity = lastVelocity * 0.65 + measuredVelocity * 0.35
        }

        lastCenter = candidate.center
        previousCenterTimestampUs = timestampUs
        lastMatchedTimestampUs = timestampUs
        val old = fingerprint
        fingerprint = if (old == null) {
            candidate.toFingerprint()
        } else {
            old.blend(candidate.toFingerprint(), alpha = 0.15)
        }
        state = PrimarySubjectLockState.LOCKED
    }

    private fun scoreLockedCandidate(
        candidate: CandidateFeatures,
        fingerprint: SubjectFingerprint,
        timestampUs: Long,
    ): Double {
        val dtSeconds = previousCenterTimestampUs
            ?.takeIf { timestampUs > it }
            ?.let { (timestampUs - it) / 1_000_000.0 }
            ?: 0.0
        val predictedCenter = lastCenter?.let { it + lastVelocity * dtSeconds }
            ?: fingerprint.center

        val normalizedDistance = distance(candidate.center, predictedCenter) /
            fingerprint.scale.coerceAtLeast(0.05)
        val continuity = exp(-1.65 * normalizedDistance)
        val scaleRatio = maxOf(
            fingerprint.scale / candidate.scale,
            candidate.scale / fingerprint.scale,
        )
        if (state == PrimarySubjectLockState.LOCKED &&
            scaleRatio > config.maxSingleFrameScaleRatio
        ) {
            return 0.0
        }
        val scale = scaleSimilarity(fingerprint.scale, candidate.scale)
        val geometry = geometrySimilarity(fingerprint.geometry, candidate.geometry)
        val observability = candidate.observability

        return (0.42 * continuity +
            0.24 * scale +
            0.29 * geometry +
            0.05 * observability).coerceIn(0.0, 1.0)
    }

    private fun acquisitionScore(candidate: CandidateFeatures): Double {
        val centered = 1.0 - (distance(candidate.center, Vec2(0.5, 0.5)) / 0.71)
            .coerceIn(0.0, 1.0)
        val usefulScale = (candidate.scale / 0.45).coerceIn(0.0, 1.0)
        return (0.48 * candidate.observability + 0.32 * centered + 0.20 * usefulScale)
            .coerceIn(0.0, 1.0)
    }

    private fun elapsedSinceMatch(timestampUs: Long): Long {
        val last = lastMatchedTimestampUs ?: return Long.MAX_VALUE
        return (timestampUs - last).coerceAtLeast(0L)
    }

    private fun isWithinLostWindow(timestampUs: Long): Boolean =
        elapsedSinceMatch(timestampUs) <= config.lostAfterUs

    private fun clearReacquisition() {
        reacquireCandidate = null
        reacquireFrames = 0
    }

    private fun result(
        state: PrimarySubjectLockState,
        candidateIndex: Int?,
        score: Double?,
        margin: Double?,
        candidateCount: Int,
    ) = PrimarySubjectLockResult(
        state = state,
        targetCandidateIndex = candidateIndex,
        targetScore = score,
        identityMargin = margin,
        candidateCount = candidateCount,
    )
}

private data class CandidateFeatures(
    val candidate: PoseSubjectCandidate,
    val center: Vec2,
    val scale: Double,
    val geometry: Map<String, Double>,
    val observability: Double,
    val landmarkCount: Int,
) {
    fun toFingerprint() = SubjectFingerprint(center, scale, geometry)

    companion object {
        fun from(candidate: PoseSubjectCandidate): CandidateFeatures? {
            val points = candidate.normalizedLandmarks.mapValues { it.value.position }
            val torsoIds = listOf(
                PoseLandmarkId.LEFT_SHOULDER,
                PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.LEFT_HIP,
                PoseLandmarkId.RIGHT_HIP,
            )
            val torso = torsoIds.mapNotNull(points::get)
            if (torso.size < 2) return null

            val center = Vec2(
                torso.map { it.x }.average(),
                torso.map { it.y }.average(),
            )
            val shoulderMid = midpoint(
                points[PoseLandmarkId.LEFT_SHOULDER],
                points[PoseLandmarkId.RIGHT_SHOULDER],
            )
            val hipMid = midpoint(
                points[PoseLandmarkId.LEFT_HIP],
                points[PoseLandmarkId.RIGHT_HIP],
            )
            val torsoLength = if (shoulderMid != null && hipMid != null) {
                distance3d(shoulderMid, hipMid)
            } else 0.0
            val shoulderWidth = pairDistance(
                points,
                PoseLandmarkId.LEFT_SHOULDER,
                PoseLandmarkId.RIGHT_SHOULDER,
            ) ?: 0.0
            val hipWidth = pairDistance(
                points,
                PoseLandmarkId.LEFT_HIP,
                PoseLandmarkId.RIGHT_HIP,
            ) ?: 0.0
            val scale = maxOf(torsoLength + 0.5 * (shoulderWidth + hipWidth), 0.05)

            val geometry = buildMap {
                normalizedRatio("shoulder_width", shoulderWidth, scale)?.let { put("shoulder_width", it) }
                normalizedRatio("hip_width", hipWidth, scale)?.let { put("hip_width", it) }
                normalizedRatio("torso", torsoLength, scale)?.let { put("torso", it) }
                limbRatio(points, PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, scale)
                    ?.let { put("left_upper_arm", it) }
                limbRatio(points, PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, scale)
                    ?.let { put("right_upper_arm", it) }
                limbRatio(points, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, scale)
                    ?.let { put("left_forearm", it) }
                limbRatio(points, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, scale)
                    ?.let { put("right_forearm", it) }
                limbRatio(points, PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE, scale)
                    ?.let { put("left_thigh", it) }
                limbRatio(points, PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE, scale)
                    ?.let { put("right_thigh", it) }
                limbRatio(points, PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE, scale)
                    ?.let { put("left_shin", it) }
                limbRatio(points, PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE, scale)
                    ?.let { put("right_shin", it) }
            }

            val visible = candidate.normalizedLandmarks.values.count { lm ->
                val visibilityOk = lm.visibility?.let { it >= 0.25 } ?: true
                val presenceOk = lm.presence?.let { it >= 0.25 } ?: true
                visibilityOk && presenceOk
            }
            val observability = if (candidate.normalizedLandmarks.isEmpty()) 0.0
            else visible.toDouble() / candidate.normalizedLandmarks.size

            return CandidateFeatures(
                candidate = candidate,
                center = center,
                scale = scale,
                geometry = geometry,
                observability = observability,
                landmarkCount = candidate.normalizedLandmarks.size,
            )
        }

        private fun normalizedRatio(name: String, value: Double, scale: Double): Double? {
            if (name.isEmpty() || value <= 0.0 || !value.isFinite() || scale <= 0.0) return null
            return value / scale
        }
    }
}

private data class SubjectFingerprint(
    val center: Vec2,
    val scale: Double,
    val geometry: Map<String, Double>,
) {
    fun blend(other: SubjectFingerprint, alpha: Double): SubjectFingerprint {
        val keys = geometry.keys + other.geometry.keys
        val blendedGeometry = keys.associateWith { key ->
            val a = geometry[key]
            val b = other.geometry[key]
            when {
                a == null -> b!!
                b == null -> a
                else -> a * (1.0 - alpha) + b * alpha
            }
        }
        return SubjectFingerprint(
            center = center * (1.0 - alpha) + other.center * alpha,
            scale = scale * (1.0 - alpha) + other.scale * alpha,
            geometry = blendedGeometry,
        )
    }
}

private data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(value: Double) = Vec2(x * value, y * value)
    operator fun div(value: Double) = Vec2(x / value, y / value)
}

private fun distance(a: Vec2, b: Vec2): Double =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

private fun distance3d(a: PoseCoordinate3d, b: PoseCoordinate3d): Double =
    sqrt(
        (a.x - b.x) * (a.x - b.x) +
            (a.y - b.y) * (a.y - b.y) +
            (a.z - b.z) * (a.z - b.z)
    )

private fun midpoint(a: PoseCoordinate3d?, b: PoseCoordinate3d?): PoseCoordinate3d? {
    if (a == null || b == null) return null
    return PoseCoordinate3d(
        x = (a.x + b.x) / 2.0,
        y = (a.y + b.y) / 2.0,
        z = (a.z + b.z) / 2.0,
    )
}

private fun pairDistance(
    points: Map<PoseLandmarkId, PoseCoordinate3d>,
    a: PoseLandmarkId,
    b: PoseLandmarkId,
): Double? {
    val p1 = points[a] ?: return null
    val p2 = points[b] ?: return null
    return distance3d(p1, p2)
}

private fun limbRatio(
    points: Map<PoseLandmarkId, PoseCoordinate3d>,
    a: PoseLandmarkId,
    b: PoseLandmarkId,
    scale: Double,
): Double? = pairDistance(points, a, b)?.takeIf { it > 0.0 }?.div(scale)

private fun scaleSimilarity(a: Double, b: Double): Double {
    if (a <= 0.0 || b <= 0.0) return 0.0
    return exp(-2.0 * abs(ln(a / b)))
}

private fun geometrySimilarity(
    a: Map<String, Double>,
    b: Map<String, Double>,
): Double {
    val keys = a.keys.intersect(b.keys)
    if (keys.isEmpty()) return 0.45
    val meanAbs = keys.map { key -> abs(a.getValue(key) - b.getValue(key)) }.average()
    return exp(-6.0 * meanAbs).coerceIn(0.0, 1.0)
}

private fun fingerprintSimilarity(
    a: SubjectFingerprint,
    b: SubjectFingerprint,
): Double = (0.45 * scaleSimilarity(a.scale, b.scale) +
    0.55 * geometrySimilarity(a.geometry, b.geometry)).coerceIn(0.0, 1.0)
