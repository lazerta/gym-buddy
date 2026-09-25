package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.camera.PersonalCameraPriorCodec
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservation
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.evidence.MetricEvidence
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.TrackingQualitySummary
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.FormRuleSeverity
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef
import com.gymbuddy.domain.profile.SignalUnit
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomCalibrationCompositionRegressionTest {
    private val context:Context
        get()=ApplicationProvider.getApplicationContext()

    @Test fun oneWorkoutCanContributeIndependentlyToTwoExercises() = withDb { db ->
        val press=Fixture(db,InitialExerciseProfiles.inclineDumbbellPress)
        val raise=Fixture(db,InitialExerciseProfiles.dumbbellLateralRaise)
        (1..3).forEach { i ->
            press.persistSet("workout-$i",i,1,.12)
            raise.persistSet("workout-$i",i,1,.13)
        }
        val profile=requireNotNull(press.calibration.loadActive())
        assertEquals(setOf(press.bundle.profile.profileId,raise.bundle.profile.profileId),
            profile.exerciseBaselines.map{it.key.exerciseProfileId}.toSet())
        listOf(press,raise).forEach { f ->
            assertNotNull(PersonalCameraPriorCodec.resolve(profile,f.bundle.profile.cameraProfile,
                f.bundle.equipment?.profileId,f.preferredView))
        }
    }

    @Test fun oneWorkoutCanContributeIndependentlyToTwoAllowedViews() = withDb { db ->
        val f=Fixture(db)
        val alternate=f.bundle.profile.cameraProfile.allowedViewClasses.first{it!=f.preferredView}
        (1..3).forEach { i ->
            f.persistSet("workout-$i",i,1,.10,view=f.preferredView)
            f.persistSet("workout-$i",i,2,.15,view=alternate)
        }
        val profile=requireNotNull(f.calibration.loadActive())
        assertEquals(setOf(f.preferredView,alternate),profile.exerciseBaselines.map{it.key.viewClass}.toSet())
    }

    @Test fun interruptedAttemptDisqualifiesOtherwiseCleanWorkoutEvidence() = withDb { db ->
        val f=Fixture(db)
        (1..3).forEach { i ->
            val clean=f.persistSet("workout-$i",i,1,.12,promote=false)
            f.persistSet("workout-$i",i,2,.12,promote=false,interrupted=true)
            f.lifecycle.onCompletedSet(clean,f.generic)
        }
        assertNull("An interrupted sibling set must not disappear from session eligibility",f.calibration.loadActive())
    }

    @Test fun mixedViewInterruptedSetCannotBeFilteredOutBeforeSessionQualityCheck() = withDb { db ->
        val f=Fixture(db)
        (1..3).forEach { i ->
            val clean=f.persistSet("workout-$i",i,1,.12,promote=false)
            f.persistSet("workout-$i",i,2,.12,view=null,interruptionEpisodes=1,promote=false)
            f.lifecycle.onCompletedSet(clean,f.generic)
        }
        assertNull("Unknown-view interrupted evidence must not make a workout look clean",f.calibration.loadActive())
    }

    @Test fun clearingActiveCalibrationCanRebuildWithoutReusingAnImmutableProfileVersion() = withDb { db ->
        val f=Fixture(db)
        (1..3).forEach { i -> f.persistSet("old-$i",i,1,.12) }
        val original=requireNotNull(f.calibration.loadActive())
        f.calibration.clearActive()
        // A rebuild is a new calibration history, not permission to overwrite v1.
        (4..6).forEach { i -> f.persistSet("new-$i",i,1,.15) }
        val rebuilt=requireNotNull(f.calibration.loadActive())
        assertNotEquals(PersonalCalibrationVersionRef.from(original),PersonalCalibrationVersionRef.from(rebuilt))
        assertEquals(original,f.calibration.loadVersion(PersonalCalibrationVersionRef.from(original)))
    }

    @Test fun calibrationPublicationFailureRollsBackHistoryAndActiveTogether() = withDb { db ->
        val repo=RoomPersonalCalibrationRepository(db.evidenceDao())
        val profile=PersonalCalibrationProfile.create("transaction-test",1,.9)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_calibration_publication BEFORE INSERT ON personal_calibration_profiles
            BEGIN SELECT RAISE(ABORT, 'injected publication failure'); END
        """.trimIndent())
        assertThrows(Exception::class.java) { repo.saveActive(profile) }
        assertNull(repo.loadActive())
        assertNull("Failed publication must not reserve an immutable version",
            repo.loadVersion(PersonalCalibrationVersionRef.from(profile)))
    }

    @Test fun datedHistoryIncludesLegacySetsEvenWhenLegacyUptimeIsNumericallyLarger() = withDb { db ->
        val f=Fixture(db)
        val legacy=f.persistSet("legacy",1,1,.12,promote=false,
            sessionStartOverride=6_000_000_000_000L,epochOverride=0L)
        val current=f.persistSet("dated",2,1,.12,promote=false,
            sessionStartOverride=1_000L,epochOverride=1_800_000_000_000L)
        val summary=requireNotNull(db.evidenceDao().setSummary(current))
        assertEquals(listOf(legacy),db.evidenceDao().recentComparableSetIds(
            f.bundle.definition.exerciseId,current,summary.endedAtEpochMs,10))
        assertEquals(listOf("legacy"),db.evidenceDao().recentComparableSessionIds(
            f.bundle.definition.exerciseId,"dated",1_800_000_000_000L,10))
    }

    private fun withDb(block:(GymBuddyDatabase)->Unit){
        val db=Room.inMemoryDatabaseBuilder(
            context,GymBuddyDatabase::class.java
        ).allowMainThreadQueries().build()
        try{
            block(db)
        }finally{
            db.close()
        }
    }

    private class Fixture(
        db:GymBuddyDatabase,
        val bundle:ExerciseBundle=InitialExerciseProfiles.inclineDumbbellPress,
    ){
        private val dao=db.evidenceDao()
        val evidence=RoomEvidenceRepository(dao)
        val lifecycle=RoomPersonalCalibrationLifecycle(dao)
        val calibration=RoomPersonalCalibrationRepository(dao)
        val generic:AnalysisConfig=AnalysisConfigResolver.resolve(
            bundle.definition,bundle.profile,bundle.equipment
        )
        val preferredView:ViewClass=
            bundle.profile.cameraProfile.preferredViewClass

        fun persistSet(
            sessionId:String,
            sessionIndex:Int,
            setOrdinal:Int,
            metricValue:Double,
            view:ViewClass?=preferredView,
            metricConfidence:Double=.95,
            formState:FormObservationState=FormObservationState.OK,
            activeObservable:Int=5,
            activeDegraded:Int=0,
            activePaused:Int=0,
            activeUnknown:Int=0,
            interruptionEpisodes:Int=0,
            cameraDisturbanceEpisodes:Int=0,
            frameFill:Double=.50,
            promote:Boolean=true,
            interrupted:Boolean=false,
            sessionStartOverride:Long?=null,
            epochOverride:Long?=null,
        ):String{
            val sessionStartUs=sessionStartOverride?:sessionIndex*100_000L
            val sessionEpoch=epochOverride?:(1_700_000_000_000L+sessionIndex*100_000L)
            val executionId=sessionId+"-"+bundle.definition.exerciseId+"-exec"
            val setId=executionId+"-set-"+setOrdinal
            val setStartUs=sessionStartUs+setOrdinal*1_000L
            val setEpoch=if(sessionEpoch==0L)0L else sessionEpoch+setOrdinal*1_000L

            evidence.ensureSession(
                WorkoutSessionRecord(
                    sessionId,
                    sessionStartUs,
                    sessionEpoch,
                )
            )
            evidence.ensureExecution(
                ExerciseExecutionRecord(
                    executionId,
                    sessionId,
                    bundle.definition.exerciseId,
                    sessionStartUs+100L,
                    if(sessionEpoch==0L)0L else sessionEpoch+100L,
                )
            )
            evidence.openSet(
                SetRecord(
                    setId,
                    executionId,
                    setOrdinal,
                    setStartUs,
                    null,
                    setEpoch,
                ),
                generic,
            )

            if(interrupted){
                RoomWorkoutFlowRepository(dao).markInterruptedSet(setId,1_800_000_000_000L,0)
                return setId
            }
            val rep=RepEvidence(
                repId=setId+"/rep-1",
                ordinal=1,
                stepId="cycle",
                primitive=bundle.profile.movementPrimitiveSequence.steps.first().primitive,
                startedAtUs=setStartUs+100L,
                completedAtUs=setStartUs+600L,
                classification=RepClassification.NORMAL,
                signals=emptyMap(),
                metrics=mapOf(
                    "bilateral_asymmetry" to MetricEvidence(
                        "bilateral_asymmetry",
                        SignalUnit.NORMALIZED,
                        EvidenceValue.Known(metricValue,metricConfidence),
                    )
                ),
                provenance=generic.provenance,
            )
            val rule=bundle.profile.formRuleSet.rules.single{
                it.ruleId=="bilateral_asymmetry"
            }
            val observation=FormObservation(
                observationId=setId+"/obs-bilateral",
                repId=rep.repId,
                ruleId=rule.ruleId,
                ruleVersion=rule.ruleVersion,
                state=formState,
                severity=FormRuleSeverity.MINOR,
                confidence=.95,
                evidenceValue=metricValue,
            )
            evidence.persistCompletedRepBundle(
                setId,
                rep,
                listOf(observation),
                emptyList(),
                emptyList(),
            )

            evidence.upsertTrackingSummary(
                TrackingQualitySummary(
                    setId=setId,
                    observableFrames=activeObservable,
                    degradedFrames=activeDegraded,
                    pausedFrames=activePaused,
                    unknownFrames=activeUnknown,
                    activeObservableFrames=activeObservable,
                    activeDegradedFrames=activeDegraded,
                    activePausedFrames=activePaused,
                    activeUnknownFrames=activeUnknown,
                    interruptionEpisodes=interruptionEpisodes,
                    cameraDisturbanceEpisodes=cameraDisturbanceEpisodes,
                    observedViewClass=view,
                    activeFrameFillMean=frameFill,
                )
            )
            evidence.finishSet(
                SetSummary(
                    setId=setId,
                    endedAtUs=setStartUs+900L,
                    completedReps=1,
                    assistedReps=0,
                    uncertainReps=0,
                    endedAtEpochMs=if(setEpoch==0L)0L else setEpoch+900L,
                )
            )
            if(promote)lifecycle.onCompletedSet(setId,generic)
            return setId
        }
    }
}
