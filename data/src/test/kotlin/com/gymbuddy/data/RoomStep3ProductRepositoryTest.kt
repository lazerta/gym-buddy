package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
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
class RoomStep3ProductRepositoryTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test fun productSelectionIsWorkoutScopedAndKeepsEquipmentPreference(){ withDb { db ->
        val repo=RoomWorkoutProductRepository(db.evidenceDao())
        val equipment=EquipmentContextRecord("smith-a","smith-generic","Smith A",100)
        repo.rememberEquipmentContext(equipment)
        repo.rememberExerciseSelection(ExercisePreferenceRecord("smith_machine_squat",true,200,"smith-a"))
        repo.setActiveSession("session-a")
        repo.markExerciseCompleted(WorkoutExerciseCompletionRecord("session-a","smith_machine_squat",3,300))
        val first=repo.loadSelectionSnapshot()
        assertEquals("session-a",first.activeSessionId)
        assertEquals(3,first.completions.single().completedSets)
        assertEquals("smith-a",first.preferences.single().equipmentContextId)
        repo.setActiveSession("session-b")
        val next=repo.loadSelectionSnapshot()
        assertTrue(next.completions.isEmpty())
        assertTrue(next.preferences.single().favorite)
        assertEquals("Smith A",next.equipmentContexts.single().label)
    }}

    @Test fun gptAnalysesAreAppendOnlyOrderedAndRoundTripArbitraryText(){ withDb { db ->
        val evidence=fixtureSet(db)
        val repo=RoomGptAnalysisRepository(db.evidenceDao())
        val first=GptAnalysisRecord("a1",evidence.set.setId,1,"gpt",100,setOf(evidence.set.setId),"summary.one",listOf("a.b","x/y"))
        val second=GptAnalysisRecord("a2",evidence.set.setId,1,"gpt",200,setOf(evidence.set.setId),"later",listOf("keep steady"))
        repo.append(second);repo.append(first)
        assertEquals(listOf(first,second),repo.listForSet(evidence.set.setId))
        assertThrows(Exception::class.java){ repo.append(first.copy(summary="mutated")) }
    }}

    @Test fun evidenceRoundTripPreservesProvenanceLoadsPhasesAndInvalidAttempt(){ withDb { db ->
        val dao=db.evidenceDao(); val repo=RoomEvidenceRepository(dao)
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
        repo.ensureSession(WorkoutSessionRecord("session",10,1_000))
        repo.ensureExecution(ExerciseExecutionRecord("exec","session",b.definition.exerciseId,20,1_100,"incline_dumbbell_press","dumbbell-home"))
        val actual=LoadSnapshot(20.0,"lb",LoadBasis.PER_IMPLEMENT,LoadSource.USER_ENTERED)
        val planned=LoadSnapshot(22.5,"lb",LoadBasis.PER_IMPLEMENT,LoadSource.PLANNED)
        val set=SetRecord("set","exec",1,30,actual,1_200,planned)
        repo.openSet(set,config)
        val rep=RepEvidence(
            "set/rep-1",1,"cycle",MovementPrimitive.RAISE,40,140,RepClassification.NORMAL,
            emptyMap(),emptyMap(),config.provenance,
            phaseIntervals=listOf(RepPhaseInterval(PrimitivePhase.OUTBOUND,40,90,.9),RepPhaseInterval(PrimitivePhase.RETURNING,90,140,.9)),
            assistanceAssessment=AssistanceAssessmentState.NOT_ASSESSED,
        )
        repo.persistCompletedRepBundle("set",rep,emptyList(),emptyList(),emptyList())
        val invalid=InvalidAttemptEvidence("set/attempt-1","cycle",MovementPrimitive.RAISE,150,210,RepInvalidReason.INTERRUPTED,.8)
        repo.persistInvalidAttempt("set",invalid)
        val loaded=requireNotNull(repo.loadSet("set"))
        assertEquals(actual,loaded.set.actualLoad)
        assertEquals(planned,loaded.set.plannedLoad)
        assertEquals("incline_dumbbell_press",dao.execution("exec")!!.plannedExerciseId)
        assertEquals("dumbbell-home",dao.execution("exec")!!.equipmentContextId)
        assertEquals(rep.phaseIntervals,loaded.reps.single().phaseIntervals)
        assertEquals(AssistanceAssessmentState.NOT_ASSESSED,loaded.reps.single().assistanceAssessment)
        assertEquals(listOf(invalid),loaded.invalidAttempts)
    }}

    private fun fixtureSet(db:GymBuddyDatabase):PersistedSetEvidence{
        val repo=RoomEvidenceRepository(db.evidenceDao())
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
        repo.ensureSession(WorkoutSessionRecord("gpt-session",1,1_000))
        repo.ensureExecution(ExerciseExecutionRecord("gpt-exec","gpt-session",b.definition.exerciseId,2,1_001))
        repo.openSet(SetRecord("gpt-set","gpt-exec",1,3,null,1_002),config)
        return requireNotNull(repo.loadSet("gpt-set"))
    }

    private fun withDb(block:(GymBuddyDatabase)->Unit){
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java).allowMainThreadQueries().build()
        try{ block(db) } finally { db.close() }
    }
}
