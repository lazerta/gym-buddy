package com.gymbuddy.domain.integration

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.tracking.PrimarySubjectLock
import com.gymbuddy.domain.tracking.PrimarySubjectLockResult
import com.gymbuddy.domain.tracking.PrimarySubjectLockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HarnessPrimarySubjectLockContractTest {
    @Test
    fun harnessIdentityInvariantsMatchProductionPrimarySubjectLock() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by the read-only harness workflow",
            !fixturePath.isNullOrBlank(),
        )

        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_HARNESS_COMMIT,fixture.harnessCommit)
        assertEquals(24,fixture.cases.size)

        fixture.cases.forEach { contract ->
            val steps=sequence(contract)
            assertEquals(
                "${contract.key}: generated frame count",
                contract.frameCount,
                steps.size,
            )

            val lock=PrimarySubjectLock()
            val results=steps.mapIndexed { index, step ->
                lock.update(
                    PoseFrame(
                        frameId=index.toLong(),
                        timestampUs=step.timestampUs,
                        width=1280,
                        height=720,
                        source=PoseFrameSource.VIDEO,
                        candidates=step.people,
                    )
                ).also { result ->
                    if(
                        result.state==PrimarySubjectLockState.LOCKED &&
                        step.targetCandidateIndex!=null
                    ) {
                        assertEquals(
                            "${contract.key}: wrong identity lock at frame $index",
                            step.targetCandidateIndex,
                            result.targetCandidateIndex,
                        )
                    }
                }
            }

            if(!contract.mayPause) {
                results.forEachIndexed { index,result ->
                    assertEquals(
                        "${contract.key}: unexpected pause at frame $index",
                        PrimarySubjectLockState.LOCKED,
                        result.state,
                    )
                }
            }

            contract.ambiguousStart?.let { start ->
                for(index in start..requireNotNull(contract.ambiguousEnd)) {
                    assertTrue(
                        "${contract.key}: unsafe lock during harness ambiguous window at frame $index",
                        results[index].state!=PrimarySubjectLockState.LOCKED,
                    )
                }
            }

            contract.occlusionStart?.let { start ->
                for(index in start..requireNotNull(contract.occlusionEnd)) {
                    assertTrue(
                        "${contract.key}: lock must pause during harness occlusion window at frame $index",
                        results[index].state!=PrimarySubjectLockState.LOCKED,
                    )
                }
            }

            contract.lostStart?.let { start ->
                for(index in start..requireNotNull(contract.lostEnd)) {
                    assertEquals(
                        "${contract.key}: long exit must become TARGET_LOST at frame $index",
                        PrimarySubjectLockState.TARGET_LOST,
                        results[index].state,
                    )
                }
            }

            contract.maxReacquire?.let { maxReacquire ->
                val returnStart=(contract.occlusionEnd?:contract.lostEnd!!)+1
                val resume=(returnStart until results.size).firstOrNull {
                    results[it].state==PrimarySubjectLockState.LOCKED
                }
                assertNotNull(
                    "${contract.key}: target never reacquired",
                    resume,
                )
                assertTrue(
                    "${contract.key}: reacquisition took too long; start=$returnStart resume=$resume max=$maxReacquire",
                    requireNotNull(resume)-returnStart<=maxReacquire,
                )
            }

            assertEquals(
                "${contract.key}: scenario should end safely locked",
                PrimarySubjectLockState.LOCKED,
                results.last().state,
            )
        }
    }

    private fun sequence(contract:Contract):List<Step> {
        val shift=(contract.variantSeed-1)*.004

        fun target(
            index:Int=0,
            x:Double=.46+shift,
            morphology:Double=1.0,
            posePhase:Double=.0,
        )=person(index,x,.50,morphology,posePhase)

        fun other(
            index:Int=1,
            x:Double=.76,
            morphology:Double=1.32,
            posePhase:Double=.0,
        )=person(index,x,.50,morphology,posePhase)

        val out=mutableListOf<Step>()

        repeat(5) { index ->
            out += Step(
                timestampUs=index*100_000L,
                people=listOf(
                    target(
                        index=0,
                        x=.44+shift+index*.005,
                    )
                ),
                targetCandidateIndex=0,
            )
        }

        when(contract.scenario) {
            "background_bystander" -> repeat(15) { index ->
                out += Step(
                    timestampUs=(5+index)*100_000L,
                    people=listOf(
                        target(
                            index=0,
                            x=.465+shift+index*.002,
                        ),
                        other(
                            index=1,
                            x=.78-shift,
                        ),
                    ),
                    targetCandidateIndex=0,
                )
            }

            "spotter" -> repeat(15) { index ->
                out += Step(
                    timestampUs=(5+index)*100_000L,
                    people=listOf(
                        target(
                            index=0,
                            x=.465+shift+index*.002,
                        ),
                        other(
                            index=1,
                            x=.68,
                            morphology=1.30,
                            posePhase=.7,
                        ),
                    ),
                    targetCandidateIndex=0,
                )
            }

            "foreground_cross" -> repeat(15) { index ->
                val people=if(index<5) {
                    listOf(
                        target(
                            index=0,
                            x=.465+shift+index*.002,
                        ),
                        other(
                            index=1,
                            x=.70-index*.04,
                            morphology=1.30,
                        ),
                    )
                } else {
                    listOf(
                        target(
                            index=0,
                            x=.475+shift+index*.001,
                        )
                    )
                }

                out += Step(
                    timestampUs=(5+index)*100_000L,
                    people=people,
                    targetCandidateIndex=0,
                )
            }

            "full_occlusion" -> {
                repeat(4) { index ->
                    out += Step(
                        timestampUs=(5+index)*100_000L,
                        people=emptyList(),
                        targetCandidateIndex=null,
                    )
                }

                repeat(8) { index ->
                    val candidateIndex=4+index
                    out += Step(
                        timestampUs=(9+index)*100_000L,
                        people=listOf(
                            target(
                                index=candidateIndex,
                                x=.47+shift+index*.002,
                            )
                        ),
                        targetCandidateIndex=candidateIndex,
                    )
                }
            }

            "track_id_change" -> {
                repeat(3) { index ->
                    out += Step(
                        timestampUs=(5+index)*100_000L,
                        people=emptyList(),
                        targetCandidateIndex=null,
                    )
                }

                repeat(8) { index ->
                    val candidateIndex=10+index
                    out += Step(
                        timestampUs=(8+index)*100_000L,
                        people=listOf(
                            target(
                                index=candidateIndex,
                                x=.47+shift+index*.001,
                            )
                        ),
                        targetCandidateIndex=candidateIndex,
                    )
                }
            }

            "lookalike" -> {
                repeat(8) { index ->
                    val x=.475+shift+index*.001
                    out += Step(
                        timestampUs=(5+index)*100_000L,
                        people=listOf(
                            target(
                                index=0,
                                x=x,
                                morphology=1.0,
                            ),
                            person(
                                index=1,
                                centerX=x+.002,
                                centerY=.50,
                                morphology=1.01,
                                posePhase=.02,
                            ),
                        ),
                        targetCandidateIndex=0,
                    )
                }

                repeat(7) { index ->
                    out += Step(
                        timestampUs=(13+index)*100_000L,
                        people=listOf(
                            target(
                                index=0,
                                x=.485+shift+index*.001,
                            )
                        ),
                        targetCandidateIndex=0,
                    )
                }
            }

            "subject_swap" -> {
                repeat(8) { index ->
                    val x=.475+shift+index*.001
                    out += Step(
                        timestampUs=(5+index)*100_000L,
                        people=listOf(
                            person(
                                index=0,
                                centerX=x-.006,
                                centerY=.50,
                                morphology=1.55,
                            ),
                            person(
                                index=1,
                                centerX=x+.006,
                                centerY=.50,
                                morphology=1.55,
                                posePhase=.2,
                            ),
                        ),
                        targetCandidateIndex=null,
                    )
                }

                repeat(7) { index ->
                    out += Step(
                        timestampUs=(13+index)*100_000L,
                        people=listOf(
                            target(
                                index=0,
                                x=.485+shift+index*.001,
                            )
                        ),
                        targetCandidateIndex=0,
                    )
                }
            }

            "exit_reenter" -> {
                repeat(12) { index ->
                    out += Step(
                        timestampUs=600_000L+index*200_000L,
                        people=emptyList(),
                        targetCandidateIndex=null,
                    )
                }

                repeat(8) { index ->
                    val candidateIndex=20+index
                    out += Step(
                        timestampUs=3_000_000L+index*100_000L,
                        people=listOf(
                            target(
                                index=candidateIndex,
                                x=.47+shift+index*.001,
                            )
                        ),
                        targetCandidateIndex=candidateIndex,
                    )
                }
            }

            else -> error(
                "Unsupported harness identity scenario: " +
                    contract.scenario
            )
        }

        return out
    }

    private fun person(
        index:Int,
        centerX:Double,
        centerY:Double,
        morphology:Double,
        posePhase:Double=.0,
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

        fun point(x:Double,y:Double)=PoseCoordinate3d(
            x,
            y,
            0.0,
        )

        val points=mapOf(
            PoseLandmarkId.LEFT_SHOULDER to
                point(centerX-shoulderHalf,shoulderY),
            PoseLandmarkId.RIGHT_SHOULDER to
                point(centerX+shoulderHalf,shoulderY),
            PoseLandmarkId.LEFT_HIP to
                point(centerX-hipHalf,hipY),
            PoseLandmarkId.RIGHT_HIP to
                point(centerX+hipHalf,hipY),
            PoseLandmarkId.LEFT_ELBOW to
                point(
                    centerX-shoulderHalf-arm,
                    shoulderY+arm*.45+phaseOffset,
                ),
            PoseLandmarkId.RIGHT_ELBOW to
                point(
                    centerX+shoulderHalf+arm,
                    shoulderY+arm*.45+phaseOffset,
                ),
            PoseLandmarkId.LEFT_WRIST to
                point(
                    centerX-shoulderHalf-arm-forearm,
                    shoulderY+arm*.40+phaseOffset*1.5,
                ),
            PoseLandmarkId.RIGHT_WRIST to
                point(
                    centerX+shoulderHalf+arm+forearm,
                    shoulderY+arm*.40+phaseOffset*1.5,
                ),
            PoseLandmarkId.LEFT_KNEE to
                point(centerX-hipHalf,hipY+thigh),
            PoseLandmarkId.RIGHT_KNEE to
                point(centerX+hipHalf,hipY+thigh),
            PoseLandmarkId.LEFT_ANKLE to
                point(centerX-hipHalf,hipY+thigh+shin),
            PoseLandmarkId.RIGHT_ANKLE to
                point(centerX+hipHalf,hipY+thigh+shin),
        )

        return PoseSubjectCandidate(
            candidateIndex=index,
            normalizedLandmarks=points.mapValues { (id,position) ->
                PoseLandmarkObservation(
                    landmarkId=id,
                    position=position,
                    visibility=.95,
                    presence=.95,
                )
            },
        )
    }

    private fun loadFixture(file:File):Fixture {
        val lines=file.readLines()
        val metadata=lines
            .takeWhile { it.startsWith("#") }
            .associate { line ->
                val parts=line
                    .removePrefix("# ")
                    .split('=',limit=2)
                parts[0] to parts.getOrElse(1){""}
            }

        val cases=lines
            .dropWhile { it.startsWith("#") }
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val p=line.split('\t')
                require(p.size==13) {
                    "Malformed harness identity row: $line"
                }

                fun optionalInt(index:Int)=
                    p[index].takeIf { it.isNotEmpty() }?.toInt()

                Contract(
                    key=p[0],
                    scenario=p[1],
                    variantSeed=p[2].toInt(),
                    frameCount=p[3].toInt(),
                    mayPause=p[4].toBooleanStrict(),
                    occlusionStart=optionalInt(5),
                    occlusionEnd=optionalInt(6),
                    ambiguousStart=optionalInt(7),
                    ambiguousEnd=optionalInt(8),
                    lostStart=optionalInt(9),
                    lostEnd=optionalInt(10),
                    maxReacquire=optionalInt(11),
                    reidTrack=p[12].ifBlank { null },
                )
            }

        return Fixture(
            harnessCommit=requireNotNull(metadata["harness_commit"]),
            cases=cases,
        )
    }

    private data class Fixture(
        val harnessCommit:String,
        val cases:List<Contract>,
    )

    private data class Contract(
        val key:String,
        val scenario:String,
        val variantSeed:Int,
        val frameCount:Int,
        val mayPause:Boolean,
        val occlusionStart:Int?,
        val occlusionEnd:Int?,
        val ambiguousStart:Int?,
        val ambiguousEnd:Int?,
        val lostStart:Int?,
        val lostEnd:Int?,
        val maxReacquire:Int?,
        val reidTrack:String?,
    )

    private data class Step(
        val timestampUs:Long,
        val people:List<PoseSubjectCandidate>,
        val targetCandidateIndex:Int?,
    )

    companion object {
        private const val FIXTURE_ENV=
            "GYM_BUDDY_HARNESS_IDENTITY_FIXTURE"
        private const val EXPECTED_HARNESS_COMMIT=
            "d8a6e9569f424c4d97ddde5d1c41df93201f163d"
    }
}
