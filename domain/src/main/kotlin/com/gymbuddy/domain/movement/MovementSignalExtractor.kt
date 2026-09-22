package com.gymbuddy.domain.movement

import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.SignalDefinition
import com.gymbuddy.domain.profile.SignalKind
import com.gymbuddy.domain.profile.SignalProfile
import com.gymbuddy.domain.profile.SignalUnit
import kotlin.math.acos
import kotlin.math.sqrt

enum class SignalUnknownReason {
    MISSING_LANDMARK,
    LOW_CONFIDENCE,
    INVALID_CONFIGURATION,
    INSUFFICIENT_HISTORY,
    UNSUPPORTED_SIGNAL_KIND,
}

data class MovementSignalObservation(
    val signalId: String,
    val unit: SignalUnit,
    val value: Double?,
    val confidence: Double?,
    val unknownReason: SignalUnknownReason? = null,
) {
    val isKnown: Boolean get() = value != null && unknownReason == null

    init {
        require(signalId.isNotBlank())
        value?.let { require(it.isFinite()) }
        confidence?.let { require(it.isFinite() && it in 0.0..1.0) }
        require((value == null) == (unknownReason != null)) {
            "unknown signals must have a reason and known signals must have a value"
        }
    }
}

data class MovementSignalFrame(
    val timestampUs: Long,
    val values: Map<String, MovementSignalObservation>,
) {
    init {
        require(timestampUs >= 0)
        require(values.all { (id, observation) -> id == observation.signalId })
    }
}

class MovementSignalExtractor {
    private data class HistoricalValue(val timestampUs: Long, val value: Double)

    private val previousRaw = mutableMapOf<String, HistoricalValue>()
    private val previousSmoothed = mutableMapOf<String, Double>()

    fun reset() {
        previousRaw.clear()
        previousSmoothed.clear()
    }

    fun extract(
        timestampUs: Long,
        pose: NormalizedPose,
        profile: SignalProfile,
    ): MovementSignalFrame {
        require(timestampUs >= 0)
        val observations = profile.definitions.associate { definition ->
            definition.signalId to extractSignal(timestampUs, pose, definition)
        }
        return MovementSignalFrame(timestampUs, observations)
    }

    private fun extractSignal(
        timestampUs: Long,
        pose: NormalizedPose,
        definition: SignalDefinition,
    ): MovementSignalObservation {
        val ids = resolveLandmarks(definition) ?: return unknown(
            definition,
            SignalUnknownReason.INVALID_CONFIGURATION,
        )
        val landmarks = ids.map { id -> pose.landmarks[id] }
        if (landmarks.any { it == null }) {
            return unknown(definition, SignalUnknownReason.MISSING_LANDMARK)
        }
        val nonNull = landmarks.filterNotNull()
        val confidence = confidence(nonNull)
        val minimumConfidence = definition.parameters["min_signal_confidence"] ?: 0.0
        if (confidence != null && confidence < minimumConfidence) {
            return unknown(definition, SignalUnknownReason.LOW_CONFIDENCE, confidence)
        }

        val rawValue = when (definition.kind) {
            SignalKind.JOINT_ANGLE -> jointAngle(nonNull, definition.unit)
            SignalKind.NORMALIZED_POINT_DISTANCE -> pointDistance(nonNull)
            SignalKind.BODY_LOCAL_DISPLACEMENT,
            SignalKind.ROM_PROXY -> coordinate(nonNull, definition.parameters["axis"])
            SignalKind.VELOCITY -> velocity(timestampUs, definition, nonNull)
            SignalKind.DIRECTION -> direction(timestampUs, definition, nonNull)
            SignalKind.CONFIDENCE -> confidence
            SignalKind.REVERSAL,
            SignalKind.PHASE_DWELL,
            SignalKind.BILATERAL_TIMING,
            SignalKind.TRAJECTORY_DEVIATION,
            SignalKind.HOLD_DURATION -> null
        }

        if (rawValue == null) {
            val reason = if (definition.kind == SignalKind.VELOCITY || definition.kind == SignalKind.DIRECTION) {
                SignalUnknownReason.INSUFFICIENT_HISTORY
            } else {
                SignalUnknownReason.UNSUPPORTED_SIGNAL_KIND
            }
            return unknown(definition, reason, confidence)
        }

        val scaledValue = rawValue * (definition.parameters["scale"] ?: 1.0) +
            (definition.parameters["offset"] ?: 0.0)
        val alpha = definition.parameters["smoothing_alpha"]
        val value = if (alpha == null) {
            scaledValue
        } else {
            require(alpha in 0.0..1.0) { "smoothing_alpha must be within [0, 1]" }
            val prior = previousSmoothed[definition.signalId]
            if (prior == null) scaledValue else prior * (1.0 - alpha) + scaledValue * alpha
        }
        previousSmoothed[definition.signalId] = value
        return MovementSignalObservation(definition.signalId, definition.unit, value, confidence)
    }

