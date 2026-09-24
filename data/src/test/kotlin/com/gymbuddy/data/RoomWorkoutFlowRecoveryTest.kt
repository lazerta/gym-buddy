package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomWorkoutFlowRecoveryTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test
    fun unfinishedSetRecoveryPreservesCommittedRepsAndRecordsInterruption(){
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try{
            val dao=db.evidenceDao()
            val evidence=RoomEvidenceRepository(dao)
            val flow=RoomWorkoutFlowRepository(dao)
            val bundle=InitialExerciseProfiles.smithMachineSquat
            val config=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment
            )
            evidence.ensureSession(WorkoutSessionRecord("session",0L))
            evidence.ensureExecution(
                ExerciseExecutionRecord("exec","session",bundle.definition.exerciseId,0L)
            )
            evidence.openSet(SetRecord("set","exec",1,100L),config)
            evidence.persistRep(
                "set",
                RepEvidence(
                    repId="rep-1",
                    ordinal=1,
                    stepId="cycle",
                    primitive=MovementPrimitive.PRESS,
                    startedAtUs=200L,
                    completedAtUs=500L,
                    classification=RepClassification.NORMAL,
                    signals=emptyMap(),
                    metrics=emptyMap(),
                    provenance=config.provenance,
                )
            )
            flow.saveActiveSetCheckpoint("set")

            val recovery=flow.loadActiveSetRecovery()!!
            assertFalse(recovery.finalized)
            assertEquals(1,recovery.committedReps)
            assertEquals("set",recovery.set.setId)
            assertNull(flow.loadRestCheckpoint())

            flow.markInterruptedSet("set",5_000L,1)
            assertNull(flow.loadActiveSetRecovery())
            val interrupted=dao.interruptedSet("set")
            assertNotNull(interrupted)
            assertEquals(1,interrupted!!.committedReps)
            assertEquals(5_000L,interrupted.recoveredAtEpochMs)
            assertNotNull(evidence.loadSet("set"))
        } finally { db.close() }
    }

    @Test
    fun finalizedSetPendingRestIsRecoverableUntilRestCheckpointReplacesActiveState(){
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try{
            val dao=db.evidenceDao()
            val evidence=RoomEvidenceRepository(dao)
            val flow=RoomWorkoutFlowRepository(dao)
            val bundle=InitialExerciseProfiles.dumbbellLateralRaise
            val config=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment
            )
            evidence.ensureSession(WorkoutSessionRecord("session",0L))
            evidence.ensureExecution(
                ExerciseExecutionRecord("exec","session",bundle.definition.exerciseId,0L)
            )
            evidence.openSet(SetRecord("set","exec",1,100L),config)
            flow.saveActiveSetCheckpoint("set")
            evidence.finishSet(SetSummary("set",1_000L,0,0,0))

            val pending=flow.loadActiveSetRecovery()!!
            assertTrue(pending.finalized)
            assertEquals(0,pending.committedReps)
            assertNull(flow.loadRestCheckpoint())

            flow.saveRestCheckpoint(
                RestCheckpointDraft(
                    completedSetId="set",
                    focus="Repeat the same setup.",
                    plannedNextLoad=null,
                    restStartedAtEpochMs=2_000L,
                )
            )
            assertNull(flow.loadActiveSetRecovery())
            val rest=flow.loadRestCheckpoint()
            assertNotNull(rest)
            assertEquals("set",rest!!.completedSet.setId)
            assertEquals(2_000L,rest.restStartedAtEpochMs)
        } finally { db.close() }
    }
}
