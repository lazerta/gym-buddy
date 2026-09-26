package com.gymbuddy.app

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.gymbuddy.app.controller.*
import com.gymbuddy.app.runtime.*
import com.gymbuddy.data.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.frames.*
import java.util.concurrent.*
import org.json.JSONObject

/** Debug-only driver. The shell kills this OS process WITHOUT close/onDestroy
 * between seed and verify. Native runtime/Room are real; poses are deterministic
 * estimator-output fixtures, not evidence of real-video perception accuracy. */
class Step3ProcessRecoveryE2EActivity:ComponentActivity(){
    private var runtime:DefaultWorkoutRuntime?=null
    private var controller:WorkoutController?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val label=TextView(this).apply{text="Process recovery verification"};setContentView(label)
        val stage=requireNotNull(intent.getStringExtra("stage"))
        Thread({
            val result=JSONObject().put("stage",stage).put("pid",Process.myPid())
            try{
                if(stage.startsWith("seed-"))seed(stage.removePrefix("seed-")) else verify(stage.removePrefix("verify-"))
                result.put("passed",true)
            }catch(t:Throwable){result.put("passed",false).put("error",t.stackTraceToString())}
            filesDir.resolve("step3-$stage.json").writeText(result.toString())
            runOnUiThread{label.text=result.toString()}
            // Deliberately stay alive; force-stop from the external script is
            // the termination event, not graceful runtime disposal.
        },"process-recovery-probe").start()
    }
    private fun start():Pair<DefaultWorkoutRuntime,WorkoutController>{
        val r=DefaultWorkoutRuntime(applicationContext);runtime=r
        val c=onMain{WorkoutController(r,clock=AndroidWorkoutClock(applicationContext))};controller=c
        repeat(6){worker(r){}}
        return r to c
    }
    private fun seed(mode:String){
        require(mode=="rest"||mode=="active")
        applicationContext.deleteDatabase("gym-buddy.db")
        val (r,c)=start()
        onMain{
            c.editEquipment(ACTUAL);c.updateEquipmentLabel("Process test equipment");c.saveEquipmentContext()
        }
        repeat(4){worker(r){}}
        onMain{c.beginSubstitution(PLANNED);c.selectOtherExercise(ACTUAL)}
        repeat(5){worker(r){}}
        val driver=driver(r,c)
        worker(r){driver.ready();driver.cycle();driver.cycle();if(mode=="active")driver.step(55.0)}
        check((c.uiState.value as WorkoutUiState.ActiveSet).repCount==2)
        if(mode=="rest"){
            onMain{c.endSet()};repeat(6){worker(r){}}
            onMain{
                c.updateNextLoad("25");c.updateNextLoadUnit("lb");c.updateNextLoadBasis(LoadBasis.PER_SIDE)
                c.updateNextResistanceKind(ResistanceKind.EXTERNAL_LOAD)
                c.updateNextMeasurementMode(LoadMeasurementMode.ADDED_LOAD)
            }
            repeat(6){worker(r){}}
            val rest=c.uiState.value as WorkoutUiState.Rest
            check(!rest.savingLoad&&!rest.loadSaveFailed)
        }
        val db=field(r,"database") as GymBuddyDatabase
        val expected=worker(r){
            val evidence=RoomEvidenceRepository(db.evidenceDao()).loadSet(driver.setId)!!
            check(evidence.reps.size==2)
            val execution=db.evidenceDao().execution(evidence.set.executionId)!!
            JSONObject().put("pid",Process.myPid()).put("set_id",driver.setId)
                .put("execution_id",execution.executionId).put("session_id",execution.sessionId)
                .put("equipment_id",execution.equipmentContextId).put("mode",mode)
        }
        filesDir.resolve("step3-process-expected.json").writeText(expected.toString())
    }
    private fun verify(mode:String){
        val expected=JSONObject(filesDir.resolve("step3-process-expected.json").readText())
        check(expected.getString("mode")==mode)
        check(Process.myPid()!=expected.getInt("pid")){"Verification did not start in a new OS process"}
        val (r,c)=start()
        val db=field(r,"database") as GymBuddyDatabase
        val oldSet=expected.getString("set_id")
        worker(r){
            val evidence=RoomEvidenceRepository(db.evidenceDao()).loadSet(oldSet)!!
            check(evidence.reps.size==2 && evidence.reps.map{it.repId}.distinct().size==2)
            val execution=db.evidenceDao().execution(evidence.set.executionId)!!
            check(execution.plannedExerciseId==PLANNED && execution.exerciseId==ACTUAL)
            check(execution.equipmentContextId==expected.getString("equipment_id"))
            check(execution.sessionId==expected.getString("session_id"))
        }
        if(mode=="rest"){
            val rest=c.uiState.value as WorkoutUiState.Rest
            check(rest.previousReps==2 && rest.completedSetNumber==1)
            check(rest.plannedNextLoadText=="25" && rest.plannedNextLoadUnit=="lb")
            check(rest.plannedNextLoadBasis==LoadBasis.PER_SIDE)
            check(rest.plannedNextResistanceKind==ResistanceKind.EXTERNAL_LOAD)
            check(rest.plannedNextMeasurementMode==LoadMeasurementMode.ADDED_LOAD)
            check(rest.timerAnchor!=null && !rest.timerAnchor.estimated)
            worker(r){
                val saved=RoomWorkoutFlowRepository(db.evidenceDao()).loadRestCheckpoint()!!
                check(saved.clockAnchor?.bootId!=null)
                check(db.evidenceDao().setSummary(oldSet)!!.completedReps==2)
            }
        }else{
            val setup=c.uiState.value as WorkoutUiState.CameraSetup
            check(setup.setNumber==2)
            worker(r){
                check(db.evidenceDao().interruptedSet(oldSet)?.committedReps==2)
                check(db.evidenceDao().setSummary(oldSet)==null)
            }
            val next=driver(r,c)
            worker(r){next.ready();next.cycle()}
            check((c.uiState.value as WorkoutUiState.ActiveSet).repCount==1)
            onMain{c.endSet()};repeat(6){worker(r){}}
            check((c.uiState.value as WorkoutUiState.Rest).previousReps==1)
            worker(r){
                check(RoomEvidenceRepository(db.evidenceDao()).loadSet(oldSet)!!.reps.size==2)
                val newEvidence=RoomEvidenceRepository(db.evidenceDao()).loadSet(next.setId)!!
                check(newEvidence.reps.size==1 && newEvidence.set.setOrdinal==2)
                check(newEvidence.reps.none{newRep->db.evidenceDao().repsForSet(oldSet).any{it.repId==newRep.repId}})
            }
        }
    }
    private fun driver(r:DefaultWorkoutRuntime,c:WorkoutController):Driver{
        worker(r){
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            c.frameConsumer().onFrame(FramePacket(0L,0L,64,64,FrameOrigin.VIDEO,BitmapImageBuilder(bitmap).build()))
        }
        return worker(r){Driver(r,c)}
    }
    private class Driver(r:DefaultWorkoutRuntime,private val c:WorkoutController){
        private val analyzer=field(r,"currentAnalyzer") as ProductionFrameAnalyzer
        private val processor=field(analyzer,"processor") as ProductionPoseFrameProcessor
        val setId=(field(r,"activeSet") as SetRecord).setId
        private var timestamp=120_000L
        fun ready(){repeat(4){step(20.0)}}
        fun cycle(){step(55.0);step(90.0);step(55.0);step(20.0)}
        fun step(angle:Double){
            val now=timestamp;timestamp+=120_000L
            ProductionFrameAnalyzer::class.java.getDeclaredField("lastTimestampUs").apply{isAccessible=true}.set(analyzer,now)
            val result=processor.process(pose(now,angle),TrackingObservationContext(observedViewClass=ViewClass.FRONT,cameraMotionScore=0.0))
            c.onRuntimeSnapshot(WorkoutRuntimeSnapshot(result.pipeline.cameraGuidance,result.pipeline.lifecycleState,
                result.pipeline.tracking.state,result.repCount,null))
        }
    }
    private fun <T> worker(r:DefaultWorkoutRuntime,body:()->T):T{
        val task=FutureTask<T>{body()};r.analysisExecutor.execute(task);return task.get(20,TimeUnit.SECONDS)
    }
    private fun <T> onMain(body:()->T):T{
        val task=FutureTask<T>{body()};runOnUiThread(task);return task.get(10,TimeUnit.SECONDS)
    }
    override fun onDestroy(){controller?.close();super.onDestroy()}
    companion object{
        private const val ACTUAL="dumbbell_lateral_raise"
        private const val PLANNED="incline_dumbbell_press"
        private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(target)
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
