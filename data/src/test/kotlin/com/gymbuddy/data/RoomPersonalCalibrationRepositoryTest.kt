package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.profile.BaselineStatistic
import com.gymbuddy.domain.profile.ExerciseBaseline
import com.gymbuddy.domain.profile.ExerciseBaselineKey
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef
import com.gymbuddy.domain.profile.ViewClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomPersonalCalibrationRepositoryTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test
    fun activeProfileRoundTripsAllPersonalizationContracts() {
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo=RoomPersonalCalibrationRepository(db.evidenceDao())
            val profile=profile()
            assertNull(repo.loadActive())
            repo.saveActive(profile)
            assertEquals(profile,repo.loadActive())
            repo.clearActive()
            assertNull(repo.loadActive())
        } finally { db.close() }
    }

    @Test
    fun newerActiveProfileReplacesOlderProfileAtomically() {
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo=RoomPersonalCalibrationRepository(db.evidenceDao())
            val first=profile()
            val second=PersonalCalibrationProfile.create(
                calibrationProfileId=first.calibrationProfileId,
                profileVersion=first.profileVersion+1,
                sourceConfidence=.90,
                normalizedBodyGeometry=first.normalizedBodyGeometry,
                cameraSetupPreferences=first.cameraSetupPreferences,
                exerciseBaselines=first.exerciseBaselines,
                equipmentAssociations=first.equipmentAssociations,
                lateralityBaseline=first.lateralityBaseline,
                cueEffectiveness=first.cueEffectiveness,
                evidenceReferences=first.evidenceReferences+"session-4",
            )
            repo.saveActive(first)
            repo.saveActive(second)
            assertEquals(second,repo.loadActive())
            assertEquals(
                first,
                repo.loadVersion(PersonalCalibrationVersionRef.from(first)),
            )
            assertEquals(
                second,
                repo.loadVersion(PersonalCalibrationVersionRef.from(second)),
            )
        } finally { db.close() }
    }

    private fun profile():PersonalCalibrationProfile = PersonalCalibrationProfile.create(
        calibrationProfileId="personal-active",
        profileVersion=3,
        sourceConfidence=.82,
        normalizedBodyGeometry=mapOf("arm_to_torso" to 1.08,"unknown_ratio" to null),
        cameraSetupPreferences=mapOf("camera.prior" to .44),
        exerciseBaselines=listOf(
            ExerciseBaseline(
                profileId="press-baseline",
                profileVersion=2,
                semanticHash="press-baseline-v2",
                key=ExerciseBaselineKey(
                    exerciseProfileId="press-profile",
                    exerciseProfileVersion=3,
                    equipmentProfileId="dumbbells",
                    viewClass=ViewClass.FRONT_OBLIQUE,
                ),
                metricStatistics=mapOf(
                    "left_rom" to BaselineStatistic(.80,.70,.86,30,5,.82),
                    "unknown_metric" to BaselineStatistic(null,null,null,0,3,.50),
                ),
            )
        ),
        equipmentAssociations=setOf("dumbbells"),
        lateralityBaseline=mapOf("left_bias" to .02),
        cueEffectiveness=mapOf("bilateral_asymmetry" to .70),
        evidenceReferences=setOf("session-1","session-2","session-3"),
    )
}