    private fun resolveLandmarks(definition: SignalDefinition): List<PoseLandmarkId>? {
        val names = if (definition.orderedLandmarkIds.isNotEmpty()) {
            definition.orderedLandmarkIds
        } else {
            definition.requiredLandmarkIds.sorted()
        }
        val ids = names.map { name ->
            PoseLandmarkId.entries.firstOrNull { it.wireName == name } ?: return null
        }
        val requiredCount = when (definition.kind) {
            SignalKind.JOINT_ANGLE -> 3
            SignalKind.NORMALIZED_POINT_DISTANCE -> 2
            SignalKind.BODY_LOCAL_DISPLACEMENT,
            SignalKind.ROM_PROXY,
            SignalKind.VELOCITY,
            SignalKind.DIRECTION -> 1
            SignalKind.CONFIDENCE -> ids.size
            else -> ids.size
        }
        return ids.takeIf { it.size == requiredCount || definition.kind == SignalKind.CONFIDENCE }
    }

    private fun jointAngle(
        landmarks: List<BodyLocalLandmark>,
        unit: SignalUnit,
    ): Double? {
        if (landmarks.size != 3) return null
        val a = landmarks[0]
        val b = landmarks[1]
        val c = landmarks[2]
        val ab = doubleArrayOf(a.x - b.x, a.y - b.y, a.z - b.z)
        val cb = doubleArrayOf(c.x - b.x, c.y - b.y, c.z - b.z)
        val dot = ab.zip(cb).sumOf { (x, y) -> x * y }
        val na = sqrt(ab.sumOf { it * it })
        val nc = sqrt(cb.sumOf { it * it })
        if (na <= 1e-12 || nc <= 1e-12) return null
        val radians = acos((dot / (na * nc)).coerceIn(-1.0, 1.0))
        return when (unit) {
            SignalUnit.DEGREES -> Math.toDegrees(radians)
            SignalUnit.RADIANS -> radians
            else -> null
        }
    }

    private fun pointDistance(landmarks: List<BodyLocalLandmark>): Double? {
        if (landmarks.size != 2) return null
        val a = landmarks[0]
        val b = landmarks[1]
        return sqrt(
            (a.x - b.x) * (a.x - b.x) +
                (a.y - b.y) * (a.y - b.y) +
                (a.z - b.z) * (a.z - b.z)
        )
    }

    private fun coordinate(
        landmarks: List<BodyLocalLandmark>,
        axisValue: Double?,
    ): Double? {
        if (landmarks.size != 1 || axisValue == null) return null
        return when (axisValue.toInt()) {
            0 -> landmarks[0].x
            1 -> landmarks[0].y
            2 -> landmarks[0].z
            else -> null
        }
    }

    private fun velocity(
        timestampUs: Long,
        definition: SignalDefinition,
        landmarks: List<BodyLocalLandmark>,
    ): Double? {
        val current = coordinate(landmarks, definition.parameters["axis"]) ?: return null
        val key = definition.signalId
        val previous = previousRaw.put(key, HistoricalValue(timestampUs, current)) ?: return null
        val dt = (timestampUs - previous.timestampUs) / 1_000_000.0
        if (dt <= 0.0) return null
        return (current - previous.value) / dt
    }

    private fun direction(
        timestampUs: Long,
        definition: SignalDefinition,
        landmarks: List<BodyLocalLandmark>,
    ): Double? {
        val current = coordinate(landmarks, definition.parameters["axis"]) ?: return null
        val key = definition.signalId
        val previous = previousRaw.put(key, HistoricalValue(timestampUs, current)) ?: return null
        val delta = current - previous.value
        val epsilon = definition.parameters["direction_epsilon"] ?: 1e-4
        return when {
            delta > epsilon -> 1.0
            delta < -epsilon -> -1.0
            else -> 0.0
        }
    }

    private fun confidence(landmarks: List<BodyLocalLandmark>): Double? {
        val values = landmarks.flatMap { lm -> listOfNotNull(lm.visibility, lm.presence) }
        return values.minOrNull()
    }

    private fun unknown(
        definition: SignalDefinition,
        reason: SignalUnknownReason,
        confidence: Double? = null,
    ) = MovementSignalObservation(
        signalId = definition.signalId,
        unit = definition.unit,
        value = null,
        confidence = confidence,
        unknownReason = reason,
    )
}
