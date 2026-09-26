package com.gymbuddy.domain.persistence

/** Persisted elapsed-realtime timestamp; comparable only within the same known boot. */
data class RestClockAnchor(val elapsedRealtimeMs:Long,val bootId:String?){
    init { require(elapsedRealtimeMs>=0); require(bootId==null||bootId.isNotBlank()) }
}

/** A display anchor is established once, never by repeatedly subtracting wall clocks. */
data class RestTimerAnchor(
    val elapsedAtAnchorMs:Long,
    val anchorElapsedMs:Long,
    val estimated:Boolean=false,
){
    init { require(elapsedAtAnchorMs>=0);require(anchorElapsedMs>=0) }
    fun elapsedMs(nowElapsedMs:Long):Long{
        val delta=(nowElapsedMs-anchorElapsedMs).coerceAtLeast(0L)
        return if(delta>Long.MAX_VALUE-elapsedAtAnchorMs)Long.MAX_VALUE else elapsedAtAnchorMs+delta
    }
    companion object{
        fun restore(
            startedAtEpochMs:Long,
            saved:RestClockAnchor?,
            nowEpochMs:Long,
            nowElapsedMs:Long,
            bootId:String?,
        ):RestTimerAnchor{
            require(nowElapsedMs>=0&&nowEpochMs>=0&&startedAtEpochMs>=0)
            if(saved!=null&&bootId!=null&&saved.bootId==bootId&&nowElapsedMs>=saved.elapsedRealtimeMs){
                return RestTimerAnchor(0,saved.elapsedRealtimeMs)
            }
            // Reboot, legacy row or unavailable boot identity: reconstruct ONCE
            // from wall time and label the estimate. Subsequent ticks are monotonic.
            return RestTimerAnchor((nowEpochMs-startedAtEpochMs).coerceAtLeast(0),nowElapsedMs,true)
        }
    }
}
