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

    @Test fun activeSetSurvivesTrackingInterruption() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovement(null))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onCameraGuidance(CameraGuidanceAction.CANNOT_ASSESS))
    }

    @Test fun inactivityAloneCannotAutoEndRestPause() {
        val lifecycle = active()
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onEndEvidence(false))
        assertEquals(SetLifecycleState.POSSIBLE_END, lifecycle.onEndEvidence(true))
        assertEquals(SetLifecycleState.ACTIVE_SET, lifecycle.onMovementResumed())
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