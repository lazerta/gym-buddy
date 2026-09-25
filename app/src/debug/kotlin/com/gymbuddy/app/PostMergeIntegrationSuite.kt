package com.gymbuddy.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.gymbuddy.app.controller.WorkoutClock
import com.gymbuddy.app.controller.WorkoutController
import com.gymbuddy.app.controller.WorkoutUiState
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.data.*
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Integration at the production pose boundary, not a claim of image-model accuracy.
 * Only input poses, clocks and SQLite failures are controlled. The movement engine,
 * processor, runtime finalization, Room, recovery and controller are production code.
 */
internal class PostMergeIntegrationSuite(private val context:Context) {
    fun attemptAndRecoveryCheckpointCommitAtomically() = withDirect { rig ->
        rig.ready()
        rig.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_checkpoint BEFORE INSERT ON workout_flow_states " +
                "BEGIN SELECT RAISE(ABORT, 'Injected attempt checkpoint failure'); END")
        val failure=runCatching { rig.step(55.0) }.exceptionOrNull()
        check(failure!=null) { "Active attempt bypassed its durable recovery checkpoint" }
        check(rig.dao.set("set-2")==null) { "Failed checkpoint left a durable orphan attempt" }
        check(rig.flow.loadRestCheckpoint()?.completedSet?.setId=="set-1") {
            "Failed attempt erased the previous durable REST continuation"
        }
        rig.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
        rig.step(90.0)
        val recovery=requireNotNull(rig.flow.loadActiveSetRecovery())
        check(recovery.set.setId=="set-2" && recovery.committedReps==0 && !recovery.finalized)
        rig.step(55.0);rig.step()
        check(rig.dao.repsForSet("set-2").size==1)
        check(rig.flow.loadActiveSetRecovery()?.committedReps==1)
    }

    fun failedFinalizationDoesNotAdmitMoreMovement() = withDirect(failFinishOnce=true) { rig ->
        rig.cycle()
        check(runCatching { rig.processor.finishSet(rig.ts,10_000L) }.isFailure)
        val before=rig.dao.repsForSet("set-2").size
        val rejected=runCatching { rig.step() }.exceptionOrNull()
        check(rejected is IllegalStateException) { "A stopped set still accepts camera movement after a failed save" }
        rig.processor.finishSet(rig.ts,20_000L)
        check(rig.dao.setSummary("set-2")!!.completedReps==before)
    }

    fun retryPreservesOriginalStopTime() = withDirect(failFinishOnce=true) { rig ->
        rig.cycle()
        val stopUs=rig.ts
        check(runCatching { rig.processor.finishSet(stopUs,10_000L) }.isFailure)
        rig.processor.finishSet(stopUs+5_000_000L,20_000L)
        val summary=requireNotNull(rig.dao.setSummary("set-2"))
        check(summary.endedAtUs==stopUs && summary.endedAtEpochMs==10_000L) {
            "Retry rewrote the stop time as the later storage-success time: $summary"
        }
    }

    fun successfulFinalizationIsImmutable() = withDirect { rig ->
        rig.cycle()
        rig.processor.finishSet(rig.ts,10_000L)
        val first=requireNotNull(rig.dao.setSummary("set-2"))
        rig.processor.finishSet(rig.ts+5_000_000L,20_000L)
        check(first==rig.dao.setSummary("set-2")) { "Repeated End rewrote a completed summary" }
        check(runCatching { rig.step() }.exceptionOrNull() is IllegalStateException) {
            "Completed processor still accepts movement"
        }
    }

    fun runtimeControllerAndRecoveryAgreeOnRetriedLastRep():Unit = withRuntime { rig ->
        rig.ready()
        rig.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_last_rep BEFORE INSERT ON rep_evidence " +
                "BEGIN SELECT RAISE(ABORT, 'Injected last-rep transaction failure'); END")
        rig.step(55.0);rig.step(90.0);rig.step(55.0)
        check(runCatching { rig.step() }.isFailure) { "The real completed-rep write did not fail" }
        check((rig.controller.uiState.value as WorkoutUiState.ActiveSet).repCount==0)
        rig.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_last_rep")
        rig.controller.endSet()
        // End is queued on the actual runtime executor; do not synthesize completion.
    }.let { result ->
        check(result.persistedReps==1) { "The retried rep was not committed" }
        check(result.displayedReps==result.persistedReps) {
            "UI reported ${result.displayedReps} reps but Room committed ${result.persistedReps}"
        }
        check(result.recoveredReps==result.persistedReps) { "Restart recovery disagrees with committed history" }
    }

    fun restUsesCommittedCompletionTime():Unit = withRuntime(controllerEpochMs=90_000L) { rig ->
        rig.ready();rig.step(55.0);rig.step(90.0);rig.step(55.0);rig.step()
        rig.controller.endSet()
    }.let { result ->
        check(result.restStartedAtEpochMs==result.endedAtEpochMs) {
            "Rest starts at callback time (${result.restStartedAtEpochMs}), not committed end (${result.endedAtEpochMs})"
        }
    }

    private fun withDirect(failFinishOnce:Boolean=false,body:(DirectRig)->Unit) {
        val name="postmerge-${UUID.randomUUID()}.db"
        val database=GymBuddyDatabaseFactory.create(context,name)
        try { body(DirectRig(database,failFinishOnce)) }
        finally { database.close();context.deleteDatabase(name) }
    }

    private class DirectRig(val database:GymBuddyDatabase,failFinishOnce:Boolean) {
        val dao=database.evidenceDao()
        val flow=RoomWorkoutFlowRepository(dao)
        private val actual=RoomEvidenceRepository(dao)
        private val b=InitialExerciseProfiles.dumbbellLateralRaise
        private val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
        private val session=WorkoutSessionRecord("session",0L,1_000L)
        private val execution=ExerciseExecutionRecord("execution","session",b.definition.exerciseId,0L,1_000L)
        private var failFinish=failFinishOnce
        private val storage=object:EvidenceRepository by actual {
            override fun finishSet(summary:SetSummary) {
                if(failFinish) { failFinish=false;error("Injected finalization failure") }
                actual.finishSet(summary)
            }
        }
        val processor:ProductionPoseFrameProcessor
        var ts=120_000L
        init {
            actual.ensureSession(session);actual.ensureExecution(execution)
            actual.openSet(SetRecord("set-1","execution",1,0L,null,1_000L),config)
            actual.finishSet(SetSummary("set-1",100L,0,0,0,2_000L))
            flow.saveRestCheckpoint(RestCheckpointDraft("set-1","Repeat the same setup.",null,2_000L))
            processor=ProductionPoseFrameProcessor(config,storage,session,execution,
                SetRecord("set-2","execution",2,120_000L,null,3_000L))
        }
        fun step(angle:Double=20.0):ProductionFrameResult {
            val now=ts;ts+=120_000L
            return processor.process(pose(now,angle),TrackingObservationContext(observedViewClass=ViewClass.FRONT))
        }
        fun ready() { repeat(4) { step() };check(dao.set("set-2")==null) }
        fun cycle() { ready();step(55.0);step(90.0);step(55.0);step() }
    }

    private data class RuntimeResult(val persistedReps:Int,val displayedReps:Int,val recoveredReps:Int,
        val endedAtEpochMs:Long,val restStartedAtEpochMs:Long)

    private fun withRuntime(controllerEpochMs:Long=10_000L,body:(RuntimeRig)->Unit):RuntimeResult {
        val runtime=DefaultWorkoutRuntime(context,wallClock={10_000L})
        val database=field(runtime,"database") as GymBuddyDatabase
        val executor=runtime.analysisExecutor as ExecutorService
        fun <T> worker(body:()->T):T {
            val task=FutureTask<T>{body()};executor.execute(task)
            return task.get(20,TimeUnit.SECONDS)
        }
        var controller:WorkoutController?=null
        try {
            worker { database.evidenceDao().deleteWorkoutFlowState("active") }
            controller=WorkoutController(runtime,clock=object:WorkoutClock {
                override fun nowEpochMs()=controllerEpochMs
            })
            worker {};worker {} // drain both stages of asynchronous restart lookup
            val c=requireNotNull(controller)
            val rig=worker {
                c.selectExercise("dumbbell_lateral_raise")
                val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                c.frameConsumer().onFrame(FramePacket(0L,0L,64,64,FrameOrigin.VIDEO,
                    BitmapImageBuilder(bitmap).build()))
                RuntimeRig(c,runtime,database)
            }
            worker { body(rig) }
            worker {};worker {} // actual end callback and durable REST write
            return worker {
                val summary=requireNotNull(database.evidenceDao().setSummary(rig.setId))
                val state=c.uiState.value as WorkoutUiState.Rest
                val recovered=requireNotNull(RoomWorkoutFlowRepository(database.evidenceDao()).loadRestCheckpoint())
                RuntimeResult(summary.completedReps,state.previousReps,recovered.previousReps,
                    summary.endedAtEpochMs,state.restStartedAtEpochMs)
            }
        } finally {
            worker { database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_last_rep") }
            controller?.close()?:runtime.close()
            check(executor.awaitTermination(10,TimeUnit.SECONDS)) { "Runtime failed to quiesce" }
        }
    }

    private class RuntimeRig(val controller:WorkoutController,runtime:DefaultWorkoutRuntime,
        val database:GymBuddyDatabase) {
        private val analyzer=field(runtime,"currentAnalyzer") as ProductionFrameAnalyzer
        private val processor=field(analyzer,"processor") as ProductionPoseFrameProcessor
        val setId=(field(runtime,"activeSet") as SetRecord).setId
        private var ts=120_000L
        fun ready() { repeat(4) { step() } }
        fun step(angle:Double=20.0) {
            val now=ts;ts+=120_000L
            // Inject observations at the PoseEstimator output boundary, retaining
            // the real runtime's processor, timestamps, IDs and persistence objects.
            val timestamp=ProductionFrameAnalyzer::class.java.getDeclaredField("lastTimestampUs")
            timestamp.isAccessible=true;timestamp.set(analyzer,now)
            val result=processor.process(pose(now,angle),TrackingObservationContext(observedViewClass=ViewClass.FRONT))
            controller.onRuntimeSnapshot(WorkoutRuntimeSnapshot(result.pipeline.cameraGuidance,
                result.pipeline.lifecycleState,result.pipeline.tracking.state,result.repCount,null))
        }
    }

    companion object {
        private fun field(target:Any,name:String):Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible=true }.get(target)

        private fun pose(timestamp:Long,angle:Double):PoseFrame {
            val points=mutableMapOf(
                PoseLandmarkId.LEFT_SHOULDER to doubleArrayOf(.4,.3),
                PoseLandmarkId.RIGHT_SHOULDER to doubleArrayOf(.6,.3),
                PoseLandmarkId.LEFT_HIP to doubleArrayOf(.43,.6),
                PoseLandmarkId.RIGHT_HIP to doubleArrayOf(.57,.6),
                PoseLandmarkId.NOSE to doubleArrayOf(.5,.2),
                PoseLandmarkId.LEFT_EYE to doubleArrayOf(.48,.19),
                PoseLandmarkId.RIGHT_EYE to doubleArrayOf(.52,.19))
            fun arm(s:PoseLandmarkId,h:PoseLandmarkId,e:PoseLandmarkId,w:PoseLandmarkId,degrees:Double) {
                val shoulder=points.getValue(s);val hip=points.getValue(h)
                val radians=kotlin.math.atan2(hip[1]-shoulder[1],hip[0]-shoulder[0])+Math.toRadians(degrees)
                val dx=.16*kotlin.math.cos(radians);val dy=.16*kotlin.math.sin(radians)
                points[e]=doubleArrayOf(shoulder[0]+dx,shoulder[1]+dy)
                points[w]=doubleArrayOf(shoulder[0]+1.75*dx,shoulder[1]+1.75*dy)
            }
            arm(PoseLandmarkId.LEFT_SHOULDER,PoseLandmarkId.LEFT_HIP,PoseLandmarkId.LEFT_ELBOW,PoseLandmarkId.LEFT_WRIST,angle)
            arm(PoseLandmarkId.RIGHT_SHOULDER,PoseLandmarkId.RIGHT_HIP,PoseLandmarkId.RIGHT_ELBOW,PoseLandmarkId.RIGHT_WRIST,-angle)
            val candidate=PoseSubjectCandidate(0,points.mapValues { (id,p)->
                PoseLandmarkObservation(id,PoseCoordinate3d(p[0],p[1],0.0),.95,.95) })
            return PoseFrame(timestamp,timestamp,640,480,PoseFrameSource.VIDEO,listOf(candidate))
        }
    }
}
