package com.gymbuddy.app

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.data.GymBuddyDatabaseFactory
import com.gymbuddy.data.RoomEvidenceRepository
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Actual Android runtime + native MediaPipe + Room boundary tests, debug only. */
class RuntimeBoundaryE2EActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply{text="Runtime boundary E2E"}
        setContentView(status)
        Thread({
            val results=JSONArray()
            fun test(name:String,body:()->Unit){
                val result=JSONObject().put("name",name)
                try { body();result.put("passed",true) }
                catch(t:Throwable){result.put("passed",false).put("error",t.toString())}
                results.put(result)
            }
            test("shutdown_drains_admitted_database_work",::shutdownDrainsAdmittedWork)
            test("failed_finalization_remains_retryable",::failedFinalizationRemainsRetryable)
            val passed=(0 until results.length()).all{results.getJSONObject(it).getBoolean("passed")}
            val payload=JSONObject().put("schema_version",1).put("passed",passed).put("tests",results)
            filesDir.resolve("runtime-boundary-e2e.json").writeText(payload.toString())
            runOnUiThread{status.text=if(passed)"RUNTIME_BOUNDARY_E2E_PASS" else payload.toString()}
        },"runtime-boundary-test").start()
    }

    private fun shutdownDrainsAdmittedWork(){
        val runtime=DefaultWorkoutRuntime(applicationContext)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val terminal=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        var readCompleted=false
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->
            if(thread.name=="gym-buddy-runtime"){
                failure.set(error);terminal.countDown()
            }else previous?.uncaughtException(thread,error)
        }
        try {
            runtime.analysisExecutor.execute{
                entered.countDown()
                check(release.await(15,TimeUnit.SECONDS))
            }
            check(entered.await(5,TimeUnit.SECONDS))
            runtime.loadRestCheckpoint{readCompleted=true;terminal.countDown()}
            // The blocking operation proves the following DB read was admitted
            // before shutdown. It must run before the resource is disposed.
            runtime.close()
            release.countDown()
            check(terminal.await(5,TimeUnit.SECONDS)){"Admitted read was discarded"}
            check(readCompleted&&failure.get()==null){"Database was closed ahead of queued work: ${failure.get()}"}
        } finally {
            release.countDown()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun failedFinalizationRemainsRetryable(){
        val name="finalization-${UUID.randomUUID()}.db"
        val database=GymBuddyDatabaseFactory.create(applicationContext,name)
        val pose=MediaPipePoseAnalyzer(applicationContext)
        try {
            val real=RoomEvidenceRepository(database.evidenceDao())
            var failOnce=true
            val storage=object:EvidenceRepository by real {
                override fun finishSet(summary:SetSummary){
                    if(failOnce){failOnce=false;throw IllegalStateException("Injected finalization failure")}
                    real.finishSet(summary)
                }
            }
            val b=InitialExerciseProfiles.dumbbellLateralRaise
            val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
            val analyzer=ProductionFrameAnalyzer(poseAnalyzer=pose,processorFactory={first->
                ProductionPoseFrameProcessor(config,storage,
                    WorkoutSessionRecord("session",first.timestampUs),
                    ExerciseExecutionRecord("exec","session",b.definition.exerciseId,first.timestampUs),
                    SetRecord("set","exec",1,first.timestampUs))
            })
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            analyzer.analyze(FramePacket(1L,1_000L,64,64,FrameOrigin.VIDEO,BitmapImageBuilder(bitmap).build()))
            check(runCatching{analyzer.finishSet(10_000L)}.isFailure){"Fault injection did not fire"}
            check(real.loadSet("set")!!.summary==null)
            analyzer.finishSet(10_000L)
            check(real.loadSet("set")!!.summary!=null){"Retry was ignored after the first write failure"}
            analyzer.finishSet(10_000L) // A successful retry is idempotent.
        } finally {
            pose.close();database.close();deleteDatabase(name)
        }
    }
}
