package com.gymbuddy.domain.engine

import com.gymbuddy.domain.camera.PoseViewEstimator
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.domain.tracking.TrackingQualityReason
import org.junit.Assert.*
import org.junit.Test

class ProductionControlRegressionTest {
    @Test fun repeatedReadyGuidanceDoesNotEraseArmedState() {
        val lifecycle = com.gymbuddy.domain.lifecycle.SetLifecycleController()
        lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        lifecycle.onMovement(com.gymbuddy.domain.movement.MovementPrimitiveFrame(0, mapOf(
            "cycle" to com.gymbuddy.domain.movement.MovementPrimitiveObservation(
                "cycle", MovementPrimitive.RAISE, 0, com.gymbuddy.domain.movement.PrimitivePhase.START, .1, 0.0, .9
            )
        )))
        assertEquals(SetLifecycleState.ARMED, lifecycle.state)
        assertEquals(SetLifecycleState.ARMED, lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY))
        lifecycle.onMovement(com.gymbuddy.domain.movement.MovementPrimitiveFrame(100_000, mapOf(
            "cycle" to com.gymbuddy.domain.movement.MovementPrimitiveObservation(
                "cycle", MovementPrimitive.RAISE, 100_000, com.gymbuddy.domain.movement.PrimitivePhase.OUTBOUND, .5, 1.0, .9
            )
        )))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.state)
    }

    @Test fun interruptionEpisodeGateTriggersExactlyOnceUntilRecovery() {
        val gate = InterruptionEpisodeGate()
        assertTrue(gate.onInvalid())
        assertFalse(gate.onInvalid())
        assertFalse(gate.onInvalid())
        gate.onValid()
        assertTrue(gate.onInvalid())
    }

    @Test fun wrongViewGatesProductionUntilReadyDwellCompletes() {
        val bundle = InitialExerciseProfiles.dumbbellLateralRaise
        val pipeline = ProductionMovementPipeline(AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment))
        val wrong = PoseFrame(0, 0, 640, 480, PoseFrameSource.CAMERA, listOf(candidate(front = false)))
        assertEquals(TrackingQualityReason.CAMERA_NOT_READY, pipeline.process(wrong).tracking.reason)

        pipeline.reset()
        val ready1 = pipeline.process(PoseFrame(0, 0, 640, 480, PoseFrameSource.CAMERA, listOf(candidate(front = true))))
        val ready2 = pipeline.process(PoseFrame(1, 100_000, 640, 480, PoseFrameSource.CAMERA, listOf(candidate(front = true))))
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, ready1.cameraGuidance)
        assertEquals(CameraGuidanceAction.CAMERA_READY, ready2.cameraGuidance)
        assertNotEquals(SetLifecycleState.CAMERA_GUIDANCE, ready2.lifecycleState)
    }

    @Test fun cameraDisturbanceImmediatelyReturnsRepositionGuidance() {
        val bundle=InitialExerciseProfiles.dumbbellLateralRaise
        val pipeline=ProductionMovementPipeline(
            AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)
        )
        val context=TrackingObservationContext(
            observedViewClass=ViewClass.FRONT,
            cameraMotionScore=0.0,
        )
        val pose=candidate(front=true)
        pipeline.process(PoseFrame(0,0,640,480,PoseFrameSource.CAMERA,listOf(pose)),context)
        val ready=pipeline.process(PoseFrame(1,100_000,640,480,PoseFrameSource.CAMERA,listOf(pose)),context)
        assertEquals(CameraGuidanceAction.CAMERA_READY,ready.cameraGuidance)

        val disturbed=pipeline.process(
            PoseFrame(2,200_000,640,480,PoseFrameSource.CAMERA,listOf(pose)),
            context.copy(cameraMotionScore=.95),
        )
        assertEquals(TrackingQualityReason.CAMERA_DISTURBANCE,disturbed.tracking.reason)
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE,disturbed.lifecycleState)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS,disturbed.cameraGuidance)
    }

    @Test fun viewEstimatorDoesNotGuessFrontWithoutFaceEvidence() {
        val noFace = PoseViewEstimator().estimate(candidate(front = false, wide = true))
        assertTrue(noFace == ViewClass.REAR || noFace == ViewClass.REAR_OBLIQUE)
        val withFace = PoseViewEstimator().estimate(candidate(front = true, wide = true))
        assertTrue(withFace == ViewClass.FRONT || withFace == ViewClass.FRONT_OBLIQUE)
    }

    private fun candidate(front: Boolean, wide: Boolean = front): PoseSubjectCandidate {
        val shoulderWidth = if (wide) .18 else .04
        val hipWidth = if (wide) .14 else .03
        fun lm(id: PoseLandmarkId, x: Double, y: Double) = PoseLandmarkObservation(id, PoseCoordinate3d(x, y, 0.0), .95, .95)
        val map = mutableMapOf(
            PoseLandmarkId.LEFT_SHOULDER to lm(PoseLandmarkId.LEFT_SHOULDER, .5 - shoulderWidth / 2, .3),
            PoseLandmarkId.RIGHT_SHOULDER to lm(PoseLandmarkId.RIGHT_SHOULDER, .5 + shoulderWidth / 2, .3),
            PoseLandmarkId.LEFT_HIP to lm(PoseLandmarkId.LEFT_HIP, .5 - hipWidth / 2, .55),
            PoseLandmarkId.RIGHT_HIP to lm(PoseLandmarkId.RIGHT_HIP, .5 + hipWidth / 2, .55),
            PoseLandmarkId.LEFT_ELBOW to lm(PoseLandmarkId.LEFT_ELBOW, .35, .35),
            PoseLandmarkId.RIGHT_ELBOW to lm(PoseLandmarkId.RIGHT_ELBOW, .65, .35),
            PoseLandmarkId.LEFT_WRIST to lm(PoseLandmarkId.LEFT_WRIST, .25, .4),
            PoseLandmarkId.RIGHT_WRIST to lm(PoseLandmarkId.RIGHT_WRIST, .75, .4),
        )
        if (front) map[PoseLandmarkId.NOSE] = lm(PoseLandmarkId.NOSE, .5, .2)
        return PoseSubjectCandidate(0, map)
    }
}
