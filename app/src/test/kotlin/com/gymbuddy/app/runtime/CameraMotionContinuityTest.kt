package com.gymbuddy.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraMotionContinuityTest {
    @Test
    fun estimatorUsesFrameToFrameBorderChangeFraction(){
        val estimator=CameraMotionContinuityEstimator(changedPixelDelta=40)
        assertNull(estimator.update(CameraMotionSample(intArrayOf(10,20,30,40))))
        assertEquals(
            0.0,
            estimator.update(CameraMotionSample(intArrayOf(12,18,35,41))),
            0.0,
        )
        assertEquals(
            .75,
            estimator.update(CameraMotionSample(intArrayOf(100,90,35,120))),
            0.0,
        )
    }

    @Test
    fun signalStoreIsTimestampScopedAndConsumedOnce(){
        val store=CameraMotionSignalStore()
        store.publish(100L,.80)
        store.publish(200L,.20)
        assertEquals(.80,store.consume(100L)!!,0.0)
        assertNull(store.consume(100L))
        assertEquals(.20,store.consume(200L)!!,0.0)
    }
}
