package com.gymbuddy.domain.movement

import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.MovementPrimitiveSequence
import com.gymbuddy.domain.profile.MovementPrimitiveStep
import com.gymbuddy.domain.profile.SignalUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalRepSeededFuzzTest {
    @Test
    fun interruptionsAndJitterNeverStitchAcrossReleasePrimitives() {
        val primitives=listOf(
            MovementPrimitive.PRESS,
            MovementPrimitive.SQUAT,
            MovementPrimitive.RAISE,
        )
        primitives.forEachIndexed { primitiveIndex,primitive ->
            val sequence=sequence(primitive)
            repeat(SEEDS_PER_PRIMITIVE) { seedIndex ->
                val random=Random(BASE_SEED+primitiveIndex*10_000+seedIndex)
                val interpreter=MovementPrimitiveInterpreter()
                val detector=TemporalRepDetector(
                    stepConfigs=RepDetectorConfig.fromSequence(sequence)
                )
                var timestampUs=0L
                var completed=0

                fun emit(progress:Double) {
                    completed += detector.update(
                        interpreter.interpret(
                            signal(timestampUs,progress),
                            sequence,
                        )
                    ).count { it.kind==RepCompletionKind.COMPLETED }
                    timestampUs += step(random)
                }

                emit(.10)
                emit(.10)
                emit(.50)
                emit(.75)

                detector.onInterruption(timestampUs)
                interpreter.reset()
                timestampUs += step(random)

                emit(.90)
                emit(.50)
                emit(.10)

                assertEquals(
                    "$primitive seed=$seedIndex stitched across interruption",
                    0,
                    completed,
                )

                emit(.10)
                emit(.10)
                emit(.50)
                emit(.90)
                emit(.50)
                emit(.10)

                assertEquals(
                    "$primitive seed=$seedIndex failed fresh post-gap cycle",
                    1,
                    completed,
                )
            }
        }
    }

    @Test
    fun duplicateAndOutOfOrderFramesCannotCreateAnotherRep() {
        val sequence=sequence(MovementPrimitive.RAISE)
        val interpreter=MovementPrimitiveInterpreter()
        val detector=TemporalRepDetector(
            stepConfigs=RepDetectorConfig.fromSequence(sequence)
        )
        var completed=0

        listOf(
            0L to .10,
            120_000L to .10,
            300_000L to .50,
            500_000L to .90,
            700_000L to .50,
            900_000L to .10,
        ).forEach { (timestampUs,progress) ->
            completed += detector.update(
                interpreter.interpret(
                    signal(timestampUs,progress),
                    sequence,
                )
            ).count { it.kind==RepCompletionKind.COMPLETED }
        }
        assertEquals(1,completed)

        val duplicate=detector.update(
            interpreter.interpret(
                signal(900_000L,.90),
                sequence,
            )
        )
        val outOfOrder=detector.update(
            interpreter.interpret(
                signal(899_999L,.90),
                sequence,
            )
        )
        assertTrue(duplicate.none { it.kind==RepCompletionKind.COMPLETED })
        assertTrue(outOfOrder.none { it.kind==RepCompletionKind.COMPLETED })
    }

    private fun sequence(primitive:MovementPrimitive)=
        MovementPrimitiveSequence(
            "seeded-$primitive",
            1,
            "seeded-$primitive-v1",
            listOf(
                MovementPrimitiveStep(
                    "cycle",
                    primitive,
                    listOf("left_progress","right_progress"),
                    mapOf(
                        "start_max" to .20,
                        "end_min" to .80,
                        "stable_start_us" to 100_000.0,
                        "minimum_rep_duration_us" to 250_000.0,
                    ),
                )
            ),
        )

    private fun signal(
        timestampUs:Long,
        progress:Double,
    )=MovementSignalFrame(
        timestampUs,
        mapOf(
            "left_progress" to MovementSignalObservation(
                "left_progress",
                SignalUnit.NORMALIZED,
                progress,
                .95,
            ),
            "right_progress" to MovementSignalObservation(
                "right_progress",
                SignalUnit.NORMALIZED,
                progress,
                .95,
            ),
        ),
    )

    private fun step(random:Random)=
        125_000L+random.nextInt(0,175_000).toLong()

    companion object {
        private const val SEEDS_PER_PRIMITIVE=100
        private const val BASE_SEED=20260926
    }
}
