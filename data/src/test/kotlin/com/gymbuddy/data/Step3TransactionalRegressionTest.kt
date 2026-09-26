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
class Step3TransactionalRegressionTest {
    @Test fun equipmentAndPreferenceCommitTogetherAndPreserveFavorite(){withDb{db->
        val dao=db.evidenceDao()
        dao.changeFavorite("dumbbell_lateral_raise",true)
        val equipment=EquipmentContextRecord("bench-a","dumbbell-generic","Bench A",100)
        failInsert(db,"exercise_preferences","fail_preference")
        assertThrows(Exception::class.java){dao.rememberEquipmentForExercise("dumbbell_lateral_raise",equipment)}
        assertTrue(dao.equipmentContexts().isEmpty())
        assertTrue(dao.exercisePreferences().single().favorite)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_preference")
        dao.rememberEquipmentForExercise("dumbbell_lateral_raise",equipment)
        dao.rememberSelection(ExercisePreferenceRecord("dumbbell_lateral_raise",lastSelectedAtEpochMs=200))
        val preference=dao.readProductSnapshot().preferences.single()
        assertTrue(preference.favorite)
        assertEquals("bench-a",preference.equipmentContextId)
        assertEquals(200L,preference.lastSelectedAtEpochMs)
        dao.changeFavorite("dumbbell_lateral_raise",false)
        assertEquals(preference.copy(favorite=false),dao.readProductSnapshot().preferences.single())
    }}

    @Test fun completionAndRecoveryClearRollBackTogetherThenRetryCountsHistory(){withDb{db->
        fixture(db,"set-a","exec-a");fixture(db,"set-b","exec-b")
        val dao=db.evidenceDao()
        RoomWorkoutProductRepository(dao).setActiveSession("session")
        RoomWorkoutFlowRepository(dao).saveRestCheckpoint(RestCheckpointDraft("set-b","focus",null,2000))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_clear BEFORE DELETE ON workout_flow_states BEGIN SELECT RAISE(ABORT,'injected clear'); END")
        assertThrows(Exception::class.java){dao.completeExerciseFromHistory("session","dumbbell_lateral_raise",3000)}
        assertTrue(dao.workoutCompletions("session").isEmpty())
        assertNotNull(RoomWorkoutFlowRepository(dao).loadRestCheckpoint())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_clear")
        dao.completeExerciseFromHistory("session","dumbbell_lateral_raise",3000)
        dao.completeExerciseFromHistory("session","dumbbell_lateral_raise",2500)
        assertEquals(2,dao.workoutCompletions("session").single().completedSets)
        assertEquals(3000L,dao.workoutCompletions("session").single().completedAtEpochMs)
        assertNull(dao.workoutFlowState("active"))
    }}

