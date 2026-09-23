package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.profile.*

enum class FormObservationState { OK,DEVIATION,UNKNOWN }
data class FormObservation(val observationId:String,val repId:String,val ruleId:String,val ruleVersion:Int,val state:FormObservationState,val severity:FormRuleSeverity,val confidence:Double?,val evidenceValue:Double?)

class FormAnalysisEngine(
    private val personalBaselineResolver: PersonalMovementBaselineResolver = PersonalMovementBaselineResolver(),
) {
    fun analyze(rep: RepEvidence, rules: FormRuleSet): List<FormObservation> =
        analyze(rep, rules, metricProfile = null, baseline = null, personalProfileConfidence = null)

    fun analyze(rep: RepEvidence, config: AnalysisConfig): List<FormObservation> =
        analyze(
            rep = rep,
            rules = config.exerciseProfile.formRuleSet,
            metricProfile = config.exerciseProfile.metricProfile,
            baseline = config.activeExerciseBaseline,
            personalProfileConfidence = config.personalCalibrationProfile?.sourceConfidence,
        )

    private fun analyze(
        rep: RepEvidence,
        rules: FormRuleSet,
        metricProfile: MetricProfile?,
        baseline: ExerciseBaseline?,
        personalProfileConfidence: Double?,
    ): List<FormObservation> = rules.rules.map { rule ->
        val resolvedMetric = metricProfile?.let {
            personalBaselineResolver.resolveMetric(rule, it, baseline)
        }
        if (resolvedMetric != null) {
            val metricEvidence = rep.metrics[resolvedMetric.definition.metricId]
                ?: return@map unknown(rep, rule)
            val metricKnown = metricEvidence.value as? EvidenceValue.Known
                ?: return@map unknown(rep, rule)
            if (metricKnown.confidence == null || metricKnown.confidence < rule.minConfidence || rule.threshold == null) {
                return@map unknown(rep, rule, metricKnown.confidence)
            }
            val threshold = personalBaselineResolver.effectiveThreshold(
                rule,
                metricProfile,
                baseline,
                personalProfileConfidence,
            ) ?: rule.threshold
            val violation = isViolation(rule.comparison, metricKnown.value, threshold)
            return@map observation(rep, rule, metricKnown.confidence, metricKnown.value, violation)
        }

        if (rule.comparison == FormComparison.MAX_ABS_DIFFERENCE) {
            val known = rep.metrics[rule.ruleId]?.value as? EvidenceValue.Known ?: return@map unknown(rep, rule)
            if (known.confidence == null || known.confidence < rule.minConfidence || rule.threshold == null) {
                return@map unknown(rep, rule, known.confidence)
            }
            val violation = isViolation(rule.comparison, known.value, rule.threshold)
            return@map observation(rep, rule, known.confidence, known.value, violation)
        }

        val signals = rule.evidenceSignalIds.mapNotNull(rep.signals::get)
        if (signals.size != rule.evidenceSignalIds.size) return@map unknown(rep, rule)
        val confidence = signals.mapNotNull { it.confidence }.minOrNull()
        if (confidence == null || confidence < rule.minConfidence || rule.threshold == null) {
            return@map unknown(rep, rule, confidence)
        }
        val observed = when (rule.comparison) {
            FormComparison.MAX_VALUE -> signals.mapNotNull { it.max }.maxOrNull()
            FormComparison.MIN_VALUE -> signals.mapNotNull { it.min }.minOrNull()
            FormComparison.RANGE_AT_MOST -> if (signals.size == 1 && signals[0].min != null && signals[0].max != null) {
                signals[0].max!! - signals[0].min!!
            } else null
            FormComparison.MAX_ABS_DIFFERENCE -> null
        } ?: return@map unknown(rep, rule, confidence)
        val violation = isViolation(rule.comparison, observed, rule.threshold)
        observation(rep, rule, confidence, observed, violation)
    }

    private fun isViolation(comparison: FormComparison, observed: Double, threshold: Double): Boolean =
        when (comparison) {
            FormComparison.MIN_VALUE -> observed < threshold
            else -> observed > threshold
        }

    private fun observation(
        rep: RepEvidence,
        rule: FormRule,
        confidence: Double,
        observed: Double,
        violation: Boolean,
    ) = FormObservation(
        "obs-${rep.repId}-${rule.ruleId}",
        rep.repId,
        rule.ruleId,
        rule.ruleVersion,
        if (violation) FormObservationState.DEVIATION else FormObservationState.OK,
        rule.severity,
        confidence,
        observed,
    )

    private fun unknown(rep: RepEvidence, rule: FormRule, c: Double? = null) =
        FormObservation(
            "obs-${rep.repId}-${rule.ruleId}",
            rep.repId,
            rule.ruleId,
            rule.ruleVersion,
            FormObservationState.UNKNOWN,
            rule.severity,
            c,
            null,
        )
}
