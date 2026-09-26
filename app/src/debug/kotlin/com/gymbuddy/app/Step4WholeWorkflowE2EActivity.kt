package com.gymbuddy.app

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.gymbuddy.app.controller.*
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.data.*
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Step 4 gate: compose the already-tested production pieces into one realistic
 * two-set workflow. Pose observations are injected only at the PoseEstimator
 * output boundary; Controller, runtime, movement engine, Room, recovery, product
 * state, finalization, GPT history and export are production code.
 */
class Step4WholeWorkflowE2EActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val label=TextView(this).apply{text="Step 4 whole-workflow verification"}
        setContentView(label)
        Thread({
            val rows=JSONArray()
            val row=JSONObject().put("name","two_set_restart_substitution_equipment_load_interruption_history_export")
            try{
                wholeWorkflow()
                row.put("passed",true)
            }catch(t:Throwable){
                row.put("passed",false).put("error",t.stackTraceToString())
            }
            rows.put(row)
            val passed=row.getBoolean("passed")
            val result=JSONObject().put("schema_version",1).put("passed",passed).put("tests",rows)
            filesDir.resolve("step4-whole-workflow-e2e.json").writeText(result.toString())
            runOnUiThread{label.text=if(passed)"STEP4_WHOLE_WORKFLOW_PASS" else result.toString()}
        },"step4-whole-workflow").start()
    }

    private fun wholeWorkflow() {
        applicationContext.deleteDatabase(DB)
        val epoch=AtomicLong(10_000L)
        val clock=WorkoutClock { epoch.getAndAdd(1_000L) }

        var runtime1:DefaultWorkoutRuntime?=null
        var controller1:WorkoutController?=null
        var runtime2:DefaultWorkoutRuntime?=null
        var controller2:WorkoutController?=null
        try{
            runtime1=DefaultWorkoutRuntime(applicationContext,wallClock={epoch.getAndAdd(1_000L)})
            val db1=field(runtime1,"database") as GymBuddyDatabase
            controller1=onMain{WorkoutController(runtime1,clock=clock)}
            drain(runtime1,6)
            val c1=requireNotNull(controller1)

            onMain{
                c1.toggleFavorite(ACTUAL)
                c1.editEquipment(ACTUAL)
                c1.updateEquipmentLabel(EQUIPMENT_LABEL)
                c1.saveEquipmentContext()
            }
            drain(runtime1,6)
            val product1=worker(runtime1){RoomWorkoutProductRepository(db1.evidenceDao()).loadSelectionSnapshot()}
            val equipmentId=requireNotNull(
                product1.preferences.firstOrNull{it.exerciseId==ACTUAL}?.equipmentContextId
            )
            check(product1.preferences.first{it.exerciseId==ACTUAL}.favorite)
            check(product1.equipmentContexts.first{it.contextId==equipmentId}.label==EQUIPMENT_LABEL)

            onMain{
                c1.beginSubstitution(PLANNED)
                c1.updateExerciseSearch("lateral")
            }
            val search=c1.uiState.value as WorkoutUiState.ExerciseSelection
            check(search.substitutionForExerciseId==PLANNED)
            check(search.searchResults.any{it.exerciseId==ACTUAL})
            onMain{c1.selectOtherExercise(ACTUAL)}
            drain(runtime1,6)
            check(c1.uiState.value is WorkoutUiState.CameraSetup)

            val driver1=initializeDriver(runtime1,c1)
            worker(runtime1){driver1.ready();driver1.cycle()}
            val active1=c1.uiState.value as WorkoutUiState.ActiveSet
            check(active1.repCount==1)
            val set1=driver1.setId
            onMain{c1.endSet()}
            drain(runtime1,6)
            val rest1=c1.uiState.value as WorkoutUiState.Rest
            check(rest1.previousReps==1)

            onMain{
                c1.updateNextLoad("25")
                c1.updateNextLoadUnit("lb")
                c1.updateNextLoadBasis(LoadBasis.PER_SIDE)
            }
            drain(runtime1,5)
            val edited=c1.uiState.value as WorkoutUiState.Rest
            check(edited.plannedNextLoadText=="25")
            check(edited.plannedNextLoadUnit=="lb")
            check(edited.plannedNextLoadBasis==LoadBasis.PER_SIDE)

            // Process death/recreation at REST. The persisted plan and substitution
            // provenance must be enough to continue without any in-memory object.
            onMain{c1.close()}
            check((runtime1.analysisExecutor as ExecutorService).awaitTermination(15,TimeUnit.SECONDS))
            runtime1=null;controller1=null

            runtime2=DefaultWorkoutRuntime(applicationContext,wallClock={epoch.getAndAdd(1_000L)})
            val db2=field(runtime2,"database") as GymBuddyDatabase
            controller2=onMain{WorkoutController(runtime2,clock=clock)}
            drain(runtime2,8)
            val c2=requireNotNull(controller2)
            val recovered=c2.uiState.value as WorkoutUiState.Rest
            check(recovered.completedSetNumber==1)
            check(recovered.previousReps==1)
            check(recovered.plannedNextLoadText=="25")
            check(recovered.plannedNextLoadUnit=="lb")
            check(recovered.plannedNextLoadBasis==LoadBasis.PER_SIDE)

            onMain{c2.nextSet()}
            drain(runtime2,6)
            check(c2.uiState.value is WorkoutUiState.CameraSetup)
            val driver2=initializeDriver(runtime2,c2)
            worker(runtime2){
                driver2.ready()
                driver2.step(55.0)
                val interrupted=driver2.step(90.0,.95)
                check(interrupted.movement.paused)
                val pausedState=c2.uiState.value as WorkoutUiState.ActiveSet
                check(pausedState.cameraInstruction!=null)
                repeat(6){driver2.step(20.0)}
                driver2.cycle()
            }
            val active2=c2.uiState.value as WorkoutUiState.ActiveSet
            check(active2.repCount==1){"Interrupted motion stitched into a rep: $active2"}
            val set2=driver2.setId

            onMain{c2.endSet()}
            drain(runtime2,7)
            val rest2=c2.uiState.value as WorkoutUiState.Rest
            check(rest2.completedSetNumber==2&&rest2.previousReps==1)
            val evidence2=worker(runtime2){requireNotNull(RoomEvidenceRepository(db2.evidenceDao()).loadSet(set2))}
            check(evidence2.invalidAttempts.isNotEmpty()){"Camera interruption was not durably represented"}
            check(evidence2.reps.size==1)

            val persistedSet2=evidence2.set
            check(persistedSet2.actualLoad?.value==25.0)
            check(persistedSet2.actualLoad?.unit=="lb")
            check(persistedSet2.actualLoad?.basis==LoadBasis.PER_SIDE)
            check(persistedSet2.actualLoad?.source==LoadSource.USER_ENTERED)
            check(persistedSet2.plannedLoad?.source==LoadSource.PLANNED)

            onMain{c2.finishExercise()}
            drain(runtime2,8)
            val summary0=c2.uiState.value as WorkoutUiState.Summary
            check(summary0.completedSets.size==2)

            // GPT history write failure is part of the whole workflow, not an
            // isolated repository test: Summary must remain truthful and retryable.
            worker(runtime2){
                db2.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_step4_gpt BEFORE INSERT ON gpt_analyses "+
                        "BEGIN SELECT RAISE(ABORT, 'step4 gpt failure'); END")
            }
            val failed=CountDownLatch(1)
            val failedOk=AtomicReference<Boolean?>(null)
            onMain{
                c2.recordExternalGptAnalysis("Step 4 analysis",listOf("Keep the same setup")){
                    failedOk.set(it);failed.countDown()
                }
            }
            check(failed.await(10,TimeUnit.SECONDS)&&failedOk.get()==false)
            drain(runtime2,4)
            check((c2.uiState.value as WorkoutUiState.Summary).savedAnalyses.isEmpty())
            worker(runtime2){db2.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_step4_gpt")}

            val saved=CountDownLatch(1)
            val savedOk=AtomicReference<Boolean?>(null)
            onMain{
                c2.recordExternalGptAnalysis("Step 4 analysis",listOf("Keep the same setup")){
                    savedOk.set(it);saved.countDown()
                }
            }
            check(saved.await(10,TimeUnit.SECONDS)&&savedOk.get()==true)
            drain(runtime2,5)
            check((c2.uiState.value as WorkoutUiState.Summary).savedAnalyses.size==1)

            val exportLatch=CountDownLatch(1)
            val export=AtomicReference<Result<String>>()
            onMain{c2.askChatGpt{export.set(it);exportLatch.countDown()}}
            check(exportLatch.await(10,TimeUnit.SECONDS))
            val json=export.get().getOrThrow()
            check(json.contains("\"exercise_id\":\"$ACTUAL\""))
            check(json.contains("\"planned_exercise_id\":\"$PLANNED\""))
            check(json.contains("\"equipment_context_id\":\"$equipmentId\""))
            check(json.contains("\"basis\":\"PER_SIDE\""))
            check(json.contains("\"source\":\"USER_ENTERED\""))
            check(json.contains("\"source\":\"PLANNED\""))
            check(json.contains("\"assistance_assessment\":\"NOT_ASSESSED\""))
            check(json.contains("\"invalid_attempts\":[{"))

            onMain{c2.returnToSelection()}
            drain(runtime2,7)
            val selection=c2.uiState.value as WorkoutUiState.ExerciseSelection
            val rowActual=selection.exercises.first{it.exerciseId==ACTUAL}
            check(rowActual.completedSets==2)
            check(rowActual.favorite&&rowActual.recent)
            check(rowActual.equipmentLabel==EQUIPMENT_LABEL)
            check(selection.favoriteExercises.any{it.exerciseId==ACTUAL})
            check(selection.recentExercises.any{it.exerciseId==ACTUAL})

            onMain{c2.startNewWorkout()}
            drain(runtime2,7)
            val fresh=c2.uiState.value as WorkoutUiState.ExerciseSelection
            check(fresh.exercises.first{it.exerciseId==ACTUAL}.completedSets==0)
            worker(runtime2){
                check(db2.evidenceDao().set(set1)!=null)
                check(db2.evidenceDao().set(set2)!=null)
                check(RoomWorkoutProductRepository(db2.evidenceDao()).loadSelectionSnapshot().activeSessionId==null)
            }
        }finally{
            runCatching{controller1?.close()}
            runtime1?.let{runCatching{(it.analysisExecutor as ExecutorService).awaitTermination(15,TimeUnit.SECONDS)}}
            runCatching{controller2?.close()}
            runtime2?.let{runCatching{(it.analysisExecutor as ExecutorService).awaitTermination(15,TimeUnit.SECONDS)}}
            applicationContext.deleteDatabase(DB)
        }
    }

    private fun initializeDriver(runtime:DefaultWorkoutRuntime,controller:WorkoutController):PoseDriver{
        worker(runtime){
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            controller.frameConsumer().onFrame(
                FramePacket(0L,0L,64,64,FrameOrigin.VIDEO,BitmapImageBuilder(bitmap).build())
            )
        }
        return worker(runtime){PoseDriver(runtime,controller)}
    }

    private class PoseDriver(
        private val runtime:DefaultWorkoutRuntime,
        private val controller:WorkoutController,
    ){
        private val analyzer=field(runtime,"currentAnalyzer") as ProductionFrameAnalyzer
        private val processor=field(analyzer,"processor") as ProductionPoseFrameProcessor
        val setId=(field(runtime,"activeSet") as SetRecord).setId
        private var ts=120_000L

        fun ready(){repeat(4){step(20.0)}}
        fun cycle(){step(55.0);step(90.0);step(55.0);step(20.0)}
        fun step(angle:Double,motion:Double=0.0):ProductionFrameResult{
            val now=ts;ts+=120_000L
            val timestamp=ProductionFrameAnalyzer::class.java.getDeclaredField("lastTimestampUs")
            timestamp.isAccessible=true;timestamp.set(analyzer,now)
            val result=processor.process(
                pose(now,angle),
                TrackingObservationContext(observedViewClass=ViewClass.FRONT,cameraMotionScore=motion),
            )
            controller.onRuntimeSnapshot(
                WorkoutRuntimeSnapshot(
                    result.pipeline.cameraGuidance,result.pipeline.lifecycleState,
                    result.pipeline.tracking.state,result.repCount,null,
                )
            )
            return result
        }
    }

    private fun drain(runtime:DefaultWorkoutRuntime,count:Int){repeat(count){worker(runtime){}}}
    private fun <T> worker(runtime:DefaultWorkoutRuntime,body:()->T):T{
        val task=FutureTask<T>{body()};runtime.analysisExecutor.execute(task)
        return task.get(20,TimeUnit.SECONDS)
    }
    private fun <T> onMain(body:()->T):T{
        val task=FutureTask<T>{body()};runOnUiThread(task);return task.get(10,TimeUnit.SECONDS)
    }

    companion object{
        private const val DB="gym-buddy.db"
        private const val PLANNED="incline_dumbbell_press"
        private const val ACTUAL="dumbbell_lateral_raise"
        private const val EQUIPMENT_LABEL="Step4 Dumbbells"

        private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name)
            .apply{isAccessible=true}.get(target)

        private fun pose(timestamp:Long,angle:Double):PoseFrame{
            val points=mutableMapOf(
                PoseLandmarkId.LEFT_SHOULDER to doubleArrayOf(.4,.3),
                PoseLandmarkId.RIGHT_SHOULDER to doubleArrayOf(.6,.3),
                PoseLandmarkId.LEFT_HIP to doubleArrayOf(.43,.6),
                PoseLandmarkId.RIGHT_HIP to doubleArrayOf(.57,.6),
                PoseLandmarkId.NOSE to doubleArrayOf(.5,.2),
                PoseLandmarkId.LEFT_EYE to doubleArrayOf(.48,.19),
                PoseLandmarkId.RIGHT_EYE to doubleArrayOf(.52,.19),
            )
            fun arm(s:PoseLandmarkId,h:PoseLandmarkId,e:PoseLandmarkId,w:PoseLandmarkId,degrees:Double){
                val shoulder=points.getValue(s);val hip=points.getValue(h)
                val radians=kotlin.math.atan2(hip[1]-shoulder[1],hip[0]-shoulder[0])+Math.toRadians(degrees)
                val dx=.16*kotlin.math.cos(radians);val dy=.16*kotlin.math.sin(radians)
                points[e]=doubleArrayOf(shoulder[0]+dx,shoulder[1]+dy)
                points[w]=doubleArrayOf(shoulder[0]+1.75*dx,shoulder[1]+1.75*dy)
            }
            arm(PoseLandmarkId.LEFT_SHOULDER,PoseLandmarkId.LEFT_HIP,PoseLandmarkId.LEFT_ELBOW,PoseLandmarkId.LEFT_WRIST,angle)
            arm(PoseLandmarkId.RIGHT_SHOULDER,PoseLandmarkId.RIGHT_HIP,PoseLandmarkId.RIGHT_ELBOW,PoseLandmarkId.RIGHT_WRIST,-angle)
            val candidate=PoseSubjectCandidate(0,points.mapValues{(id,p)->
                PoseLandmarkObservation(id,PoseCoordinate3d(p[0],p[1],0.0),.95,.95)
            })
            return PoseFrame(timestamp,timestamp,640,480,PoseFrameSource.VIDEO,listOf(candidate))
        }
    }
}
