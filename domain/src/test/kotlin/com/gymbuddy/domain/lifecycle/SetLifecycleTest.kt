package com.gymbuddy.domain.lifecycle

import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.MovementPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SetLifecycleTest {
    @Test fun stableStartThenOutboundStartsExactlyOnce() {
        val lifecycle = SetLifecycleController()
        assertEquals(SetLifecycleState.CAMERA_READY, lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY))
        assertEquals(SetLifecycleState.ARMED, lifecycle.onMovement(frame(PrimitivePhase.START)))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
    }

    @Test fun midRepStartIsRejectedUntilStableStartThenOutbound() {
        val lifecycle = SetLifecycleController()
        lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.END_RANGE)))
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.RETURNING)))
        assertEquals(SetLifecycleState.ARMED, lifecycle.onMovement(frame(PrimitivePhase.START)))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
    }

    @Test fun setupOrUnknownDisarmsBeforeActivation() {
        val lifecycle = SetLifecycleController()
        lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        assertEquals(SetLifecycleState.ARMED, lifecycle.onMovement(frame(PrimitivePhase.START)))
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.SETUP)))
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
        assertEquals(SetLifecycleState.ARMED, lifecycle.onMovement(frame(PrimitivePhase.START)))
        assertEquals(SetLifecycleState.WAITING, lifecycle.onMovement(frame(PrimitivePhase.UNKNOWN)))
    }

    @Test fun cameraInvalidationBeforeActiveReturnsToGuidance() {
        val lifecycle = SetLifecycleController()
        lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        lifecycle.onMovement(frame(PrimitivePhase.START))
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, lifecycle.onCameraGuidance(CameraGuidanceAction.CANNOT_ASSESS))
    }

    @Test fun activeSetSurvivesTrackingInterruption() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovement(null))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onCameraGuidance(CameraGuidanceAction.CANNOT_ASSESS))
    }

    @Test fun materialCameraInvalidationForcesActiveSetBackToGuidance() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, lifecycle.invalidateCameraSetup())
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, lifecycle.state)
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, lifecycle.onMovement(frame(PrimitivePhase.OUTBOUND)))
        assertEquals(SetLifecycleState.CAMERA_READY, lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY))
    }

    @Test fun inactivityAloneCannotAutoEndRestPause() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onEndEvidence(false))
        assertEquals(SetLifecycleState.POSSIBLE_END, lifecycle.onEndEvidence(true))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovementResumed())
    }

    @Test fun automaticEndMustPassThroughFinalizing() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.POSSIBLE_END, lifecycle.onEndEvidence(true))
        assertEquals(SetLifecycleState.POSSIBLE_END, lifecycle.finalized())
        assertEquals(SetLifecycleState.FINALIZING, lifecycle.confirmAutomaticEnd())
        assertEquals(SetLifecycleState.ENDED, lifecycle.finalized())
    }

    @Test fun manualEndBeforeActiveDoesNotCreateASetEnd() {
        val lifecycle = SetLifecycleController()
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, lifecycle.manualEnd())
        lifecycle.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        assertEquals(SetLifecycleState.CAMERA_READY, lifecycle.manualEnd())
        lifecycle.onMovement(frame(PrimitivePhase.START))
        assertEquals(SetLifecycleState.ARMED, lifecycle.manualEnd())
    }

    @Test fun manualEndIsIdempotent() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.FINALIZING, lifecycle.manualEnd())
        assertEquals(SetLifecycleState.FINALIZING, lifecycle.manualEnd())
        assertEquals(SetLifecycleState.ENDED, lifecycle.finalized())
        assertEquals(SetLifecycleState.ENDED, lifecycle.finalized())
    }

    private fun active() = SetLifecycleController().also {
        it.onCameraGuidance(CameraGuidanceAction.CAMERA_READY)
        it.onMovement(frame(PrimitivePhase.START))
        it.onMovement(frame(PrimitivePhase.OUTBOUND))
    }

    private fun frame(phase: PrimitivePhase) = MovementPrimitiveFrame(
        timestampUs = 1_000,
        observations = mapOf(
            "cycle" to MovementPrimitiveObservation(
                stepId = "cycle",
                primitive = MovementPrimitive.PRESS,
                timestampUs = 1_000,
                phase = phase,
                progress = .2,
                velocity = null,
                confidence = .9,
            )
        ),
    )
}
