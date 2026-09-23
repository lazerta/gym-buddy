package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.MovementEngineOutput
import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepInvalidReason
import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.MetricAggregation
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
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SmithMachineSquatT20Test {
    private val bundle=InitialExerciseProfiles.smithMachineSquat
    private val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)

    @Test
    fun profileDeclaresSideViewAndBilateralRomEvidence(){
        val profile=bundle.profile
        assertEquals(3,profile.profileVersion)
        assertEquals(ViewClass.SIDE,profile.cameraProfile.preferredViewClass)
        assertEquals(setOf(ViewClass.SIDE,ViewClass.SIDE_OBLIQUE),profile.cameraProfile.allowedViewClasses)
        assertEquals(3,profile.metricProfile.profileVersion)
        assertEquals(
            setOf("left_rom","right_rom","bilateral_asymmetry","bilateral_timing_ms"),
            profile.metricProfile.metrics.map{it.metricId}.toSet(),
        )
        assertEquals(
            MetricAggregation.RANGE,
            profile.metricProfile.metrics.single{it.metricId=="right_rom"}.aggregation,
        )
        assertTrue(profile.cameraProfile.requiredLandmarks.any{it.landmarkId=="left_ankle"})
        assertTrue(profile.cameraProfile.requiredLandmarks.any{it.landmarkId=="right_ankle"})
    }

    @Test
    fun unrackAndStanceAdjustmentCannotCountBeforeStableStart(){
        val engine=MovementInterpretationEngine(config)
        listOf(135.0,115.0,145.0,125.0,140.0).forEachIndexed{index,angle->
            val output=engine.process(index*120_000L,pose(angle))
            assertTrue(output.repEvents.isEmpty())
            assertTrue(output.repEvidence.isEmpty())
        }

        val completed=completeRep(engine,800_000,90.0)
        assertEquals(1,completed.repEvidence.size)
        assertEquals(1,completed.repEvidence.single().ordinal)
    }

    @Test
    fun partialDescentIsRejectedAndFullCycleCountsExactlyOnce(){
        val engine=MovementInterpretationEngine(config)
        engine.process(0,pose(170.0))
        engine.process(120_000,pose(170.0))
        engine.process(350_000,pose(130.0))
        val partial=engine.process(600_000,pose(145.0))
        assertEquals(1,partial.repEvents.size)
        assertEquals(RepCompletionKind.INVALID_ATTEMPT,partial.repEvents.single().kind)
        assertEquals(RepInvalidReason.PARTIAL_RANGE,partial.repEvents.single().invalidReason)
        assertTrue(partial.repEvidence.isEmpty())

        val completed=completeRep(engine,1_000_000,90.0)
        assertEquals(1,completed.repEvidence.size)
        assertEquals(1,completed.repEvidence.single().ordinal)
    }

    @Test
    fun romEvidenceShowsShorteningTrendAcrossValidReps(){
        val engine=MovementInterpretationEngine(config)
        val full=completeRep(engine,0,90.0).repEvidence.single()
        val shortened=completeRep(engine,2_000_000,105.0).repEvidence.single()

        val fullLeft=(full.metrics.getValue("left_rom").value as EvidenceValue.Known).value
        val fullRight=(full.metrics.getValue("right_rom").value as EvidenceValue.Known).value
        val shortLeft=(shortened.metrics.getValue("left_rom").value as EvidenceValue.Known).value
        val shortRight=(shortened.metrics.getValue("right_rom").value as EvidenceValue.Known).value

        assertTrue(fullLeft>shortLeft)
        assertTrue(fullRight>shortRight)
        assertEquals(1.0,fullLeft,1e-9)
        assertEquals(.8125,shortLeft,1e-9)
    }

    @Test
    fun railOcclusionPausesTrackingInsteadOfProducingBiomechanics(){
        val gate=TrackingQualityGate()
        val lock=locked()
        val valid=gate.evaluate(
            frame(0,trackingCandidate()),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.SIDE),
        )
        assertTrue(valid.allowsBiomechanics)

        val occluded=gate.evaluate(
            frame(100_000,trackingCandidate(missing=setOf(PoseLandmarkId.RIGHT_ANKLE))),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.SIDE),
        )
        assertEquals(TrackingQualityState.PAUSED,occluded.state)
        assertEquals(TrackingQualityReason.REQUIRED_LANDMARKS_MISSING,occluded.reason)
        assertTrue(!occluded.allowsBiomechanics)
    }

    @Test
    fun cameraBumpPausesAndCleanFrameReacquiresWithoutWeakeningSideView(){
        val gate=TrackingQualityGate()
        val lock=locked()
        val first=gate.evaluate(
            frame(0,trackingCandidate()),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.SIDE,cameraMotionScore=.0),
        )
        assertEquals(TrackingQualityState.OBSERVABLE,first.state)

        val bumped=gate.evaluate(
            frame(100_000,trackingCandidate()),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.SIDE,cameraMotionScore=.80),
        )
        assertEquals(TrackingQualityState.PAUSED,bumped.state)
        assertEquals(TrackingQualityReason.CAMERA_DISTURBANCE,bumped.reason)

        val recovered=gate.evaluate(
            frame(200_000,trackingCandidate()),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.SIDE,cameraMotionScore=.0),
        )
        assertEquals(TrackingQualityState.OBSERVABLE,recovered.state)
        assertEquals(TrackingQualityReason.OK,recovered.reason)
    }

    private fun completeRep(
        engine:MovementInterpretationEngine,
        base:Long,
        bottomAngle:Double,
    ):MovementEngineOutput{
        engine.process(base,pose(170.0))
        engine.process(base+120_000,pose(170.0))
        engine.process(base+350_000,pose(130.0))
        engine.process(base+650_000,pose(bottomAngle))
        engine.process(base+950_000,pose(130.0))
        return engine.process(base+1_250_000,pose(170.0))
    }

    private fun pose(kneeAngle:Double):NormalizedPose{
        val leftHip=Point(-.25,-.45)
        val rightHip=Point(.25,-.45)
        val leftKnee=Point(-.25,.05)
        val rightKnee=Point(.25,.05)
        val leftAnkle=ankle(leftHip,leftKnee,kneeAngle,1.0)
        val rightAnkle=ankle(rightHip,rightKnee,kneeAngle,-1.0)
        val map=mapOf(
            PoseLandmarkId.LEFT_HIP to local(PoseLandmarkId.LEFT_HIP,leftHip),
            PoseLandmarkId.RIGHT_HIP to local(PoseLandmarkId.RIGHT_HIP,rightHip),
            PoseLandmarkId.LEFT_KNEE to local(PoseLandmarkId.LEFT_KNEE,leftKnee),
            PoseLandmarkId.RIGHT_KNEE to local(PoseLandmarkId.RIGHT_KNEE,rightKnee),
            PoseLandmarkId.LEFT_ANKLE to local(PoseLandmarkId.LEFT_ANKLE,leftAnkle),
            PoseLandmarkId.RIGHT_ANKLE to local(PoseLandmarkId.RIGHT_ANKLE,rightAnkle),
        )
        return NormalizedPose(0,1.0,map)
    }

    private fun ankle(hip:Point,knee:Point,angleDegrees:Double,rotationSign:Double):Point{
        val vx=hip.x-knee.x
        val vy=hip.y-knee.y
        val norm=sqrt(vx*vx+vy*vy)
        val ux=vx/norm
        val uy=vy/norm
        val radians=Math.toRadians(angleDegrees*rotationSign)
        val rx=ux*cos(radians)-uy*sin(radians)
        val ry=ux*sin(radians)+uy*cos(radians)
        return Point(knee.x+rx*.45,knee.y+ry*.45)
    }

    private fun local(id:PoseLandmarkId,p:Point)=
        BodyLocalLandmark(id,p.x,p.y,0.0,.95,.95)

    private fun locked()=PrimarySubjectLockResult(
        state=PrimarySubjectLockState.LOCKED,
        targetCandidateIndex=0,
        targetScore=.95,
        identityMargin=.95,
        candidateCount=1,
    )

    private fun frame(ts:Long,candidate:PoseSubjectCandidate)=
        PoseFrame(ts/100_000,ts,640,480,PoseFrameSource.VIDEO,listOf(candidate))

    private fun trackingCandidate(
        missing:Set<PoseLandmarkId> = emptySet(),
    ):PoseSubjectCandidate{
        val positions=mapOf(
            PoseLandmarkId.LEFT_SHOULDER to Point(.48,.25),
            PoseLandmarkId.RIGHT_SHOULDER to Point(.52,.25),
            PoseLandmarkId.LEFT_HIP to Point(.49,.50),
            PoseLandmarkId.RIGHT_HIP to Point(.51,.50),
            PoseLandmarkId.LEFT_KNEE to Point(.49,.68),
            PoseLandmarkId.RIGHT_KNEE to Point(.51,.68),
            PoseLandmarkId.LEFT_ANKLE to Point(.49,.86),
            PoseLandmarkId.RIGHT_ANKLE to Point(.51,.86),
        )
        val landmarks=positions.filterKeys{it !in missing}.mapValues{(id,p)->
            PoseLandmarkObservation(id,PoseCoordinate3d(p.x,p.y,0.0),.95,.95)
        }
        return PoseSubjectCandidate(0,landmarks)
    }

    private data class Point(val x:Double,val y:Double)
}
