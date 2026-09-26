package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.MovementEngineOutput
import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.SignalDefinition
import com.gymbuddy.domain.profile.SignalProfile
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded production-engine safety fuzz.
 *
 * These tests intentionally assert invariants rather than duplicating the
 * external oracle. Camera/identity composite fuzz remains harness-derived;
 * this suite stresses the real MovementInterpretationEngine across timing,
 * interruptions, missing progress evidence, and duplicate/out-of-order input.
 */
class ProductionMovementSeededFuzzTest {
    @Test
    fun interruptedPartialMovementNeverStitchesAcrossReleaseProfiles() {
        InitialExerciseProfiles.all.forEachIndexed { exerciseIndex,bundle ->
            repeat(SEEDS_PER_EXERCISE) { seedIndex ->
                val random=Random(BASE_SEED + exerciseIndex*10_000 + seedIndex)
                val engine=engine(bundle)
                val angles=angles(bundle)
                var ts=0L
                val observed=mutableListOf<MovementEngineOutput>()

                fun emit(angle:Double) {
                    observed += engine.process(
                        ts,
                        poseFor(bundle.profile.signalProfile,angle,angle),
                    )
                    ts += step(random)
                }

                emit(angles.start)
                emit(angles.start)
                emit(angles.mid)
                emit(angles.nearEnd)

                val interruption=engine.onInterruption(ts)
                observed += interruption
                ts += step(random)

                // This is the continuation of the pre-gap motion. It must not
                // become a completed repetition after the temporal reset.
                emit(angles.end)
                emit(angles.mid)
                emit(angles.start)

                assertEquals(
                    "${bundle.definition.exerciseId} seed=$seedIndex stitched a rep across interruption",
                    0,
                    observed.sumOf { it.repEvidence.size },
                )
                assertTrue(
                    "${bundle.definition.exerciseId} seed=$seedIndex did not expose interruption evidence",
                    observed.any { it.paused },
                )

                // Reacquire from a fresh stable start and complete one clean rep.
                val recovered=mutableListOf<MovementEngineOutput>()
                fun recover(angle:Double) {
                    recovered += engine.process(
                        ts,
                        poseFor(bundle.profile.signalProfile,angle,angle),
                    )
                    ts += step(random)
                }
                recover(angles.start)
                recover(angles.start)
                recover(angles.mid)
                recover(angles.end)
                recover(angles.mid)
                recover(angles.start)

                assertEquals(
                    "${bundle.definition.exerciseId} seed=$seedIndex failed fresh post-gap rep",
                    1,
                    recovered.sumOf { it.repEvidence.size },
                )
            }
        }
    }

    @Test
    fun missingProgressEvidenceNeverFabricatesRepOrCue() {
        InitialExerciseProfiles.all.forEachIndexed { exerciseIndex,bundle ->
            repeat(SEEDS_PER_EXERCISE) { seedIndex ->
                val random=Random(BASE_SEED + 100_000 + exerciseIndex*10_000 + seedIndex)
                val engine=engine(bundle)
                val angles=angles(bundle)
                var ts=0L
                val outputs=mutableListOf<MovementEngineOutput>()

                fun emit(angle:Double,missing:Boolean=false) {
                    val pose=poseFor(bundle.profile.signalProfile,angle,angle)
                    outputs += engine.process(
                        ts,
                        if(missing) missingProgress(pose,bundle.profile.signalProfile) else pose,
                    )
                    ts += step(random)
                }

                emit(angles.start)
                emit(angles.start)
                emit(angles.mid)
                emit(angles.nearEnd,missing=true)
                emit(angles.end)
                emit(angles.mid)
                emit(angles.start)

                assertEquals(
                    "${bundle.definition.exerciseId} seed=$seedIndex fabricated a rep across missing evidence",
                    0,
                    outputs.sumOf { it.repEvidence.size },
                )
                assertEquals(
                    "${bundle.definition.exerciseId} seed=$seedIndex emitted a cue without a valid completed rep",
                    0,
                    outputs.sumOf { it.cueEvents.size },
                )
                assertTrue(
                    "${bundle.definition.exerciseId} seed=$seedIndex did not pause on missing progress evidence",
                    outputs.any { it.paused },
                )
            }
        }
    }

