package com.gymbuddy.app

import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseAnalysisTest {
    @Test
    fun wireMapKeepsLegacyHarnessPoseShape() {
        val pose = List(PoseLandmarkId.entries.size) { index ->
            PoseAdapterLandmark(
                x = 0.1 + index * 0.001,
                y = 0.2,
                z = -0.3,
                visibility = 0.9,
                presence = 0.8,
            )
        }
        val frame = MediaPipePoseFrameMapper.map(
            frameId = 1,
            timestampUs = 33_333,
            width = 640,
            height = 480,
            source = PoseFrameSource.SIMULATOR,
            normalizedPoses = listOf(pose),
            worldPoses = listOf(pose),
        )

        val wire = PoseFrameWireEncoder.encode(frame)

        assertEquals(33L, wire["mediapipe_timestamp_ms"])
        assertEquals(1, wire["pose_count"])
        assertEquals(1, (wire["landmarks"] as List<*>).size)
        assertEquals(1, (wire["world_landmarks"] as List<*>).size)
    }
}
