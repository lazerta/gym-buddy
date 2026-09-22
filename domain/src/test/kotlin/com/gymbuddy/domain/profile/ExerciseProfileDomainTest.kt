package com.gymbuddy.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseProfileDomainTest {
    @Test fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { sampleCameraProfile().copy(minVisibleRequiredFraction = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { sampleCuePolicy().copy(requiredOccurrences = 4, persistenceWindowReps = 3) }
        assertThrows(IllegalArgumentException::class.java) { sampleExerciseProfile().copy(movementPrimitiveSequence = samplePrimitiveSequence(listOf("not_declared"))) }
    }

    @Test fun preservesProfileVersionAndSemanticHashInAnalysisProvenance() {
        val profile = sampleExerciseProfile(7, "exercise-profile-v7")
        val config = AnalysisConfigResolver.resolve(sampleDefinition(), profile)
        assertEquals(7, config.provenance.exerciseProfile.profileVersion)
        assertEquals("exercise-profile-v7", config.provenance.exerciseProfile.semanticHash)
        assertNull(config.provenance.equipmentProfile)
        assertNull(config.provenance.personalCalibrationProfile)
    }

    @Test fun equipmentOverridesOnlyDeclaredBehaviorParameters() {
        val equipment = sampleEquipmentProfile(ViewClass.SIDE, mapOf("press_depth" to mapOf("bottom_threshold" to 0.72)))
        val config = AnalysisConfigResolver.resolve(sampleDefinition(), sampleExerciseProfile(), equipment)
        assertEquals(ViewClass.SIDE, config.preferredViewClass)
        assertEquals(0.72, config.resolvedSignalParameters.getValue("press_depth").getValue("bottom_threshold"), 0.0)
        assertEquals(0.15, config.resolvedSignalParameters.getValue("press_depth").getValue("start_threshold"), 0.0)
    }

    @Test fun equipmentCannotIntroduceHiddenSignalParametersOrUnsupportedView() {
        val hidden = sampleEquipmentProfile(signalParameterOverrides = mapOf("press_depth" to mapOf("secret_threshold" to 0.2)))
        assertThrows(IllegalArgumentException::class.java) { AnalysisConfigResolver.resolve(sampleDefinition(), sampleExerciseProfile(), hidden) }
        val invalidView = sampleEquipmentProfile(preferredViewOverride = ViewClass.FRONT)
        assertThrows(IllegalArgumentException::class.java) { AnalysisConfigResolver.resolve(sampleDefinition(), sampleExerciseProfile(), invalidView) }
    }

    @Test fun personalCalibrationAugmentsWithoutWeakeningGenericSafetyConstraints() {
        val generic = sampleExerciseProfile()
        val baseline = ExerciseBaseline("baseline-1", 1, "baseline-hash", ExerciseBaselineKey(generic.profileId, generic.profileVersion, viewClass = ViewClass.SIDE_OBLIQUE), mapOf(
            "rom_proxy" to BaselineStatistic(0.82, 0.75, 0.90, 48, 6, 0.9)
        ))
        val calibration = PersonalCalibrationProfile("personal-1", 3, "personal-hash-v3", 0.9,
            normalizedBodyGeometry = mapOf("arm_to_torso" to 1.08, "unknown_absolute_height" to null), exerciseBaselines = listOf(baseline))
        val config = AnalysisConfigResolver.resolve(sampleDefinition(), generic, personalCalibrationProfile = calibration)
        assertSame(generic.cameraProfile, config.exerciseProfile.cameraProfile)
        assertEquals(generic.cameraProfile.requiredLandmarks, config.exerciseProfile.cameraProfile.requiredLandmarks)
        assertEquals(generic.cameraProfile.minVisibleRequiredFraction, config.exerciseProfile.cameraProfile.minVisibleRequiredFraction, 0.0)
        assertEquals(generic.cameraProfile.maxTrackingGapMs, config.exerciseProfile.cameraProfile.maxTrackingGapMs)
        assertEquals(baseline, config.activeExerciseBaseline)
        assertEquals(3, config.provenance.personalCalibrationProfile?.profileVersion)
    }

    @Test fun personalCalibrationCannotInventUnsupportedMetric() {
        val generic = sampleExerciseProfile()
        val calibration = PersonalCalibrationProfile("personal-invalid", 1, "personal-invalid-hash", 0.8, exerciseBaselines = listOf(
            ExerciseBaseline("baseline-invalid", 1, "baseline-invalid-hash", ExerciseBaselineKey(generic.profileId, generic.profileVersion), mapOf(
                "joint_load" to BaselineStatistic(median = 10.0, sampleCount = 20, sessionCount = 3, confidence = 0.8)
            ))
        ))
        assertThrows(IllegalArgumentException::class.java) { AnalysisConfigResolver.resolve(sampleDefinition(), generic, personalCalibrationProfile = calibration) }
    }

    @Test fun noPersonalCalibrationFallsBackToGenericProfile() {
        val generic = sampleExerciseProfile()
        val config = AnalysisConfigResolver.resolve(sampleDefinition(), generic)
        assertSame(generic, config.exerciseProfile)
        assertNull(config.personalCalibrationProfile)
        assertNull(config.activeExerciseBaseline)
        assertEquals(generic.cameraProfile.preferredViewClass, config.preferredViewClass)
    }

    @Test fun semanticHashIsDeterministicAndBehaviorSensitive() {
        val a = SemanticHash.sha256("profile", "1", "bottom_threshold=0.7")
        val b = SemanticHash.sha256("profile", "1", "bottom_threshold=0.7")
        val changed = SemanticHash.sha256("profile", "1", "bottom_threshold=0.72")
        assertEquals(a, b)
        assertNotEquals(a, changed)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun sampleDefinition() = ExerciseDefinition("incline_dumbbell_press", 1, "definition-hash", "Incline Dumbbell Press", setOf("Incline DB Press"), MovementFamily.PRESS)
    private fun sampleCameraProfile() = CameraProfile("camera-press-side-oblique", 1, "camera-hash", ViewClass.SIDE_OBLIQUE,
        setOf(ViewClass.SIDE_OBLIQUE, ViewClass.SIDE), setOf(LensFacing.BACK), setOf(
            LandmarkRequirement("left_shoulder", 0.65), LandmarkRequirement("left_elbow", 0.65), LandmarkRequirement("left_wrist", 0.65)),
        NumericRange(0.35, 0.85), 0.8, 300, CameraGuidanceAction.entries.toSet())
    private fun sampleSignalProfile() = SignalProfile("signals-press-v1", 1, "signals-hash", listOf(
        SignalDefinition("press_depth", SignalKind.ROM_PROXY, SignalUnit.NORMALIZED, setOf("left_shoulder", "left_elbow", "left_wrist"), mapOf("start_threshold" to 0.15, "bottom_threshold" to 0.70)),
        SignalDefinition("bilateral_timing", SignalKind.BILATERAL_TIMING, SignalUnit.MILLISECONDS, setOf("left_wrist", "right_wrist"))))
    private fun samplePrimitiveSequence(signalIds: List<String> = listOf("press_depth")) = MovementPrimitiveSequence("primitive-press-v1", 1, "primitive-hash", listOf(
        MovementPrimitiveStep("press-cycle", MovementPrimitive.PRESS, signalIds, mapOf("min_excursion" to 0.5))))
    private fun sampleMetricProfile() = MetricProfile("metrics-press-v1", 1, "metrics-hash", listOf(
        MetricDefinition("rom_proxy", setOf("press_depth"), SignalUnit.NORMALIZED), MetricDefinition("bilateral_timing_ms", setOf("bilateral_timing"), SignalUnit.MILLISECONDS)))
    private fun sampleFormRuleSet() = FormRuleSet("rules-press-v1", 1, "rules-hash", listOf(
        FormRule("timing_asymmetry", 1, setOf("bilateral_timing"), 0.75, FormRuleSeverity.MINOR)))
    private fun sampleCuePolicy() = CuePolicy("cue-press-v1", 1, "cue-hash", 3, 2, 15_000, 2)
    private fun sampleExerciseProfile(profileVersion: Int = 1, semanticHash: String = "exercise-profile-hash") = ExerciseProfile(
        "incline-db-press-v1", profileVersion, semanticHash, "incline_dumbbell_press", LateralityMode.BILATERAL, setOf(EquipmentType.DUMBBELL),
        sampleCameraProfile(), sampleSignalProfile(), samplePrimitiveSequence(), sampleMetricProfile(), sampleFormRuleSet(), sampleCuePolicy(),
        setOf(ProfileCapability.CAMERA_GUIDANCE, ProfileCapability.REP_DETECTION, ProfileCapability.FORM_ANALYSIS))
    private fun sampleEquipmentProfile(preferredViewOverride: ViewClass? = null, signalParameterOverrides: Map<String, Map<String, Double>> = emptyMap()) =
        EquipmentProfile("bench-a", 2, "equipment-hash-v2", EquipmentType.DUMBBELL, setOf("incline_dumbbell_press"), preferredViewOverride, signalParameterOverrides)
}
