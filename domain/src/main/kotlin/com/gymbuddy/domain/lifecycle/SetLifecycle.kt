package com.gymbuddy.domain.lifecycle

import com.gymbuddy.domain.movement.MovementPrimitiveFrame
import com.gymbuddy.domain.movement.PrimitivePhase
import com.gymbuddy.domain.profile.CameraGuidanceAction

enum class SetLifecycleState { CAMERA_GUIDANCE, CAMERA_READY, WAITING, ARMED, ACTIVE_SET, POSSIBLE_END, FINALIZING, ENDED }

class SetLifecycleController {
    var state = SetLifecycleState.CAMERA_GUIDANCE
        private set

    fun reset() { state = SetLifecycleState.CAMERA_GUIDANCE }

    fun invalidateCameraSetup(): SetLifecycleState {
        if (state != SetLifecycleState.FINALIZING && state != SetLifecycleState.ENDED) {
            state = SetLifecycleState.CAMERA_GUIDANCE
        }
        return state
    }

    fun onCameraGuidance(action: CameraGuidanceAction): SetLifecycleState {
        if (state in setOf(SetLifecycleState.ACTIVE_SET, SetLifecycleState.POSSIBLE_END, SetLifecycleState.FINALIZING, SetLifecycleState.ENDED)) {
            return state
        }
        val ready = action == CameraGuidanceAction.CAMERA_READY
        state = when (state) {
            SetLifecycleState.CAMERA_GUIDANCE -> if (ready) SetLifecycleState.CAMERA_READY else SetLifecycleState.CAMERA_GUIDANCE
            SetLifecycleState.CAMERA_READY -> if (ready) SetLifecycleState.CAMERA_READY else SetLifecycleState.CAMERA_GUIDANCE
            SetLifecycleState.WAITING, SetLifecycleState.ARMED -> if (ready) state else SetLifecycleState.CAMERA_GUIDANCE
            else -> state
        }
        return state
    }

    fun onMovement(frame: MovementPrimitiveFrame?): SetLifecycleState {
        if (state in setOf(SetLifecycleState.ACTIVE_SET, SetLifecycleState.POSSIBLE_END, SetLifecycleState.FINALIZING, SetLifecycleState.ENDED)) return state
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

    fun onEndEvidence(validatedEndPosition: Boolean): SetLifecycleState {
        if (state == SetLifecycleState.ACTIVE_SET && validatedEndPosition) state = SetLifecycleState.POSSIBLE_END
        return state
    }

    fun onMovementResumed(): SetLifecycleState {
        if (state == SetLifecycleState.POSSIBLE_END) state = SetLifecycleState.ACTIVE_SET
        return state
    }

    fun confirmAutomaticEnd(): SetLifecycleState {
        if (state == SetLifecycleState.POSSIBLE_END) state = SetLifecycleState.FINALIZING
        return state
    }

    fun manualEnd(): SetLifecycleState {
        if (state == SetLifecycleState.ACTIVE_SET || state == SetLifecycleState.POSSIBLE_END) state = SetLifecycleState.FINALIZING
        return state
    }

    fun finalized(): SetLifecycleState {
        if (state == SetLifecycleState.FINALIZING) state = SetLifecycleState.ENDED
        return state
    }
}
