package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.profile.LandmarkRequirement
import com.gymbuddy.domain.profile.LensFacing
import com.gymbuddy.domain.profile.NumericRange
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.tracking.PrimarySubjectLockResult
import com.gymbuddy.domain.tracking.PrimarySubjectLockState
import com.gymbuddy.domain.tracking.TrackingObservationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalCameraGuidancePriorTest {
    private val profile = profile()
    private val locked = PrimarySubjectLockResult(
        PrimarySubjectLockState.LOCKED,
        0,
        .90,
        .50,
        1,
    )
    private val side = TrackingObservationContext(
        observedViewClass = ViewClass.SIDE,
    )

    @Test fun stableKnownSetupReacquiresFasterThanGenericDwell() {
        val generic = CameraGuidanceEngine(
            readyDwellFrames = 2,
            knownSetupReadyDwellFrames = 1,
        )
        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            generic.evaluate(frame(.50), locked, profile, side),
        )
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            generic.evaluate(frame(.50), locked, profile, side),
        )

        val personalized = CameraGuidanceEngine(
            readyDwellFrames = 2,
            knownSetupReadyDwellFrames = 1,
        )
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            personalized.evaluate(
                frame(.50),
                locked,
                profile,
                side,
                stablePrior(),
            ),
        )
    }

    @Test fun personalPriorCannotMakeWrongViewOrBadFramingReady() {
        val prior = stablePrior()
        val engine = CameraGuidanceEngine(2, 1)

        assertEquals(
            CameraGuidanceAction.ADJUST_ANGLE,
            engine.evaluate(
                frame(.50),
                locked,
                profile,
                TrackingObservationContext(ViewClass.FRONT),
                prior,
            ),
        )
        assertEquals(
            CameraGuidanceAction.MOVE_CLOSER,
            engine.evaluate(frame(.10), locked, profile, side, prior),
        )
        assertEquals(
            CameraGuidanceAction.MOVE_FARTHER,
            engine.evaluate(frame(.90), locked, profile, side, prior),
        )
    }

    @Test fun missingRequiredLandmarkStillBlocksKnownSetup() {
        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            CameraGuidanceEngine(2, 1).evaluate(
                frame(.50, missing = PoseLandmarkId.RIGHT_HIP),
                locked,
                profile,
                side,
                stablePrior(),
            ),
        )
    }

    @Test fun stablePriorRanksKnownSetupActionsWithoutOverridingAllowedView() {
        val prior = stablePrior()
        assertEquals(
            CameraGuidanceAction.MOVE_CLOSER,
            prior.rankedActions(profile, ViewClass.SIDE, .40).first(),
        )
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            prior.rankedActions(profile, ViewClass.SIDE, .50).first(),
        )
        assertEquals(
            CameraGuidanceAction.ADJUST_ANGLE,
            prior.rankedActions(
                profile,
                ViewClass.SIDE_OBLIQUE,
                .50,
            ).first(),
        )

        val engine = CameraGuidanceEngine(2, 1)
        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            engine.evaluate(
                frame(.50),
                locked,
                profile,
                TrackingObservationContext(ViewClass.SIDE_OBLIQUE),
                prior,
            ),
        )
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            engine.evaluate(
                frame(.50),
                locked,
                profile,
                TrackingObservationContext(ViewClass.SIDE_OBLIQUE),
                prior,
            ),
        )
    }

    @Test fun unstableStaleOrMismatchedPriorFailsClosed() {
        assertNull(
            PersonalCameraPriorCodec.resolve(
                calibration(
                    sessions = 2,
                    confidence = .90,
                ),
                profile,
                "dumbbell",
                ViewClass.SIDE,
            )
        )
        assertNull(
            PersonalCameraPriorCodec.resolve(
                calibration(
                    sessions = 5,
                    confidence = .60,
                ),
                profile,
                "dumbbell",
                ViewClass.SIDE,
            )
        )
        assertNull(
            PersonalCameraPriorCodec.resolve(
                calibration(),
                profile,
                "smith",
                ViewClass.SIDE,
            )
        )
        assertNull(
            PersonalCameraPriorCodec.resolve(
                calibration(),
                profile(semanticHash = "camera-v2"),
                "dumbbell",
                ViewClass.SIDE,
            )
        )
    }

    @Test fun personalizedAccelerationRequiresExactKnownFill() {
        val engine = CameraGuidanceEngine(2, 1)
        val prior = stablePrior()

        assertEquals(
            CameraGuidanceAction.CANNOT_ASSESS,
            engine.evaluate(frame(.35), locked, profile, side, prior),
        )
        assertEquals(
            CameraGuidanceAction.CAMERA_READY,
            engine.evaluate(frame(.35), locked, profile, side, prior),
        )
    }

    private fun stablePrior(): PersonalCameraPrior =
        requireNotNull(
            PersonalCameraPriorCodec.resolve(
                calibration(),
                profile,
                "dumbbell",
                ViewClass.SIDE,
            )
        )

    private fun calibration(
        sessions: Int = 5,
        confidence: Double = .90,
    ): PersonalCalibrationProfile =
        PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 4,
            sourceConfidence = .80,
            cameraSetupPreferences = PersonalCameraPriorCodec.entries(
                profile = profile,
                equipmentProfileId = "dumbbell",
                viewClass = ViewClass.SIDE,
                targetFrameFill = .50,
                tolerance = .04,
                sessionCount = sessions,
                confidence = confidence,
            ),
            evidenceReferences = setOf("setup-history"),
        )

    private fun profile(
        semanticHash: String = "camera-v1",
    ) = CameraProfile(
        profileId = "test-camera",
        profileVersion = 1,
        semanticHash = semanticHash,
        preferredViewClass = ViewClass.SIDE,
        allowedViewClasses = setOf(
            ViewClass.SIDE,
            ViewClass.SIDE_OBLIQUE,
        ),
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

    private fun frame(
        fill: Double,
        missing: PoseLandmarkId? = null,
    ): PoseFrame {
        val left = .5 - fill / 2
        val right = .5 + fill / 2
        val top = .5 - fill / 2
        val bottom = .5 + fill / 2

        fun landmark(
            id: PoseLandmarkId,
            x: Double,
            y: Double,
        ) = PoseLandmarkObservation(
            id,
            PoseCoordinate3d(x, y, 0.0),
            .90,
            .90,
        )

        return PoseFrame(
            frameId = 1,
            timestampUs = 1_000,
            width = 1080,
            height = 1920,
            source = PoseFrameSource.VIDEO,
            candidates = listOf(
                PoseSubjectCandidate(
                    0,
                    buildMap {
                        if (missing != PoseLandmarkId.LEFT_SHOULDER) {
                            put(
                                PoseLandmarkId.LEFT_SHOULDER,
                                landmark(
                                    PoseLandmarkId.LEFT_SHOULDER,
                                    left,
                                    top,
                                ),
                            )
                        }
                        if (missing != PoseLandmarkId.RIGHT_SHOULDER) {
                            put(
                                PoseLandmarkId.RIGHT_SHOULDER,
                                landmark(
                                    PoseLandmarkId.RIGHT_SHOULDER,
                                    right,
                                    top,
                                ),
                            )
                        }
                        if (missing != PoseLandmarkId.LEFT_HIP) {
                            put(
                                PoseLandmarkId.LEFT_HIP,
                                landmark(
                                    PoseLandmarkId.LEFT_HIP,
                                    left,
                                    bottom,
                                ),
                            )
                        }
                        if (missing != PoseLandmarkId.RIGHT_HIP) {
                            put(
                                PoseLandmarkId.RIGHT_HIP,
                                landmark(
                                    PoseLandmarkId.RIGHT_HIP,
                                    right,
                                    bottom,
                                ),
                            )
                        }
                    },
                )
            ),
        )
    }
}
