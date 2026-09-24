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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomPersonalCalibrationLifecycleTest {
    private val context:Context
        get()=ApplicationProvider.getApplicationContext()

    @Test
    fun threeSetsInOneWorkoutDoNotCountAsThreeCalibrationSessions(){
        withDb{db->
            val fixture=Fixture(db)
            repeat(3){index->
                fixture.persistSet(
                    sessionId="session-1",
                    sessionIndex=1,
                    setOrdinal=index+1,
                    metricValue=.12+index*.005,
                )
            }
            assertNull(fixture.calibration.loadActive())

            fixture.persistSet(
                sessionId="session-2",
                sessionIndex=2,
                setOrdinal=1,
                metricValue=.13,
            )
            assertNull(fixture.calibration.loadActive())

            fixture.persistSet(
                sessionId="session-3",
                sessionIndex=3,
                setOrdinal=1,
                metricValue=.14,
            )

            val active=requireNotNull(fixture.calibration.loadActive())
            val baseline=active.exerciseBaselines.single{
                it.key.viewClass==fixture.preferredView
            }
            assertEquals(
                3,
                baseline.metricStatistics
                    .getValue("bilateral_asymmetry").sessionCount,
            )
            assertEquals(
                setOf(
                    "movement-session:session-1",
                    "movement-session:session-2",
                    "movement-session:session-3",
                ),
                active.evidenceReferences.filter{
                    it.startsWith("movement-session:")
                }.toSet(),
            )

            val next=AnalysisConfigResolver.resolve(
                fixture.bundle.definition,
                fixture.bundle.profile,
                fixture.bundle.equipment,
                active,
            )
            assertNotNull(next.activeExerciseBaseline)
            assertEquals(
                PersonalCalibrationVersionRef.from(active),
                next.provenance.personalCalibrationProfile,
            )

            val camera=PersonalCameraPriorCodec.resolve(
                calibration=active,
                profile=fixture.bundle.profile.cameraProfile,
                equipmentProfileId=fixture.bundle.equipment?.profileId,
                viewClass=fixture.preferredView,
            )
            assertNotNull(camera)
            assertEquals(3,camera!!.sessionCount)

            fixture.lifecycle.resetTarget(next)
            val reset=requireNotNull(fixture.calibration.loadActive())
            val resetConfig=AnalysisConfigResolver.resolve(
                fixture.bundle.definition,
                fixture.bundle.profile,
                fixture.bundle.equipment,
                reset,
            )
            assertNull(resetConfig.activeExerciseBaseline)
            assertNull(
                PersonalCameraPriorCodec.resolve(
                    reset,
                    fixture.bundle.profile.cameraProfile,
                    fixture.bundle.equipment?.profileId,
                    fixture.preferredView,
                )
            )
            assertNotNull(fixture.evidence.loadSet("session-1-set-1"))
        }
    }

    @Test
    fun trackingInterruptionCameraConfidenceAndBadFormEvidenceAreRejected(){
        withDb{db->
            val fixture=Fixture(db)

            fixture.persistSet(
                "tracking-poor",1,1,.12,
                activeObservable=1,
                activeDegraded=3,
            )
            fixture.persistSet(
                "interrupted",2,1,.12,
                interruptionEpisodes=1,
            )
            fixture.persistSet(
                "camera-disturbed",3,1,.12,
                interruptionEpisodes=1,
                cameraDisturbanceEpisodes=1,
            )
            fixture.persistSet(
                "low-confidence",4,1,.12,
                metricConfidence=.20,
            )
            fixture.persistSet(
                "bad-form",5,1,.40,
                formState=FormObservationState.DEVIATION,
            )

            assertNull(fixture.calibration.loadActive())

            fixture.persistSet("clean-1",6,1,.12)
            fixture.persistSet("clean-2",7,1,.13)
            fixture.persistSet("clean-3",8,1,.14)

            val active=requireNotNull(fixture.calibration.loadActive())
            val baseline=active.exerciseBaselines.single()
            assertEquals(
                3,
                baseline.metricStatistics
                    .getValue("bilateral_asymmetry").sessionCount,
            )
            val movementRefs=active.evidenceReferences
                .filter{it.startsWith("movement-session:")}
                .toSet()
            assertEquals(
                setOf(
                    "movement-session:clean-1",
                    "movement-session:clean-2",
                    "movement-session:clean-3",
                ),
                movementRefs,
            )
        }
    }

    @Test
    fun movementAndCameraCalibrationRemainScopedToActualObservedView(){
        withDb{db->
            val fixture=Fixture(db)
            val alternate=fixture.bundle.profile.cameraProfile.allowedViewClasses
                .first{it!=fixture.preferredView}

            fixture.persistSet("alt-1",1,1,.10,view=alternate,frameFill=.42)
            fixture.persistSet("alt-2",2,1,.11,view=alternate,frameFill=.43)
            fixture.persistSet("alt-3",3,1,.12,view=alternate,frameFill=.44)

            var active=requireNotNull(fixture.calibration.loadActive())
            var altBaseline=active.exerciseBaselines.single{
                it.key.viewClass==alternate
            }
            assertEquals(
                .11,
                altBaseline.metricStatistics
                    .getValue("bilateral_asymmetry").median!!,
                1e-9,
            )
            assertNotNull(
                PersonalCameraPriorCodec.resolve(
                    active,
                    fixture.bundle.profile.cameraProfile,
                    fixture.bundle.equipment?.profileId,
                    alternate,
                )
            )

            fixture.persistSet("preferred-1",4,1,.15,frameFill=.52)
            fixture.persistSet("preferred-2",5,1,.16,frameFill=.53)
            fixture.persistSet("preferred-3",6,1,.17,frameFill=.54)

            active=requireNotNull(fixture.calibration.loadActive())
            val byView=active.exerciseBaselines.associateBy{it.key.viewClass}
            assertEquals(setOf(alternate,fixture.preferredView),byView.keys)
            altBaseline=requireNotNull(byView[alternate])
            val preferred=requireNotNull(byView[fixture.preferredView])
            assertEquals(
                .11,
                altBaseline.metricStatistics
                    .getValue("bilateral_asymmetry").median!!,
                1e-9,
            )
            assertEquals(
                .16,
                preferred.metricStatistics
                    .getValue("bilateral_asymmetry").median!!,
                1e-9,
            )
            assertNotNull(
                PersonalCameraPriorCodec.resolve(
                    active,
                    fixture.bundle.profile.cameraProfile,
                    fixture.bundle.equipment?.profileId,
                    fixture.preferredView,
                )
            )
        }
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
            view:ViewClass=preferredView,
            metricConfidence:Double=.95,
            formState:FormObservationState=FormObservationState.OK,
            activeObservable:Int=5,
            activeDegraded:Int=0,
            activePaused:Int=0,
            activeUnknown:Int=0,
            interruptionEpisodes:Int=0,
            cameraDisturbanceEpisodes:Int=0,
            frameFill:Double=.50,
        ){
            val sessionStartUs=sessionIndex*100_000L
            val sessionEpoch=1_700_000_000_000L+sessionIndex*100_000L
            val executionId=sessionId+"-exec"
            val setId=sessionId+"-set-"+setOrdinal
            val setStartUs=sessionStartUs+setOrdinal*1_000L
            val setEpoch=sessionEpoch+setOrdinal*1_000L

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
                    sessionEpoch+100L,
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

            val rep=RepEvidence(
                repId=setId+"/rep-1",
                ordinal=1,
                stepId="cycle",
                primitive=MovementPrimitive.PRESS,
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
                    endedAtEpochMs=setEpoch+900L,
                )
            )
            lifecycle.onCompletedSet(setId,generic)
        }
    }
}
