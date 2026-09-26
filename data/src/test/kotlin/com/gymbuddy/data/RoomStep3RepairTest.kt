package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.coaching.*
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
class RoomStep3RepairTest {
    @Test fun kindModeAndTimerSurviveReopenWithoutChangingHistoricalActual(){
        withReopen{open->
            val actual=LoadSnapshot(20.0,"lb",LoadBasis.PER_SIDE,LoadSource.USER_ENTERED,
                ResistanceKind.EXTERNAL_LOAD,LoadMeasurementMode.ADDED_LOAD)
            val plan=actual.copy(value=25.0,source=LoadSource.PLANNED,measurementMode=LoadMeasurementMode.IMPLEMENT_MASS)
            open().use{db->
                fixture(db,actual,plan)
                val flow=RoomWorkoutFlowRepository(db.evidenceDao())
                flow.saveRestCheckpoint(RestCheckpointDraft("set","focus",plan,2000,RestClockAnchor(1200,"boot-1")))
            }
            open().use{db->
                val repo=RoomEvidenceRepository(db.evidenceDao())
                val evidence=repo.loadSet("set")!!
                assertEquals(actual,evidence.set.actualLoad);assertEquals(plan,evidence.set.plannedLoad)
                val flow=RoomWorkoutFlowRepository(db.evidenceDao())
                val rest=flow.loadRestCheckpoint()!!
                assertEquals(plan,rest.plannedNextLoad);assertEquals(RestClockAnchor(1200,"boot-1"),rest.clockAnchor)
                flow.saveRestCheckpoint(RestCheckpointDraft("set","focus",plan.copy(value=35.0),2000,rest.clockAnchor))
                assertEquals(actual,repo.loadSet("set")!!.set.actualLoad)
                assertEquals(plan,repo.loadSet("set")!!.set.plannedLoad)
                assertThrows(Exception::class.java){repo.openSet(evidence.set.copy(actualLoad=actual.copy(
                    measurementMode=LoadMeasurementMode.DISPLAY_VALUE)),config())}
            }
        }
    }
    @Test fun failedCheckpointTransactionRetainsOldPlanAndAnchorThenRetries(){
        withReopen{open->
            val old=LoadSnapshot(20.0,"kg",LoadBasis.TOTAL,LoadSource.PLANNED)
            val next=old.copy(value=25.0,resistanceKind=ResistanceKind.EXTERNAL_LOAD,
                measurementMode=LoadMeasurementMode.IMPLEMENT_MASS)
            open().use{db->
                fixture(db,old,null)
                val flow=RoomWorkoutFlowRepository(db.evidenceDao())
                flow.saveRestCheckpoint(RestCheckpointDraft("set","focus",old,2000,RestClockAnchor(1000,"1")))
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_rest BEFORE INSERT ON workout_flow_states BEGIN SELECT RAISE(ABORT,'injected rest write'); END")
                assertThrows(Exception::class.java){flow.saveRestCheckpoint(RestCheckpointDraft("set","focus",next,2000,RestClockAnchor(1000,"1")))}
                assertEquals(old,flow.loadRestCheckpoint()!!.plannedNextLoad)
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_rest")
                flow.saveRestCheckpoint(RestCheckpointDraft("set","focus",next,2000,RestClockAnchor(1000,"1")))
            }
            open().use{db->
                val saved=RoomWorkoutFlowRepository(db.evidenceDao()).loadRestCheckpoint()!!
                assertEquals(next,saved.plannedNextLoad);assertEquals(RestClockAnchor(1000,"1"),saved.clockAnchor)
            }
        }
    }
    @Test fun allRecurringAndDeliveredResponseEvidenceReconstructsAfterReopen(){
        withReopen{open->
            var live:SetCoachingSummary?=null
            open().use{db->
                fixture(db,null,null,finish=false)
                val repo=RoomEvidenceRepository(db.evidenceDao())
                (1..3).forEach{n->
                    val rep=RepEvidence("rep$n",n,"cycle",MovementPrimitive.RAISE,n*100L,n*100L+50,
                        RepClassification.NORMAL,emptyMap(),emptyMap(),config().provenance)
                    val observations=listOf("bilateral_asymmetry","lateral_raise_over_elevation").map{rule->
                        FormObservation("obs$n-$rule",rep.repId,rule,1,
                            if(n==3&&rule=="bilateral_asymmetry")FormObservationState.OK else FormObservationState.DEVIATION,
                            FormRuleSeverity.MINOR,.95,.5)
                    }
                    val cue=if(n==2)listOf(CueEvidenceLink(CueEvent("cue","bilateral_asymmetry",rep.repId,250,"MINOR"),observations.first().observationId))else emptyList()
                    val response=if(n==3)listOf(CueResponse("cue",rep.repId,CueResponseState.IMPROVED))else emptyList()
                    repo.persistCompletedRepBundle("set",rep,observations,cue,response)
                }
                repo.persistCueDelivery(CueDeliveryRecord("cue",CueDeliveryState.COMPLETED))
                repo.finishSet(SetSummary("set",400,3,0,0,2000))
                live=EvidenceSummaryEngine().summarize(repo.loadSet("set")!!)
                RoomWorkoutFlowRepository(db.evidenceDao()).saveRestCheckpoint(RestCheckpointDraft("set","stale focus",null,2000))
            }
            open().use{db->
                val restored=RoomWorkoutFlowRepository(db.evidenceDao(),EvidenceSummaryEngine()).loadRestCheckpoint()!!
                val summary=restored.completedSets.single().coachingSummary!!
                assertEquals(live,summary)
                assertEquals(listOf("lateral_raise_over_elevation"),summary.recurringRuleIds)
                assertEquals(setOf("bilateral_asymmetry"),summary.resolvedRuleIds)
                assertEquals(CueResponseState.IMPROVED,summary.cueResponses.single().state)
            }
        }
    }
    private fun config():AnalysisConfig{val b=InitialExerciseProfiles.dumbbellLateralRaise
        return AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)}
    private fun fixture(db:GymBuddyDatabase,actual:LoadSnapshot?,planned:LoadSnapshot?,finish:Boolean=true){
        val repo=RoomEvidenceRepository(db.evidenceDao())
        repo.ensureSession(WorkoutSessionRecord("session",0,1000))
        repo.ensureExecution(ExerciseExecutionRecord("exec","session","dumbbell_lateral_raise",10,1100))
        repo.openSet(SetRecord("set","exec",1,20,actual,1200,planned),config())
        if(finish)repo.finishSet(SetSummary("set",400,0,0,0,2000))
    }
    private fun withReopen(body:(()->GymBuddyDatabase)->Unit){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="repair-${System.nanoTime()}.db"
        try{body{Room.databaseBuilder(context,GymBuddyDatabase::class.java,name)
            .addMigrations(*GymBuddyMigrations.ALL).allowMainThreadQueries().build()}}
        finally{context.deleteDatabase(name)}
    }
}
