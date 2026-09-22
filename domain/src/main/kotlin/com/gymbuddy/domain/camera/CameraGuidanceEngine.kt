package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.tracking.PrimarySubjectLockResult
import com.gymbuddy.domain.tracking.PrimarySubjectLockState
import com.gymbuddy.domain.tracking.TrackingObservationContext

/**
 * Converts observable setup evidence into exactly one user-facing camera action.
 * Readiness is deliberately stricter than person detection: identity, view,
 * framing, required-landmark observability, and short continuity must all pass.
 */
class CameraGuidanceEngine(
    private val readyDwellFrames: Int = 2,
) {
    init { require(readyDwellFrames >= 1) }

    private var consecutiveReadyFrames = 0
    private var lastTargetIndex: Int? = null

    fun reset() {
        consecutiveReadyFrames = 0
        lastTargetIndex = null
    }

    fun evaluate(
        frame: PoseFrame,
        lock: PrimarySubjectLockResult,
        profile: CameraProfile,
        context: TrackingObservationContext = TrackingObservationContext(),
    ): CameraGuidanceAction {
        if (lock.state != PrimarySubjectLockState.LOCKED) return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
        val targetIndex = lock.targetCandidateIndex ?: return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
        val target = frame.candidates.firstOrNull { it.candidateIndex == targetIndex }
            ?: return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)

        // Unknown view is not evidence of a valid view.
        val observedView = context.observedViewClass
            ?: return notReady(profile, CameraGuidanceAction.ADJUST_ANGLE)
        if (observedView !in profile.allowedViewClasses) {
            return notReady(profile, CameraGuidanceAction.ADJUST_ANGLE)
        }

        val required = profile.requiredLandmarks
        val observations = required.mapNotNull { req ->
            val id = PoseLandmarkId.entries.firstOrNull { it.wireName == req.landmarkId }
                ?: return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
            target.normalized(id)?.let { req to it }
        }
        if (observations.size != required.size) return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)

        if (observations.any { (_, lm) -> lm.position.x !in 0.0..1.0 || lm.position.y !in 0.0..1.0 }) {
            return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
        }

        val reliable = observations.count { (req, lm) ->
            val visibilityOk = lm.visibility?.let { it >= req.minVisibility } ?: false
            val presenceOk = req.minPresence?.let { threshold ->
                lm.presence?.let { it >= threshold } ?: false
            } ?: true
            visibilityOk && presenceOk
        }
        if (reliable.toDouble() / required.size < profile.minVisibleRequiredFraction) {
            return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
        }

        val points = target.normalizedLandmarks.values.map { it.position }
        if (points.size < 2) return notReady(profile, CameraGuidanceAction.CANNOT_ASSESS)
        val fill = maxOf(
            points.maxOf { it.x } - points.minOf { it.x },
            points.maxOf { it.y } - points.minOf { it.y },
        )
        if (fill < profile.frameFillRange.min) return notReady(profile, CameraGuidanceAction.MOVE_CLOSER)
        if (fill > profile.frameFillRange.max) return notReady(profile, CameraGuidanceAction.MOVE_FARTHER)

        if (lastTargetIndex != targetIndex) {
            consecutiveReadyFrames = 0
            lastTargetIndex = targetIndex
        }
        consecutiveReadyFrames += 1
        return if (consecutiveReadyFrames >= readyDwellFrames) {
            CameraGuidanceAction.CAMERA_READY
        } else {
            CameraGuidanceAction.CANNOT_ASSESS
        }
    }

    private fun notReady(
        profile: CameraProfile,
        action: CameraGuidanceAction,
    ): CameraGuidanceAction {
        consecutiveReadyFrames = 0
        return if (action in profile.guidanceActions) {
            action
        } else {
            CameraGuidanceAction.CANNOT_ASSESS
        }
    }
}
