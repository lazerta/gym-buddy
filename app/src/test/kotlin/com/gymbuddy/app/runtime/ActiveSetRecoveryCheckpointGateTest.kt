package com.gymbuddy.app.runtime

import com.gymbuddy.domain.lifecycle.SetLifecycleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSetRecoveryCheckpointGateTest {
    @Test
    fun setupWaitingAndArmedStatesNeverPersistActiveSetRecovery(){
        val gate=ActiveSetRecoveryCheckpointGate()
        assertFalse(gate.shouldPersist(SetLifecycleState.CAMERA_GUIDANCE))
        assertFalse(gate.shouldPersist(SetLifecycleState.CAMERA_READY))
        assertFalse(gate.shouldPersist(SetLifecycleState.WAITING))
        assertFalse(gate.shouldPersist(SetLifecycleState.ARMED))
    }

    @Test
    fun trueActiveAttemptPersistsExactlyOnce(){
        val gate=ActiveSetRecoveryCheckpointGate()
        assertTrue(gate.shouldPersist(SetLifecycleState.ACTIVE_SET))
        assertFalse(gate.shouldPersist(SetLifecycleState.ACTIVE_SET))
        assertFalse(gate.shouldPersist(SetLifecycleState.POSSIBLE_END))
        assertFalse(gate.shouldPersist(SetLifecycleState.FINALIZING))
    }

    @Test
    fun possibleEndOrFinalizingCanPersistIfFirstObservedStateAfterRestartRace(){
        val possible=ActiveSetRecoveryCheckpointGate()
        assertTrue(possible.shouldPersist(SetLifecycleState.POSSIBLE_END))

        val finalizing=ActiveSetRecoveryCheckpointGate()
        assertTrue(finalizing.shouldPersist(SetLifecycleState.FINALIZING))
    }
}
