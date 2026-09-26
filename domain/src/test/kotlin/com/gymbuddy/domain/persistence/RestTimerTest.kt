package com.gymbuddy.domain.persistence

import org.junit.Assert.*
import org.junit.Test

class RestTimerTest {
    @Test fun sameBootUsesElapsedTimeDespiteForwardWallClockJump(){
        val timer=RestTimerAnchor.restore(100_000,RestClockAnchor(1_000,"boot-3"),3_700_000,61_000,"boot-3")
        assertEquals(60_000,timer.elapsedMs(61_000));assertFalse(timer.estimated)
    }
    @Test fun sameBootSurvivesBackwardWallClockAndProcessRecreation(){
        val timer=RestTimerAnchor.restore(100_000,RestClockAnchor(1_000,"boot-3"),50_000,61_000,"boot-3")
        assertEquals(60_000,timer.elapsedMs(61_000));assertEquals(90_000,timer.elapsedMs(91_000))
    }
    @Test fun newBootReconstructsOnceAndLabelsEstimate(){
        val timer=RestTimerAnchor.restore(100_000,RestClockAnchor(90_000,"old"),160_000,500,"new")
        assertTrue(timer.estimated);assertEquals(60_000,timer.elapsedMs(500));assertEquals(61_000,timer.elapsedMs(1_500))
    }
    @Test fun legacyOrUnknownBootCannotPretendElapsedAnchorsAreComparable(){
        listOf<RestClockAnchor?>(null,RestClockAnchor(50_000,null)).forEach{saved->
            val timer=RestTimerAnchor.restore(100_000,saved,130_000,100_000,null)
            assertTrue(timer.estimated);assertEquals(30_000,timer.elapsedMs(100_000))
        }
    }
    @Test fun elapsedResetDespiteEqualBootTokenUsesExplicitFallback(){
        val timer=RestTimerAnchor.restore(100_000,RestClockAnchor(50_000,"boot"),130_000,1_000,"boot")
        assertTrue(timer.estimated);assertEquals(30_000,timer.elapsedMs(1_000))
    }
    @Test fun negativeFallbackIsClampedWithoutClaimingPrecision(){
        val timer=RestTimerAnchor.restore(100_000,null,50_000,1_000,"boot")
        assertTrue(timer.estimated);assertEquals(0,timer.elapsedMs(1_000));assertEquals(500,timer.elapsedMs(1_500))
    }
    @Test fun sameDisplayAnchorCannotBeAffectedByWallTime(){
        val timer=RestTimerAnchor(20_000,5_000)
        assertEquals(21_000,timer.elapsedMs(6_000));assertEquals(20_000,timer.elapsedMs(4_000))
    }
    @Test fun elapsedOverflowSaturatesInsteadOfBecomingNegative(){
        assertEquals(Long.MAX_VALUE,RestTimerAnchor(Long.MAX_VALUE-5,0).elapsedMs(10))
    }
}
