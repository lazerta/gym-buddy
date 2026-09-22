package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.MovementPrimitiveSequence

enum class RepClassification { NORMAL, ASSISTED, UNCERTAIN }
enum class RepCompletionKind { COMPLETED, INVALID_ATTEMPT }
enum class RepInvalidReason { PARTIAL_RANGE, INTERRUPTED, FAILED_RETURN }

data class RepDetectorConfig(
    val minimumRepDurationUs: Long = 250_000,
    val maximumRepDurationUs: Long = 30_000_000,
    val maximumPauseUs: Long = 10_000_000,
    val minimumConfidence: Double = 0.55,
    val assistanceThreshold: Double = 0.5,
    val uncertainAssistanceThreshold: Double = 0.85,
) {
    init {
        require(minimumRepDurationUs >= 0)
        require(maximumRepDurationUs > minimumRepDurationUs)
        require(maximumPauseUs >= 0)
        require(minimumConfidence in 0.0..1.0)
        require(assistanceThreshold in 0.0..1.0)
        require(uncertainAssistanceThreshold in assistanceThreshold..1.0)
    }
    companion object {
        fun fromSequence(sequence: MovementPrimitiveSequence): Map<String, RepDetectorConfig> =
            sequence.steps.associate { step ->
                val p = step.parameters
                step.stepId to RepDetectorConfig(
                    minimumRepDurationUs = (p["minimum_rep_duration_us"] ?: 250_000.0).toLong(),
                    maximumRepDurationUs = (p["maximum_rep_duration_us"] ?: 30_000_000.0).toLong(),
                    maximumPauseUs = (p["maximum_pause_us"] ?: 10_000_000.0).toLong(),
                    minimumConfidence = p["minimum_confidence"] ?: 0.55,
                    assistanceThreshold = p["assistance_threshold"] ?: 0.5,
                    uncertainAssistanceThreshold = p["uncertain_assistance_threshold"] ?: 0.85,
                )
            }
    }
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
    init {
        require(ordinal >= 0)
        require(stepId.isNotBlank())
        require(startedAtUs >= 0)
        require(completedAtUs >= startedAtUs)
        minConfidence?.let { require(it.isFinite() && it in 0.0..1.0) }
        maxAssistance?.let { require(it.isFinite() && it in 0.0..1.0) }
        if (kind == RepCompletionKind.COMPLETED) {
            require(ordinal > 0)
            require(classification != null)
            require(invalidReason == null)
        } else {
            require(classification == null)
            require(invalidReason != null)
        }
    }
}

