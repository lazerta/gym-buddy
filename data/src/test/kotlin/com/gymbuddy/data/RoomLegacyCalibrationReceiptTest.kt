package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.camera.PersonalCameraPriorCodec
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomLegacyCalibrationReceiptTest {
    @Test fun resettingLegacyNullViewMovementBaselineRequiresFreshWorkouts() = withFixture { f ->
        val config=press()
        (1..3).forEach { f.record(config,it) }
        val original=f.active()
        val baseline=original.exerciseBaselines.single()
        // Older profiles may have a view-agnostic baseline and no camera prior.
        val legacy=copyProfile(original,
            baselines=listOf(baseline.copy(key=baseline.key.copy(viewClass=null),
                semanticHash=SemanticHash.sha256(baseline.semanticHash,"legacy-null-view"))),
            camera=emptyMap(),
            refs=(1..3).map { "movement-session:workout-$it" }.toSet())
        f.calibration.saveActive(legacy)
        assertTrue(f.lifecycle.resetTarget(config))
        f.record(config,4)
        assertTrue("Reset must not turn consumed view-agnostic evidence into new sessions",
            f.active().exerciseBaselines.isEmpty())
        // Camera learning is independent: this legacy fixture had neither a
        // camera prior nor consumed camera receipts. Only movement was reset.
        f.record(config,5)
        f.record(config,6)
        assertEquals(3,f.active().exerciseBaselines.single()
            .metricStatistics.getValue("bilateral_asymmetry").sessionCount)
        assertEquals(legacy,f.calibration.loadVersion(PersonalCalibrationVersionRef.from(legacy)))
    }

    @Test fun publishingAnotherExercisePreservesLegacyCameraOnlyReceipts() = withFixture(
        MultiSessionCalibrationUpdater(MultiSessionCalibrationPolicy(
            minimumEligibleSessions=5,driftConsecutiveSessions=5)),
    ) { f ->
        val a=press()
        val b=InitialExerciseProfiles.dumbbellLateralRaise.let {
            AnalysisConfigResolver.resolve(it.definition,it.profile,it.equipment)
        }
        (1..3).forEach { f.record(a,it) }
        assertTrue(f.active().exerciseBaselines.isEmpty())
        val priorA=requireNotNull(f.prior(a))
        f.calibration.saveActive(copyProfile(f.active(),
            refs=(1..3).map { "camera-session:workout-$it" }.toSet()))
        (1..3).forEach { f.record(b,it) }
        assertNotNull("Legacy exercise A must not consume exercise B's camera evidence",f.prior(b))
        val before=f.active()
        f.record(a,4)
        assertEquals("A single new session must not republish camera A using lost legacy receipts",
            priorA,f.prior(a))
        assertEquals(before,f.active())
    }

    private fun press()=InitialExerciseProfiles.inclineDumbbellPress.let {
        AnalysisConfigResolver.resolve(it.definition,it.profile,it.equipment)
    }

    private fun copyProfile(
        p:PersonalCalibrationProfile,
        baselines:List<ExerciseBaseline> = p.exerciseBaselines,
        camera:Map<String,Double?> = p.cameraSetupPreferences,
        refs:Set<String> = p.evidenceReferences,
    )=PersonalCalibrationProfile.create(
        calibrationProfileId=p.calibrationProfileId,profileVersion=p.profileVersion+1,
        sourceConfidence=p.sourceConfidence,normalizedBodyGeometry=p.normalizedBodyGeometry,
        cameraSetupPreferences=camera,exerciseBaselines=baselines,
        equipmentAssociations=p.equipmentAssociations,lateralityBaseline=p.lateralityBaseline,
        cueEffectiveness=p.cueEffectiveness,evidenceReferences=refs,
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

    private class Fixture(dao:EvidenceDao,updater:MultiSessionCalibrationUpdater) {
        val evidence=RoomEvidenceRepository(dao)
        val calibration=RoomPersonalCalibrationRepository(dao)
        val lifecycle=RoomPersonalCalibrationLifecycle(dao,updater=updater)
        fun active()=requireNotNull(calibration.loadActive())
        fun prior(c:AnalysisConfig)=PersonalCameraPriorCodec.resolve(
            calibration.loadActive(),c.exerciseProfile.cameraProfile,
            c.equipmentProfile?.profileId,c.preferredViewClass)

        fun record(c:AnalysisConfig,index:Int) {
            val sid="workout-$index"
            val start=index*100_000L
            val epoch=1_700_000_000_000L+start
            val eid="$sid/${c.exerciseDefinition.exerciseId}"
            val id="$eid/set-1"
            evidence.ensureSession(WorkoutSessionRecord(sid,start,epoch))
            evidence.ensureExecution(ExerciseExecutionRecord(eid,sid,
                c.exerciseDefinition.exerciseId,start+100L,epoch+100L))
            evidence.openSet(SetRecord(id,eid,1,start+1_000L,null,epoch+1_000L),c)
            val rep=RepEvidence("$id/rep-1",1,"cycle",
                c.exerciseProfile.movementPrimitiveSequence.steps.first().primitive,
                start+1_100L,start+1_600L,RepClassification.NORMAL,emptyMap(),
                mapOf("bilateral_asymmetry" to MetricEvidence("bilateral_asymmetry",
                    SignalUnit.NORMALIZED,EvidenceValue.Known(.12,.95))),c.provenance)
            val rule=c.exerciseProfile.formRuleSet.rules.single { it.ruleId=="bilateral_asymmetry" }
            val form=FormObservation("$id/form",rep.repId,rule.ruleId,rule.ruleVersion,
                FormObservationState.OK,rule.severity,.95,.12)
            evidence.persistCompletedRepBundle(id,rep,listOf(form),emptyList(),emptyList())
            evidence.upsertTrackingSummary(TrackingQualitySummary(id,5,0,0,0,
                activeObservableFrames=5,observedViewClass=c.preferredViewClass,activeFrameFillMean=.50))
            evidence.finishSet(SetSummary(id,start+1_900L,1,0,0,epoch+1_900L))
            lifecycle.onCompletedSet(id,c)
        }
    }
}
