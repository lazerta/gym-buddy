package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.MovementEngineOutput
import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepInvalidReason
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.FormComparison
import com.gymbuddy.domain.profile.MetricAggregation
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class InclineDumbbellPressT19Test {
    private val bundle=InitialExerciseProfiles.inclineDumbbellPress
    private val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)

    @Test
    fun profileDeclaresPressSpecificRomTimingElbowPathAndCameraContract(){
        val profile=bundle.profile
        assertEquals(3,profile.profileVersion)
        assertEquals(3,profile.signalProfile.profileVersion)
        assertEquals(3,profile.metricProfile.profileVersion)
        assertEquals(3,profile.formRuleSet.profileVersion)
        assertEquals(ViewClass.SIDE_OBLIQUE,profile.cameraProfile.preferredViewClass)
        assertEquals(setOf(ViewClass.SIDE_OBLIQUE,ViewClass.SIDE),profile.cameraProfile.allowedViewClasses)
        assertTrue(profile.cameraProfile.requiredLandmarks.any{it.landmarkId=="left_wrist"})
        assertTrue(profile.cameraProfile.requiredLandmarks.any{it.landmarkId=="right_wrist"})
        assertTrue(CameraGuidanceAction.CAMERA_READY in profile.cameraProfile.guidanceActions)

        assertEquals(
            setOf("left_progress","right_progress","left_elbow_path_angle","right_elbow_path_angle"),
            profile.signalProfile.definitions.map{it.signalId}.toSet(),
        )
        assertEquals(
            setOf("left_rom","right_rom","bilateral_asymmetry","bilateral_timing_ms","press_elbow_path_flare_deg"),
            profile.metricProfile.metrics.map{it.metricId}.toSet(),
        )
        assertEquals(
            MetricAggregation.CROSSING_TIME_DIFFERENCE,
            profile.metricProfile.metrics.single{it.metricId=="bilateral_timing_ms"}.aggregation,
        )
        val elbowRule=profile.formRuleSet.rules.single{it.ruleId=="press_elbow_path_flare"}
        assertEquals(FormComparison.MAX_VALUE,elbowRule.comparison)
        assertEquals(80.0,elbowRule.threshold!!,0.0)
    }

    @Test
    fun pickupAndPositioningMotionCannotBecomeARepBeforeStablePressStart(){
        val engine=MovementInterpretationEngine(config)
        val setupOutputs=listOf(
            engine.process(0,pose(120.0,120.0)),
            engine.process(150_000,pose(95.0,95.0)),
            engine.process(300_000,pose(125.0,125.0)),
            engine.process(450_000,pose(90.0,90.0)),
        )
        assertTrue(setupOutputs.flatMap{it.repEvents}.isEmpty())
        assertTrue(setupOutputs.flatMap{it.repEvidence}.isEmpty())

        engine.process(700_000,pose(160.0,160.0))
        engine.process(820_000,pose(160.0,160.0))
        val completed=completeRepFromArmedStart(engine,1_000_000,60.0)
        assertEquals(1,completed.repEvidence.size)
        assertEquals(1,completed.repEvidence.single().ordinal)
    }

    @Test
    fun completedPressCapturesBilateralRomAndTiming(){
        val engine=MovementInterpretationEngine(config)
        engine.process(0,pose(160.0,160.0))
        engine.process(120_000,pose(160.0,160.0))
        engine.process(400_000,pose(100.0,115.0))
        engine.process(700_000,pose(70.0,100.0))
        engine.process(900_000,pose(90.0,70.0))
        engine.process(1_200_000,pose(115.0,115.0))
        val output=engine.process(1_500_000,pose(160.0,160.0))

        val rep=output.repEvidence.single()
        val leftRom=rep.metrics.getValue("left_rom").value as EvidenceValue.Known
        val rightRom=rep.metrics.getValue("right_rom").value as EvidenceValue.Known
        val timing=rep.metrics.getValue("bilateral_timing_ms").value as EvidenceValue.Known
        val elbowPath=rep.metrics.getValue("press_elbow_path_flare_deg").value as EvidenceValue.Known

        assertTrue(leftRom.value>.95)
        assertTrue(rightRom.value>.95)
        assertEquals(200.0,timing.value,1e-9)
        assertEquals(60.0,elbowPath.value,1e-9)
        assertEquals(
            FormObservationState.OK,
            output.formObservations.single{it.ruleId=="press_elbow_path_flare"}.state,
        )
    }

    @Test
    fun isolatedElbowFlareDoesNotCueButRepeatedFlareDoes(){
        val engine=MovementInterpretationEngine(config)
        val first=completeRep(engine,0,88.0)
        val firstElbow=first.formObservations.single{it.ruleId=="press_elbow_path_flare"}
        assertEquals(FormObservationState.DEVIATION,firstElbow.state)
        assertTrue(first.cueEvents.none{it.ruleId=="press_elbow_path_flare"})

        val second=completeRep(engine,2_000_000,88.0)
        assertEquals(
            listOf("press_elbow_path_flare"),
            second.cueEvents.map{it.ruleId},
        )
    }

    @Test
    fun wristOcclusionInvalidatesActiveAttemptAndRecoveryStartsFresh(){
        val engine=MovementInterpretationEngine(config)
        engine.process(0,pose(160.0,160.0))
        engine.process(120_000,pose(160.0,160.0))
        engine.process(400_000,pose(115.0,115.0))

        val occluded=engine.process(
            600_000,
            pose(90.0,90.0,missing=setOf(PoseLandmarkId.RIGHT_WRIST)),
        )
        val invalid=occluded.repEvents.singleOrNull{it.kind==RepCompletionKind.INVALID_ATTEMPT}
        assertNotNull(invalid)
        assertEquals(RepInvalidReason.INTERRUPTED,invalid!!.invalidReason)
        assertTrue(occluded.repEvidence.isEmpty())

        val recovered=completeRep(engine,1_000_000,60.0)
        assertEquals(1,recovered.repEvidence.size)
        assertEquals(1,recovered.repEvidence.single().ordinal)
    }

    private fun completeRep(
        engine:MovementInterpretationEngine,
        base:Long,
        elbowPathAngle:Double,
    ):MovementEngineOutput{
        engine.process(base,pose(160.0,160.0,elbowPathAngle))
        engine.process(base+120_000,pose(160.0,160.0,elbowPathAngle))
        return completeRepFromArmedStart(engine,base+300_000,elbowPathAngle)
    }

    private fun completeRepFromArmedStart(
        engine:MovementInterpretationEngine,
        base:Long,
        elbowPathAngle:Double,
    ):MovementEngineOutput{
        engine.process(base,pose(115.0,115.0,elbowPathAngle))
        engine.process(base+300_000,pose(70.0,70.0,elbowPathAngle))
        engine.process(base+600_000,pose(115.0,115.0,elbowPathAngle))
        return engine.process(base+900_000,pose(160.0,160.0,elbowPathAngle))
    }

    private fun pose(
        leftElbowAngle:Double,
        rightElbowAngle:Double,
        elbowPathAngle:Double=60.0,
        missing:Set<PoseLandmarkId> = emptySet(),
    ):NormalizedPose{
        val points=mutableMapOf<PoseLandmarkId,BodyLocalLandmark>()
        val leftShoulder=Point(-.40,-.50)
        val rightShoulder=Point(.40,-.50)
        val leftHip=Point(-.40,.50)
        val rightHip=Point(.40,.50)
        val leftElbow=elbowFromShoulder(leftShoulder,elbowPathAngle,-1.0)
        val rightElbow=elbowFromShoulder(rightShoulder,elbowPathAngle,1.0)
        val leftWrist=wristFromElbow(leftShoulder,leftElbow,leftElbowAngle,1.0)
        val rightWrist=wristFromElbow(rightShoulder,rightElbow,rightElbowAngle,-1.0)

        fun add(id:PoseLandmarkId,p:Point){
            if(id !in missing)points[id]=BodyLocalLandmark(id,p.x,p.y,0.0,.95,.95)
        }
        add(PoseLandmarkId.LEFT_SHOULDER,leftShoulder)
        add(PoseLandmarkId.RIGHT_SHOULDER,rightShoulder)
        add(PoseLandmarkId.LEFT_HIP,leftHip)
        add(PoseLandmarkId.RIGHT_HIP,rightHip)
        add(PoseLandmarkId.LEFT_ELBOW,leftElbow)
        add(PoseLandmarkId.RIGHT_ELBOW,rightElbow)
        add(PoseLandmarkId.LEFT_WRIST,leftWrist)
        add(PoseLandmarkId.RIGHT_WRIST,rightWrist)
        return NormalizedPose(0,1.0,points)
    }

    private fun elbowFromShoulder(shoulder:Point,angleDegrees:Double,side:Double):Point{
        val radians=Math.toRadians(angleDegrees)
        val length=.24
        return Point(
            shoulder.x+side*sin(radians)*length,
            shoulder.y+cos(radians)*length,
        )
    }

    private fun wristFromElbow(
        shoulder:Point,
        elbow:Point,
        elbowAngleDegrees:Double,
        rotationSign:Double,
    ):Point{
        val vx=shoulder.x-elbow.x
        val vy=shoulder.y-elbow.y
        val norm=sqrt(vx*vx+vy*vy)
        val ux=vx/norm
        val uy=vy/norm
        val radians=Math.toRadians(elbowAngleDegrees*rotationSign)
        val rx=ux*cos(radians)-uy*sin(radians)
        val ry=ux*sin(radians)+uy*cos(radians)
        val length=.22
        return Point(elbow.x+rx*length,elbow.y+ry*length)
    }

    private data class Point(val x:Double,val y:Double)
}
