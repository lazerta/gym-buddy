package com.gymbuddy.app.runtime

import com.gymbuddy.domain.lifecycle.SetLifecycleState

internal class ActiveSetRecoveryCheckpointGate {
    private var saved=false

    fun shouldPersist(state:SetLifecycleState):Boolean{
        if(saved)return false
        val eligible=state==SetLifecycleState.ACTIVE_SET||
            state==SetLifecycleState.POSSIBLE_END||
            state==SetLifecycleState.FINALIZING
        if(eligible)saved=true
        return eligible
    }

    fun reset(){saved=false}
}
