package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.profile.BaselineStatistic
import com.gymbuddy.domain.profile.ExerciseBaseline
import com.gymbuddy.domain.profile.FormComparison
import com.gymbuddy.domain.profile.FormRule
import com.gymbuddy.domain.profile.MetricAggregation
import com.gymbuddy.domain.profile.MetricDefinition
import com.gymbuddy.domain.profile.MetricProfile
import kotlin.math.max
import kotlin.math.min

data class PersonalMovementBaselinePolicy(
    val minimumSessionCount: Int = 3,
    val minimumMetricConfidence: Double = .50,
    val minimumProfileConfidence: Double = .50,
) {
    init {
        require(minimumSessionCount >= 2)
        require(minimumMetricConfidence.isFinite() && minimumMetricConfidence in 0.0..1.0)
        require(minimumProfileConfidence.isFinite() && minimumProfileConfidence in 0.0..1.0)
    }
}

internal data class ResolvedFormMetric(
    val definition: MetricDefinition,
    val statistic: BaselineStatistic?,
)

class PersonalMovementBaselineResolver(
    private val policy: PersonalMovementBaselinePolicy = PersonalMovementBaselinePolicy(),
) {
    internal fun resolveMetric(
        rule: FormRule,
        metricProfile: MetricProfile,
        baseline: ExerciseBaseline?,
    ): ResolvedFormMetric? {
        val definition = matchingMetric(rule, metricProfile) ?: return null
        return ResolvedFormMetric(definition, baseline?.metricStatistics?.get(definition.metricId))
    }

    fun effectiveThreshold(
        rule: FormRule,
        metricProfile: MetricProfile,
        baseline: ExerciseBaseline?,
        personalProfileConfidence: Double?,
    ): Double? {
        val generic = rule.threshold ?: return null
        if (baseline == null || personalProfileConfidence == null ||
            personalProfileConfidence < policy.minimumProfileConfidence
        ) return generic

        val resolved = resolveMetric(rule, metricProfile, baseline) ?: return generic
        val statistic = resolved.statistic ?: return generic
        if (statistic.sessionCount < policy.minimumSessionCount ||
            statistic.confidence < policy.minimumMetricConfidence
        ) return generic

        val center = statistic.median ?: return generic
        return when (rule.comparison) {
            FormComparison.MIN_VALUE -> {
                // Personalization may only relax the lower threshold when the baseline center
                // itself remains on the generic acceptable side. This prevents a repeatedly
                // bad baseline from becoming the new definition of "good" form.
                if (center < generic) generic
                else statistic.lowerBound?.let { min(generic, it) } ?: generic
            }
            FormComparison.MAX_VALUE,
            FormComparison.MAX_ABS_DIFFERENCE,
            FormComparison.RANGE_AT_MOST -> {
                if (center > generic) generic
                else statistic.upperBound?.let { max(generic, it) } ?: generic
            }
        }
    }

    private fun matchingMetric(rule: FormRule, metricProfile: MetricProfile): MetricDefinition? {
        metricProfile.metrics.singleOrNull {
            it.metricId == rule.ruleId && it.sourceSignalIds == rule.evidenceSignalIds
        }?.let { return it }

        val expectedAggregation = when (rule.comparison) {
            FormComparison.MAX_ABS_DIFFERENCE -> MetricAggregation.ABS_DIFFERENCE
            FormComparison.RANGE_AT_MOST -> MetricAggregation.RANGE
            FormComparison.MAX_VALUE -> MetricAggregation.MAX
            FormComparison.MIN_VALUE -> MetricAggregation.MIN
        }
        return metricProfile.metrics.singleOrNull {
            it.sourceSignalIds == rule.evidenceSignalIds && it.aggregation == expectedAggregation
        }
    }
}
