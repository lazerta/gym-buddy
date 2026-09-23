package com.gymbuddy.domain.profile

import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCalibrationBootstrapTest {
    @Test fun noEvidenceLeavesCalibrationAbsent() {
        assertNull(
            PersonalCalibrationBootstrapper.bootstrap(
                calibrationProfileId = "personal",
                profileVersion = 1,
                evidence = null,
            )
        )
    }

    @Test fun explicitMeasurementsPersistOnlyLowConfidenceNormalizedGeometry() {
        val profile = requireNotNull(
            PersonalCalibrationBootstrapper.bootstrap(
                calibrationProfileId = "personal",
                profileVersion = 1,
                evidence = PersonalCalibrationBootstrapEvidence(
                    evidenceReference = "measurement-session-1",
                    sourceConfidence = .90,
                    normalizedGeometry = mapOf(
                        "shoulder_to_hip_width" to 1.20,
                    ),
                    explicitRatios = listOf(
                        BootstrapGeometryRatio("arm_to_torso", 65.0, 60.0),
                        BootstrapGeometryRatio("unknown_leg_to_torso", null, 60.0),
                    ),
                ),
            )
        )

        assertEquals(
            PersonalCalibrationBootstrapper.MAX_BOOTSTRAP_CONFIDENCE,
            profile.sourceConfidence,
            0.0,
        )
        assertEquals(
            1.20,
            profile.normalizedBodyGeometry.getValue("shoulder_to_hip_width")!!,
            0.0,
        )
        assertEquals(
            65.0 / 60.0,
            profile.normalizedBodyGeometry.getValue("arm_to_torso")!!,
            1e-12,
        )
        assertTrue(profile.normalizedBodyGeometry.containsKey("unknown_leg_to_torso"))
        assertNull(profile.normalizedBodyGeometry["unknown_leg_to_torso"])
        assertTrue(profile.cameraSetupPreferences.isEmpty())
        assertTrue(profile.exerciseBaselines.isEmpty())
        assertTrue(profile.equipmentAssociations.isEmpty())
        assertTrue(profile.lateralityBaseline.isEmpty())
        assertTrue(profile.cueEffectiveness.isEmpty())
        assertEquals(setOf("measurement-session-1"), profile.evidenceReferences)
        assertEquals(profile.semanticHash, PersonalCalibrationSemanticHash.compute(profile))
    }

    @Test fun bootstrapPreservesLowerSourceConfidenceAndRejectsAmbiguousOrInvalidGeometry() {
        val lower = requireNotNull(
            PersonalCalibrationBootstrapper.bootstrap(
                calibrationProfileId = "personal",
                profileVersion = 2,
                evidence = PersonalCalibrationBootstrapEvidence(
                    evidenceReference = "selected-bootstrap-set",
                    sourceConfidence = .20,
                    normalizedGeometry = mapOf("arm_to_torso" to 1.10),
                ),
            )
        )
        assertEquals(.20, lower.sourceConfidence, 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            PersonalCalibrationBootstrapEvidence(
                evidenceReference = "ambiguous",
                sourceConfidence = .20,
                normalizedGeometry = mapOf("arm_to_torso" to 1.10),
                explicitRatios = listOf(
                    BootstrapGeometryRatio("arm_to_torso", 55.0, 50.0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapGeometryRatio("bad_denominator", 50.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalCalibrationBootstrapEvidence(
                evidenceReference = "bad-normalized",
                sourceConfidence = .20,
                normalizedGeometry = mapOf("bad" to Double.NaN),
            )
        }
    }

    @Test fun bootstrapCannotOverrideGenericObservabilityOrTrackingGates() {
        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val bootstrap = requireNotNull(
            PersonalCalibrationBootstrapper.bootstrap(
                calibrationProfileId = "personal",
                profileVersion = 1,
                evidence = PersonalCalibrationBootstrapEvidence(
                    evidenceReference = "measurement-session",
                    sourceConfidence = .95,
                    normalizedGeometry = mapOf("arm_to_torso" to 1.08),
                ),
            )
        )

        val generic = AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            bundle.equipment,
        )
        val bootstrapped = AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            bundle.equipment,
            bootstrap,
        )

        assertSame(bundle.profile.cameraProfile, bootstrapped.exerciseProfile.cameraProfile)
        assertEquals(generic.preferredViewClass, bootstrapped.preferredViewClass)
        assertEquals(
            generic.exerciseProfile.cameraProfile.requiredLandmarks,
            bootstrapped.exerciseProfile.cameraProfile.requiredLandmarks,
        )
        assertEquals(
            generic.exerciseProfile.cameraProfile.minVisibleRequiredFraction,
            bootstrapped.exerciseProfile.cameraProfile.minVisibleRequiredFraction,
            0.0,
        )
        assertEquals(
            generic.exerciseProfile.cameraProfile.maxTrackingGapMs,
            bootstrapped.exerciseProfile.cameraProfile.maxTrackingGapMs,
        )
        assertNull(bootstrapped.activeExerciseBaseline)
        assertTrue(bootstrap.cameraSetupPreferences.isEmpty())
    }
}
