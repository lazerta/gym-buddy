package com.gymbuddy.domain.tracking

import com.gymbuddy.domain.pose.*
import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded identity-safety fuzz for the real PrimarySubjectLock.
 *
 * The assertions are deliberately one-sided safety invariants: uncertainty may
 * pause/reacquire, but a competing person must never become a silently LOCKED
 * replacement for the established target.
 */
class PrimarySubjectLockSeededFuzzTest {
    @Test
    fun crossingBystandersNeverBecomeSilentlyLockedTarget() {
        repeat(SEEDS) { seed ->
            val random=Random(BASE_SEED+seed)
            val lock=PrimarySubjectLock()
            val initial=lock.update(
                frame(0L,person(0,.40,.50,1.0))
            )
            assertTrue("seed=$seed initial acquisition failed",initial.isIdentitySafe)

            repeat(8) { step ->
                val ts=(step+1)*100_000L
                val targetX=.40+step*.018+random.nextDouble(-.004,.004)
                val bystanderX=.78-step*.035+random.nextDouble(-.006,.006)
                val result=lock.update(
                    frame(
                        ts,
                        person(0,targetX,.50,1.0,posePhase=step/8.0),
                        person(
                            1,
                            bystanderX,
                            .50+random.nextDouble(-.015,.015),
                            1.28+random.nextDouble(0.0,.20),
                            posePhase=1.0-step/8.0,
                        ),
                    )
                )
                if(result.state==PrimarySubjectLockState.LOCKED) {
                    assertNotEquals(
                        "seed=$seed step=$step silently switched to bystander",
                        1,
                        result.targetCandidateIndex,
                    )
                }
            }
        }
    }

    @Test
    fun bystanderOnlyDuringTargetLossNeverBecomesLockedReplacement() {
        repeat(SEEDS) { seed ->
            val random=Random(BASE_SEED+10_000+seed)
            val lock=PrimarySubjectLock()
            lock.update(frame(0L,person(0,.46,.50,1.0)))

            repeat(4) { step ->
                val result=lock.update(
                    frame(
                        (step+1)*120_000L,
                        person(
                            1,
                            .44+random.nextDouble(-.03,.03),
                            .50+random.nextDouble(-.02,.02),
                            1.30+random.nextDouble(0.0,.30),
                            posePhase=random.nextDouble(),
                        ),
                    )
                )
                assertTrue(
                    "seed=$seed step=$step silently accepted bystander-only frame",
                    result.state!=PrimarySubjectLockState.LOCKED ||
                        result.targetCandidateIndex!=1,
                )
            }
        }
    }

    @Test
    fun nearLookalikeCompetitionNeverSilentlyChoosesCompetitor() {
        repeat(SEEDS) { seed ->
            val random=Random(BASE_SEED+20_000+seed)
            val lock=PrimarySubjectLock()
            lock.update(frame(0L,person(0,.50,.50,1.0)))

            repeat(5) { step ->
                val targetX=.50+random.nextDouble(-.008,.008)
                val competitorX=targetX+random.nextDouble(.001,.010)
                val result=lock.update(
                    frame(
                        (step+1)*100_000L,
                        person(0,targetX,.50,1.0,posePhase=.40),
                        person(
                            1,
                            competitorX,
                            .50+random.nextDouble(-.004,.004),
                            1.0+random.nextDouble(-.018,.018),
                            posePhase=.40+random.nextDouble(-.03,.03),
                        ),
                    )
                )
                if(result.state==PrimarySubjectLockState.LOCKED) {
                    assertNotEquals(
                        "seed=$seed step=$step silently locked lookalike competitor",
                        1,
                        result.targetCandidateIndex,
                    )
                }
            }
        }
    }

    private fun frame(
        timestampUs:Long,
        vararg people:PoseSubjectCandidate,
    )=PoseFrame(
        frameId=timestampUs,
        timestampUs=timestampUs,
        width=1280,
        height=720,
        source=PoseFrameSource.CAMERA,
        candidates=people.toList(),
    )

    private fun person(
        index:Int,
        centerX:Double,
        centerY:Double,
        morphology:Double,
        posePhase:Double=0.0,
    ):PoseSubjectCandidate {
        val shoulderHalf=.07*morphology
        val hipHalf=.055*morphology
        val torso=.18*morphology
        val arm=.105*morphology
        val forearm=.095*morphology
        val thigh=.16*morphology
        val shin=.15*morphology
        val shoulderY=centerY-torso/2
        val hipY=centerY+torso/2
        val phaseOffset=posePhase*.018

        fun p(x:Double,y:Double)=PoseCoordinate3d(x,y,0.0)
        val points=mapOf(
            PoseLandmarkId.LEFT_SHOULDER to p(centerX-shoulderHalf,shoulderY),
            PoseLandmarkId.RIGHT_SHOULDER to p(centerX+shoulderHalf,shoulderY),
            PoseLandmarkId.LEFT_HIP to p(centerX-hipHalf,hipY),
            PoseLandmarkId.RIGHT_HIP to p(centerX+hipHalf,hipY),
            PoseLandmarkId.LEFT_ELBOW to p(centerX-shoulderHalf-arm,shoulderY+arm*.45+phaseOffset),
            PoseLandmarkId.RIGHT_ELBOW to p(centerX+shoulderHalf+arm,shoulderY+arm*.45+phaseOffset),
            PoseLandmarkId.LEFT_WRIST to p(centerX-shoulderHalf-arm-forearm,shoulderY+arm*.40+phaseOffset*1.5),
            PoseLandmarkId.RIGHT_WRIST to p(centerX+shoulderHalf+arm+forearm,shoulderY+arm*.40+phaseOffset*1.5),
            PoseLandmarkId.LEFT_KNEE to p(centerX-hipHalf,hipY+thigh),
            PoseLandmarkId.RIGHT_KNEE to p(centerX+hipHalf,hipY+thigh),
            PoseLandmarkId.LEFT_ANKLE to p(centerX-hipHalf,hipY+thigh+shin),
            PoseLandmarkId.RIGHT_ANKLE to p(centerX+hipHalf,hipY+thigh+shin),
        )
        return PoseSubjectCandidate(
            index,
            points.mapValues { (id,pos)->
                PoseLandmarkObservation(id,pos,.95,.95)
            },
        )
    }

    companion object {
        private const val SEEDS=80
        private const val BASE_SEED=20260926
    }
}
