package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.tracking.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraGuidanceEngineTest {
    private val profile = profile()
    private val locked = PrimarySubjectLockResult(PrimarySubjectLockState.LOCKED, 0, .9, .5, 1)
    private val side = TrackingObservationContext(observedViewClass = ViewClass.SIDE)

    @Test fun unknownViewNeverBecomesReady() {
        val engine = CameraGuidanceEngine(1)
        assertEquals(CameraGuidanceAction.ADJUST_ANGLE, engine.evaluate(frame(.4), locked, profile))
    }

    @Test fun distanceGuidanceIsSingleAction() {
        val engine = CameraGuidanceEngine(1)
        assertEquals(CameraGuidanceAction.MOVE_CLOSER, engine.evaluate(frame(.1), locked, profile, side))
        assertEquals(CameraGuidanceAction.MOVE_FARTHER, engine.evaluate(frame(.9), locked, profile, side))
    }

    @Test fun unsupportedGuidanceActionFallsBackToCannotAssess() {
        val limited = profile(guidance = setOf(CameraGuidanceAction.CAMERA_READY, CameraGuidanceAction.CANNOT_ASSESS))
        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            CameraGuidanceEngine(1).evaluate(frame(.1), locked, limited, side),
        )
    }

    @Test fun missingOrOutOfFrameRequiredLandmarkBlocksReadiness() {
        val engine = CameraGuidanceEngine(1)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4, missing = PoseLandmarkId.RIGHT_HIP), locked, profile, side))
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4, outOfFrame = PoseLandmarkId.RIGHT_HIP), locked, profile, side))
    }

    @Test fun visibilityFractionHonorsProfileThreshold() {
        val engine = CameraGuidanceEngine(1)
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            engine.evaluate(frame(.4, lowConfidence = setOf(PoseLandmarkId.RIGHT_HIP)), locked, profile, side),
        )
        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            engine.evaluate(frame(.4, lowConfidence = setOf(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.LEFT_HIP)), locked, profile, side),
        )
    }

    @Test fun bystanderDoesNotBlockStrongLockedTarget() {
        val target = candidate(.4, 0)
        val bystander = candidate(.3, 1)
        val frame = PoseFrame(1, 1_000, 1080, 1920, PoseFrameSource.VIDEO, listOf(target, bystander))
        assertEquals(CameraGuidanceAction.CAMERA_READY, CameraGuidanceEngine(1).evaluate(frame, locked.copy(candidateCount = 2), profile, side))
    }

    @Test fun invalidFrameResetsReadyDwell() {
        val engine = CameraGuidanceEngine(2)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), locked, profile, side))
        assertEquals(CameraGuidanceAction.ADJUST_ANGLE, engine.evaluate(frame(.4), locked, profile, TrackingObservationContext(ViewClass.FRONT)))
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), locked, profile, side))
        assertEquals(CameraGuidanceAction.CAMERA_READY, engine.evaluate(frame(.4), locked, profile, side))
    }

    @Test fun readyRequiresContinuityAndIdentitySafety() {
        val engine = CameraGuidanceEngine(2)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), locked, profile, side))
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4, 1), locked.copy(targetCandidateIndex = 1), profile, side))
        assertEquals(CameraGuidanceAction.CAMERA_READY, engine.evaluate(frame(.4, 1), locked.copy(targetCandidateIndex = 1), profile, side))
        val ambiguous = PrimarySubjectLockResult(PrimarySubjectLockState.TARGET_AMBIGUOUS, null, .7, .02, 2)
        assertEquals(CameraGuidanceAction.CANNOT_ASSESS, engine.evaluate(frame(.4), ambiguous, profile, side))
    }

    private fun profile(guidance: Set<CameraGuidanceAction> = CameraGuidanceAction.entries.toSet()) = CameraProfile(
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
        guidanceActions = guidance,
    )

    private fun frame(
        fill: Double,
        candidateIndex: Int = 0,
        missing: PoseLandmarkId? = null,
        outOfFrame: PoseLandmarkId? = null,
        lowConfidence: Set<PoseLandmarkId> = emptySet(),
    ): PoseFrame = PoseFrame(
        1, 1_000, 1080, 1920, PoseFrameSource.VIDEO,
        listOf(candidate(fill, candidateIndex, missing, outOfFrame, lowConfidence)),
    )

    private fun candidate(
        fill: Double,
        candidateIndex: Int,
        missing: PoseLandmarkId? = null,
        outOfFrame: PoseLandmarkId? = null,
        lowConfidence: Set<PoseLandmarkId> = emptySet(),
    ): PoseSubjectCandidate {
        val left = .5 - fill / 2; val right = .5 + fill / 2
        val top = .5 - fill / 2; val bottom = .5 + fill / 2
        fun lm(id: PoseLandmarkId, x: Double, y: Double): PoseLandmarkObservation {
            val actualX = if (id == outOfFrame) 1.1 else x
            val confidence = if (id in lowConfidence) .2 else .9
            return PoseLandmarkObservation(id, PoseCoordinate3d(actualX, y, 0.0), confidence, confidence)
        }
        return PoseSubjectCandidate(candidateIndex, buildMap {
            if (missing != PoseLandmarkId.LEFT_SHOULDER) put(PoseLandmarkId.LEFT_SHOULDER, lm(PoseLandmarkId.LEFT_SHOULDER, left, top))
            if (missing != PoseLandmarkId.RIGHT_SHOULDER) put(PoseLandmarkId.RIGHT_SHOULDER, lm(PoseLandmarkId.RIGHT_SHOULDER, right, top))
            if (missing != PoseLandmarkId.LEFT_HIP) put(PoseLandmarkId.LEFT_HIP, lm(PoseLandmarkId.LEFT_HIP, left, bottom))
            if (missing != PoseLandmarkId.RIGHT_HIP) put(PoseLandmarkId.RIGHT_HIP, lm(PoseLandmarkId.RIGHT_HIP, right, bottom))
        })
    }
}
