package com.gymbuddy.app

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gymbuddy.app.controller.WorkoutController
import com.gymbuddy.app.controller.WorkoutUiState
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.app.runtime.WorkoutSessionIdentity
import com.gymbuddy.data.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Runs the real controller/runtime/Room boundary. No allowMainThreadQueries,
 * fake database, fake runtime, or synthetic detector stands in for these paths. */
class Step3ReviewE2EActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val label=TextView(this).apply{text="Step 3 review regressions"};setContentView(label)
        Thread({
            val tests=listOf<Pair<String,()->Unit>>(
                "selection_leaves_ui_thread_free" to ::selectionLeavesUiThreadFree,
                "selection_failure_rolls_back_and_retries" to ::selectionFailureRestoresStateAndRetries,
                "reset_targets_selected_equipment" to ::resetTargetsSelectedEquipment,
                "new_workout_failure_is_atomic" to ::newWorkoutFailureIsAtomic,
                "completion_uses_durable_set_count" to ::completionUsesDurableSetCount,
            )
            val rows=JSONArray()
            tests.forEach { (name,run)->
                val row=JSONObject().put("name",name)
                try{run();row.put("passed",true)}catch(t:Throwable){row.put("passed",false).put("error",t.stackTraceToString())}
                rows.put(row)
            }
            val passed=(0 until rows.length()).all{rows.getJSONObject(it).getBoolean("passed")}
            val result=JSONObject().put("passed",passed).put("tests",rows)
            filesDir.resolve("step3-review-e2e.json").writeText(result.toString())
            runOnUiThread { label.text=if(passed)"STEP3_REVIEW_E2E_PASS" else result.toString() }
        },"step3-review").start()
    }

    private fun selectionLeavesUiThreadFree()=withRuntime { r,db ->
        worker(r){db.evidenceDao().deleteWorkoutFlowState("active");RoomWorkoutProductRepository(db.evidenceDao()).setActiveSession(null)}
        val c=onMain { WorkoutController(r) }
        worker(r){};worker(r){} // drain the real asynchronous initialization
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        r.analysisExecutor.execute { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) }
        check(entered.await(5,TimeUnit.SECONDS))
        try {
            // Must return before the worker is released: moving a blocking .get()
            // to a worker would still freeze the real screen and fail this test.
            onMain { c.selectExercise("dumbbell_lateral_raise") }
        } finally { release.countDown() }
        worker(r){};worker(r){}
        check(c.uiState.value is WorkoutUiState.CameraSetup)
        worker(r) {
            check(field(r,"currentAnalyzer")!=null)
            check(db.evidenceDao().exercisePreferences().any{it.exerciseId=="dumbbell_lateral_raise"})
        }
    }

    private fun selectionFailureRestoresStateAndRetries()=withRuntime { r,db ->
        worker(r){
            db.evidenceDao().deleteWorkoutFlowState("active")
            RoomWorkoutProductRepository(db.evidenceDao()).setActiveSession(null)
        }
        val before=worker(r){db.evidenceDao().exercisePreferences()}
        val c=onMain { WorkoutController(r) }
        worker(r){};worker(r){}
        worker(r){db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_review_selection BEFORE INSERT ON exercise_preferences " +
                "BEGIN SELECT RAISE(ABORT, 'review injected selection failure'); END")}
        try {
            onMain { c.selectExercise("dumbbell_lateral_raise") }
            worker(r){};worker(r){}
            val state=c.uiState.value as WorkoutUiState.ExerciseSelection
            check(state.errorMessage!=null) { "Failed selection gave no retry feedback" }
            worker(r){
                check(field(r,"currentAnalyzer")==null)
                check((field(r,"workoutIdentity") as WorkoutSessionIdentity).sessionId==null)
                check(RoomWorkoutProductRepository(db.evidenceDao()).loadSelectionSnapshot().activeSessionId==null)
                check(db.evidenceDao().exercisePreferences()==before) { "Failed selection changed durable preferences" }
            }
        } finally { worker(r){db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_review_selection")} }
        onMain { c.selectExercise("dumbbell_lateral_raise") }
        worker(r){};worker(r){}
        check(c.uiState.value is WorkoutUiState.CameraSetup)
        worker(r){
            check(field(r,"currentAnalyzer")!=null)
            check(RoomWorkoutProductRepository(db.evidenceDao()).loadSelectionSnapshot().activeSessionId!=null)
        }
    }

    private fun resetTargetsSelectedEquipment()=withRuntime { r,db ->
        val b=InitialExerciseProfiles.smithMachineSquat
        val base=requireNotNull(b.equipment)
        val a=EquipmentContextRecord("review-smith-a",base.profileId,"Review Smith A",1)
        val other=EquipmentContextRecord("review-smith-b",base.profileId,"Review Smith B",1)
        worker(r) {
            r.beginExercise(ExerciseStartRequest(b.definition.exerciseId,b.definition.exerciseId,a))
            fun baseline(equipmentId:String)=ExerciseBaseline(
                "baseline-$equipmentId",1,"baseline-hash-$equipmentId",
                ExerciseBaselineKey(b.profile.profileId,b.profile.profileVersion,equipmentId,b.profile.cameraProfile.preferredViewClass),
                mapOf("bilateral_asymmetry" to BaselineStatistic(.1,.09,.11,9,3,.9)))
            RoomPersonalCalibrationRepository(db.evidenceDao()).saveActive(PersonalCalibrationProfile.create(
                calibrationProfileId="review-${UUID.randomUUID()}",profileVersion=1,sourceConfidence=.9,
                exerciseBaselines=listOf(baseline(a.contextId),baseline(other.contextId),baseline(base.profileId))))
        }
        val done=CountDownLatch(1);var success=false
        r.resetPersonalCalibration(b.definition.exerciseId){success=it;done.countDown()}
        check(done.await(10,TimeUnit.SECONDS)&&success)
        worker(r) {
            val keys=RoomPersonalCalibrationRepository(db.evidenceDao()).loadActive()!!.exerciseBaselines.map{it.key.equipmentProfileId}.toSet()
            check(keys==setOf(other.contextId,base.profileId)) { "Reset changed the wrong equipment context: $keys" }
        }
    }

    private fun newWorkoutFailureIsAtomic()=withRuntime { r,db ->
        val sid="review-${UUID.randomUUID()}"
        worker(r) {
            val s=WorkoutSessionRecord(sid,1,1000)
            val e=ExerciseExecutionRecord("$sid-e",sid,"dumbbell_lateral_raise",2,1100)
            persistSet(db,s,e,"$sid-set",1)
            RoomWorkoutFlowRepository(db.evidenceDao()).saveRestCheckpoint(RestCheckpointDraft("$sid-set","Repeat the same setup.",null,2000))
            r.resumeExercise(s,e)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_review_clear BEFORE DELETE ON workout_flow_states BEGIN SELECT RAISE(ABORT, 'review injected clear failure'); END")
        }
        try {
            check(!newWorkout(r)) { "Failed transition was acknowledged as saved" }
            worker(r) {
                check(RoomWorkoutProductRepository(db.evidenceDao()).loadSelectionSnapshot().activeSessionId==sid) { "Failed new workout erased the durable session scope" }
                check((field(r,"workoutIdentity") as WorkoutSessionIdentity).sessionId==sid) { "Failed new workout erased in-memory identity" }
                check(RoomWorkoutFlowRepository(db.evidenceDao()).loadRestCheckpoint()!=null)
            }
        } finally { worker(r){db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_review_clear")} }
        check(newWorkout(r))
        worker(r) {
            check(RoomWorkoutProductRepository(db.evidenceDao()).loadSelectionSnapshot().activeSessionId==null)
            check((field(r,"workoutIdentity") as WorkoutSessionIdentity).sessionId==null)
            check(db.evidenceDao().set("$sid-set")!=null) { "New workout deleted history" }
        }
    }

    private fun completionUsesDurableSetCount()=withRuntime { r,db ->
        val sid="review-${UUID.randomUUID()}"
        val s=WorkoutSessionRecord(sid,1,1000)
        val old=ExerciseExecutionRecord("$sid-old",sid,"dumbbell_lateral_raise",2,1100)
        val current=old.copy(executionId="$sid-current")
        worker(r) {
            persistSet(db,s,old,"$sid-s1",1);persistSet(db,s,old,"$sid-s2",2)
            persistSet(db,s,current,"$sid-s3",1)
            r.resumeExercise(s,current)
        }
        val done=CountDownLatch(1);var success=false
        // Simulate a recreated controller whose in-memory total only knows its
        // current execution. Durable history, not that stale count, is authority.
        r.markExerciseCompleted(current.exerciseId,1,5000){success=it;done.countDown()}
        check(done.await(10,TimeUnit.SECONDS)&&success)
        worker(r) {
            check(RoomWorkoutProductRepository(db.evidenceDao()).loadSelectionSnapshot().completions.single().completedSets==3) {
                "Recreated controller overwrote the total with only its current execution"
            }
        }
    }

    private fun persistSet(db:GymBuddyDatabase,s:WorkoutSessionRecord,e:ExerciseExecutionRecord,id:String,n:Int) {
        val repo=RoomEvidenceRepository(db.evidenceDao())
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        repo.ensureSession(s);repo.ensureExecution(e)
        repo.openSet(SetRecord(id,e.executionId,n,10+n.toLong(),null,1200),AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment))
        repo.finishSet(SetSummary(id,100,0,0,0,2000))
    }
    private fun newWorkout(r:DefaultWorkoutRuntime):Boolean {
        val latch=CountDownLatch(1);var success=false
        r.startNewWorkout{success=it;latch.countDown()}
        check(latch.await(10,TimeUnit.SECONDS));return success
    }
    private fun withRuntime(body:(DefaultWorkoutRuntime,GymBuddyDatabase)->Unit) {
        val r=DefaultWorkoutRuntime(applicationContext)
        try{body(r,field(r,"database") as GymBuddyDatabase)}
        finally{r.close();check((r.analysisExecutor as ExecutorService).awaitTermination(15,TimeUnit.SECONDS))}
    }
    private fun <T> onMain(body:()->T):T {
        val task=FutureTask<T>{body()};runOnUiThread(task);return task.get(5,TimeUnit.SECONDS)
    }
    private fun <T> worker(r:DefaultWorkoutRuntime,body:()->T):T {
        val task=FutureTask<T>{body()};r.analysisExecutor.execute(task);return task.get(15,TimeUnit.SECONDS)
    }
    private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(target)
}