class TemporalRepDetector(
    private val defaultConfig: RepDetectorConfig = RepDetectorConfig(),
    private val stepConfigs: Map<String, RepDetectorConfig> = emptyMap(),
) {
    private enum class State { WAITING_FOR_START, ARMED, OUTBOUND, END_REACHED, RETURNING }
    private data class Attempt(
        var state: State = State.WAITING_FOR_START,
        var startedAtUs: Long? = null,
        var lastTimestampUs: Long? = null,
        var lastMotionTimestampUs: Long? = null,
        var minConfidence: Double? = null,
        var maxAssistance: Double? = null,
        var primitive: MovementPrimitive = MovementPrimitive.UNKNOWN,
    )
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
            if (a.state in setOf(State.OUTBOUND, State.END_REACHED, State.RETURNING) && a.startedAtUs != null) {
                out += RepDetectionEvent(0, step, a.primitive, a.startedAtUs!!, timestampUs, RepCompletionKind.INVALID_ATTEMPT, null, reason, a.minConfidence, a.maxAssistance)
            }
            resetAttempt(a)
            a.lastTimestampUs = timestampUs
        }
        return out
    }

    private fun updateOne(obs: MovementPrimitiveObservation, assistance: Double?): RepDetectionEvent? {
        val config = stepConfigs[obs.stepId] ?: defaultConfig
        val a = attempts.getOrPut(obs.stepId) { Attempt() }
        val last = a.lastTimestampUs
        if (last != null && obs.timestampUs <= last) return null
        a.lastTimestampUs = obs.timestampUs
        a.primitive = obs.primitive
        obs.confidence?.let { c -> a.minConfidence = a.minConfidence?.let { minOf(it, c) } ?: c }
        assistance?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { x -> a.maxAssistance = maxOf(a.maxAssistance ?: 0.0, x) }

        val start = a.startedAtUs
        if (start != null && obs.timestampUs - start > config.maximumRepDurationUs) {
            return finishInvalid(a, obs, RepInvalidReason.FAILED_RETURN)
        }

        if (obs.phase == PrimitivePhase.UNKNOWN) {
            return if (a.state in setOf(State.OUTBOUND, State.END_REACHED, State.RETURNING) && a.startedAtUs != null) {
                finishInvalid(a, obs, RepInvalidReason.INTERRUPTED)
            } else {
                resetAttempt(a); null
            }
        }
        if (obs.phase == PrimitivePhase.SETUP) {
            return if (a.state in setOf(State.OUTBOUND, State.END_REACHED, State.RETURNING) && a.startedAtUs != null) {
                finishInvalid(a, obs, RepInvalidReason.PARTIAL_RANGE)
            } else {
                resetAttempt(a); null
            }
        }
        if (obs.phase == PrimitivePhase.PAUSED) {
            val motionTs = a.lastMotionTimestampUs
            if (motionTs != null && obs.timestampUs - motionTs > config.maximumPauseUs) {
                return if (a.state in setOf(State.OUTBOUND, State.END_REACHED, State.RETURNING) && a.startedAtUs != null) {
                    finishInvalid(a, obs, RepInvalidReason.FAILED_RETURN)
                } else {
                    resetAttempt(a); null
                }
            }
            return null
        }
        a.lastMotionTimestampUs = obs.timestampUs

        return when (a.state) {
            State.WAITING_FOR_START -> { if (obs.phase == PrimitivePhase.START) a.state = State.ARMED; null }
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
                PrimitivePhase.START -> finishCompleted(a, obs, config)
                PrimitivePhase.END_RANGE -> { a.state = State.END_REACHED; null }
                else -> null
            }
        }
    }

    private fun finishCompleted(a: Attempt, obs: MovementPrimitiveObservation, config: RepDetectorConfig): RepDetectionEvent? {
        val start = a.startedAtUs ?: return resetAttempt(a).let { null }
        val duration = obs.timestampUs - start
        if (duration < config.minimumRepDurationUs || duration > config.maximumRepDurationUs) return finishInvalid(a, obs, RepInvalidReason.FAILED_RETURN)
        val assistance = a.maxAssistance ?: 0.0
        val classification = when {
            assistance >= config.uncertainAssistanceThreshold -> RepClassification.UNCERTAIN
            assistance >= config.assistanceThreshold -> RepClassification.ASSISTED
            a.minConfidence != null && a.minConfidence!! < config.minimumConfidence -> RepClassification.UNCERTAIN
            else -> RepClassification.NORMAL
        }
        val event = RepDetectionEvent(nextOrdinal++, obs.stepId, obs.primitive, start, obs.timestampUs, RepCompletionKind.COMPLETED, classification, null, a.minConfidence, a.maxAssistance)
        resetAttempt(a, armed = true)
        return event
    }

    private fun finishInvalid(a: Attempt, obs: MovementPrimitiveObservation, reason: RepInvalidReason): RepDetectionEvent? {
        val start = a.startedAtUs ?: obs.timestampUs
        val primitive = if (a.primitive != MovementPrimitive.UNKNOWN) a.primitive else obs.primitive
        val event = RepDetectionEvent(0, obs.stepId, primitive, start, obs.timestampUs, RepCompletionKind.INVALID_ATTEMPT, null, reason, a.minConfidence, a.maxAssistance)
        resetAttempt(a, armed = obs.phase == PrimitivePhase.START)
        return event
    }

    private fun resetAttempt(a: Attempt, armed: Boolean = false): Attempt {
        a.state = if (armed) State.ARMED else State.WAITING_FOR_START
        a.startedAtUs = null
        a.lastMotionTimestampUs = null
        a.minConfidence = null
        a.maxAssistance = null
        a.primitive = MovementPrimitive.UNKNOWN
        return a
    }
}
