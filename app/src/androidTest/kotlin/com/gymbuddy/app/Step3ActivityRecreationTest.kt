package com.gymbuddy.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymbuddy.app.controller.*
import com.gymbuddy.app.runtime.AndroidWorkoutClock
import com.gymbuddy.data.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity destruction/recreation; no fake runtime or Room. */
@RunWith(AndroidJUnit4::class)
class Step3ActivityRecreationTest {
    @Test fun mainActivityRecreationRetainsRestPlanEquipmentAndMonotonicAnchor(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("gym-buddy.db")
        val clock=AndroidWorkoutClock(context)
        val anchor=RestClockAnchor(clock.nowElapsedMs(),clock.bootId())
        val plan=LoadSnapshot(25.0,"lb",LoadBasis.PER_SIDE,LoadSource.PLANNED,
            ResistanceKind.EXTERNAL_LOAD,LoadMeasurementMode.ADDED_LOAD)
        GymBuddyDatabaseFactory.create(context).withDatabase{db->
            val b=InitialExerciseProfiles.dumbbellLateralRaise
            val dao=db.evidenceDao();val repo=RoomEvidenceRepository(dao)
            val equipment=EquipmentContextRecord("recreation-equipment",b.equipment!!.profileId,"Bench A",1000)
            dao.rememberEquipmentForExercise(b.definition.exerciseId,equipment)
            repo.ensureSession(WorkoutSessionRecord("session",0,1000))
            repo.ensureExecution(ExerciseExecutionRecord("exec","session",b.definition.exerciseId,10,1100,
                "incline_dumbbell_press",equipment.contextId))
            repo.openSet(SetRecord("set","exec",1,20,plan.copy(value=20.0,source=LoadSource.USER_ENTERED),1200,plan),
                AnalysisConfigResolver.resolve(b.definition,b.profile,equipment.specialize(b.equipment!!)))
            repo.finishSet(SetSummary("set",400,0,0,0,clock.nowEpochMs()))
            RoomWorkoutProductRepository(dao).setActiveSession("session")
            RoomWorkoutFlowRepository(dao).saveRestCheckpoint(RestCheckpointDraft("set","Repeat the same setup.",
                plan,clock.nowEpochMs(),anchor))
        }
        val controllers=mutableListOf<WorkoutController>()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try{
            val first=controller(scenario);controllers+=first
            awaitRest(first)
            scenario.onActivity{first.selectDay(WorkoutDay.LEGS)}
            val before=first.uiState.value as WorkoutUiState.Rest
            assertEquals("25",before.plannedNextLoadText)
            scenario.recreate()
            val second=controller(scenario);controllers+=second
            assertNotSame(first,second)
            awaitRest(second)
            val restored=second.uiState.value as WorkoutUiState.Rest
            assertEquals(WorkoutDay.LEGS,second.currentDay)
            assertEquals(before.completedSetNumber,restored.completedSetNumber)
            assertEquals(before.previousActualLoadText,restored.previousActualLoadText)
            assertEquals("25",restored.plannedNextLoadText)
            assertEquals("lb",restored.plannedNextLoadUnit)
            assertEquals(LoadBasis.PER_SIDE,restored.plannedNextLoadBasis)
            assertEquals(ResistanceKind.EXTERNAL_LOAD,restored.plannedNextResistanceKind)
            assertEquals(LoadMeasurementMode.ADDED_LOAD,restored.plannedNextMeasurementMode)
            val restoredTimer=requireNotNull(restored.timerAnchor)
            assertFalse(restoredTimer.estimated)
            assertTrue(restoredTimer.elapsedMs(clock.nowElapsedMs())>=before.timerAnchor!!.elapsedMs(anchor.elapsedRealtimeMs))
            GymBuddyDatabaseFactory.create(context).withDatabase{db->
                val saved=RoomWorkoutFlowRepository(db.evidenceDao()).loadRestCheckpoint()!!
                assertEquals(anchor,saved.clockAnchor)
                assertEquals("recreation-equipment",saved.execution.equipmentContextId)
                assertEquals("incline_dumbbell_press",saved.execution.plannedExerciseId)
                assertEquals(plan,saved.plannedNextLoad)
            }
        }finally{
            scenario.close()
            controllers.forEach{assertTrue((it.analysisExecutor as ExecutorService).awaitTermination(15,TimeUnit.SECONDS))}
            context.deleteDatabase("gym-buddy.db")
        }
    }
    private fun <T> GymBuddyDatabase.withDatabase(body:(GymBuddyDatabase)->T):T =
        try{body(this)}finally{close()}

    private fun controller(scenario:ActivityScenario<MainActivity>):WorkoutController{
        val reference=AtomicReference<WorkoutController>()
        scenario.onActivity{activity->reference.set(MainActivity::class.java.getDeclaredField("controller").apply{
            isAccessible=true
        }.get(activity) as WorkoutController)}
        return reference.get()
    }
    private fun awaitRest(c:WorkoutController){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(c.uiState.value !is WorkoutUiState.Rest&&System.nanoTime()<end)Thread.sleep(20)
        assertTrue("MainActivity did not recover REST: ${c.uiState.value}",c.uiState.value is WorkoutUiState.Rest)
    }
}
