package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.camera.PersonalCameraPriorCodec
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomCalibrationContextRegressionTest {
    @Test fun exercisesInTheSameThreeWorkoutsLearnIndependently() = withFixture { f ->
        val a=regressionConfig()
        val b=regressionConfig("raise")
        (1..3).forEach { f.record(a,it,value=.11+it*.01) }
        val original=f.active().exerciseBaselines.single()
        (1..3).forEach { f.record(b,it,value=.21+it*.01) }
        val active=f.active()
        assertEquals(setOf(a.exerciseProfile.profileId,b.exerciseProfile.profileId),
            active.exerciseBaselines.map { it.key.exerciseProfileId }.toSet())
        assertEquals(original,active.exerciseBaselines.single { it.key.exerciseProfileId==a.exerciseProfile.profileId })
        assertNotNull(f.prior(a))
        assertNotNull(f.prior(b))
    }

    @Test fun allowedViewsInTheSameWorkoutsLearnIndependently() = withFixture { f ->
        val c=regressionConfig()
        (1..3).forEach { f.record(c,it,view=ViewClass.SIDE) }
        (1..3).forEach { f.record(c,it,ordinal=2,view=ViewClass.SIDE_OBLIQUE,value=.2) }
        assertEquals(setOf(ViewClass.SIDE,ViewClass.SIDE_OBLIQUE),
            f.active().exerciseBaselines.map { it.key.viewClass }.toSet())
        assertNotNull(f.prior(c,ViewClass.SIDE))
        assertNotNull(f.prior(c,ViewClass.SIDE_OBLIQUE))
    }

    @Test fun equipmentContextsInTheSameWorkoutsLearnIndependently() = withFixture { f ->
        val a=regressionConfig()
        val b=regressionConfig(equipment="equipment-b")
        (1..3).forEach { f.record(a,it) }
        (1..3).forEach { f.record(b,it,value=.2) }
        assertEquals(setOf("equipment-a","equipment-b"),
            f.active().exerciseBaselines.map { it.key.equipmentProfileId }.toSet())
        assertNotNull(f.prior(a))
        assertNotNull(f.prior(b))
    }

    @Test fun cameraOnlyLearningHasItsOwnContextScopedReceipts() = withFixture(
        MultiSessionCalibrationUpdater(MultiSessionCalibrationPolicy(
            minimumEligibleSessions=4,driftConsecutiveSessions=4)),
    ) { f ->
        val a=regressionConfig()
        val b=regressionConfig("raise")
        (1..3).forEach { f.record(a,it) }
        assertTrue(f.active().exerciseBaselines.isEmpty())
        (1..3).forEach { f.record(b,it) }
        assertNotNull(f.prior(a))
        assertNotNull(f.prior(b))
        assertTrue(f.active().exerciseBaselines.isEmpty())
    }

    @Test fun clearAndRebuildPreservesPublishedHistoryAndWorkoutRows() = withFixture { f ->
        val c=regressionConfig()
        val oldSetIds=(1..3).map { f.record(c,it) }
        val old=f.active()
        val oldRef=PersonalCalibrationVersionRef.from(old)
        f.calibration.clearActive()
        val newSetIds=(4..6).map { f.record(c,it,value=.24,promote=false) }
        f.lifecycle.onCompletedSet(newSetIds.last(),c)
        val rebuilt=f.active()
        assertNotEquals(old.calibrationProfileId,rebuilt.calibrationProfileId)
        assertEquals(old,f.calibration.loadVersion(oldRef))
        (oldSetIds+newSetIds).forEach { assertNotNull(f.evidence.loadSet(it)) }
    }

    @Test fun retryingACompletedSetDoesNotRepublishTheSameEvidence() = withFixture { f ->
        val c=regressionConfig()
        val last=(1..3).map { f.record(c,it) }.last()
        val old=f.active()
        repeat(3) { f.lifecycle.onCompletedSet(last,c) }
        assertEquals(old,f.active())
    }

    @Test fun scopingDoesNotTurnSetsIntoWorkoutSessions() = withFixture { f ->
        val c=regressionConfig()
        (1..3).forEach { f.record(c,1,ordinal=it) }
        assertNull(f.calibration.loadActive())
    }

    @Test fun lowConfidenceAndInterruptedSessionsRemainIneligible() = withFixture { f ->
        val c=regressionConfig()
        (1..3).forEach { f.record(c,it,confidence=.2) }
        (4..6).forEach { f.record(c,it,interrupted=true) }
        assertNull(f.calibration.loadActive())
        (7..9).forEach { f.record(c,it) }
        assertEquals(3,f.active().exerciseBaselines.single().metricStatistics.getValue("metric").sessionCount)
    }

    @Test fun legacyReceiptsDoNotBlockAnotherExerciseOrReplayIntoAnExistingOne() = withFixture { f ->
        val a=regressionConfig()
        val b=regressionConfig("raise")
        (1..3).forEach { f.record(a,it) }
        val original=f.active()
        val legacyRefs=(1..3).flatMap { listOf("movement-session:workout-$it","camera-session:workout-$it") }.toSet()
        f.calibration.saveActive(withReceipts(original,legacyRefs))
        (1..3).forEach { f.record(b,it,value=.2) }
        assertEquals(2,f.active().exerciseBaselines.size)
        assertNotNull(f.prior(b))
        val before=f.active()
        f.record(a,4,value=.28)
        assertEquals(before,f.active())
    }

    @Test fun targetResetRelearnsFromFreshSessionsWithoutChangingOtherTargets() = withFixture { f ->
        val a=regressionConfig()
        val b=regressionConfig("raise")
        (1..3).forEach { f.record(a,it); f.record(b,it,value=.2) }
        val old=f.active()
        val other=old.exerciseBaselines.single { it.key.exerciseProfileId==b.exerciseProfile.profileId }
        val otherCamera=f.prior(b)
        assertTrue(f.lifecycle.resetTarget(a))
        assertEquals(listOf(other),f.active().exerciseBaselines)
        assertNull(f.prior(a))
        f.record(a,4,value=.15)
        f.record(a,5,value=.16)
        assertEquals(listOf(other),f.active().exerciseBaselines)
        f.record(a,6,value=.17)
        assertEquals(other,f.active().exerciseBaselines.single { it.key==other.key })
        assertEquals(otherCamera,f.prior(b))
        assertEquals(3,f.active().exerciseBaselines.single { it.key.exerciseProfileId==a.exerciseProfile.profileId }
            .metricStatistics.getValue("metric").sessionCount)
        assertEquals(old,f.calibration.loadVersion(PersonalCalibrationVersionRef.from(old)))
    }

    @Test fun legacyResetDoesNotImmediatelyRelearnDiscardedEvidence() = withFixture { f ->
        val c=regressionConfig()
        (1..3).forEach { f.record(c,it) }
        val legacyRefs=(1..3).flatMap { listOf("movement-session:workout-$it","camera-session:workout-$it") }.toSet()
        f.calibration.saveActive(withReceipts(f.active(),legacyRefs))
        assertTrue(f.lifecycle.resetTarget(c))
        f.record(c,4,value=.17)
        assertTrue(f.active().exerciseBaselines.isEmpty())
        assertNull(f.prior(c))
        f.record(c,5,value=.18)
        f.record(c,6,value=.19)
        val statistic=f.active().exerciseBaselines.single().metricStatistics.getValue("metric")
        assertEquals(3,statistic.sessionCount)
        assertEquals(.18,statistic.median!!,1e-9)
    }

    @Test fun corruptActiveResetCanRebuildWithoutCollidingWithHistory() = withFixture { f ->
        val c=regressionConfig()
        (1..3).forEach { f.record(c,it) }
        val old=f.active()
        val row=requireNotNull(f.dao.personalCalibration("active"))
        f.dao.upsertPersonalCalibration(row.copy(payload="corrupt"))
        assertTrue(f.lifecycle.resetTarget(c))
        assertNull(f.calibration.loadActive())
        val last=(4..6).map { f.record(c,it,value=.24,promote=false) }.last()
        f.lifecycle.onCompletedSet(last,c)
        assertNotEquals(old.calibrationProfileId,f.active().calibrationProfileId)
        assertEquals(old,f.calibration.loadVersion(PersonalCalibrationVersionRef.from(old)))
    }

    private fun withReceipts(p:PersonalCalibrationProfile,refs:Set<String>) = PersonalCalibrationProfile.create(
        p.calibrationProfileId,p.profileVersion+1,p.sourceConfidence,p.normalizedBodyGeometry,
        p.cameraSetupPreferences,p.exerciseBaselines,p.equipmentAssociations,p.lateralityBaseline,
        p.cueEffectiveness,refs,
    )

    private fun withFixture(
        updater:MultiSessionCalibrationUpdater=MultiSessionCalibrationUpdater(),
        block:(Fixture)->Unit,
    ) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(Fixture(db.evidenceDao(),updater)) } finally { db.close() }
    }

    private class Fixture(val dao:EvidenceDao,updater:MultiSessionCalibrationUpdater) {
        val evidence=RoomEvidenceRepository(dao)
        val calibration=RoomPersonalCalibrationRepository(dao)
        val lifecycle=RoomPersonalCalibrationLifecycle(dao,updater=updater)
        fun active()=requireNotNull(calibration.loadActive())
        fun prior(c:AnalysisConfig,view:ViewClass=ViewClass.SIDE)=PersonalCameraPriorCodec.resolve(
            calibration.loadActive(),c.exerciseProfile.cameraProfile,c.equipmentProfile?.profileId,view)

        fun record(
            c:AnalysisConfig,session:Int,ordinal:Int=1,view:ViewClass=ViewClass.SIDE,
            value:Double=.12,confidence:Double=.95,interrupted:Boolean=false,promote:Boolean=true,
        ):String {
            val sid="workout-$session"
            val start=session*100_000L
            val epoch=1_700_000_000_000L+start
            val eid="$sid/${c.exerciseDefinition.exerciseId}/${c.equipmentProfile!!.profileId}"
            val id="$eid/set-$ordinal"
            val setStart=start+ordinal*1_000L
            evidence.ensureSession(WorkoutSessionRecord(sid,start,epoch))
            evidence.ensureExecution(ExerciseExecutionRecord(eid,sid,c.exerciseDefinition.exerciseId,start+100L,epoch+100L))
            evidence.openSet(SetRecord(id,eid,ordinal,setStart,null,epoch+ordinal*1_000L),c)
            val rep=RepEvidence(
                repId="$id/rep-1",ordinal=1,stepId="cycle",primitive=MovementPrimitive.PRESS,
                startedAtUs=setStart+100L,completedAtUs=setStart+600L,classification=RepClassification.NORMAL,
                signals=emptyMap(),metrics=mapOf("metric" to MetricEvidence("metric",SignalUnit.NORMALIZED,EvidenceValue.Known(value,confidence))),
                provenance=c.provenance,
            )
            val observation=FormObservation("$id/observation",rep.repId,"form",1,FormObservationState.OK,FormRuleSeverity.MINOR,.95,value)
            evidence.persistCompletedRepBundle(id,rep,listOf(observation),emptyList(),emptyList())
            evidence.upsertTrackingSummary(TrackingQualitySummary(
                setId=id,observableFrames=5,degradedFrames=0,pausedFrames=0,unknownFrames=0,
                activeObservableFrames=5,activeDegradedFrames=0,activePausedFrames=0,activeUnknownFrames=0,
                interruptionEpisodes=if(interrupted)1 else 0,cameraDisturbanceEpisodes=0,
                observedViewClass=view,activeFrameFillMean=.50,
            ))
            evidence.finishSet(SetSummary(id,setStart+900L,1,0,0,epoch+ordinal*1_000L+900L))
            if(promote)lifecycle.onCompletedSet(id,c)
            return id
        }
    }
}

