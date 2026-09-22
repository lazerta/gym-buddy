package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.ViewClass
import kotlin.math.abs

/** Conservative coarse view estimate used only for setup gating. */
class PoseViewEstimator {
    fun estimate(target: PoseSubjectCandidate): ViewClass? {
        fun p(id: PoseLandmarkId) = target.normalized(id)?.position
        val leftShoulder = p(PoseLandmarkId.LEFT_SHOULDER) ?: return null
        val rightShoulder = p(PoseLandmarkId.RIGHT_SHOULDER) ?: return null
        val leftHip = p(PoseLandmarkId.LEFT_HIP) ?: return null
        val rightHip = p(PoseLandmarkId.RIGHT_HIP) ?: return null

        val shoulderWidth = abs(rightShoulder.x - leftShoulder.x)
        val hipWidth = abs(rightHip.x - leftHip.x)
        val torsoHeight = abs(
            (leftHip.y + rightHip.y) / 2.0 -
                (leftShoulder.y + rightShoulder.y) / 2.0
        ).coerceAtLeast(0.05)
        val widthRatio = maxOf(shoulderWidth, hipWidth) / torsoHeight

        val faceVisible = listOf(
            PoseLandmarkId.NOSE,
            PoseLandmarkId.LEFT_EYE,
            PoseLandmarkId.RIGHT_EYE,
        ).mapNotNull(target::normalized).any { (it.visibility ?: 0.0) >= 0.5 }

        return when {
            widthRatio >= 0.75 -> if (faceVisible) ViewClass.FRONT else ViewClass.REAR
            widthRatio >= 0.45 -> if (faceVisible) ViewClass.FRONT_OBLIQUE else ViewClass.REAR_OBLIQUE
            widthRatio >= 0.25 -> ViewClass.SIDE_OBLIQUE
            else -> ViewClass.SIDE
        }
    }
}
