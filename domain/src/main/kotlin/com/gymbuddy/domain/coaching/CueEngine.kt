package com.gymbuddy.domain.coaching

import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.profile.*
import java.util.ArrayDeque
import kotlin.math.max

 data class CueEvent(val cueId:String,val ruleId:String,val repId:String,val emittedAtUs:Long,val severity:String)
enum class CueResponseState { IMPROVED,UNCHANGED,WORSENED,UNKNOWN }
data class CueResponse(val cueId:String,val repId:String,val state:CueResponseState)
data class CueDecision(val cues:List<CueEvent>,val responses:List<CueResponse>)

class CueEngine(private val policy:CuePolicy, rules:FormRuleSet?=null,private val idNamespace:String?=null){
    private data class Pending(val cue:CueEvent,val observation:FormObservation)
    private val ruleById=rules?.rules?.associateBy{it.ruleId}.orEmpty()
    private val history=mutableMapOf<String,ArrayDeque<FormObservationState>>()
    private val lastCueAt=mutableMapOf<String,Long>()
    private val repeatCount=mutableMapOf<String,Int>()
    private var pending:Pending?=null
    private var pendingInterrupted=false

    fun reset(){history.clear();lastCueAt.clear();repeatCount.clear();pending=null;pendingInterrupted=false}
    fun onInterruption(){history.clear();if(pending!=null)pendingInterrupted=true}

    fun evaluate(rep:RepEvidence,observations:List<FormObservation>):CueDecision{
        val responses=mutableListOf<CueResponse>()
        val p=pending
        if(p!=null){
            val post=observations.firstOrNull{it.ruleId==p.cue.ruleId}
            val state=if(pendingInterrupted||post==null)CueResponseState.UNKNOWN else classifyResponse(p.observation,post,ruleById[p.cue.ruleId])
            responses+=CueResponse(p.cue.cueId,rep.repId,state)
            if(state==CueResponseState.IMPROVED)repeatCount[p.cue.ruleId]=0
            pending=null;pendingInterrupted=false
        }

        observations.forEach{o->
            val q=history.getOrPut(o.ruleId){ArrayDeque()};q.addLast(o.state);while(q.size>policy.persistenceWindowReps)q.removeFirst()
        }
        val eligible=observations.filter{o->
            if(o.state!=FormObservationState.DEVIATION)return@filter false
            val q=history[o.ruleId]?:return@filter false
            if(q.count{it==FormObservationState.DEVIATION}<policy.requiredOccurrences)return@filter false
            val last=lastCueAt[o.ruleId];if(last!=null&&rep.completedAtUs-last<policy.cooldownMs*1000L)return@filter false
            (repeatCount[o.ruleId]?:0)<policy.maxRepeatedIdenticalCues
        }
        val selected=eligible.maxWithOrNull(compareBy<FormObservation>{severityRank(it.severity)}.thenBy{it.ruleId})
        val cues=if(selected==null)emptyList() else {
            val baseId="cue-${selected.ruleId}-${rep.completedAtUs}"\n            val cueId=idNamespace?.let{it+"/"+baseId}?:baseId\n            val cue=CueEvent(cueId,selected.ruleId,rep.repId,rep.completedAtUs,selected.severity.name)
            pending=Pending(cue,selected);lastCueAt[selected.ruleId]=rep.completedAtUs;repeatCount[selected.ruleId]=(repeatCount[selected.ruleId]?:0)+1
            listOf(cue)
        }
        return CueDecision(cues,responses)
    }

    private fun classifyResponse(pre:FormObservation,post:FormObservation,rule:FormRule?):CueResponseState{
        if(post.state==FormObservationState.UNKNOWN)return CueResponseState.UNKNOWN
        if(post.state==FormObservationState.OK)return CueResponseState.IMPROVED
        if(rule==null||pre.evidenceValue==null||post.evidenceValue==null||rule.threshold==null)return CueResponseState.UNKNOWN
        val before=violationMagnitude(rule,pre.evidenceValue);val after=violationMagnitude(rule,post.evidenceValue);val eps=1e-9
        return when{after<before-eps->CueResponseState.IMPROVED;after>before+eps->CueResponseState.WORSENED;else->CueResponseState.UNCHANGED}
    }
    private fun violationMagnitude(rule:FormRule,value:Double):Double=when(rule.comparison){FormComparison.MIN_VALUE->max(0.0,rule.threshold!!-value);else->max(0.0,value-rule.threshold!!)}
    private fun severityRank(s:FormRuleSeverity)=when(s){FormRuleSeverity.INFO->0;FormRuleSeverity.MINOR->1;FormRuleSeverity.MAJOR->2}
}
