package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.coaching.CueResponseState
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.CueDeliveryState
import com.gymbuddy.domain.persistence.PersistedSetEvidence
import com.gymbuddy.domain.profile.FormRuleSeverity

data class SetCoachingSummary(
    val focusRuleId:String?=null,
    val focusText:String="Repeat the same setup.",
    val recurringRuleIds:List<String> = emptyList(),
    val deliveredCueRuleIds:List<String> = emptyList(),
    val unknownObservationCount:Int=0,
    val resolvedRuleIds:Set<String> = emptySet(),
    val cueResponses:List<DeliveredCueResponseSummary> = emptyList(),
)

data class DeliveredCueResponseSummary(
    val cueId:String,
    val ruleId:String,
    val state:CueResponseState,
    val responseRepId:String?=null,
)

class EvidenceSummaryEngine(private val ruleText:(String)->String={it}){
    fun summarize(evidence:PersistedSetEvidence):SetCoachingSummary{
        val validReps=evidence.reps.filter{it.classification==RepClassification.NORMAL}.associateBy{it.repId}
        val deliveredIds=evidence.cueDeliveries.filter{it.state==CueDeliveryState.COMPLETED}.map{it.cueId}.toSet()
        val delivered=evidence.cues.filter{it.cueId in deliveredIds&&it.repId in validReps}
            .sortedWith(compareBy({it.emittedAtUs},{it.cueId}))
        val assessed=evidence.observations.filter{
            it.repId in validReps&&it.state!=FormObservationState.UNKNOWN&&(it.confidence?:0.0)>=.60
        }
        val tracking=evidence.tracking
        val activeFrames=tracking?.let{it.activeObservableFrames+it.activeDegradedFrames+it.activePausedFrames+it.activeUnknownFrames}?:0
        val unreliable=tracking!=null&&(tracking.interruptionEpisodes>0||tracking.cameraDisturbanceEpisodes>0||
            tracking.activePausedFrames>0||tracking.activeUnknownFrames>0||
            (activeFrames>0&&tracking.activeDegradedFrames.toDouble()/activeFrames>.20))
        val byRule=assessed.filter{it.state==FormObservationState.DEVIATION}.groupBy{it.ruleId}
        val resolved=delivered.filter{cue->
            evidence.responses.any{response->
                val rep=validReps[response.repId]
                response.cueId==cue.cueId&&response.state==CueResponseState.IMPROVED&&
                    rep!=null&&rep.completedAtUs>cue.emittedAtUs&&
                    assessed.any{it.repId==response.repId&&it.ruleId==cue.ruleId&&it.state==FormObservationState.OK}&&
                    byRule[cue.ruleId].orEmpty().none{validReps.getValue(it.repId).ordinal>=rep.ordinal}
            }
        }.map{it.ruleId}.toSet()
        val recurring=if(unreliable||evidence.summary==null)emptyList() else byRule.entries
            .filter{it.key !in resolved&&it.value.map{row->row.repId}.distinct().size>=2}
            .sortedWith(compareByDescending<Map.Entry<String,List<FormObservation>>>{entry->
                entry.value.maxOf{severityWeight(it.severity)}
            }.thenByDescending{it.value.map{row->row.repId}.distinct().size}.thenBy{it.key})
            .map{it.key}
        val focus=recurring.firstOrNull()
        val responses=delivered.map{cue->
            val response=if(unreliable||evidence.summary==null)null else evidence.responses
                .filter{it.cueId==cue.cueId&&validReps[it.repId]?.let{rep->
                    rep.completedAtUs>cue.emittedAtUs&&assessed.any{o->o.repId==rep.repId&&o.ruleId==cue.ruleId}
                }==true}
                .maxWithOrNull(compareBy({validReps.getValue(it.repId).ordinal},{it.repId}))
            DeliveredCueResponseSummary(cue.cueId,cue.ruleId,response?.state?:CueResponseState.UNKNOWN,response?.repId)
        }
        return SetCoachingSummary(focus,focus?.let(ruleText)?:"Repeat the same setup.",recurring,
            delivered.map{it.ruleId}.distinct(),evidence.observations.count{it.state==FormObservationState.UNKNOWN},
            if(unreliable||evidence.summary==null)emptySet() else resolved,responses)
    }
    private fun severityWeight(severity:FormRuleSeverity)=when(severity){
        FormRuleSeverity.MAJOR->3
        FormRuleSeverity.MINOR->2
        FormRuleSeverity.INFO->1
    }
}
