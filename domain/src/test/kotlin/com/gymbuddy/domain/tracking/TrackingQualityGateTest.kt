package com.gymbuddy.domain.tracking

import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingQualityGateTest {
    @Test fun requiredJointsOutOfFramePause() {
        val gate = TrackingQualityGate()
        val target = person(0).copy(normalizedLandmarks = person(0).normalizedLandmarks - PoseLandmarkId.LEFT_WRIST)
        val result = gate.evaluate(frame(0, target), locked(0), profile())
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.REQUIRED_LANDMARKS_MISSING, result.reason)
    }

    @Test fun lowConfidencePauses() {
        val gate = TrackingQualityGate()
        val p = person(0)
        val landmarks = p.normalizedLandmarks.mapValues { (_, lm) -> lm.copy(visibility = 0.20) }
        val result = gate.evaluate(frame(0, p.copy(normalizedLandmarks = landmarks)), locked(0), profile())
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.LOW_LANDMARK_CONFIDENCE, result.reason)
    }

    @Test fun temporaryPartialOcclusionCanBeDegradedWhenProfileAllowsIt() {
        val camera = profile(minVisible = 0.60)
        val gate = TrackingQualityGate(degradedMargin = 0.0)
        val p = person(0)
        val landmarks = p.normalizedLandmarks.toMutableMap()
        landmarks[PoseLandmarkId.LEFT_WRIST] = landmarks.getValue(PoseLandmarkId.LEFT_WRIST).copy(visibility = 0.30)
        val result = gate.evaluate(frame(0, p.copy(normalizedLandmarks = landmarks)), locked(0), camera)
        assertEquals(TrackingQualityState.DEGRADED, result.state)
    }

    @Test fun cameraBumpPausesWithoutChangingIdentity() {
        val result = TrackingQualityGate().evaluate(
            frame(0, person(0)), locked(0), profile(), TrackingObservationContext(cameraMotionScore = 0.8)
        )
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.CAMERA_DISTURBANCE, result.reason)
    }

    @Test fun wrongCameraAnglePauses() {
        val result = TrackingQualityGate().evaluate(
            frame(0, person(0)), locked(0), profile(), TrackingObservationContext(observedViewClass = ViewClass.FRONT)
        )
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.WRONG_VIEW, result.reason)
    }

    @Test fun additionalPersonDoesNotPauseWhenTargetRemainsObservable() {
        val result = TrackingQualityGate().evaluate(
            frame(0, person(0), person(1, offset = 0.30)), locked(0), profile()
        )
        assertEquals(TrackingQualityState.OBSERVABLE, result.state)
        assertEquals(0, result.targetCandidateIndex)
    }

    @Test fun targetVisibleButIdentityAmbiguousPauses() {
        val ambiguous = PrimarySubjectLockResult(
            PrimarySubjectLockState.TARGET_AMBIGUOUS, null, 0.8, 0.03, 2
        )
        val result = TrackingQualityGate().evaluate(frame(0, person(0), person(1)), ambiguous, profile())
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.IDENTITY_UNSAFE, result.reason)
    }

    @Test fun identitySafeButBiomechanicsUnobservablePauses() {
        val p = person(0)
        val landmarks = p.normalizedLandmarks.mapValues { (id, lm) ->
            if (id == PoseLandmarkId.LEFT_WRIST || id == PoseLandmarkId.RIGHT_WRIST) lm.copy(visibility = 0.05) else lm
        }
        val result = TrackingQualityGate().evaluate(
            frame(0, p.copy(normalizedLandmarks = landmarks)), locked(0), profile()
        )
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertTrue(!result.allowsBiomechanics)
    }

    @Test fun continuityGapPausesOncePreviousSampleWasAccepted() {
        val gate = TrackingQualityGate()
        assertEquals(TrackingQualityState.OBSERVABLE, gate.evaluate(frame(0, person(0)), locked(0), profile()).state)
        val result = gate.evaluate(frame(600_000, person(0)), locked(0), profile())
        assertEquals(TrackingQualityState.PAUSED, result.state)
        assertEquals(TrackingQualityReason.CONTINUITY_GAP, result.reason)
    }

    private fun locked(index: Int) = PrimarySubjectLockResult(
        PrimarySubjectLockState.LOCKED, index, 0.9, 0.5, 1
    )

    private fun profile(minVisible: Double = 0.80) = CameraProfile(
        profileId = "tracking-side",
        profileVersion = 1,
        semanticHash = "tracking-hash",
        preferredViewClass = ViewClass.SIDE,
        allowedViewClasses = setOf(ViewClass.SIDE, ViewClass.SIDE_OBLIQUE),
        allowedLensFacing = setOf(LensFacing.BACK),
        requiredLandmarks = setOf(
            LandmarkRequirement("left_shoulder", 0.65),
            LandmarkRequirement("right_shoulder", 0.65),
            LandmarkRequirement("left_hip", 0.65),
            LandmarkRequirement("right_hip", 0.65),
            LandmarkRequirement("left_wrist", 0.65),
            LandmarkRequirement("right_wrist", 0.65),
        ),
        frameFillRange = NumericRange(0.20, 0.90),
        minVisibleRequiredFraction = minVisible,
        maxTrackingGapMs = 300,
        guidanceActions = CameraGuidanceAction.entries.toSet(),
    )

    private fun frame(ts: Long, vararg people: PoseSubjectCandidate) = PoseFrame(
        frameId = ts,
        timestampUs = ts,
        width = 640,
        height = 480,
        source = PoseFrameSource.CAMERA,
        candidates = people.toList(),
    )

    private fun person(index: Int, offset: Double = 0.0): PoseSubjectCandidate {
        val points = mapOf(
            PoseLandmarkId.LEFT_SHOULDER to p(.40 + offset, .30),
            PoseLandmarkId.RIGHT_SHOULDER to p(.60 + offset, .30),
            PoseLandmarkId.LEFT_HIP to p(.43 + offset, .50),
            PoseLandmarkId.RIGHT_HIP to p(.57 + offset, .50),
            PoseLandmarkId.LEFT_WRIST to p(.30 + offset, .55),
            PoseLandmarkId.RIGHT_WRIST to p(.70 + offset, .55),
        )
        return PoseSubjectCandidate(index, points.mapValues { (id, pos) ->
            PoseLandmarkObservation(id, pos, .95, .95)
        })
    }

    private fun p(x: Double, y: Double) = PoseCoordinate3d(x, y, 0.0)
}
