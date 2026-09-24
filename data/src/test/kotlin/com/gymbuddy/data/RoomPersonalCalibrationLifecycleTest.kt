package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.MetricEvidence
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef
import com.gymbuddy.domain.profile.SignalUnit
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomPersonalCalibrationLifecycleTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test
    fun threeCompletedProductionSetsPromoteCalibrationUsedByNextSet(){
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try{
            val dao=db.evidenceDao()
            val evidence=RoomEvidenceRepository(dao)
            val lifecycle=RoomPersonalCalibrationLifecycle(dao)
            val calibration=RoomPersonalCalibrationRepository(dao)
            val bundle=InitialExerciseProfiles.inclineDumbbellPress
            val generic=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment
            )
            evidence.ensureSession(WorkoutSessionRecord("session",0L))
            evidence.ensureExecution(
                ExerciseExecutionRecord(
                    "exec","session",bundle.definition.exerciseId,0L
                )
            )

            repeat(3){index->
                val ordinal=index+1
                val setId="set-"+ordinal
                val started=ordinal*1_000_000L
                val set=SetRecord(setId,"exec",ordinal,started)
                evidence.openSet(set,generic)
                val rep=RepEvidence(
                    repId="rep-"+ordinal,
                    ordinal=1,
                    stepId="cycle",
                    primitive=MovementPrimitive.PRESS,
                    startedAtUs=started+100_000L,
                    completedAtUs=started+600_000L,
                    classification=RepClassification.NORMAL,
                    signals=emptyMap(),
                    metrics=mapOf(
                        "bilateral_asymmetry" to MetricEvidence(
                            "bilateral_asymmetry",
                            SignalUnit.NORMALIZED,
                            EvidenceValue.Known(.12+ordinal*.005,.95),
                        )
                    ),
                    provenance=generic.provenance,
                )
                evidence.persistCompletedRepBundle(setId,rep,emptyList(),emptyList(),emptyList())
                evidence.finishSet(
                    SetSummary(setId,started+900_000L,1,0,0)
                )
                lifecycle.onCompletedSet(setId,generic)
            }

            val active=assertNotNull(calibration.loadActive()).let{calibration.loadActive()!!}
            val next=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment,active
            )
            assertNotNull(next.activeExerciseBaseline)
            assertEquals(
                PersonalCalibrationVersionRef.from(active),
                next.provenance.personalCalibrationProfile,
            )
            assertEquals(
                3,
                next.activeExerciseBaseline!!
                    .metricStatistics.getValue("bilateral_asymmetry").sessionCount,
            )

            lifecycle.resetTarget(next)
            val reset=calibration.loadActive()!!
            val resetConfig=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment,reset
            )
            assertNull(resetConfig.activeExerciseBaseline)
            assertNotNull(evidence.loadSet("set-1"))
        } finally { db.close() }
    }
}
