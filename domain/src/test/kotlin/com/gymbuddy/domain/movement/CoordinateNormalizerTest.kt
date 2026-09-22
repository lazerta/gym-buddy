package com.gymbuddy.domain.movement

import com.gymbuddy.domain.pose.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateNormalizerTest {
    @Test fun sameMotionAtDifferentFrameScalesProducesSameBodyLocalCoordinates() {
        val normalizer = CoordinateNormalizer()
        val small = valid(normalizer.normalize(person(scale = 0.7)))
        val large = valid(normalizer.normalize(person(scale = 1.4)))
        val ids = small.landmarks.keys.intersect(large.landmarks.keys)
        ids.forEach { id ->
            assertEquals(small.landmarks.getValue(id).x, large.landmarks.getValue(id).x, 1e-9)
            assertEquals(small.landmarks.getValue(id).y, large.landmarks.getValue(id).y, 1e-9)
        }
    }

    @Test fun cameraDistanceChangeWithinToleranceDoesNotChangeNormalizedGeometry() {
        val normalizer = CoordinateNormalizer()
        val a = valid(normalizer.normalize(person(scale = 1.0, centerX = .45, centerY = .48)))
        val b = valid(normalizer.normalize(person(scale = 1.18, centerX = .53, centerY = .51)))
        assertEquals(a.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).x, b.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).x, 1e-9)
        assertEquals(a.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).y, b.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).y, 1e-9)
    }

    @Test fun mirroredImagePreservesPhysicalLeftRightIdsAndLocalSemantics() {
        val normalizer = CoordinateNormalizer()
        val original = valid(normalizer.normalize(person()))
        val mirroredCandidate = mirrorX(person())
        val mirrored = valid(normalizer.normalize(mirroredCandidate))
        assertTrue(original.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).x < 0.0)
        assertTrue(original.landmarks.getValue(PoseLandmarkId.RIGHT_WRIST).x > 0.0)
        assertTrue(mirrored.landmarks.getValue(PoseLandmarkId.LEFT_WRIST).x < 0.0)
        assertTrue(mirrored.landmarks.getValue(PoseLandmarkId.RIGHT_WRIST).x > 0.0)
    }

    @Test fun partialNonAnchorLandmarksRemainAbsentWithoutFabrication() {
        val candidate = person().copy(
            normalizedLandmarks = person().normalizedLandmarks - PoseLandmarkId.LEFT_WRIST
        )
        val pose = valid(CoordinateNormalizer().normalize(candidate))
        assertFalse(PoseLandmarkId.LEFT_WRIST in pose.landmarks)
        assertTrue(PoseLandmarkId.RIGHT_WRIST in pose.landmarks)
    }

    @Test fun missingTorsoAnchorReturnsUnknown() {
        val candidate = person().copy(
            normalizedLandmarks = person().normalizedLandmarks - PoseLandmarkId.LEFT_HIP
        )
        val result = CoordinateNormalizer().normalize(candidate)
        assertEquals(
            NormalizationUnknownReason.MISSING_TORSO_ANCHORS,
            (result as CoordinateNormalizationResult.Unknown).reason,
        )
    }

    @Test fun invalidDegenerateBodyBasisReturnsUnknown() {
        val candidate = person().let { p ->
            val same = PoseCoordinate3d(.5, .5, 0.0)
            p.copy(normalizedLandmarks = p.normalizedLandmarks.mapValues { (id, lm) ->
                if (id in setOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER)) {
                    lm.copy(position = same)
                } else lm
            })
        }
        val result = CoordinateNormalizer().normalize(candidate)
        assertTrue(result is CoordinateNormalizationResult.Unknown)
    }

    private fun valid(result: CoordinateNormalizationResult): NormalizedPose =
        (result as CoordinateNormalizationResult.Valid).pose

    private fun person(
        scale: Double = 1.0,
        centerX: Double = .5,
        centerY: Double = .5,
    ): PoseSubjectCandidate {
        fun q(dx: Double, dy: Double) = PoseCoordinate3d(centerX + dx * scale, centerY + dy * scale, 0.0)
        val points = mapOf(
            PoseLandmarkId.LEFT_SHOULDER to q(-.08, -.10),
            PoseLandmarkId.RIGHT_SHOULDER to q(.08, -.10),
            PoseLandmarkId.LEFT_HIP to q(-.06, .10),
            PoseLandmarkId.RIGHT_HIP to q(.06, .10),
            PoseLandmarkId.LEFT_ELBOW to q(-.15, -.01),
            PoseLandmarkId.RIGHT_ELBOW to q(.15, -.01),
            PoseLandmarkId.LEFT_WRIST to q(-.22, .08),
            PoseLandmarkId.RIGHT_WRIST to q(.22, .08),
        )
        return PoseSubjectCandidate(0, points.mapValues { (id, pos) ->
            PoseLandmarkObservation(id, pos, .9, .9)
        })
    }

    private fun mirrorX(candidate: PoseSubjectCandidate): PoseSubjectCandidate = candidate.copy(
        normalizedLandmarks = candidate.normalizedLandmarks.mapValues { (_, lm) ->
            lm.copy(position = lm.position.copy(x = 1.0 - lm.position.x))
        }
    )
}
