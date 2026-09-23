package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.coaching.CueEngine
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMovementBaselineSafetyTest {
    @Test
    fun threeSessionOutlierRoundTripCannotHideRepeatedLargeAsymmetry() {
        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val equipment = requireNotNull(bundle.equipment)
        val key = ExerciseBaselineKey(
            exerciseProfileId = bundle.profile.profileId,
            exerciseProfileVersion = bundle.profile.profileVersion,
            equipmentProfileId = equipment.profileId,
            viewClass = bundle.profile.cameraProfile.preferredViewClass,
        )
        val sessions = listOf(
            session("s1", 1, key, .10),
            session("s2", 2, key, .11),
            session("s3", 3, key, .90),
        )
        val proposal = MultiSessionCalibrationUpdater().propose(
            calibrationProfileId = "personal",
            currentProfile = null,
            targetKey = key,
            supportedMetricIds = setOf("bilateral_asymmetry"),
            sessions = sessions,
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, proposal.status)

        val candidate = requireNotNull(proposal.candidateProfile)
        val baseline = requireNotNull(
            candidate.exerciseBaselines.singleOrNull { it.key == key }
        )
        val statistic = requireNotNull(
            baseline.metricStatistics["bilateral_asymmetry"]
        )
        assertEquals(.11, statistic.median!!, 1e-9)
        assertEquals(.505, statistic.upperBound!!, 1e-9)
        assertEquals(3, statistic.sessionCount)
        assertEquals(.50, statistic.confidence, 1e-9)

        val config = AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            equipment,
            candidate,
        )
        val rule = bundle.profile.formRuleSet.rules.single {
            it.ruleId == "bilateral_asymmetry"
        }
        val effective = PersonalMovementBaselineResolver().effectiveThreshold(
            rule = rule,
            metricProfile = bundle.profile.metricProfile,
            baseline = baseline,
            personalProfileConfidence = candidate.sourceConfidence,
        )
        assertEquals(.25, requireNotNull(effective), 1e-9)

        val form = FormAnalysisEngine()
        val cue = CueEngine(bundle.profile.cuePolicy, bundle.profile.formRuleSet)
        var cueCount = 0
        repeat(2) { repIndex ->
            val rep = asymmetryRep(
                config = config,
                id = "outlier-round-trip-" + (repIndex + 1),
                ordinal = repIndex + 1,
                completedAtUs = (repIndex + 1) * 2_000_000L,
                asymmetry = .40,
            )
            val observations = form.analyze(rep, config)
            assertEquals(
                FormObservationState.DEVIATION,
                observations.single { it.ruleId == "bilateral_asymmetry" }.state,
            )
            cueCount += cue.evaluate(rep, observations).cues.size
        }
        assertEquals(1, cueCount)
    }

    @Test
    fun broadMinimumValueDistributionIsBoundedByGenericHeadroom() {
        val metricProfile = MetricProfile(
            profileId = "rom-metrics",
            profileVersion = 1,
            semanticHash = "rom-metrics-v1",
            metrics = listOf(
                MetricDefinition(
                    metricId = "left_rom",
                    sourceSignalIds = setOf("left_progress"),
                    unit = SignalUnit.NORMALIZED,
                    aggregation = MetricAggregation.RANGE,
                )
            ),
        )
        val rule = FormRule(
            ruleId = "left_rom",
            ruleVersion = 1,
            evidenceSignalIds = setOf("left_progress"),
            minConfidence = .60,
            severity = FormRuleSeverity.MINOR,
            comparison = FormComparison.MIN_VALUE,
            threshold = .75,
        )
        val baseline = baseline(
            "left_rom",
            BaselineStatistic(
                median = .80,
                lowerBound = .20,
                upperBound = .82,
                sampleCount = 30,
                sessionCount = 5,
                confidence = .90,
            ),
        )
        val effective = PersonalMovementBaselineResolver().effectiveThreshold(
            rule,
            metricProfile,
            baseline,
            .90,
        )
        assertEquals(.70, requireNotNull(effective), 1e-9)
    }

    @Test
    fun boundedRelaxationScalesWithRuleUnitsAndPreservesStablePersonalBands() {
        val resolver = PersonalMovementBaselineResolver()

        val degreeMetric = MetricProfile(
            "degree-metrics",
            1,
            "degree-metrics-v1",
            listOf(
                MetricDefinition(
                    "path_deg",
                    setOf("path"),
                    SignalUnit.DEGREES,
                    MetricAggregation.MAX,
                )
            ),
        )
        val degreeRule = FormRule(
            "path_deg",
            1,
            setOf("path"),
            .60,
            FormRuleSeverity.MINOR,
            FormComparison.MAX_VALUE,
            80.0,
        )
        val stableDegree = baseline(
            "path_deg",
            BaselineStatistic(76.0, 70.0, 84.0, 30, 5, .90),
        )
        val extremeDegree = baseline(
            "path_deg",
            BaselineStatistic(76.0, 70.0, 120.0, 30, 5, .90),
        )
        assertEquals(
            84.0,
            requireNotNull(
                resolver.effectiveThreshold(degreeRule, degreeMetric, stableDegree, .90)
            ),
            1e-9,
        )
        assertEquals(
            84.0,
            requireNotNull(
                resolver.effectiveThreshold(degreeRule, degreeMetric, extremeDegree, .90)
            ),
            1e-9,
        )

        val timingMetric = MetricProfile(
            "timing-metrics",
            1,
            "timing-metrics-v1",
            listOf(
                MetricDefinition(
                    "timing_ms",
                    setOf("left_progress", "right_progress"),
                    SignalUnit.MILLISECONDS,
                    MetricAggregation.MAX,
                )
            ),
        )
        val timingRule = FormRule(
            "timing_ms",
            1,
            setOf("left_progress", "right_progress"),
            .60,
            FormRuleSeverity.MINOR,
            FormComparison.MAX_VALUE,
            100.0,
        )
        val timing = baseline(
            "timing_ms",
            BaselineStatistic(70.0, 45.0, 1000.0, 30, 5, .90),
        )
        assertEquals(
            130.0,
            requireNotNull(
                resolver.effectiveThreshold(timingRule, timingMetric, timing, .90)
            ),
            1e-9,
        )
    }

    @Test
    fun minimumSessionBoundaryStillFallsBackBelowThreeSessions() {
        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val rule = bundle.profile.formRuleSet.rules.single {
            it.ruleId == "bilateral_asymmetry"
        }
        val resolver = PersonalMovementBaselineResolver()
        val twoSessions = baseline(
            "bilateral_asymmetry",
            BaselineStatistic(.14, .08, .50, 20, 2, .90),
        )
        val threeSessions = baseline(
            "bilateral_asymmetry",
            BaselineStatistic(.14, .08, .50, 30, 3, .50),
        )

        assertEquals(
            .18,
            requireNotNull(
                resolver.effectiveThreshold(
                    rule,
                    bundle.profile.metricProfile,
                    twoSessions,
                    .90,
                )
            ),
            1e-9,
        )
        assertEquals(
            .22,
            requireNotNull(
                resolver.effectiveThreshold(
                    rule,
                    bundle.profile.metricProfile,
                    threeSessions,
                    .50,
                )
            ),
            1e-9,
        )
    }

    @Test
    fun boundedRelaxationPolicyIsExplicitlyVersioned() {
        val policy = PersonalMovementBaselinePolicy()
        assertEquals(PERSONAL_MOVEMENT_BASELINE_POLICY_VERSION, policy.version)
        assertEquals(2, policy.version)
        assertEquals(1.0, policy.maximumBoundaryRelaxationHeadroomFactor, 0.0)
    }

    private fun session(
        ref: String,
        sequence: Long,
        key: ExerciseBaselineKey,
        value: Double,
    ) = CalibrationSessionEvidence(
        evidenceReference = ref,
        sequence = sequence,
        key = key,
        eligibility = CalibrationSessionEligibility.ELIGIBLE,
        metricSamples = mapOf(
            "bilateral_asymmetry" to List(10) { value },
        ),
    )

    private fun baseline(
        metricId: String,
        statistic: BaselineStatistic,
    ) = ExerciseBaseline(
        profileId = "test-baseline-" + metricId,
        profileVersion = 1,
        semanticHash = "test-baseline-" + metricId + "-v1",
        key = ExerciseBaselineKey(
            exerciseProfileId = "test-profile",
            exerciseProfileVersion = 1,
        ),
        metricStatistics = mapOf(metricId to statistic),
    )

    private fun asymmetryRep(
        config: AnalysisConfig,
        id: String,
        ordinal: Int,
        completedAtUs: Long,
        asymmetry: Double,
    ) = RepEvidence(
        repId = id,
        ordinal = ordinal,
        stepId = "cycle",
        primitive = MovementPrimitive.PRESS,
        startedAtUs = completedAtUs - 1_000_000L,
        completedAtUs = completedAtUs,
        classification = RepClassification.NORMAL,
        signals = mapOf(
            "left_progress" to SignalEvidence(
                "left_progress",
                SignalUnit.NORMALIZED,
                .0,
                .8,
                .4,
                .0,
                .95,
            ),
            "right_progress" to SignalEvidence(
                "right_progress",
                SignalUnit.NORMALIZED,
                .0,
                .8,
                .4,
                .0,
                .95,
            ),
        ),
        metrics = mapOf(
            "bilateral_asymmetry" to MetricEvidence(
                "bilateral_asymmetry",
                SignalUnit.NORMALIZED,
                EvidenceValue.Known(asymmetry, .95),
            )
        ),
        provenance = config.provenance,
    )
}
