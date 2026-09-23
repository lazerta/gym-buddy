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

const val PERSONAL_MOVEMENT_BASELINE_POLICY_VERSION = 2

data class PersonalMovementBaselinePolicy(
    val version: Int = PERSONAL_MOVEMENT_BASELINE_POLICY_VERSION,
    val minimumSessionCount: Int = 3,
    val minimumMetricConfidence: Double = .50,
    val minimumProfileConfidence: Double = .50,
    // A personal boundary may extend beyond the generic boundary by no more than
    // this fraction of the baseline median's headroom on the generic acceptable side.
    // This is dimensionless, so it behaves consistently for normalized ROM, degrees,
    // milliseconds, and other supported metric units.
    val maximumBoundaryRelaxationHeadroomFactor: Double = 1.0,
) {
    init {
        require(version > 0)
        require(minimumSessionCount >= 2)
        require(minimumMetricConfidence.isFinite() && minimumMetricConfidence in 0.0..1.0)
        require(minimumProfileConfidence.isFinite() && minimumProfileConfidence in 0.0..1.0)
        require(
            maximumBoundaryRelaxationHeadroomFactor.isFinite() &&
                maximumBoundaryRelaxationHeadroomFactor in 0.0..1.0
        )
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
            FormComparison.MIN_VALUE -> boundedLowerThreshold(
                generic = generic,
                center = center,
                personalLowerBound = statistic.lowerBound,
            )
            FormComparison.MAX_VALUE,
            FormComparison.MAX_ABS_DIFFERENCE,
            FormComparison.RANGE_AT_MOST -> boundedUpperThreshold(
                generic = generic,
                center = center,
                personalUpperBound = statistic.upperBound,
            )
        }
    }

    private fun boundedLowerThreshold(
        generic: Double,
        center: Double,
        personalLowerBound: Double?,
    ): Double {
        // A baseline centered on the violating side must never redefine bad form as normal.
        if (center < generic) return generic
        val lower = personalLowerBound ?: return generic
        val requested = min(generic, lower)
        val acceptableHeadroom = center - generic
        val minimumAllowed =
            generic - acceptableHeadroom * policy.maximumBoundaryRelaxationHeadroomFactor
        return max(requested, minimumAllowed)
    }

    private fun boundedUpperThreshold(
        generic: Double,
        center: Double,
        personalUpperBound: Double?,
    ): Double {
        // A baseline centered on the violating side must never redefine bad form as normal.
        if (center > generic) return generic
        val upper = personalUpperBound ?: return generic
        val requested = max(generic, upper)
        val acceptableHeadroom = generic - center
        val maximumAllowed =
            generic + acceptableHeadroom * policy.maximumBoundaryRelaxationHeadroomFactor
        return min(requested, maximumAllowed)
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
