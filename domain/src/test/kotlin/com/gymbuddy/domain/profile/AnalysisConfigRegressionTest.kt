package com.gymbuddy.domain.profile

import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test

class AnalysisConfigRegressionTest {
    @Test fun unrelatedExerciseCalibrationDoesNotPoisonResolution() {
        val press = InitialExerciseProfiles.inclineDumbbellPress
        val squat = InitialExerciseProfiles.smithMachineSquat
        val valid = ExerciseBaseline("press-base", 1, "h", ExerciseBaselineKey(
            press.profile.profileId, press.profile.profileVersion, press.equipment?.profileId, press.profile.cameraProfile.preferredViewClass
        ), mapOf("left_rom" to BaselineStatistic(.8, .7, .9, 10, 3, .9)))
        val unrelated = ExerciseBaseline("squat-base", 1, "h", ExerciseBaselineKey(
            squat.profile.profileId, squat.profile.profileVersion, squat.equipment?.profileId, squat.profile.cameraProfile.preferredViewClass
        ), mapOf("squat_only_metric" to BaselineStatistic(.5, .4, .6, 10, 3, .9)))
        val calibration = PersonalCalibrationProfile("personal", 1, "h", .9, exerciseBaselines = listOf(valid, unrelated))
        assertSame(valid, AnalysisConfigResolver.resolve(press.definition, press.profile, press.equipment, calibration).activeExerciseBaseline)
    }

    @Test fun dumbbellProfilesShareOneStableEquipmentIdentity() {
        val press = InitialExerciseProfiles.inclineDumbbellPress.equipment!!
        val raise = InitialExerciseProfiles.dumbbellLateralRaise.equipment!!
        assertSame(press, raise)
        assertEquals(setOf("incline_dumbbell_press", "dumbbell_lateral_raise"), press.compatibleExerciseIds)
    }

    @Test fun shippedCameraContractsMatchExerciseAuthority() {
        val press = InitialExerciseProfiles.inclineDumbbellPress.profile.cameraProfile
        val squat = InitialExerciseProfiles.smithMachineSquat.profile.cameraProfile
        val raise = InitialExerciseProfiles.dumbbellLateralRaise.profile.cameraProfile
        assertEquals(ViewClass.SIDE_OBLIQUE, press.preferredViewClass)
        assertEquals(setOf(ViewClass.SIDE, ViewClass.SIDE_OBLIQUE), squat.allowedViewClasses)
        assertEquals(ViewClass.FRONT, raise.preferredViewClass)
        assertEquals(setOf(ViewClass.FRONT, ViewClass.FRONT_OBLIQUE), raise.allowedViewClasses)
        assertTrue(raise.requiredLandmarks.any { it.landmarkId == "left_wrist" })
        assertTrue(raise.requiredLandmarks.any { it.landmarkId == "right_wrist" })
    }
}
