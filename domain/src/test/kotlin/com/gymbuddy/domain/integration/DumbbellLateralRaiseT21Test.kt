package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.MovementEngineOutput
import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.FormComparison
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

class DumbbellLateralRaiseT21Test {
    private val bundle=InitialExerciseProfiles.dumbbellLateralRaise
    private val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)

    @Test
    fun profileDeclaresFrontViewArmElevationAndBilateralTiming(){
        val profile=bundle.profile
        assertEquals(3,profile.profileVersion)
        assertEquals(3,profile.signalProfile.profileVersion)
        assertEquals(3,profile.metricProfile.profileVersion)
        assertEquals(3,profile.formRuleSet.profileVersion)
        assertEquals(ViewClass.FRONT,profile.cameraProfile.preferredViewClass)
        assertEquals(setOf(ViewClass.FRONT,ViewClass.FRONT_OBLIQUE),profile.cameraProfile.allowedViewClasses)
        assertEquals(
            setOf("left_progress","right_progress","left_arm_elevation_deg","right_arm_elevation_deg"),
            profile.signalProfile.definitions.map{it.signalId}.toSet(),
        )
        assertEquals(
            setOf("left_rom","right_rom","bilateral_asymmetry","bilateral_timing_ms","arm_elevation_peak_deg"),
            profile.metricProfile.metrics.map{it.metricId}.toSet(),
        )
        assertEquals(
            MetricAggregation.CROSSING_TIME_DIFFERENCE,
            profile.metricProfile.metrics.single{it.metricId=="bilateral_timing_ms"}.aggregation,
        )
        val rule=profile.formRuleSet.rules.single{it.ruleId=="lateral_raise_over_elevation"}
        assertEquals(FormComparison.MAX_VALUE,rule.comparison)
        assertEquals(105.0,rule.threshold!!,0.0)
    }

    @Test
    fun cleanRaiseProducesExplicitArmElevationMetric(){
        val output=completeRep(MovementInterpretationEngine(config),0,90.0)
        val rep=output.repEvidence.single()
        val peak=rep.metrics.getValue("arm_elevation_peak_deg").value as EvidenceValue.Known
        assertEquals(90.0,peak.value,1e-9)
        assertEquals(
            FormObservationState.OK,
            output.formObservations.single{it.ruleId=="lateral_raise_over_elevation"}.state,
        )
    }

    @Test
    fun bilateralTimingMetricCapturesDelayedArm(){
        val engine=MovementInterpretationEngine(config)
        engine.process(0,pose(20.0,20.0))
        engine.process(120_000,pose(20.0,20.0))
        engine.process(350_000,pose(55.0,55.0))
        engine.process(650_000,pose(85.0,70.0))
        engine.process(850_000,pose(90.0,85.0))
        engine.process(1_100_000,pose(55.0,55.0))
        val completed=engine.process(1_400_000,pose(20.0,20.0))
        val timing=(completed.repEvidence.single().metrics.getValue("bilateral_timing_ms").value as EvidenceValue.Known).value
        assertEquals(200.0,timing,1e-9)
    }

    @Test
    fun isolatedOverElevationDoesNotCueButRepeatedDeviationDoes(){
        val engine=MovementInterpretationEngine(config)
        val first=completeRep(engine,0,112.0)
        assertEquals(
            FormObservationState.DEVIATION,
            first.formObservations.single{it.ruleId=="lateral_raise_over_elevation"}.state,
        )
        assertTrue(first.cueEvents.none{it.ruleId=="lateral_raise_over_elevation"})

        val second=completeRep(engine,2_000_000,112.0)
        assertEquals(
            listOf("lateral_raise_over_elevation"),
            second.cueEvents.map{it.ruleId},
        )
    }

    @Test
    fun pickupAndStanceAdjustmentDoNotCountBeforeStableStart(){
        val engine=MovementInterpretationEngine(config)
        listOf(55.0,80.0,45.0,70.0,50.0).forEachIndexed{index,angle->
            val output=engine.process(index*120_000L,pose(angle,angle))
            assertTrue(output.repEvents.isEmpty())
            assertTrue(output.repEvidence.isEmpty())
        }
        val completed=completeRep(engine,800_000,90.0)
        assertEquals(1,completed.repEvidence.size)
        assertEquals(1,completed.repEvidence.single().ordinal)
    }

    @Test
    fun missingTorsoLandmarkForcesObservabilityPause(){
        val gate=TrackingQualityGate()
        val lock=locked()
        val good=gate.evaluate(
            frame(0,trackingCandidate()),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.FRONT),
        )
        assertTrue(good.allowsBiomechanics)

        val missingHip=gate.evaluate(
            frame(100_000,trackingCandidate(missing=setOf(PoseLandmarkId.LEFT_HIP))),
            lock,
            bundle.profile.cameraProfile,
            TrackingObservationContext(observedViewClass=ViewClass.FRONT),
        )
        assertEquals(TrackingQualityState.PAUSED,missingHip.state)
        assertEquals(TrackingQualityReason.REQUIRED_LANDMARKS_MISSING,missingHip.reason)
        assertTrue(!missingHip.allowsBiomechanics)
    }

    private fun completeRep(
        engine:MovementInterpretationEngine,
        base:Long,
        topAngle:Double,
    ):MovementEngineOutput{
        engine.process(base,pose(20.0,20.0))
        engine.process(base+120_000,pose(20.0,20.0))
        engine.process(base+350_000,pose(55.0,55.0))
        engine.process(base+650_000,pose(topAngle,topAngle))
        engine.process(base+950_000,pose(55.0,55.0))
        return engine.process(base+1_250_000,pose(20.0,20.0))
    }

    private fun pose(leftAngle:Double,rightAngle:Double):NormalizedPose{
        val leftHip=Point(-.25,.45)
        val rightHip=Point(.25,.45)
        val leftShoulder=Point(-.25,-.20)
        val rightShoulder=Point(.25,-.20)
        val leftElbow=elbow(leftHip,leftShoulder,leftAngle,-1.0)
        val rightElbow=elbow(rightHip,rightShoulder,rightAngle,1.0)
        val map=mapOf(
            PoseLandmarkId.LEFT_HIP to local(PoseLandmarkId.LEFT_HIP,leftHip),
            PoseLandmarkId.RIGHT_HIP to local(PoseLandmarkId.RIGHT_HIP,rightHip),
            PoseLandmarkId.LEFT_SHOULDER to local(PoseLandmarkId.LEFT_SHOULDER,leftShoulder),
            PoseLandmarkId.RIGHT_SHOULDER to local(PoseLandmarkId.RIGHT_SHOULDER,rightShoulder),
            PoseLandmarkId.LEFT_ELBOW to local(PoseLandmarkId.LEFT_ELBOW,leftElbow),
            PoseLandmarkId.RIGHT_ELBOW to local(PoseLandmarkId.RIGHT_ELBOW,rightElbow),
        )
        return NormalizedPose(0,1.0,map)
    }

    private fun elbow(hip:Point,shoulder:Point,angleDegrees:Double,side:Double):Point{
        val baseX=hip.x-shoulder.x
        val baseY=hip.y-shoulder.y
        val base=Math.atan2(baseY,baseX)
        val angle=base+Math.toRadians(angleDegrees*side)
        return Point(shoulder.x+cos(angle)*.35,shoulder.y+sin(angle)*.35)
    }

    private fun local(id:PoseLandmarkId,p:Point)=BodyLocalLandmark(id,p.x,p.y,0.0,.95,.95)

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
            PoseLandmarkId.LEFT_SHOULDER to Point(.36,.30),
            PoseLandmarkId.RIGHT_SHOULDER to Point(.64,.30),
            PoseLandmarkId.LEFT_ELBOW to Point(.25,.42),
            PoseLandmarkId.RIGHT_ELBOW to Point(.75,.42),
            PoseLandmarkId.LEFT_WRIST to Point(.18,.50),
            PoseLandmarkId.RIGHT_WRIST to Point(.82,.50),
            PoseLandmarkId.LEFT_HIP to Point(.42,.65),
            PoseLandmarkId.RIGHT_HIP to Point(.58,.65),
        )
        return PoseSubjectCandidate(
            0,
            positions.filterKeys{it !in missing}.mapValues{(id,p)->
                PoseLandmarkObservation(id,PoseCoordinate3d(p.x,p.y,0.0),.95,.95)
            },
        )
    }

    private data class Point(val x:Double,val y:Double)
}
