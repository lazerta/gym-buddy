package com.gymbuddy.domain.coaching

import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.profile.CuePolicy
import java.util.ArrayDeque

data class CueEvent(val cueId:String,val ruleId:String,val repId:String,val emittedAtUs:Long,val severity:String)
enum class CueResponseState { IMPROVED, PERSISTED, UNKNOWN }
data class CueResponse(val cueId:String,val repId:String,val state:CueResponseState)

data class CueDecision(val cues:List<CueEvent>,val responses:List<CueResponse>)

class CueEngine(private val policy:CuePolicy){
    private val history=mutableMapOf<String,ArrayDeque<FormObservationState>>()
    private val lastCueAt=mutableMapOf<String,Long>()
    private val repeatCount=mutableMapOf<String,Int>()
    private val pending=mutableMapOf<String,CueEvent>()
    fun reset(){history.clear();lastCueAt.clear();repeatCount.clear();pending.clear()}
    fun onInterruption(){history.clear();pending.clear()}
    fun evaluate(rep:RepEvidence,observations:List<FormObservation>):CueDecision{
        val responses=mutableListOf<CueResponse>();val cues=mutableListOf<CueEvent>()
        observations.forEach{o->pending.remove(o.ruleId)?.let{cue->responses+=CueResponse(cue.cueId,rep.repId,when(o.state){FormObservationState.OK->CueResponseState.IMPROVED;FormObservationState.DEVIATION->CueResponseState.PERSISTED;FormObservationState.UNKNOWN->CueResponseState.UNKNOWN})}}
        observations.forEach{o->
            val q=history.getOrPut(o.ruleId){ArrayDeque()};q.addLast(o.state);while(q.size>policy.persistenceWindowReps)q.removeFirst()
            if(o.state!=FormObservationState.DEVIATION)return@forEach
            val occurrences=q.count{it==FormObservationState.DEVIATION};if(occurrences<policy.requiredOccurrences)return@forEach
            val last=lastCueAt[o.ruleId];if(last!=null&&rep.completedAtUs-last<policy.cooldownMs*1000L)return@forEach
            val count=repeatCount[o.ruleId]?:0;if(count>=policy.maxRepeatedIdenticalCues)return@forEach
            val cue=CueEvent("cue-${o.ruleId}-${rep.completedAtUs}",o.ruleId,rep.repId,rep.completedAtUs,o.severity.name);cues+=cue;pending[o.ruleId]=cue;lastCueAt[o.ruleId]=rep.completedAtUs;repeatCount[o.ruleId]=count+1
        }
        return CueDecision(cues,responses)
    }
}
