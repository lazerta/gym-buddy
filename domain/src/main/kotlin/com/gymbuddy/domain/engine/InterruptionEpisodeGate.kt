package com.gymbuddy.domain.engine

class InterruptionEpisodeGate {
    private var open=false
    fun onInvalid():Boolean = if(open) false else { open=true; true }
    fun onValid(){open=false}
    fun reset(){open=false}
}
