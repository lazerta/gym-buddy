package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.MovementPrimitive

enum class RepClassification { NORMAL, ASSISTED, UNCERTAIN }
enum class RepCompletionKind { COMPLETED, INVALID_ATTEMPT }
enum class RepInvalidReason { PARTIAL_RANGE, INTERRUPTED, FAILED_RETURN }

data class RepDetectorConfig(
    val minimumRepDurationUs: Long = 250_000,
    val maximumRepDurationUs: Long = 15_000_000,
    val maximumPauseUs: Long = 2_000_000,
    val minimumConfidence: Double = 0.55,
    val assistanceThreshold: Double = 0.5,
) {
    init { require(minimumRepDurationUs >= 0); require(maximumRepDurationUs > minimumRepDurationUs); require(maximumPauseUs >= 0); require(minimumConfidence in 0.0..1.0); require(assistanceThreshold in 0.0..1.0) }
}

data class RepDetectionEvent(
    val ordinal: Int,
    val stepId: String,
    val primitive: MovementPrimitive,
    val startedAtUs: Long,
    val completedAtUs: Long,
    val kind: RepCompletionKind,
    val classification: RepClassification?,
    val invalidReason: RepInvalidReason? = null,
    val minConfidence: Double?,
    val maxAssistance: Double?,
) {
    init { require(ordinal >= 0); require(startedAtUs >= 0); require(completedAtUs >= startedAtUs); if (kind == RepCompletionKind.COMPLETED) require(classification != null) }
}

class TemporalRepDetector(private val config: RepDetectorConfig = RepDetectorConfig()) {
    private enum class State { WAITING_FOR_START, ARMED, OUTBOUND, END_REACHED, RETURNING }
    private data class Attempt(var state: State = State.WAITING_FOR_START, var startedAtUs: Long? = null, var lastTimestampUs: Long? = null, var lastMotionTimestampUs: Long? = null, var minConfidence: Double? = null, var maxAssistance: Double? = null)
    private val attempts = mutableMapOf<String, Attempt>()
    private var nextOrdinal = 1

    fun reset() { attempts.clear(); nextOrdinal = 1 }

    fun update(frame: MovementPrimitiveFrame, assistance: Map<String, Double?> = emptyMap()): List<RepDetectionEvent> {
        val out = mutableListOf<RepDetectionEvent>()
        frame.observations.values.forEach { obs -> updateOne(obs, assistance[obs.stepId])?.let(out::add) }
        return out
    }

    fun onInterruption(timestampUs: Long, reason: RepInvalidReason = RepInvalidReason.INTERRUPTED): List<RepDetectionEvent> {
        val out = mutableListOf<RepDetectionEvent>()
        attempts.forEach { (step, a) ->
            val last = a.lastTimestampUs
            if (last != null && timestampUs <= last) return@forEach
            if (a.state != State.WAITING_FOR_START && a.state != State.ARMED && a.startedAtUs != null) {
                out += RepDetectionEvent(0, step, MovementPrimitive.UNKNOWN, a.startedAtUs!!, timestampUs, RepCompletionKind.INVALID_ATTEMPT, null, reason, a.minConfidence, a.maxAssistance)
            }
            attempts[step] = Attempt(lastTimestampUs = timestampUs)
        }
        return out
    }

    private fun updateOne(obs: MovementPrimitiveObservation, assistance: Double?): RepDetectionEvent? {
        val a = attempts.getOrPut(obs.stepId) { Attempt() }
        val last = a.lastTimestampUs
        if (last != null && obs.timestampUs <= last) return null
        a.lastTimestampUs = obs.timestampUs
        obs.confidence?.let { c -> a.minConfidence = a.minConfidence?.let { minOf(it, c) } ?: c }
        assistance?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { x -> a.maxAssistance = maxOf(a.maxAssistance ?: 0.0, x) }

        if (obs.phase == PrimitivePhase.UNKNOWN || obs.phase == PrimitivePhase.SETUP) {
            if (a.state != State.WAITING_FOR_START && a.state != State.ARMED) a.state = State.WAITING_FOR_START
            return null
        }
        if (obs.phase == PrimitivePhase.PAUSED) {
            val motionTs = a.lastMotionTimestampUs
            if (motionTs != null && obs.timestampUs - motionTs > config.maximumPauseUs) {
                a.state = State.WAITING_FOR_START; a.startedAtUs = null; a.minConfidence = null; a.maxAssistance = null
            }
            return null
        }
        a.lastMotionTimestampUs = obs.timestampUs

        return when (a.state) {
            State.WAITING_FOR_START -> {
                if (obs.phase == PrimitivePhase.START) a.state = State.ARMED
                null
            }
            State.ARMED -> {
                if (obs.phase == PrimitivePhase.OUTBOUND) { a.state = State.OUTBOUND; a.startedAtUs = obs.timestampUs }
                null
            }
            State.OUTBOUND -> when (obs.phase) {
                PrimitivePhase.END_RANGE -> { a.state = State.END_REACHED; null }
                PrimitivePhase.RETURNING, PrimitivePhase.START -> finishInvalid(a, obs, RepInvalidReason.PARTIAL_RANGE)
                else -> null
            }
            State.END_REACHED -> when (obs.phase) {
                PrimitivePhase.RETURNING -> { a.state = State.RETURNING; null }
                PrimitivePhase.START -> finishInvalid(a, obs, RepInvalidReason.FAILED_RETURN)
                else -> null
            }
            State.RETURNING -> when (obs.phase) {
                PrimitivePhase.START -> finishCompleted(a, obs)
                PrimitivePhase.END_RANGE -> { a.state = State.END_REACHED; null }
                else -> null
            }
        }
    }

    private fun finishCompleted(a: Attempt, obs: MovementPrimitiveObservation): RepDetectionEvent? {
        val start = a.startedAtUs ?: return resetAttempt(a).let { null }
        val duration = obs.timestampUs - start
        if (duration < config.minimumRepDurationUs || duration > config.maximumRepDurationUs) return finishInvalid(a, obs, RepInvalidReason.FAILED_RETURN)
        val classification = when {
            (a.maxAssistance ?: 0.0) >= config.assistanceThreshold -> RepClassification.ASSISTED
            a.minConfidence != null && a.minConfidence!! < config.minimumConfidence -> RepClassification.UNCERTAIN
            else -> RepClassification.NORMAL
        }
        val event = RepDetectionEvent(nextOrdinal++, obs.stepId, obs.primitive, start, obs.timestampUs, RepCompletionKind.COMPLETED, classification, null, a.minConfidence, a.maxAssistance)
        resetAttempt(a, armed = true)
        return event
    }

    private fun finishInvalid(a: Attempt, obs: MovementPrimitiveObservation, reason: RepInvalidReason): RepDetectionEvent? {
        val start = a.startedAtUs ?: obs.timestampUs
        val event = RepDetectionEvent(0, obs.stepId, obs.primitive, start, obs.timestampUs, RepCompletionKind.INVALID_ATTEMPT, null, reason, a.minConfidence, a.maxAssistance)
        resetAttempt(a, armed = obs.phase == PrimitivePhase.START)
        return event
    }

    private fun resetAttempt(a: Attempt, armed: Boolean = false): Attempt {
        a.state = if (armed) State.ARMED else State.WAITING_FOR_START
        a.startedAtUs = null; a.lastMotionTimestampUs = null; a.minConfidence = null; a.maxAssistance = null
        return a
    }
}