    @Test fun gptPublicationRequiresAllSourcesAndExactRetryIsIdempotent(){withDb{db->
        fixture(db,"set-a","exec-a");fixture(db,"set-b","exec-b",finish=false)
        val repo=RoomGptAnalysisRepository(db.evidenceDao())
        val record=GptAnalysisRecord("analysis","set-a",1,"model-v1",2000,setOf("set-a"),"summary",listOf("steady"))
        assertThrows(Exception::class.java){repo.append(record.copy(sourceSetIds=setOf("set-a","missing")))}
        assertThrows(Exception::class.java){repo.append(record.copy(sourceSetIds=setOf("set-a","set-b")))}
        assertTrue(repo.listForSet("set-a").isEmpty())
        failInsert(db,"gpt_analyses","fail_analysis")
        assertThrows(Exception::class.java){repo.append(record)}
        assertTrue(repo.listForSet("set-a").isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_analysis")
        repo.append(record);repo.append(record)
        assertEquals(listOf(record),repo.listForSet("set-a"))
        assertThrows(Exception::class.java){repo.append(record.copy(modelLabel="another-model"))}
        assertEquals(listOf(record),repo.listForSet("set-a"))
    }}

    @Test fun gptOrderingBreaksTimestampTiesAndSourceOrderDoesNotChangeIdentity(){withDb{db->
        fixture(db,"set-a","exec-a");fixture(db,"set-b","exec-b")
        val repo=RoomGptAnalysisRepository(db.evidenceDao())
        val a=GptAnalysisRecord("a","set-a",1,"model",2000,linkedSetOf("set-b","set-a"),"summary")
        repo.append(a.copy(analysisId="z"));repo.append(a)
        repo.append(a.copy(sourceSetIds=linkedSetOf("set-a","set-b")))
        assertEquals(listOf("a","z"),repo.listForSet("set-a").map{it.analysisId})
        assertEquals(setOf("set-a","set-b"),repo.listForSet("set-a").first().sourceSetIds)
        assertEquals(0,db.evidenceDao().setSummary("set-a")!!.completedReps)
    }}

    @Test fun invalidAttemptRetryCannotChangeAnyPublishedField(){withDb{db->
        fixture(db,"set","exec",finish=false)
        val repo=RoomEvidenceRepository(db.evidenceDao())
        val attempt=InvalidAttemptEvidence("attempt","cycle",MovementPrimitive.RAISE,30,100,RepInvalidReason.INTERRUPTED,.9)
        repo.persistInvalidAttempt("set",attempt);repo.persistInvalidAttempt("set",attempt)
        assertThrows(Exception::class.java){repo.persistInvalidAttempt("set",attempt.copy(stepId="other"))}
        assertThrows(Exception::class.java){repo.persistInvalidAttempt("set",attempt.copy(minConfidence=.2))}
        assertEquals(listOf(attempt),repo.loadSet("set")!!.invalidAttempts)
        assertTrue(repo.loadSet("set")!!.reps.isEmpty())
    }}

    @Test fun recoveredFocusRebuildsFromTheSameCommittedObservations(){withDb{db->
        val config=fixture(db,"set","exec",finish=false)
        val repo=RoomEvidenceRepository(db.evidenceDao())
        (1..2).forEach{n->
            val rep=RepEvidence("rep-$n",n,"cycle",MovementPrimitive.RAISE,100L*n,100L*n+50,
                RepClassification.NORMAL,emptyMap(),emptyMap(),config.provenance)
            val obs=FormObservation("obs-$n",rep.repId,"bilateral_asymmetry",1,
                FormObservationState.DEVIATION,FormRuleSeverity.MINOR,.95,.2)
            repo.persistCompletedRepBundle("set",rep,listOf(obs),emptyList(),emptyList())
        }
        repo.finishSet(SetSummary("set",400,2,0,0,2000))
        val engine=EvidenceSummaryEngine{"Correct $it"}
        val live=engine.summarize(repo.loadSet("set")!!)
        assertNotNull(live.focusRuleId)
        val flow=RoomWorkoutFlowRepository(db.evidenceDao(),engine)
        flow.saveRestCheckpoint(RestCheckpointDraft("set","stale in-memory text",null,2000))
        assertEquals(live.focusText,flow.loadRestCheckpoint()!!.focus)
        assertEquals(live.focusText,flow.loadRestCheckpoint()!!.completedSets.single().focus)
    }}

    private fun fixture(db:GymBuddyDatabase,id:String,execution:String,finish:Boolean=true):AnalysisConfig{
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
        val repo=RoomEvidenceRepository(db.evidenceDao())
        repo.ensureSession(WorkoutSessionRecord("session",0,1000))
        repo.ensureExecution(ExerciseExecutionRecord(execution,"session",b.definition.exerciseId,10,1100))
        repo.openSet(SetRecord(id,execution,1,20,null,1200),config)
        if(finish)repo.finishSet(SetSummary(id,400,0,0,0,2000))
        return config
    }
    private fun failInsert(db:GymBuddyDatabase,table:String,name:String){
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT,'injected $name'); END")
    }
    private fun withDb(run:(GymBuddyDatabase)->Unit){
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),GymBuddyDatabase::class.java)
            .allowMainThreadQueries().build()
        try{run(db)}finally{db.close()}
    }
}
