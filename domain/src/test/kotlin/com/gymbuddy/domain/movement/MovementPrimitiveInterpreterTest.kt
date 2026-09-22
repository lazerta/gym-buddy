package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementPrimitiveInterpreterTest {
    @Test fun stableStartArmsOnlyAfterDwell() {
        val interpreter = MovementPrimitiveInterpreter()
        assertEquals(PrimitivePhase.SETUP, phase(interpreter, 0, 0.10))
        assertEquals(PrimitivePhase.SETUP, phase(interpreter, 100_000, 0.10))
        assertEquals(PrimitivePhase.START, phase(interpreter, 160_000, 0.10))
    }

    @Test fun setupMotionAwayFromStartIsIgnoredUntilStableStart() {
        val interpreter = MovementPrimitiveInterpreter()
        assertEquals(PrimitivePhase.SETUP, phase(interpreter, 0, 0.55))
        assertEquals(PrimitivePhase.SETUP, phase(interpreter, 100_000, 0.10))
        assertEquals(PrimitivePhase.START, phase(interpreter, 260_000, 0.10))
    }

    @Test fun correctDirectionProducesOutboundThenEndRange() {
        val interpreter = armed()
        assertEquals(PrimitivePhase.OUTBOUND, phase(interpreter, 300_000, 0.45))
        assertEquals(PrimitivePhase.END_RANGE, phase(interpreter, 500_000, 0.85))
    }

    @Test fun partialMotionDoesNotBecomeEndRange() {
        val interpreter = armed()
        assertEquals(PrimitivePhase.OUTBOUND, phase(interpreter, 300_000, 0.55))
    }

    @Test fun returnPhaseIsDetectedAfterEndRange() {
        val interpreter = armed()
        phase(interpreter, 300_000, 0.85)
        assertEquals(PrimitivePhase.RETURNING, phase(interpreter, 500_000, 0.50))
        assertEquals(PrimitivePhase.START, phase(interpreter, 700_000, 0.15))
    }

    @Test fun legitimatePauseRemainsPausedNotReset() {
        val interpreter = armed()
        phase(interpreter, 300_000, 0.50)
        assertEquals(PrimitivePhase.PAUSED, phase(interpreter, 500_000, 0.501))
        assertEquals(PrimitivePhase.OUTBOUND, phase(interpreter, 700_000, 0.70))
    }

    @Test fun sameInterpreterWorksForPressSquatAndRaisePrimitives() {
        listOf(MovementPrimitive.PRESS, MovementPrimitive.SQUAT, MovementPrimitive.RAISE).forEach { primitive ->
            val interpreter = MovementPrimitiveInterpreter()
            val sequence = sequence(primitive)
            val phases = listOf(
                interpreter.interpret(signals(0, .1), sequence).observations.getValue("cycle").phase,
                interpreter.interpret(signals(160_000, .1), sequence).observations.getValue("cycle").phase,
                interpreter.interpret(signals(300_000, .5), sequence).observations.getValue("cycle").phase,
            )
            assertEquals(listOf(PrimitivePhase.SETUP, PrimitivePhase.START, PrimitivePhase.OUTBOUND), phases)
        }
    }

    @Test fun multipleProgressSignalsAreAggregatedWithoutExerciseDispatch() {
        val interpreter = MovementPrimitiveInterpreter()
        val sequence = MovementPrimitiveSequence(
            "bilateral", 1, "hash", listOf(
                MovementPrimitiveStep(
                    "cycle", MovementPrimitive.PRESS,
                    listOf("left", "right"),
                    mapOf("start_max" to .2, "end_min" to .8, "stable_start_us" to 0.0),
                )
            )
        )
        fun frame(ts: Long, left: Double, right: Double) = MovementSignalFrame(
            ts, mapOf(
                "left" to MovementSignalObservation("left", SignalUnit.NORMALIZED, left, .9),
                "right" to MovementSignalObservation("right", SignalUnit.NORMALIZED, right, .8),
            )
        )
        val start = interpreter.interpret(frame(0, .1, .1), sequence).observations.getValue("cycle")
        val outbound = interpreter.interpret(frame(100_000, .5, .7), sequence).observations.getValue("cycle")
        assertEquals(PrimitivePhase.START, start.phase)
        assertEquals(.6, outbound.progress!!, 1e-9)
        assertEquals(.8, outbound.confidence!!, 0.0)
    }

    private fun armed(): MovementPrimitiveInterpreter = MovementPrimitiveInterpreter().also {
        phase(it, 0, 0.10)
        phase(it, 160_000, 0.10)
    }

    private fun phase(interpreter: MovementPrimitiveInterpreter, ts: Long, value: Double): PrimitivePhase =
        interpreter.interpret(signals(ts, value), sequence(MovementPrimitive.PRESS))
            .observations.getValue("cycle").phase

    private fun signals(ts: Long, value: Double) = MovementSignalFrame(
        ts,
        mapOf("progress" to MovementSignalObservation("progress", SignalUnit.NORMALIZED, value, .95)),
    )

    private fun sequence(primitive: MovementPrimitive) = MovementPrimitiveSequence(
        "primitive-${primitive.name.lowercase()}", 1, "hash-${primitive.name}", listOf(
            MovementPrimitiveStep(
                "cycle", primitive, listOf("progress"), mapOf(
                    "start_max" to .20,
                    "end_min" to .80,
                    "stable_start_us" to 150_000.0,
                    "pause_velocity_threshold" to .03,
                )
            )
        )
    )
}
