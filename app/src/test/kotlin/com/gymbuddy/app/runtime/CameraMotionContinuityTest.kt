package com.gymbuddy.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMotionContinuityTest {
    @Test
    fun estimatorUsesSpatialChangeRatherThanGlobalExposureShift(){
        val estimator=CameraMotionContinuityEstimator(changedPixelDelta=40)
        assertNull(
            estimator.update(
                CameraMotionSample(intArrayOf(10,20,30,40))
            )
        )
        assertEquals(
            0.0,
            requireNotNull(
                estimator.update(
                    CameraMotionSample(intArrayOf(60,70,80,90))
                )
            ),
            0.0,
        )
        val changed=requireNotNull(
            estimator.update(
                CameraMotionSample(intArrayOf(150,20,170,30))
            )
        )
        assertTrue(changed>=.55)
    }

    @Test
    fun localizedBorderMotionDoesNotLookLikeWholeCameraMovement(){
        val estimator=CameraMotionContinuityEstimator(changedPixelDelta=30)
        val base=CameraMotionSample(
            intArrayOf(10,20,30,40,50,60,70,80)
        )
        assertNull(estimator.update(base))
        val score=requireNotNull(
            estimator.update(
                CameraMotionSample(
                    intArrayOf(200,210,30,40,50,60,70,80)
                )
            )
        )
        assertTrue(score<.55)
    }

    @Test
    fun slowSpatialDriftAccumulatesAgainstStableReferenceThenResetsEpoch(){
        val estimator=CameraMotionContinuityEstimator(
            changedPixelDelta=25,
            materialChangeFraction=.55,
        )
        assertNull(
            estimator.update(
                CameraMotionSample(
                    intArrayOf(0,0,0,0,255,255,255,255)
                )
            )
        )
        val first=requireNotNull(
            estimator.update(
                CameraMotionSample(
                    intArrayOf(10,10,10,10,245,245,245,245)
                )
            )
        )
        assertTrue(first<.55)

        val accumulated=requireNotNull(
            estimator.update(
                CameraMotionSample(
                    intArrayOf(30,30,30,30,225,225,225,225)
                )
            )
        )
        assertTrue(accumulated>=.55)

        val stableAfterChange=requireNotNull(
            estimator.update(
                CameraMotionSample(
                    intArrayOf(30,30,30,30,225,225,225,225)
                )
            )
        )
        assertEquals(0.0,stableAfterChange,0.0)
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
