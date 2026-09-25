package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.persistence.CueDeliveryState
import com.gymbuddy.domain.persistence.PersistedSetEvidence
import com.gymbuddy.domain.profile.FormRuleSeverity

data class SetCoachingSummary(
    val focusRuleId:String?=null,
    val focusText:String="Repeat the same setup.",
    val recurringRuleIds:List<String> = emptyList(),
    val deliveredCueRuleIds:List<String> = emptyList(),
    val unknownObservationCount:Int=0,
)

class EvidenceSummaryEngine(
    private val ruleText:(String)->String={it},
){
    fun summarize(evidence:PersistedSetEvidence):SetCoachingSummary{
        val deliveredCueIds=evidence.cueDeliveries
            .filter{it.state==CueDeliveryState.COMPLETED}
            .map{it.cueId}.toSet()
        val deliveredRules=evidence.cues
            .filter{it.cueId in deliveredCueIds}
            .map{it.ruleId}
            .distinct()
        val deviations=evidence.observations.filter{
            it.state==FormObservationState.DEVIATION&&
                (it.confidence?:0.0)>=.60
        }
        val byRule=deviations.groupBy{it.ruleId}
        val recurring=byRule.entries
            .filter{it.value.size>=2}
            .sortedWith(compareByDescending<Map.Entry<String,List<FormObservation>>>{entries->
                entries.value.maxOfOrNull{severityWeight(it.severity)}?:0
            }.thenByDescending{it.value.size}.thenBy{it.key})
            .map{it.key}
        val focusRule=recurring.firstOrNull()
            ?:deliveredRules.lastOrNull()
        return SetCoachingSummary(
            focusRuleId=focusRule,
            focusText=focusRule?.let(ruleText)?:"Repeat the same setup.",
            recurringRuleIds=recurring,
            deliveredCueRuleIds=deliveredRules,
            unknownObservationCount=evidence.observations.count{it.state==FormObservationState.UNKNOWN},
        )
    }

    private fun severityWeight(severity:FormRuleSeverity)=when(severity){
        FormRuleSeverity.MAJOR->3
        FormRuleSeverity.MINOR->2
        FormRuleSeverity.INFO->1
    }
}
