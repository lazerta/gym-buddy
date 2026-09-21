package com.gymbuddy.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseAnalysisTest {

    @Test
    fun wireMapKeepsPoseShape() {
        val point = PosePoint(
            x = 0.1,
            y = 0.2,
            z = -0.3,
            visibility = 0.9,
            presence = 0.8,
        )

        val result = PoseAnalysis(
            mediaPipeTimestampMs = 33,
            normalizedPoses =
                listOf(listOf(point)),
            worldPoses =
                listOf(listOf(point)),
        )

        val wire = result.toWireMap()

        assertEquals(
            33L,
            wire["mediapipe_timestamp_ms"]
        )
        assertEquals(
            1,
            wire["pose_count"]
        )
        assertEquals(
            1,
            (wire["landmarks"] as List<*>).size
        )
        assertEquals(
            1,
            (wire["world_landmarks"] as List<*>).size
        )
    }
}
