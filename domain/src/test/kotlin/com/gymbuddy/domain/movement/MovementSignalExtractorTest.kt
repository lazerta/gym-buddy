package com.gymbuddy.domain.movement

import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementSignalExtractorTest {
    @Test fun deterministicJointAngleFixtureIsNinetyDegrees() {
        val pose = pose(
            PoseLandmarkId.LEFT_SHOULDER to point(-1.0, 0.0),
            PoseLandmarkId.LEFT_ELBOW to point(0.0, 0.0),
            PoseLandmarkId.LEFT_WRIST to point(0.0, 1.0),
        )
        val profile = SignalProfile("signals", 1, "hash", listOf(
            SignalDefinition(
                "elbow_angle", SignalKind.JOINT_ANGLE, SignalUnit.DEGREES,
                setOf("left_shoulder", "left_elbow", "left_wrist"),
                orderedLandmarkIds = listOf("left_shoulder", "left_elbow", "left_wrist"),
            )
        ))
        val value = MovementSignalExtractor().extract(0, pose, profile).values.getValue("elbow_angle")
        assertEquals(90.0, value.value!!, 1e-9)
    }

    @Test fun missingDataPropagatesUnknown() {
        val profile = displacementProfile("left_wrist", axis = 1.0)
        val value = MovementSignalExtractor().extract(0, pose(), profile).values.getValue("progress")
        assertNull(value.value)
        assertEquals(SignalUnknownReason.MISSING_LANDMARK, value.unknownReason)
    }

    @Test fun lowConfidencePropagatesUnknown() {
        val p = pose(PoseLandmarkId.LEFT_WRIST to point(0.1, 0.4, confidence = 0.2))
        val profile = SignalProfile("signals", 1, "hash", listOf(
            SignalDefinition("progress", SignalKind.ROM_PROXY, SignalUnit.NORMALIZED,
                setOf("left_wrist"), parameters = mapOf("axis" to 1.0, "min_signal_confidence" to 0.6))
        ))
        val value = MovementSignalExtractor().extract(0, p, profile).values.getValue("progress")
        assertEquals(SignalUnknownReason.LOW_CONFIDENCE, value.unknownReason)
    }

    @Test fun directionSignUsesTemporalHistory() {
        val extractor = MovementSignalExtractor()
        val profile = SignalProfile("signals", 1, "hash", listOf(
            SignalDefinition("vertical_direction", SignalKind.DIRECTION, SignalUnit.UNITLESS,
                setOf("left_wrist"), parameters = mapOf("axis" to 1.0))
        ))
        assertEquals(SignalUnknownReason.INSUFFICIENT_HISTORY,
            extractor.extract(0, pose(PoseLandmarkId.LEFT_WRIST to point(0.0, 0.1)), profile)
                .values.getValue("vertical_direction").unknownReason)
        assertEquals(1.0,
            extractor.extract(100_000, pose(PoseLandmarkId.LEFT_WRIST to point(0.0, 0.2)), profile)
                .values.getValue("vertical_direction").value!!, 0.0)
        assertEquals(-1.0,
            extractor.extract(200_000, pose(PoseLandmarkId.LEFT_WRIST to point(0.0, 0.15)), profile)
                .values.getValue("vertical_direction").value!!, 0.0)
    }

    @Test fun smoothingReducesSingleFrameNoise() {
        val extractor = MovementSignalExtractor()
        val profile = SignalProfile("signals", 1, "hash", listOf(
            SignalDefinition("progress", SignalKind.ROM_PROXY, SignalUnit.NORMALIZED,
                setOf("left_wrist"), parameters = mapOf("axis" to 1.0, "smoothing_alpha" to 0.2))
        ))
        val first = extractor.extract(0, pose(PoseLandmarkId.LEFT_WRIST to point(0.0, 0.0)), profile)
            .values.getValue("progress").value!!
        val noisy = extractor.extract(100_000, pose(PoseLandmarkId.LEFT_WRIST to point(0.0, 1.0)), profile)
            .values.getValue("progress").value!!
        assertEquals(0.0, first, 0.0)
        assertEquals(0.2, noisy, 1e-9)
    }

    @Test fun profileScaleAndOffsetCanNormalizeRawAnglesWithoutExerciseBranching() {
        val p = pose(
            PoseLandmarkId.LEFT_SHOULDER to point(-1.0, 0.0),
            PoseLandmarkId.LEFT_ELBOW to point(0.0, 0.0),
            PoseLandmarkId.LEFT_WRIST to point(0.0, 1.0),
        )
        val profile = SignalProfile("signals", 1, "hash", listOf(
            SignalDefinition(
                "progress", SignalKind.JOINT_ANGLE, SignalUnit.DEGREES,
                setOf("left_shoulder", "left_elbow", "left_wrist"),
                parameters = mapOf("scale" to -0.01, "offset" to 1.8),
                orderedLandmarkIds = listOf("left_shoulder", "left_elbow", "left_wrist"),
            )
        ))
        val value = MovementSignalExtractor().extract(0, p, profile).values.getValue("progress")
        assertEquals(0.9, value.value!!, 1e-9)
    }

    @Test fun signalMappingIsProfileDeclaredNotExerciseNameBased() {
        val p = pose(
            PoseLandmarkId.LEFT_WRIST to point(-0.4, 0.7),
            PoseLandmarkId.RIGHT_WRIST to point(0.5, 0.2),
        )
        val leftY = MovementSignalExtractor().extract(0, p, displacementProfile("left_wrist", 1.0))
            .values.getValue("progress").value!!
        val rightX = MovementSignalExtractor().extract(0, p, displacementProfile("right_wrist", 0.0))
            .values.getValue("progress").value!!
        assertEquals(0.7, leftY, 0.0)
        assertEquals(0.5, rightX, 0.0)
        assertTrue(leftY != rightX)
    }

    private fun displacementProfile(landmark: String, axis: Double) = SignalProfile(
        "signals-$landmark-$axis", 1, "hash-$landmark-$axis", listOf(
            SignalDefinition("progress", SignalKind.ROM_PROXY, SignalUnit.NORMALIZED,
                setOf(landmark), parameters = mapOf("axis" to axis))
        )
    )

    private fun point(x: Double, y: Double, confidence: Double = 0.9) = BodyLocalLandmark(
        PoseLandmarkId.LEFT_WRIST, x, y, 0.0, confidence, confidence
    )

    private fun pose(vararg entries: Pair<PoseLandmarkId, BodyLocalLandmark>): NormalizedPose {
        val map = entries.associate { (id, landmark) -> id to landmark.copy(landmarkId = id) }
        return NormalizedPose(0, 1.0, map)
    }
}
