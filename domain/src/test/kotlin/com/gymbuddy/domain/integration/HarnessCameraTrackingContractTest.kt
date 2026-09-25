package com.gymbuddy.domain.integration

import com.gymbuddy.domain.camera.CameraGuidanceEngine
import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.PrimarySubjectLockResult
import com.gymbuddy.domain.tracking.PrimarySubjectLockState
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.domain.tracking.TrackingQualityGate
import com.gymbuddy.domain.tracking.TrackingQualityReason
import com.gymbuddy.domain.tracking.TrackingQualityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

class HarnessCameraTrackingContractTest {
    @Test
    fun harnessCameraAndTrackingContractsMatchProductionGates() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by the read-only harness workflow",
            !fixturePath.isNullOrBlank(),
        )

        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_HARNESS_COMMIT,fixture.harnessCommit)
        assertEquals(33,fixture.cases.size)

        fixture.cases.forEach { contract ->
            val profile=requireNotNull(
                InitialExerciseProfiles.resolveByExternalId(
                    contract.exerciseId
                )
            ).profile.cameraProfile

            val target=candidate(
                profile=profile,
                fill=contract.frameFill,
                visibleRequiredFraction=contract.visibleRequiredFraction,
                trackingQuality=contract.trackingQuality,
                candidateIndex=0,
            )
            val candidates=buildList {
                add(target)
                repeat((contract.detectedPeople-1).coerceAtLeast(0)) { index ->
                    add(
                        candidate(
                            profile=profile,
                            fill=.35,
                            visibleRequiredFraction=1.0,
                            trackingQuality=.95,
                            candidateIndex=index+1,
                        )
                    )
                }
            }
            val lock=PrimarySubjectLockResult(
                state=PrimarySubjectLockState.LOCKED,
                targetCandidateIndex=0,
                targetScore=.95,
                identityMargin=.50,
                candidateCount=candidates.size,
            )
            val view=observedView(profile,contract)
            val context=TrackingObservationContext(
                observedViewClass=view,
                cameraMotionScore=contract.cameraMotionScore,
            )

            val tracking=if(
                contract.family=="tracking_gap" ||
                contract.family=="target_exit_reenter"
            ) {
                val gate=TrackingQualityGate()
                val baseline=gate.evaluate(
                    frame(
                        timestampUs=0,
                        candidates=listOf(
                            candidate(
                                profile=profile,
                                fill=contract.frameFill,
                                visibleRequiredFraction=1.0,
                                trackingQuality=.95,
                                candidateIndex=0,
                            )
                        ),
                    ),
                    lock.copy(candidateCount=1),
                    profile,
                    TrackingObservationContext(
                        observedViewClass=profile.preferredViewClass,
                        cameraMotionScore=0.0,
                    ),
                )
                assertTrue(
                    "${contract.key}: baseline must be observable before a gap",
                    baseline.allowsBiomechanics,
                )

                gate.evaluate(
                    frame(
                        timestampUs=contract.trackingGapMs*1_000L,
                        candidates=candidates,
                    ),
                    lock,
                    profile,
                    context,
                )
            } else {
                TrackingQualityGate().evaluate(
                    frame(
                        timestampUs=1_000L,
                        candidates=candidates,
                    ),
                    lock,
                    profile,
                    context,
                )
            }

            val expectedTracking=expectedTracking(contract)
            assertEquals(
                "${contract.key} from harness ${fixture.harnessCommit}",
                expectedTracking.first,
                tracking.state,
            )
            assertEquals(
                "${contract.key} from harness ${fixture.harnessCommit}",
                expectedTracking.second,
                tracking.reason,
            )

            expectedGuidance(contract)?.let { expected ->
                val guidance=CameraGuidanceEngine(
                    readyDwellFrames=1
                ).evaluate(
                    frame(
                        timestampUs=2_000L,
                        candidates=candidates,
                    ),
                    lock,
                    profile,
                    context,
                )
                assertEquals(
                    "${contract.key} camera guidance",
                    expected,
                    guidance,
                )
            }
        }
    }

    private fun expectedTracking(
        contract:Contract,
    ):Pair<TrackingQualityState,TrackingQualityReason> =
        when(contract.oracleReason) {
            "clean" ->
                TrackingQualityState.OBSERVABLE to
                    TrackingQualityReason.OK

            "camera_guidance" -> when(contract.family) {
                "wrong_view" ->
                    TrackingQualityState.PAUSED to
                        TrackingQualityReason.WRONG_VIEW
                "too_close" ->
                    TrackingQualityState.PAUSED to
                        TrackingQualityReason.FRAMING_INVALID
                else -> error(
                    "Unsupported camera-guidance harness family: " +
                        contract.family
                )
            }

            "camera_changed" ->
                TrackingQualityState.PAUSED to
                    TrackingQualityReason.CAMERA_DISTURBANCE

            "target_observation_low" ->
                TrackingQualityState.PAUSED to
                    TrackingQualityReason.LOW_LANDMARK_CONFIDENCE

            "target_temporarily_lost" ->
                TrackingQualityState.PAUSED to
                    TrackingQualityReason.CONTINUITY_GAP

            else -> error(
                "Unsupported harness oracle reason: " +
                    contract.oracleReason
            )
        }

    private fun expectedGuidance(
        contract:Contract,
    ):CameraGuidanceAction? =
        when(contract.oracleReason) {
            "clean" -> CameraGuidanceAction.CAMERA_READY
            "camera_guidance" -> when(contract.family) {
                "wrong_view" -> CameraGuidanceAction.ADJUST_ANGLE
                "too_close" -> CameraGuidanceAction.MOVE_FARTHER
                else -> null
            }
            "target_observation_low" ->
                CameraGuidanceAction.CANNOT_ASSESS
            else -> null
        }

    private fun observedView(
        profile:CameraProfile,
        contract:Contract,
    ):ViewClass =
        if(contract.family=="wrong_view") {
            ViewClass.entries.first {
                it !in profile.allowedViewClasses
            }
        } else {
            profile.preferredViewClass
        }

    private fun candidate(
        profile:CameraProfile,
        fill:Double,
        visibleRequiredFraction:Double,
        trackingQuality:Double,
        candidateIndex:Int,
    ):PoseSubjectCandidate {
        val span=fill.coerceIn(0.0,1.0)
        val half=span/2.0
        val left=.5-half
        val right=.5+half
        val top=.5-half
        val bottom=.5+half
        val coordinates=listOf(
            left to top,
            right to top,
            left to bottom,
            right to bottom,
            left to .5,
            right to .5,
            .5 to top,
            .5 to bottom,
        )

        val required=profile.requiredLandmarks
            .sortedBy { it.landmarkId }
        val reliableTarget=floor(
            visibleRequiredFraction*required.size
        ).toInt().coerceIn(0,required.size)

        val landmarks=required.mapIndexed { index, requirement ->
            val id=PoseLandmarkId.entries.firstOrNull {
                it.wireName==requirement.landmarkId
            } ?: error(
                "Unsupported required landmark: " +
                    requirement.landmarkId
            )
            val point=coordinates[index%coordinates.size]
            val confidence=if(index<reliableTarget) {
                trackingQuality.coerceIn(0.0,1.0)
            } else {
                .20
            }
            id to PoseLandmarkObservation(
                id,
                PoseCoordinate3d(
                    point.first,
                    point.second,
                    0.0,
                ),
                confidence,
                confidence,
            )
        }.toMap()

        return PoseSubjectCandidate(
            candidateIndex,
            landmarks,
        )
    }

    private fun frame(
        timestampUs:Long,
        candidates:List<PoseSubjectCandidate>,
    )=PoseFrame(
        frameId=timestampUs,
        timestampUs=timestampUs,
        width=640,
        height=480,
        source=PoseFrameSource.VIDEO,
        candidates=candidates,
    )

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
                require(p.size==15) {
                    "Malformed harness camera/tracking row: $line"
                }
                Contract(
                    key=p[0],
                    exerciseId=p[1],
                    family=p[3],
                    frameFill=p[5].toDouble(),
                    visibleRequiredFraction=p[6].toDouble(),
                    trackingQuality=p[7].toDouble(),
                    cameraMotionScore=p[8].toDouble(),
                    trackingGapMs=p[9].toLong(),
                    detectedPeople=p[10].toInt(),
                    oracleReady=p[11].toBooleanStrict(),
                    oraclePause=p[12].toBooleanStrict(),
                    oracleResetOnce=p[13].toBooleanStrict(),
                    oracleReason=p[14],
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
        val exerciseId:String,
        val family:String,
        val frameFill:Double,
        val visibleRequiredFraction:Double,
        val trackingQuality:Double,
        val cameraMotionScore:Double,
        val trackingGapMs:Long,
        val detectedPeople:Int,
        val oracleReady:Boolean,
        val oraclePause:Boolean,
        val oracleResetOnce:Boolean,
        val oracleReason:String,
    )

    companion object {
        private const val FIXTURE_ENV=
            "GYM_BUDDY_HARNESS_CAMERA_TRACKING_FIXTURE"
        private const val EXPECTED_HARNESS_COMMIT=
            "d8a6e9569f424c4d97ddde5d1c41df93201f163d"
    }
}
