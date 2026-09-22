package com.gymbuddy.domain.lifecycle

import com.gymbuddy.domain.movement.MovementPrimitiveFrame
import com.gymbuddy.domain.movement.PrimitivePhase
import com.gymbuddy.domain.profile.CameraGuidanceAction

enum class SetLifecycleState {
    CAMERA_GUIDANCE,
    CAMERA_READY,
    WAITING,
    ARMED,
    ACTIVE_SET,
    POSSIBLE_END,
    FINALIZING,
    ENDED,
}

/**
 * Product set lifecycle. ACTIVE_SET is sticky across tracking interruptions;
 * observability is controlled independently by TrackingQualityGate.
 */
class SetLifecycleController {
    var state: SetLifecycleState = SetLifecycleState.CAMERA_GUIDANCE
        private set

    fun reset() { state = SetLifecycleState.CAMERA_GUIDANCE }

    fun onCameraGuidance(action: CameraGuidanceAction): SetLifecycleState {
        if (state == SetLifecycleState.ACTIVE_SET || state == SetLifecycleState.POSSIBLE_END ||
            state == SetLifecycleState.FINALIZING || state == SetLifecycleState.ENDED) return state
        state = if (action == CameraGuidanceAction.CAMERA_READY) {
            SetLifecycleState.CAMERA_READY
        } else {
            SetLifecycleState.CAMERA_GUIDANCE
        }
        return state
    }

    fun onMovement(frame: MovementPrimitiveFrame?): SetLifecycleState {
        if (state == SetLifecycleState.ACTIVE_SET || state == SetLifecycleState.POSSIBLE_END ||
            state == SetLifecycleState.FINALIZING || state == SetLifecycleState.ENDED) return state
        if (state == SetLifecycleState.CAMERA_READY) state = SetLifecycleState.WAITING
        val phase = frame?.observations?.values?.firstOrNull()?.phase ?: return state
        state = when (state) {
            SetLifecycleState.WAITING -> if (phase == PrimitivePhase.START) SetLifecycleState.ARMED else state
            SetLifecycleState.ARMED -> when (phase) {
                PrimitivePhase.OUTBOUND -> SetLifecycleState.ACTIVE_SET
                PrimitivePhase.SETUP, PrimitivePhase.UNKNOWN -> SetLifecycleState.WAITING
                else -> state
            }
            else -> state
        }
        return state
    }

    /**
     * Idle time alone is intentionally insufficient to end a set. The caller
     * must supply independently validated end-position evidence.
     */
    fun onEndEvidence(validatedEndPosition: Boolean): SetLifecycleState {
        if (state == SetLifecycleState.ACTIVE_SET && validatedEndPosition) {
            state = SetLifecycleState.POSSIBLE_END
        }
        return state
    }

    fun onMovementResumed(): SetLifecycleState {
        if (state == SetLifecycleState.POSSIBLE_END) state = SetLifecycleState.ACTIVE_SET
        return state
    }

    fun manualEnd(): SetLifecycleState {
        if (state != SetLifecycleState.ENDED) state = SetLifecycleState.FINALIZING
        return state
    }

    fun finalized(): SetLifecycleState {
        if (state == SetLifecycleState.FINALIZING || state == SetLifecycleState.POSSIBLE_END) {
            state = SetLifecycleState.ENDED
        }
        return state
    }
}