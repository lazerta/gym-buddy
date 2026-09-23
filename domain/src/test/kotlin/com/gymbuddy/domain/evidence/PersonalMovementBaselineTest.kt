package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.coaching.CueEngine
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMovementBaselineTest {
    @Test
    fun normalPersonalVariationSuppressesFalsePositiveButRepeatedTrueDeviationStillCues() {
        val config = pressConfig(defaultStatistics())
        val rules = config.exerciseProfile.formRuleSet
        val form = FormAnalysisEngine()

        val normal = rep(config, "normal", 1, 2_000_000L, asymmetry = .20, pathDeg = 82.0)
        val generic = form.analyze(normal, rules).associateBy { it.ruleId }
        assertEquals(FormObservationState.DEVIATION, generic.getValue("bilateral_asymmetry").state)
        assertEquals(FormObservationState.DEVIATION, generic.getValue("press_elbow_path_flare").state)

        val personalized = form.analyze(normal, config).associateBy { it.ruleId }
        assertEquals(FormObservationState.OK, personalized.getValue("bilateral_asymmetry").state)
        assertEquals(FormObservationState.OK, personalized.getValue("press_elbow_path_flare").state)

        val normalCue = CueEngine(config.exerciseProfile.cuePolicy, rules)
        repeat(2) { index ->
            val candidate = rep(
                config,
                "normal-${index + 1}",
                index + 1,
                (index + 1) * 2_000_000L,
                asymmetry = .20,
                pathDeg = 82.0,
            )
            assertTrue(normalCue.evaluate(candidate, form.analyze(candidate, config)).cues.isEmpty())
        }

        val genuineCue = CueEngine(config.exerciseProfile.cuePolicy, rules)
        val first = rep(config, "genuine-1", 1, 2_000_000L, asymmetry = .27, pathDeg = 90.0)
        val second = rep(config, "genuine-2", 2, 4_000_000L, asymmetry = .27, pathDeg = 90.0)
        assertTrue(genuineCue.evaluate(first, form.analyze(first, config)).cues.isEmpty())
        assertEquals(1, genuineCue.evaluate(second, form.analyze(second, config)).cues.size)
    }

    @Test
    fun baselineCenteredInGenericViolationCannotTeachEngineToAcceptBadForm() {
        val statistics = defaultStatistics().toMutableMap().apply {
            this["bilateral_asymmetry"] = BaselineStatistic(.24, .20, .28, 30, 5, .85)
            this["press_elbow_path_flare_deg"] = BaselineStatistic(86.0, 82.0, 91.0, 30, 5, .85)
        }
        val config = pressConfig(statistics)
        val observations = FormAnalysisEngine().analyze(
            rep(config, "bad-centered", 1, 2_000_000L, asymmetry = .20, pathDeg = 82.0),
            config,
        ).associateBy { it.ruleId }

        assertEquals(FormObservationState.DEVIATION, observations.getValue("bilateral_asymmetry").state)
        assertEquals(FormObservationState.DEVIATION, observations.getValue("press_elbow_path_flare").state)
    }

    @Test
    fun lowConfidenceOrWrongContextFallsBackToGenericRules() {
        val lowConfidence = defaultStatistics().toMutableMap().apply {
            this["bilateral_asymmetry"] = BaselineStatistic(.14, .08, .22, 18, 3, .40)
        }
        val lowConfig = pressConfig(lowConfidence)
        val lowObservation = FormAnalysisEngine().analyze(
            rep(lowConfig, "low-confidence", 1, 2_000_000L, asymmetry = .20, pathDeg = 78.0),
            lowConfig,
        ).first { it.ruleId == "bilateral_asymmetry" }
        assertEquals(FormObservationState.DEVIATION, lowObservation.state)

        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val equipment = requireNotNull(bundle.equipment)
        val wrongKey = ExerciseBaselineKey(
            exerciseProfileId = bundle.profile.profileId,
            exerciseProfileVersion = bundle.profile.profileVersion,
            equipmentProfileId = "different-dumbbell-profile",
            viewClass = bundle.profile.cameraProfile.preferredViewClass,
        )
        val calibration = PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 1,
            sourceConfidence = .85,
            exerciseBaselines = listOf(
                ExerciseBaseline("wrong-context", 1, "wrong-context-v1", wrongKey, defaultStatistics())
            ),
        )
        val wrongContext = AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            equipment,
            calibration,
        )
        assertNull(wrongContext.activeExerciseBaseline)
        val observation = FormAnalysisEngine().analyze(
            rep(wrongContext, "wrong-context", 1, 2_000_000L, asymmetry = .20, pathDeg = 78.0),
            wrongContext,
        ).first { it.ruleId == "bilateral_asymmetry" }
        assertEquals(FormObservationState.DEVIATION, observation.state)
    }

    @Test
    fun romTimingPathAndAsymmetryDistributionsAreUsableWithoutExerciseNameDispatch() {
        val base = pressConfig(defaultStatistics())
        val rules = FormRuleSet(
            profileId = "personal-metric-rules",
            profileVersion = 1,
            semanticHash = "personal-metric-rules-v1",
            rules = listOf(
                FormRule(
                    ruleId = "left_rom",
                    ruleVersion = 1,
                    evidenceSignalIds = setOf("left_progress"),
                    minConfidence = .60,
                    severity = FormRuleSeverity.MINOR,
                    comparison = FormComparison.MIN_VALUE,
                    threshold = .75,
                ),
                FormRule(
                    ruleId = "bilateral_timing_ms",
                    ruleVersion = 1,
                    evidenceSignalIds = setOf("left_progress", "right_progress"),
                    minConfidence = .60,
                    severity = FormRuleSeverity.MINOR,
                    comparison = FormComparison.MAX_VALUE,
                    threshold = 100.0,
                ),
            ),
        )
        val config = base.copy(
            exerciseProfile = base.exerciseProfile.copy(formRuleSet = rules),
        )
        val withinPersonal = rep(
            config,
            "personal-band",
            1,
            2_000_000L,
            asymmetry = .14,
            pathDeg = 76.0,
            leftRom = .72,
            timingMs = 102.0,
        )
        val within = FormAnalysisEngine().analyze(withinPersonal, config).associateBy { it.ruleId }
        assertEquals(FormObservationState.OK, within.getValue("left_rom").state)
        assertEquals(FormObservationState.OK, within.getValue("bilateral_timing_ms").state)

        val outsidePersonal = rep(
            config,
            "outside-band",
            2,
            4_000_000L,
            asymmetry = .14,
            pathDeg = 76.0,
            leftRom = .64,
            timingMs = 130.0,
        )
        val outside = FormAnalysisEngine().analyze(outsidePersonal, config).associateBy { it.ruleId }
        assertEquals(FormObservationState.DEVIATION, outside.getValue("left_rom").state)
        assertEquals(FormObservationState.DEVIATION, outside.getValue("bilateral_timing_ms").state)

        val metricIds = requireNotNull(config.activeExerciseBaseline).metricStatistics.keys
        assertTrue("left_rom" in metricIds)
        assertTrue("bilateral_timing_ms" in metricIds)
        assertTrue("bilateral_asymmetry" in metricIds)
        assertTrue("press_elbow_path_flare_deg" in metricIds)
    }

    private fun pressConfig(
        statistics: Map<String, BaselineStatistic>,
        sourceConfidence: Double = .85,
    ): AnalysisConfig {
        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val equipment = requireNotNull(bundle.equipment)
        val key = ExerciseBaselineKey(
            exerciseProfileId = bundle.profile.profileId,
            exerciseProfileVersion = bundle.profile.profileVersion,
            equipmentProfileId = equipment.profileId,
            viewClass = bundle.profile.cameraProfile.preferredViewClass,
        )
        val calibration = PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 3,
            sourceConfidence = sourceConfidence,
            exerciseBaselines = listOf(
                ExerciseBaseline(
                    profileId = "personal-press-baseline",
                    profileVersion = 3,
                    semanticHash = "personal-press-baseline-v3",
                    key = key,
                    metricStatistics = statistics,
                )
            ),
            equipmentAssociations = setOf(equipment.profileId),
            evidenceReferences = setOf("session-1", "session-2", "session-3"),
        )
        return AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            equipment,
            calibration,
        )
    }

    private fun defaultStatistics(): Map<String, BaselineStatistic> = mapOf(
        "left_rom" to BaselineStatistic(.80, .70, .86, 30, 5, .82),
        "right_rom" to BaselineStatistic(.81, .71, .87, 30, 5, .82),
        "bilateral_asymmetry" to BaselineStatistic(.14, .08, .22, 30, 5, .82),
        "bilateral_timing_ms" to BaselineStatistic(70.0, 45.0, 105.0, 30, 5, .82),
        "press_elbow_path_flare_deg" to BaselineStatistic(76.0, 70.0, 84.0, 30, 5, .82),
    )

    private fun rep(
        config: AnalysisConfig,
        id: String,
        ordinal: Int,
        completedAtUs: Long,
        asymmetry: Double,
        pathDeg: Double,
        leftRom: Double = .80,
        timingMs: Double = 70.0,
    ): RepEvidence = RepEvidence(
        repId = id,
        ordinal = ordinal,
        stepId = "cycle",
        primitive = MovementPrimitive.PRESS,
        startedAtUs = completedAtUs - 1_000_000L,
        completedAtUs = completedAtUs,
        classification = RepClassification.NORMAL,
        signals = mapOf(
            "left_progress" to SignalEvidence(
                "left_progress", SignalUnit.NORMALIZED, leftRom, leftRom, leftRom, leftRom, .95
            ),
            "right_progress" to SignalEvidence(
                "right_progress", SignalUnit.NORMALIZED, .80, .80, .80, .80, .95
            ),
            "left_elbow_path_angle" to SignalEvidence(
                "left_elbow_path_angle", SignalUnit.DEGREES, 60.0, pathDeg, pathDeg, pathDeg, .95
            ),
            "right_elbow_path_angle" to SignalEvidence(
                "right_elbow_path_angle", SignalUnit.DEGREES, 60.0, pathDeg - 1.0, pathDeg - 1.0, pathDeg - 1.0, .95
            ),
        ),
        metrics = mapOf(
            "left_rom" to MetricEvidence(
                "left_rom", SignalUnit.NORMALIZED, EvidenceValue.Known(leftRom, .95)
            ),
            "right_rom" to MetricEvidence(
                "right_rom", SignalUnit.NORMALIZED, EvidenceValue.Known(.80, .95)
            ),
            "bilateral_asymmetry" to MetricEvidence(
                "bilateral_asymmetry", SignalUnit.NORMALIZED, EvidenceValue.Known(asymmetry, .95)
            ),
            "bilateral_timing_ms" to MetricEvidence(
                "bilateral_timing_ms", SignalUnit.MILLISECONDS, EvidenceValue.Known(timingMs, .95)
            ),
            "press_elbow_path_flare_deg" to MetricEvidence(
                "press_elbow_path_flare_deg", SignalUnit.DEGREES, EvidenceValue.Known(pathDeg, .95)
            ),
        ),
        provenance = config.provenance,
    )
}
