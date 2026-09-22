package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.MovementPrimitiveSequence
import com.gymbuddy.domain.profile.MovementPrimitiveStep
import kotlin.math.abs

enum class PrimitivePhase {
    UNKNOWN,
    SETUP,
    START,
    OUTBOUND,
    END_RANGE,
    RETURNING,
    PAUSED,
}

data class MovementPrimitiveObservation(
    val stepId: String,
    val primitive: MovementPrimitive,
    val timestampUs: Long,
    val phase: PrimitivePhase,
    val progress: Double?,
    val velocity: Double?,
    val confidence: Double?,
) {
    init {
        require(stepId.isNotBlank())
        require(timestampUs >= 0)
        progress?.let { require(it.isFinite()) }
        velocity?.let { require(it.isFinite()) }
        confidence?.let { require(it.isFinite() && it in 0.0..1.0) }
    }
}

data class MovementPrimitiveFrame(
    val timestampUs: Long,
    val observations: Map<String, MovementPrimitiveObservation>,
)

class MovementPrimitiveInterpreter {
    private data class StepState(
        var lastTimestampUs: Long? = null,
        var lastProgress: Double? = null,
        var startEnteredUs: Long? = null,
        var armed: Boolean = false,
    )

    private val states = mutableMapOf<String, StepState>()

    fun reset() {
        states.clear()
    }

    fun interpret(
        signals: MovementSignalFrame,
        sequence: MovementPrimitiveSequence,
    ): MovementPrimitiveFrame {
        val observations = sequence.steps.associate { step ->
            step.stepId to interpretStep(signals, step)
        }
        return MovementPrimitiveFrame(signals.timestampUs, observations)
    }

    private fun interpretStep(
        signals: MovementSignalFrame,
        step: MovementPrimitiveStep,
    ): MovementPrimitiveObservation {
        if (step.progressSignalIds.isEmpty()) return unknown(step, signals.timestampUs)
        val progressSignals = step.progressSignalIds.map { id ->
            signals.values[id] ?: return unknown(step, signals.timestampUs)
        }
        if (progressSignals.any { it.value == null }) {
            return unknown(
                step,
                signals.timestampUs,
                progressSignals.mapNotNull { it.confidence }.minOrNull(),
            )
        }
        val progress = progressSignals.map { requireNotNull(it.value) }.average()
        val progressConfidence = progressSignals.mapNotNull { it.confidence }.minOrNull()

        val startMax = step.parameters["start_max"] ?: 0.20
        val endMin = step.parameters["end_min"] ?: 0.80
        val stableStartUs = (step.parameters["stable_start_us"] ?: 150_000.0).toLong()
        val directionEpsilon = step.parameters["direction_epsilon"] ?: 1e-4
        val pauseVelocity = step.parameters["pause_velocity_threshold"] ?: 0.03
        require(startMax < endMin) { "start_max must be < end_min" }
        require(stableStartUs >= 0)
        require(directionEpsilon >= 0.0)
        require(pauseVelocity >= 0.0)

        val state = states.getOrPut(step.stepId) { StepState() }
        val previousTimestamp = state.lastTimestampUs
        val previousProgress = state.lastProgress
        if (previousTimestamp != null && signals.timestampUs <= previousTimestamp) {
            return observation(
                step,
                signals.timestampUs,
                PrimitivePhase.UNKNOWN,
                progress,
                null,
                progressConfidence,
            )
        }
        val velocity = if (
            previousTimestamp != null && previousProgress != null &&
            signals.timestampUs > previousTimestamp
        ) {
            val dt = (signals.timestampUs - previousTimestamp) / 1_000_000.0
            (progress - previousProgress) / dt
        } else null

        val inStart = progress <= startMax
        if (!state.armed) {
            if (!inStart) {
                state.startEnteredUs = null
                updateHistory(state, signals.timestampUs, progress)
                return observation(step, signals.timestampUs, PrimitivePhase.SETUP, progress, velocity, progressConfidence)
            }
            val entered = state.startEnteredUs ?: signals.timestampUs.also { state.startEnteredUs = it }
            if (signals.timestampUs - entered >= stableStartUs) {
                state.armed = true
            }
            updateHistory(state, signals.timestampUs, progress)
            return observation(
                step,
                signals.timestampUs,
                if (state.armed) PrimitivePhase.START else PrimitivePhase.SETUP,
                progress,
                velocity,
                progressConfidence,
            )
        }

        val phase = when {
            inStart -> PrimitivePhase.START
            progress >= endMin -> PrimitivePhase.END_RANGE
            velocity == null -> PrimitivePhase.PAUSED
            abs(velocity) <= pauseVelocity -> PrimitivePhase.PAUSED
            velocity > directionEpsilon -> PrimitivePhase.OUTBOUND
            velocity < -directionEpsilon -> PrimitivePhase.RETURNING
            else -> PrimitivePhase.PAUSED
        }
        updateHistory(state, signals.timestampUs, progress)
        return observation(step, signals.timestampUs, phase, progress, velocity, progressConfidence)
    }

    private fun updateHistory(state: StepState, timestampUs: Long, progress: Double) {
        state.lastTimestampUs = timestampUs
        state.lastProgress = progress
    }

    private fun observation(
        step: MovementPrimitiveStep,
        timestampUs: Long,
        phase: PrimitivePhase,
        progress: Double?,
        velocity: Double?,
        confidence: Double?,
    ) = MovementPrimitiveObservation(
        step.stepId,
        step.primitive,
        timestampUs,
        phase,
        progress,
        velocity,
        confidence,
    )

    private fun unknown(
        step: MovementPrimitiveStep,
        timestampUs: Long,
        confidence: Double? = null,
    ) = observation(
        step,
        timestampUs,
        PrimitivePhase.UNKNOWN,
        null,
        null,
        confidence,
    )
}
