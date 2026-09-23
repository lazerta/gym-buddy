package com.gymbuddy.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCalibrationProfileTest {
    @Test fun canonicalHashIsOrderIndependentAndBehaviorSensitive() {
        val press = baseline(
            profileId = "press-baseline",
            semanticHash = "press-baseline-v1",
            exerciseProfileId = "incline-dumbbell-press-profile",
            equipmentProfileId = "dumbbell-generic",
            viewClass = ViewClass.SIDE_OBLIQUE,
            metrics = linkedMapOf(
                "right_rom" to BaselineStatistic(.82, .75, .91, 40, 5, .90),
                "left_rom" to BaselineStatistic(.81, .74, .90, 40, 5, .90),
            ),
        )
        val squat = baseline(
            profileId = "squat-baseline",
            semanticHash = "squat-baseline-v1",
            exerciseProfileId = "smith-squat-profile",
            equipmentProfileId = "smith-generic",
            viewClass = ViewClass.SIDE,
            metrics = mapOf(
                "left_rom" to BaselineStatistic(.88, .80, .95, 32, 4, .85),
            ),
        )

        val first = PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 7,
            sourceConfidence = .90,
            normalizedBodyGeometry = linkedMapOf("arm_ratio" to 1.08, "unknown_height" to null),
            cameraSetupPreferences = linkedMapOf("tripod_height" to .62, "distance" to 1.8),
            exerciseBaselines = listOf(press, squat),
            equipmentAssociations = linkedSetOf("smith-generic", "dumbbell-generic"),
            lateralityBaseline = linkedMapOf("asymmetry" to .04),
            cueEffectiveness = linkedMapOf("bilateral_asymmetry" to .73),
            evidenceReferences = linkedSetOf("set-2", "set-1"),
        )
        val reordered = PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 7,
            sourceConfidence = .90,
            normalizedBodyGeometry = linkedMapOf("unknown_height" to null, "arm_ratio" to 1.08),
            cameraSetupPreferences = linkedMapOf("distance" to 1.8, "tripod_height" to .62),
            exerciseBaselines = listOf(
                squat,
                press.copy(metricStatistics = press.metricStatistics.toSortedMap()),
            ),
            equipmentAssociations = linkedSetOf("dumbbell-generic", "smith-generic"),
            lateralityBaseline = linkedMapOf("asymmetry" to .04),
            cueEffectiveness = linkedMapOf("bilateral_asymmetry" to .73),
            evidenceReferences = linkedSetOf("set-1", "set-2"),
        )

        assertEquals(first.semanticHash, reordered.semanticHash)
        assertEquals(first.semanticHash, PersonalCalibrationSemanticHash.compute(first))
        assertEquals(64, first.semanticHash.length)
        assertTrue(first.semanticHash.all { it in '0'..'9' || it in 'a'..'f' })

        val changed = PersonalCalibrationProfile.create(
            calibrationProfileId = "personal",
            profileVersion = 8,
            sourceConfidence = .90,
            normalizedBodyGeometry = first.normalizedBodyGeometry,
            cameraSetupPreferences = first.cameraSetupPreferences,
            exerciseBaselines = listOf(
                press.copy(
                    metricStatistics = press.metricStatistics + (
                        "left_rom" to BaselineStatistic(.84, .74, .90, 40, 5, .90)
                    )
                ),
                squat,
            ),
            equipmentAssociations = first.equipmentAssociations,
            lateralityBaseline = first.lateralityBaseline,
            cueEffectiveness = first.cueEffectiveness,
            evidenceReferences = first.evidenceReferences,
        )
        assertNotEquals(first.semanticHash, changed.semanticHash)
    }

    @Test fun schemaRejectsDuplicateBaselineKeysAndInvalidOptionalData() {
        val one = baseline(
            profileId = "baseline-one",
            semanticHash = "baseline-one-v1",
            exerciseProfileId = "press-profile",
            equipmentProfileId = "dumbbell-generic",
            viewClass = ViewClass.SIDE_OBLIQUE,
            metrics = mapOf(
                "left_rom" to BaselineStatistic(.8, .7, .9, 12, 3, .8)
            ),
        )
        val duplicateKey = one.copy(
            profileId = "baseline-two",
            semanticHash = "baseline-two-v1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PersonalCalibrationProfile.create(
                calibrationProfileId = "personal",
                profileVersion = 1,
                sourceConfidence = .8,
                exerciseBaselines = listOf(one, duplicateKey),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalCalibrationProfile.create(
                calibrationProfileId = "personal",
                profileVersion = 1,
                sourceConfidence = .8,
                normalizedBodyGeometry = mapOf("bad" to Double.NaN),
            )
        }
    }

    private fun baseline(
        profileId: String,
        semanticHash: String,
        exerciseProfileId: String,
        equipmentProfileId: String?,
        viewClass: ViewClass?,
        metrics: Map<String, BaselineStatistic>,
    ) = ExerciseBaseline(
        profileId = profileId,
        profileVersion = 1,
        semanticHash = semanticHash,
        key = ExerciseBaselineKey(
            exerciseProfileId = exerciseProfileId,
            exerciseProfileVersion = 3,
            equipmentProfileId = equipmentProfileId,
            viewClass = viewClass,
        ),
        metricStatistics = metrics,
    )
}
