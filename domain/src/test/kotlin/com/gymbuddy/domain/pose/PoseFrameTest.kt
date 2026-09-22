package com.gymbuddy.domain.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFrameTest {
    @Test
    fun missingLandmarksRemainAbsentRatherThanFabricated() {
        val candidate = PoseSubjectCandidate(
            candidateIndex = 0,
            normalizedLandmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to landmark(PoseLandmarkId.LEFT_SHOULDER),
            ),
        )

        assertNull(candidate.normalized(PoseLandmarkId.LEFT_ELBOW))
        assertTrue(candidate.worldLandmarks.isEmpty())
        assertNull(candidate.confidence)
    }

    @Test
    fun lowConfidenceLandmarksArePreservedForLaterQualityGating() {
        val low = PoseLandmarkObservation(
            landmarkId = PoseLandmarkId.LEFT_WRIST,
            position = PoseCoordinate3d(0.2, 0.3, -0.1),
            visibility = 0.08,
            presence = 0.12,
        )

        assertEquals(0.08, low.visibility!!, 0.0)
        assertEquals(0.12, low.presence!!, 0.0)
    }

    @Test
    fun timestampGuardRejectsDuplicateAndOutOfOrderFrames() {
        val guard = PoseFrameTimestampGuard()
        guard.accept(frame(timestampUs = 1_000L))
        guard.accept(frame(timestampUs = 2_000L))

        assertThrows(IllegalArgumentException::class.java) {
            guard.accept(frame(timestampUs = 2_000L))
        }

        guard.reset()
        guard.accept(frame(timestampUs = 5_000L))
        assertThrows(IllegalArgumentException::class.java) {
            guard.accept(frame(timestampUs = 4_999L))
        }
    }

    @Test
    fun poseFrameCarriesOnlyProductionObservationFields() {
        val fieldNames = PoseFrame::class.java.declaredFields.map { it.name }.toSet()
        val candidateFieldNames = PoseSubjectCandidate::class.java.declaredFields.map { it.name }.toSet()
        val forbidden = setOf(
            "groundTruth",
            "ground_truth",
            "repWindow",
            "rep_windows",
            "qpos",
            "formLabel",
            "oracleClassification",
            "groundTruthPath",
        )

        assertTrue(fieldNames.intersect(forbidden).isEmpty())
        assertTrue(candidateFieldNames.intersect(forbidden).isEmpty())
    }

    private fun landmark(id: PoseLandmarkId) = PoseLandmarkObservation(
        landmarkId = id,
        position = PoseCoordinate3d(0.1, 0.2, -0.3),
        visibility = 0.9,
        presence = 0.8,
    )

    private fun frame(timestampUs: Long) = PoseFrame(
        frameId = timestampUs,
        timestampUs = timestampUs,
        width = 640,
        height = 480,
        source = PoseFrameSource.CAMERA,
        candidates = emptyList(),
    )
}
