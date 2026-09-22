package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.tracking.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraGuidanceEngineTest {
    private val profile = CameraProfile(
        profileId = "test-camera",
        profileVersion = 1,
        semanticHash = "test-camera-v1",
        preferredViewClass = ViewClass.SIDE,
        allowedViewClasses = setOf(ViewClass.SIDE, ViewClass.SIDE_OBLIQUE),
        allowedLensFacing = setOf(LensFacing.BACK),
        requiredLandmarks = setOf(
            LandmarkRequirement("left_shoulder", .5, .5),
            LandmarkRequirement("right_shoulder", .5, .5),
            LandmarkRequirement("left_hip", .5, .5),
            LandmarkRequirement("right_hip", .5, .5),
        ),
        frameFillRange = NumericRange(.2, .8),
        minVisibleRequiredFraction = .75,
        maxTrackingGapMs = 350,
        guidanceActions = CameraGuidanceAction.entries.toSet(),
    )
    private val locked = PrimarySubjectLockResult(PrimarySubjectLockState.LOCKED, 0, .9, .5, 1)

    @Test fun unknownViewNeverBecomesReady() {
        val engine = CameraGuidanceEngine(1)
        assertEquals(CameraGuidanceAction.ADJUST_ANGLE, engine.evaluate(frame(.4), locked, profile))
    }

    @Test fun distanceGuidanceIsSingleAction() {
        val engine = CameraGuidanceEngine(1)
        val ctx = TrackingObservationContext(observedViewClass = ViewClass.SIDE)
        assertEquals(CameraGuidanceAction.MOVE_CLOSER, engine.evaluate(frame(.1), locked, profile, ctx))
        assertEquals(CameraGuidanceAction.MOVE_FARTHER, engine.evaluate(frame(.9), locked, profile, ctx))
    }

    @Test fun readyRequiresContinuityAndIdentitySafety() {
        val engine = CameraGuidanceEngine(2)
        val ctx = TrackingObservationContext(observedViewClass = ViewClass.SIDE)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), locked, profile, ctx))
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4, 1), locked.copy(targetCandidateIndex = 1), profile, ctx))
        assertEquals(CameraGuidanceAction.CAMERA_READY, engine.evaluate(frame(.4, 1), locked.copy(targetCandidateIndex = 1), profile, ctx))
        val ambiguous = PrimarySubjectLockResult(PrimarySubjectLockState.TARGET_AMBIGUOUS, null, .7, .02, 2)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), ambiguous, profile, ctx))
    }

    private fun frame(fill: Double, candidateIndex: Int = 0): PoseFrame {
        val left = .5 - fill / 2; val right = .5 + fill / 2
        val top = .5 - fill / 2; val bottom = .5 + fill / 2
        fun lm(id: PoseLandmarkId, x: Double, y: Double) = PoseLandmarkObservation(id, PoseCoordinate3d(x, y, 0.0), .9, .9)
        val landmarks = mapOf(
            PoseLandmarkId.LEFT_SHOULDER to lm(PoseLandmarkId.LEFT_SHOULDER, left, top),
            PoseLandmarkId.RIGHT_SHOULDER to lm(PoseLandmarkId.RIGHT_SHOULDER, right, top),
            PoseLandmarkId.LEFT_HIP to lm(PoseLandmarkId.LEFT_HIP, left, bottom),
            PoseLandmarkId.RIGHT_HIP to lm(PoseLandmarkId.RIGHT_HIP, right, bottom),
        )
        return PoseFrame(1, 1_000, 1080, 1920, PoseFrameSource.VIDEO, listOf(PoseSubjectCandidate(candidateIndex, landmarks)))
    }
}