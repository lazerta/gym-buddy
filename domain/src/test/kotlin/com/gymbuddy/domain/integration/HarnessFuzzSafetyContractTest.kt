package com.gymbuddy.domain.integration

import com.gymbuddy.domain.camera.CameraGuidanceEngine
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

class HarnessFuzzSafetyContractTest {
    @Test
    fun compositeFuzzNeverAdmitsBiomechanicsAcrossHarnessForbiddenGates() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by the read-only harness workflow",
            !fixturePath.isNullOrBlank(),
        )
        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_HARNESS_COMMIT,fixture.harnessCommit)
        assertEquals(200,fixture.cases.size)

        fixture.cases.forEach { contract ->
            val profile=requireNotNull(
                InitialExerciseProfiles.resolveByExternalId(contract.exerciseId)
            ).profile.cameraProfile
            val components=contract.components
            val target=candidate(
                profile=profile,
                fill=contract.frameFill,
                visibleRequiredFraction=contract.visibleRequiredFraction,
                trackingQuality=contract.trackingQuality,
                candidateIndex=0,
                centerY=when {
                    "camera_low" in components -> .28
                    "camera_high" in components -> .72
                    else -> .5
                },
            )
            val candidates=buildList {
                add(target)
                repeat((contract.detectedPeople-1).coerceAtLeast(0)) { index ->
                    add(candidate(profile,.35,1.0,.95,index+1))
                }
            }
            val ambiguous=contract.oracleReason=="target_ambiguous"
            val lock=if(ambiguous){
                PrimarySubjectLockResult(
                    PrimarySubjectLockState.TARGET_AMBIGUOUS,
                    null,
                    .70,
                    .01,
                    candidates.size.coerceAtLeast(2),
                )
            }else{
                PrimarySubjectLockResult(
                    PrimarySubjectLockState.LOCKED,
                    0,
                    .95,
                    .50,
                    candidates.size,
                )
            }
            val view=if("wrong_view" in components){
                ViewClass.entries.first{it !in profile.allowedViewClasses}
            }else profile.preferredViewClass
            val context=TrackingObservationContext(
                observedViewClass=view,
                cameraMotionScore=contract.cameraMotionScore,
            )
            val frame=frame(1_000L,candidates)
            val guidance=CameraGuidanceEngine(1).evaluate(
                frame,lock,profile,context
            )
            val tracking=tracking(profile,contract,lock,candidates,context)

            when(contract.oracleReason){
                "camera_guidance" -> {
                    assertNotEquals(
                        "${contract.key}: invalid camera geometry became READY",
                        CameraGuidanceAction.CAMERA_READY,
                        guidance,
                    )
                }
                "camera_changed",
                "target_ambiguous",
                "target_observation_low",
                "target_temporarily_lost" -> {
                    assertFalse(
                        "${contract.key}: ${contract.oracleReason} admitted biomechanics",
                        tracking.allowsBiomechanics,
                    )
                }
            }
        }
    }

    private fun tracking(
        profile:CameraProfile,
        contract:Contract,
        lock:PrimarySubjectLockResult,
        candidates:List<PoseSubjectCandidate>,
        context:TrackingObservationContext,
    ):TrackingQualityResult {
        val gate=TrackingQualityGate()
        if(contract.oracleReason=="target_temporarily_lost" && lock.state==PrimarySubjectLockState.LOCKED){
            gate.evaluate(
                frame(
                    0L,
                    listOf(candidate(profile,.45,1.0,.95,0)),
                ),
                lock.copy(candidateCount=1),
                profile,
                TrackingObservationContext(
                    observedViewClass=profile.preferredViewClass,
                    cameraMotionScore=0.0,
                ),
            )
            return gate.evaluate(
                frame(contract.trackingGapMs*1_000L,candidates),
                lock,
                profile,
                context,
            )
        }
        return gate.evaluate(frame(1_000L,candidates),lock,profile,context)
    }

    private fun candidate(
        profile:CameraProfile,
        fill:Double,
        visibleRequiredFraction:Double,
        trackingQuality:Double,
        candidateIndex:Int,
        centerY:Double=.5,
    ):PoseSubjectCandidate {
        val span=fill.coerceIn(0.0,1.0)
        val half=span/2.0
        val left=.5-half
        val right=.5+half
        val top=centerY-half
        val bottom=centerY+half
        val coordinates=listOf(
            left to top,
            right to top,
            left to bottom,
            right to bottom,
            left to centerY,
            right to centerY,
            .5 to top,
            .5 to bottom,
        )
        val required=profile.requiredLandmarks.sortedBy{it.landmarkId}
        val reliableTarget=floor(
            visibleRequiredFraction*required.size
        ).toInt().coerceIn(0,required.size)
        val landmarks=required.mapIndexed{index,requirement->
            val id=PoseLandmarkId.entries.firstOrNull{
                it.wireName==requirement.landmarkId
            }?:error("Unsupported required landmark: ${requirement.landmarkId}")
            val point=coordinates[index%coordinates.size]
            val confidence=if(index<reliableTarget){
                trackingQuality.coerceIn(0.0,1.0)
            }else .20
            id to PoseLandmarkObservation(
                id,
                PoseCoordinate3d(point.first,point.second,0.0),
                confidence,
                confidence,
            )
        }.toMap()
        return PoseSubjectCandidate(candidateIndex,landmarks)
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
            .takeWhile{it.startsWith("#")}
            .associate{line->
                val parts=line.removePrefix("# ").split('=',limit=2)
                parts[0] to parts.getOrElse(1){""}
            }
        val cases=lines
            .dropWhile{it.startsWith("#")}
            .drop(1)
            .filter{it.isNotBlank()}
            .map{line->
                val p=line.split('\t')
                require(p.size==15){"Malformed harness fuzz row: $line"}
                Contract(
                    key=p[0],
                    exerciseId=p[1],
                    components=p[3].split(',').filter{it.isNotBlank()}.toSet(),
                    frameFill=p[5].toDouble(),
                    visibleRequiredFraction=p[6].toDouble(),
                    trackingQuality=p[7].toDouble(),
                    cameraMotionScore=p[8].toDouble(),
                    trackingGapMs=p[9].toLong(),
                    detectedPeople=p[10].toInt(),
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
        val components:Set<String>,
        val frameFill:Double,
        val visibleRequiredFraction:Double,
        val trackingQuality:Double,
        val cameraMotionScore:Double,
        val trackingGapMs:Long,
        val detectedPeople:Int,
        val oracleReason:String,
    )

    companion object {
        private const val FIXTURE_ENV="GYM_BUDDY_HARNESS_FUZZ_SAFETY_FIXTURE"
        private const val EXPECTED_HARNESS_COMMIT=
            "d8a6e9569f424c4d97ddde5d1c41df93201f163d"
    }
}