private fun regressionConfig(exercise:String="press",equipment:String="equipment-a"):AnalysisConfig {
 val definition=ExerciseDefinition(exercise,1,"definition-$exercise",exercise,movementFamily=MovementFamily.PRESS)
 val camera=CameraProfile("camera-$exercise",1,"camera-hash-$exercise",ViewClass.SIDE,
  setOf(ViewClass.SIDE,ViewClass.SIDE_OBLIQUE),setOf(LensFacing.BACK),
  setOf(LandmarkRequirement("left_shoulder",.5)),NumericRange(.3,.8),.8,300,
  setOf(CameraGuidanceAction.CAMERA_READY,CameraGuidanceAction.CANNOT_ASSESS))
 val signals=SignalProfile("signals-$exercise",1,"signals-hash",listOf(SignalDefinition("signal",SignalKind.CONFIDENCE,SignalUnit.NORMALIZED,setOf("left_shoulder"))))
 val sequence=MovementPrimitiveSequence("sequence-$exercise",1,"sequence-hash",listOf(MovementPrimitiveStep("cycle",MovementPrimitive.PRESS,listOf("signal"))))
 val metrics=MetricProfile("metrics-$exercise",1,"metrics-hash",listOf(MetricDefinition("metric",setOf("signal"),SignalUnit.NORMALIZED)))
 val forms=FormRuleSet("forms-$exercise",1,"forms-hash",listOf(FormRule("form",1,setOf("signal"),.6,FormRuleSeverity.MINOR,threshold=.3)))
 val cues=CuePolicy("cues-$exercise",1,"cues-hash",3,2,1000,2)
 val profile=ExerciseProfile("profile-$exercise",1,"profile-hash-$exercise",exercise,LateralityMode.BILATERAL,setOf(EquipmentType.DUMBBELL),camera,signals,sequence,metrics,forms,cues,setOf(ProfileCapability.CAMERA_GUIDANCE,ProfileCapability.REP_DETECTION,ProfileCapability.FORM_ANALYSIS))
 return AnalysisConfigResolver.resolve(definition,profile,EquipmentProfile(equipment,1,"equipment-hash",EquipmentType.DUMBBELL,setOf(exercise)))
}
