package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.profile.*
import kotlin.math.abs

enum class FormObservationState { OK, DEVIATION, UNKNOWN }
data class FormObservation(val observationId:String,val repId:String,val ruleId:String,val ruleVersion:Int,val state:FormObservationState,val severity:FormRuleSeverity,val confidence:Double?,val evidenceValue:Double?)

class FormAnalysisEngine {
    fun analyze(rep:RepEvidence, rules:FormRuleSet):List<FormObservation> = rules.rules.map { rule ->
        val signals=rule.evidenceSignalIds.mapNotNull(rep.signals::get)
        if(signals.size!=rule.evidenceSignalIds.size) return@map unknown(rep,rule)
        val confidence=signals.mapNotNull{it.confidence}.minOrNull()
        if(confidence==null||confidence<rule.minConfidence||rule.threshold==null) return@map unknown(rep,rule,confidence)
        val observed=when(rule.comparison){
            FormComparison.MAX_VALUE -> signals.mapNotNull{it.max}.maxOrNull()
            FormComparison.MIN_VALUE -> signals.mapNotNull{it.min}.minOrNull()
            FormComparison.MAX_ABS_DIFFERENCE -> if(signals.size==2&&signals[0].mean!=null&&signals[1].mean!=null) abs(signals[0].mean!!-signals[1].mean!!) else null
            FormComparison.RANGE_AT_MOST -> if(signals.size==1&&signals[0].min!=null&&signals[0].max!=null) signals[0].max!!-signals[0].min!! else null
        } ?: return@map unknown(rep,rule,confidence)
        val threshold = rule.threshold
        val violation=when(rule.comparison){FormComparison.MIN_VALUE -> observed<threshold;else -> observed>threshold}
        FormObservation("obs-${rep.repId}-${rule.ruleId}",rep.repId,rule.ruleId,rule.ruleVersion,if(violation)FormObservationState.DEVIATION else FormObservationState.OK,rule.severity,confidence,observed)
    }
    private fun unknown(rep:RepEvidence,rule:FormRule,c:Double?=null)=FormObservation("obs-${rep.repId}-${rule.ruleId}",rep.repId,rule.ruleId,rule.ruleVersion,FormObservationState.UNKNOWN,rule.severity,c,null)
}
