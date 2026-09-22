package com.gymbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseProfileDomainTest {
    @Test
    fun versionAndSemanticHashProvenanceArePreserved() {
        val profile = ProfileFixtures.profile(profileVersion = 7)
        val config = ExerciseProfileResolver.resolve(ProfileFixtures.definition, profile)

        assertEquals(7, config.exerciseProfileProvenance.profileVersion)
        assertEquals(profile.semanticHash, config.exerciseProfileProvenance.semanticHash)
        assertEquals(profile.profileId, config.exerciseProfileProvenance.profileId)
    }

    @Test
    fun behaviorChangeChangesSemanticHashWithoutRequiringVersionMutation() {
        val a = ProfileFixtures.profile(profileVersion = 3, excursionMin = 0.35)
        val b = ProfileFixtures.profile(profileVersion = 3, excursionMin = 0.40)

        assertEquals(a.profileVersion, b.profileVersion)
        assertNotEquals(a.semanticHash, b.semanticHash)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPrimitiveSignalReferenceIsRejected() {
        val valid = ProfileFixtures.profile()
        val invalidSequence = valid.movementPrimitiveSequence.copy(
            primitives = listOf(
                valid.movementPrimitiveSequence.primitives.single().copy(
                    progressSignalId = "missing_signal"
                )
            )
        )

        ExerciseProfile.create(
            exerciseId = valid.exerciseId,
            profileId = "invalid",
            profileVersion = 1,
            lateralityMode = valid.lateralityMode,
            cameraProfile = valid.cameraProfile,
            signalProfile = valid.signalProfile,
            movementPrimitiveSequence = invalidSequence,
            metricProfile = valid.metricProfile,
            formRuleSet = valid.formRuleSet,
            cuePolicy = valid.cuePolicy,
            supportedEquipmentKinds = valid.supportedEquipmentKinds,
            capabilities = valid.capabilities,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMetricSignalReferenceIsRejected() {
        val valid = ProfileFixtures.profile()
        val invalidMetrics = valid.metricProfile.copy(
            metricDefinitions = listOf(
                MetricDefinition(
                    metricId = "bad_metric",
                    sourceSignalIds = setOf("missing_signal"),
                    unit = MetricUnit.RATIO,
                )
            )
        )

        ExerciseProfile.create(
            exerciseId = valid.exerciseId,
            profileId = "invalid_metric_profile",
            profileVersion = 1,
            lateralityMode = valid.lateralityMode,
            cameraProfile = valid.cameraProfile,
            signalProfile = valid.signalProfile,
            movementPrimitiveSequence = valid.movementPrimitiveSequence,
            metricProfile = invalidMetrics,
            formRuleSet = valid.formRuleSet,
            cuePolicy = valid.cuePolicy,
            supportedEquipmentKinds = valid.supportedEquipmentKinds,
            capabilities = valid.capabilities,
        )
    }

    @Test
    fun equipmentOverrideOnlyChangesDeclaredParameterAndAllowedView() {
        val profile = ProfileFixtures.profile()
        val equipment = ProfileFixtures.equipment(profile)
        val config = ExerciseProfileResolver.resolve(
            definition = ProfileFixtures.definition,
            profile = profile,
            equipment = equipment,
        )

        val resolved = requireNotNull(config.signalProfile.signal("press_depth"))
        assertEquals(0.40, resolved.parameters["excursion_min"])
        assertEquals(0.35, profile.signalProfile.signal("press_depth")!!.parameters["excursion_min"])
        assertEquals(ViewClass.SIDE, config.cameraProfile.preferredViewClass)
        assertEquals(profile.cameraProfile.requiredLandmarks, config.cameraProfile.requiredLandmarks)
        assertEquals(profile.cameraProfile.minLandmarkConfidence, config.cameraProfile.minLandmarkConfidence, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun equipmentCannotIntroduceHiddenBehaviorParameter() {
        val profile = ProfileFixtures.profile()
        val equipment = ProfileFixtures.equipment(
            profile,
            parameterOverrides = mapOf(
                "press_depth" to mapOf("secret_threshold" to 123.0)
            ),
        )

        ExerciseProfileResolver.resolve(ProfileFixtures.definition, profile, equipment)
    }

    @Test
    fun personalCalibrationAugmentsWithoutWeakeningGenericSafetyConstraints() {
        val profile = ProfileFixtures.profile()
        val equipment = ProfileFixtures.equipment(profile)
        val calibration = ProfileFixtures.calibration(profile, equipment)

        val config = ExerciseProfileResolver.resolve(
            definition = ProfileFixtures.definition,
            profile = profile,
            equipment = equipment,
            personalCalibration = calibration,
        )

        assertEquals(profile.cameraProfile.requiredLandmarks, config.cameraProfile.requiredLandmarks)
        assertEquals(profile.cameraProfile.minRequiredVisibleFraction, config.cameraProfile.minRequiredVisibleFraction, 0.0)
        assertEquals(profile.cameraProfile.minLandmarkConfidence, config.cameraProfile.minLandmarkConfidence, 0.0)
        assertEquals(profile.cameraProfile.minIdentityMargin, config.cameraProfile.minIdentityMargin, 0.0)
        assertEquals(0.78, config.exerciseBaseline!!.metricBaselines.getValue("rom_proxy").median, 0.0)
        assertEquals(calibration.semanticHash, config.personalCalibrationProvenance!!.semanticHash)
        assertNull(calibration.normalizedBodyGeometry["unknown_absolute_height"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun personalCalibrationCannotInventUnsupportedMetric() {
        val profile = ProfileFixtures.profile()
        val calibration = ProfileFixtures.calibration(
            profile = profile,
            metricId = "joint_load",
        )

        ExerciseProfileResolver.resolve(
            definition = ProfileFixtures.definition,
            profile = profile,
            personalCalibration = calibration,
        )
    }

    @Test
    fun noPersonalCalibrationFallsBackToGenericProfile() {
        val profile = ProfileFixtures.profile()
        val config = ExerciseProfileResolver.resolve(ProfileFixtures.definition, profile)

        assertSame(profile.cameraProfile, config.cameraProfile)
        assertSame(profile.signalProfile, config.signalProfile)
        assertNull(config.personalCalibrationProfile)
        assertNull(config.exerciseBaseline)
        assertTrue(config.exerciseProfile.semanticHash.isNotBlank())
    }
}