    @Test
    fun temporalJitterCountsExactlyOnceAndDuplicateCallbacksStaySilent() {
        InitialExerciseProfiles.all.forEachIndexed { exerciseIndex,bundle ->
            repeat(SEEDS_PER_EXERCISE) { seedIndex ->
                val random=Random(BASE_SEED + 200_000 + exerciseIndex*10_000 + seedIndex)
                val engine=engine(bundle)
                val angles=angles(bundle)
                var ts=0L
                val outputs=mutableListOf<MovementEngineOutput>()

                fun emit(angle:Double) {
                    outputs += engine.process(
                        ts,
                        poseFor(bundle.profile.signalProfile,angle,angle),
                    )
                    ts += step(random)
                }

                emit(angles.start)
                emit(angles.start)
                emit(angles.mid)
                emit(angles.end)
                emit(angles.mid)
                emit(angles.start)

                assertEquals(
                    "${bundle.definition.exerciseId} seed=$seedIndex clean jittered cycle count",
                    1,
                    outputs.sumOf { it.repEvidence.size },
                )

                val lastAccepted=outputs.last().timestampUs
                val duplicate=engine.process(
                    lastAccepted,
                    poseFor(bundle.profile.signalProfile,angles.end,angles.end),
                )
                val outOfOrder=engine.process(
                    lastAccepted-1,
                    poseFor(bundle.profile.signalProfile,angles.end,angles.end),
                )
                assertTrue(duplicate.paused)
                assertTrue(outOfOrder.paused)
                assertEquals(0,duplicate.repEvidence.size+outOfOrder.repEvidence.size)
                assertEquals(0,duplicate.cueEvents.size+outOfOrder.cueEvents.size)
            }
        }
    }

    private fun engine(bundle:ExerciseBundle)=MovementInterpretationEngine(
        AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            bundle.equipment,
        )
    )

    private data class ExerciseAngles(
        val start:Double,
        val mid:Double,
        val nearEnd:Double,
        val end:Double,
    )

    private fun angles(bundle:ExerciseBundle):ExerciseAngles {
        val (start,end)=when(bundle.definition.exerciseId) {
            "incline_dumbbell_press" -> 160.0 to 70.0
            "smith_machine_squat" -> 170.0 to 90.0
            "dumbbell_lateral_raise" -> 20.0 to 90.0
            else -> error("Unexpected release exercise ${bundle.definition.exerciseId}")
        }
        fun interpolate(fraction:Double)=start+(end-start)*fraction
        return ExerciseAngles(
            start=start,
            mid=interpolate(.50),
            nearEnd=interpolate(.82),
            end=end,
        )
    }

    private fun step(random:Random)=
        125_000L+random.nextInt(0,175_000).toLong()

    private fun missingProgress(
        pose:NormalizedPose,
        profile:SignalProfile,
    ):NormalizedPose {
        val definition=profile.definitions.first { it.signalId=="left_progress" }
        val name=definition.orderedLandmarkIds.firstOrNull()
            ?:definition.requiredLandmarkIds.sorted().first()
        val id=PoseLandmarkId.entries.first { it.wireName==name }
        return pose.copy(landmarks=pose.landmarks-id)
    }

    private fun poseFor(
        profile:SignalProfile,
        leftAngle:Double,
        rightAngle:Double,
    ):NormalizedPose {
        val landmarks=mutableMapOf<PoseLandmarkId,BodyLocalLandmark>()

        fun add(definition:SignalDefinition,angle:Double,side:Double) {
            val ids=definition.orderedLandmarkIds.map { name ->
                PoseLandmarkId.entries.first { it.wireName==name }
            }
            require(ids.size==3)
            val a=ids[0]
            val b=ids[1]
            val c=ids[2]
            val bx=side*2.0
            val radians=Math.toRadians(angle)

            fun put(id:PoseLandmarkId,x:Double,y:Double) {
                landmarks[id]=BodyLocalLandmark(
                    id,x,y,0.0,.95,.95,
                )
            }

            put(b,bx,0.0)
            put(a,bx+1.0,0.0)
            put(c,bx+cos(radians),sin(radians))
        }

        add(
            profile.definitions.first { it.signalId=="left_progress" },
            leftAngle,
            -1.0,
        )
        add(
            profile.definitions.first { it.signalId=="right_progress" },
            rightAngle,
            1.0,
        )
        return NormalizedPose(0,1.0,landmarks)
    }

    companion object {
        private const val SEEDS_PER_EXERCISE=48
        private const val BASE_SEED=20260926
    }
}
