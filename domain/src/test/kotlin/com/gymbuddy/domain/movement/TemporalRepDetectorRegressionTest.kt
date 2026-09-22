package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.*
import org.junit.Assert.*
import org.junit.Test

class TemporalRepDetectorRegressionTest {
    private val sequence = MovementPrimitiveSequence("p", 1, "h", listOf(
        MovementPrimitiveStep("cycle", MovementPrimitive.PRESS, listOf("progress"), mapOf(
            "minimum_rep_duration_us" to 100_000.0,
            "maximum_rep_duration_us" to 5_000_000.0,
            "maximum_pause_us" to 1_000_000.0,
            "assistance_threshold" to .5,
            "uncertain_assistance_threshold" to .85,
        ))
    ))

    @Test fun unknownDisarmsAndActiveUnknownInvalidatesWithoutLeakingState() {
        val detector = TemporalRepDetector(stepConfigs = RepDetectorConfig.fromSequence(sequence))
        detector.update(frame(0, PrimitivePhase.START))
        detector.update(frame(100_000, PrimitivePhase.UNKNOWN))
        assertTrue(detector.update(frame(200_000, PrimitivePhase.OUTBOUND)).isEmpty())

        detector.update(frame(300_000, PrimitivePhase.START))
        detector.update(frame(400_000, PrimitivePhase.OUTBOUND))
        val invalid = detector.update(frame(500_000, PrimitivePhase.UNKNOWN)).single()
        assertEquals(RepCompletionKind.INVALID_ATTEMPT, invalid.kind)
        assertEquals(RepInvalidReason.INTERRUPTED, invalid.invalidReason)

        // A clean rep after invalidation must start fresh and complete exactly once.
        val completed = complete(detector, 1_000_000, .0)
        assertEquals(1, completed.ordinal)
        assertEquals(RepClassification.NORMAL, completed.classification)
    }

    @Test fun assistanceClassificationSeparatesAssistedFromUncertain() {
        val detector = TemporalRepDetector(stepConfigs = RepDetectorConfig.fromSequence(sequence))
        assertEquals(RepClassification.ASSISTED, complete(detector, 1_000_000, .60).classification)
        assertEquals(RepClassification.UNCERTAIN, complete(detector, 3_000_000, .90).classification)
    }

    private fun complete(detector: TemporalRepDetector, base: Long, assistance: Double): RepDetectionEvent {
        detector.update(frame(base, PrimitivePhase.START), mapOf("cycle" to assistance))
        detector.update(frame(base + 200_000, PrimitivePhase.OUTBOUND), mapOf("cycle" to assistance))
        detector.update(frame(base + 400_000, PrimitivePhase.END_RANGE), mapOf("cycle" to assistance))
        detector.update(frame(base + 600_000, PrimitivePhase.RETURNING), mapOf("cycle" to assistance))
        return detector.update(frame(base + 800_000, PrimitivePhase.START), mapOf("cycle" to assistance)).single()
    }

    private fun frame(ts: Long, phase: PrimitivePhase) = MovementPrimitiveFrame(
        ts,
        mapOf("cycle" to MovementPrimitiveObservation("cycle", MovementPrimitive.PRESS, ts, phase, .5, .1, .9)),
    )
}
