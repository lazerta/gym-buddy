package com.gymbuddy.app.runtime

import com.gymbuddy.domain.persistence.PersonalCalibrationRepository
import com.gymbuddy.domain.profile.BaselineStatistic
import com.gymbuddy.domain.profile.ExerciseBaseline
import com.gymbuddy.domain.profile.ExerciseBaselineKey
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeAnalysisConfigResolverTest {
    @Test
    fun storedPersonalCalibrationReachesProductionSetConfigAndProvenance() {
        val bundle=InitialExerciseProfiles.inclineDumbbellPress
        val equipment=requireNotNull(bundle.equipment)
        val baseline=ExerciseBaseline(
            profileId="runtime-press-baseline",
            profileVersion=1,
            semanticHash="runtime-press-baseline-v1",
            key=ExerciseBaselineKey(
                exerciseProfileId=bundle.profile.profileId,
                exerciseProfileVersion=bundle.profile.profileVersion,
                equipmentProfileId=equipment.profileId,
                viewClass=bundle.profile.cameraProfile.preferredViewClass,
            ),
            metricStatistics=mapOf(
                "bilateral_asymmetry" to BaselineStatistic(.14,.08,.22,30,5,.82),
            ),
        )
        val calibration=PersonalCalibrationProfile.create(
            calibrationProfileId="runtime-personal",
            profileVersion=3,
            sourceConfidence=.85,
            exerciseBaselines=listOf(baseline),
            equipmentAssociations=setOf(equipment.profileId),
            evidenceReferences=setOf("session-1","session-2","session-3"),
        )
        val config=RuntimeAnalysisConfigResolver(FakeCalibrationRepository(calibration)).resolve(bundle)

        assertEquals(calibration,config.personalCalibrationProfile)
        assertNotNull(config.activeExerciseBaseline)
        assertEquals(
            PersonalCalibrationVersionRef.from(calibration),
            config.provenance.personalCalibrationProfile,
        )
    }

    @Test
    fun missingCalibrationKeepsGenericFallback() {
        val bundle=InitialExerciseProfiles.inclineDumbbellPress
        val config=RuntimeAnalysisConfigResolver(FakeCalibrationRepository(null)).resolve(bundle)
        assertNull(config.personalCalibrationProfile)
        assertNull(config.activeExerciseBaseline)
        assertNull(config.provenance.personalCalibrationProfile)
    }

    private class FakeCalibrationRepository(
        private val profile:PersonalCalibrationProfile?,
    ):PersonalCalibrationRepository{
        override fun loadActive():PersonalCalibrationProfile?=profile
        override fun saveActive(profile:PersonalCalibrationProfile)=Unit
        override fun clearActive()=Unit
    }
}
