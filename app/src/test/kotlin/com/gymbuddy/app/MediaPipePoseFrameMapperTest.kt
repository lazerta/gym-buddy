package com.gymbuddy.app

import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipePoseFrameMapperTest {
    @Test
    fun mapsMultipleSubjectCandidatesWithoutInventingIdentity() {
        val frame = MediaPipePoseFrameMapper.map(
            frameId = 7,
            timestampUs = 33_333,
            width = 1280,
            height = 720,
            source = PoseFrameSource.SIMULATOR,
            normalizedPoses = listOf(pose(0.1), pose(0.6)),
            worldPoses = listOf(pose(1.1), pose(1.6)),
        )

        assertEquals(2, frame.candidates.size)
        assertEquals(0, frame.candidates[0].candidateIndex)
        assertEquals(1, frame.candidates[1].candidateIndex)
        assertNull(frame.candidates[0].confidence)
        assertEquals(
            0.1,
            frame.candidates[0].normalized(PoseLandmarkId.NOSE)!!.position.x,
            0.0,
        )
        assertEquals(
            1.6,
            frame.candidates[1].world(PoseLandmarkId.NOSE)!!.position.x,
            0.0,
        )
    }

    @Test
    fun missingWorldPoseAndMissingLandmarksStayMissing() {
        val normalized = pose(0.2).take(12)
        val frame = MediaPipePoseFrameMapper.map(
            frameId = 1,
            timestampUs = 10_000,
            width = 640,
            height = 480,
            source = PoseFrameSource.CAMERA,
            normalizedPoses = listOf(normalized),
            worldPoses = emptyList(),
        )

        val candidate = frame.candidates.single()
        assertTrue(candidate.worldLandmarks.isEmpty())
        assertTrue(PoseLandmarkId.LEFT_SHOULDER in candidate.normalizedLandmarks)
        assertFalse(PoseLandmarkId.RIGHT_SHOULDER in candidate.normalizedLandmarks)
    }

    @Test
    fun lowConfidenceIsPreservedAndMalformedValuesBecomeUnknownOrMissing() {
        val raw = pose(0.2).toMutableList()
        raw[0] = raw[0].copy(visibility = 0.05, presence = 0.07)
        raw[1] = raw[1].copy(visibility = 4.0, presence = Double.NaN)
        raw[2] = raw[2].copy(x = Double.NaN)

        val candidate = MediaPipePoseFrameMapper.map(
            frameId = 1,
            timestampUs = 10_000,
            width = 640,
            height = 480,
            source = PoseFrameSource.CAMERA,
            normalizedPoses = listOf(raw),
            worldPoses = emptyList(),
        ).candidates.single()

        val nose = candidate.normalized(PoseLandmarkId.NOSE)!!
        assertEquals(0.05, nose.visibility!!, 0.0)
        assertEquals(0.07, nose.presence!!, 0.0)
        assertNull(candidate.normalized(PoseLandmarkId.LEFT_EYE_INNER)!!.visibility)
        assertNull(candidate.normalized(PoseLandmarkId.LEFT_EYE_INNER)!!.presence)
        assertNull(candidate.normalized(PoseLandmarkId.LEFT_EYE))
    }

    @Test
    fun wireEncodingRemainsHarnessCompatibleAndContainsNoPrivilegedTruth() {
        val frame = MediaPipePoseFrameMapper.map(
            frameId = 4,
            timestampUs = 66_667,
            width = 640,
            height = 480,
            source = PoseFrameSource.SIMULATOR,
            normalizedPoses = listOf(pose(0.3)),
            worldPoses = listOf(pose(1.3)),
        )

        val wire = PoseFrameWireEncoder.encode(frame)

        assertEquals(66L, wire["mediapipe_timestamp_ms"])
        assertEquals(1, wire["pose_count"])
        assertTrue(wire.containsKey("landmarks"))
        assertTrue(wire.containsKey("world_landmarks"))
        setOf(
            "ground_truth",
            "ground_truth_path",
            "qpos",
            "rep_windows",
            "form_label",
            "oracle_classification",
        ).forEach { forbidden ->
            assertFalse(wire.containsKey(forbidden))
        }
    }

    private fun pose(baseX: Double): List<PoseAdapterLandmark> =
        List(PoseLandmarkId.entries.size) { index ->
            PoseAdapterLandmark(
                x = baseX + index * 0.001,
                y = 0.2 + index * 0.001,
                z = -0.1,
                visibility = 0.9,
                presence = 0.8,
            )
        }
}
