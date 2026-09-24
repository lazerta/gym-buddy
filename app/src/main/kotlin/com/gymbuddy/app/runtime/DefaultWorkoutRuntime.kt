package com.gymbuddy.app.runtime

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.CallbackVisualFeedbackSink
import com.gymbuddy.app.CompositeCueFeedbackSink
import com.gymbuddy.app.MediaPipePoseAnalyzer
import com.gymbuddy.app.ProductionFrameAnalyzer
import com.gymbuddy.app.ProductionPoseFrameProcessor
import com.gymbuddy.app.TextToSpeechCueSink
import com.gymbuddy.data.GymBuddyDatabaseFactory
import com.gymbuddy.data.RoomChatGptContextRepository
import com.gymbuddy.data.RoomEvidenceRepository
import com.gymbuddy.data.RoomPersonalCalibrationLifecycle
import com.gymbuddy.data.RoomPersonalCalibrationRepository
import com.gymbuddy.data.RoomWorkoutFlowRepository
import com.gymbuddy.domain.export.ChatGptContextExporter
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.ActiveSetRecovery
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultWorkoutRuntime(
    context:Context,
    private val cameraMotionSignals:CameraMotionSignalStore=CameraMotionSignalStore(),
    private val wallClock:()->Long={System.currentTimeMillis()},
):WorkoutRuntimeGateway {
    @Volatile private var workerThread:Thread?=null
    private val worker:ExecutorService=Executors.newSingleThreadExecutor{task->
        Thread(task,"gym-buddy-runtime").also{workerThread=it}
    }
    private val poseAnalyzer=MediaPipePoseAnalyzer(context.applicationContext)
    private val database=GymBuddyDatabaseFactory.create(context.applicationContext)
    private val repository=RoomEvidenceRepository(database.evidenceDao())
    private val flowRepository=RoomWorkoutFlowRepository(database.evidenceDao())
    private val calibrationRepository=RoomPersonalCalibrationRepository(database.evidenceDao())
    private val calibrationLifecycle=RoomPersonalCalibrationLifecycle(database.evidenceDao())
    private val configResolver=RuntimeAnalysisConfigResolver(::loadCalibrationSync)
    private val chatGptExporter=ChatGptContextExporter(
        RoomChatGptContextRepository(database.evidenceDao())
    )
    private val ttsFeedback=TextToSpeechCueSink(context.applicationContext){delivery->
        if(!worker.isShutdown){
            runCatching{worker.execute{repository.persistCueDelivery(delivery)}}
        }
    }

    @Volatile private var currentAnalyzer:ProductionFrameAnalyzer?=null
    @Volatile private var latestCueText:String?=null
    private var bundle:ExerciseBundle?=null
    private val workoutIdentity=WorkoutSessionIdentity()
    private var activeSession:WorkoutSessionRecord?=null
    private var activeExecution:ExerciseExecutionRecord?=null
    private var activeSet:SetRecord?=null
    private var activeConfig:AnalysisConfig?=null
    private val recoveryCheckpointGate=ActiveSetRecoveryCheckpointGate()

    override val analysisExecutor:Executor
        get()=worker

    @Synchronized
    override fun beginExercise(exerciseId:String){
        check(currentAnalyzer==null){"Cannot change exercise while a set analyzer is active"}
        val resolved=InitialExerciseProfiles.resolveByExternalId(exerciseId)
            ?:error("Unsupported exercise_id: "+exerciseId)
        bundle=resolved
        workoutIdentity.beginExercise(resolved.definition.exerciseId)
        activeSession=null
        activeExecution=null
        activeSet=null
        activeConfig=null
        recoveryCheckpointGate.reset()
        latestCueText=null
    }

    @Synchronized
    override fun resumeExercise(
        session:WorkoutSessionRecord,
        execution:ExerciseExecutionRecord,
    ){
        check(currentAnalyzer==null){"Cannot resume while a set analyzer is active"}
        require(execution.sessionId==session.sessionId)
        val resolved=InitialExerciseProfiles.resolveByExternalId(execution.exerciseId)
            ?:error("Unsupported exercise_id: "+execution.exerciseId)
        bundle=resolved
        workoutIdentity.resume(
            sessionId=session.sessionId,
            sessionStartedAtUs=session.startedAtUs,
            executionId=execution.executionId,
            executionStartedAtUs=execution.startedAtUs,
            sessionStartedAtEpochMs=session.startedAtEpochMs,
            executionStartedAtEpochMs=execution.startedAtEpochMs,
        )
        activeSession=null
        activeExecution=null
        activeSet=null
        activeConfig=null
        recoveryCheckpointGate.reset()
        latestCueText=null
    }

    @Synchronized
    override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?){
        require(setOrdinal>0)
        check(currentAnalyzer==null){"A set analyzer is already active"}
        val selected=requireNotNull(bundle){
            "beginExercise or resumeExercise must be called before beginSet"
        }
        val selectedSessionId=requireNotNull(workoutIdentity.sessionId)
        val selectedExecutionId=requireNotNull(workoutIdentity.executionId)
        val setId=selectedExecutionId+":set:"+setOrdinal
        val config=configResolver.resolve(selected)
        activeConfig=config
        recoveryCheckpointGate.reset()
        latestCueText=null
        activeSession=null
        activeExecution=null
        activeSet=null
        val visual=CallbackVisualFeedbackSink(
            onShow={text->latestCueText=text},
            onClear={latestCueText=null},
        )
        val feedback=CompositeCueFeedbackSink(visual,ttsFeedback)

        currentAnalyzer=ProductionFrameAnalyzer(
            poseAnalyzer=poseAnalyzer,
            observationContextProvider={frame->
                TrackingObservationContext(
                    cameraMotionScore=cameraMotionSignals.consume(frame.timestampUs),
                )
            },
            processorFactory={firstFrame->
                val now=wallClock()
                val started=workoutIdentity.ensureStarted(
                    firstFrame.timestampUs,
                    now,
                )
                val sessionRecord=WorkoutSessionRecord(
                    selectedSessionId,
                    started.sessionStartedAtUs,
                    started.sessionStartedAtEpochMs,
                )
                val executionRecord=ExerciseExecutionRecord(
                    selectedExecutionId,
                    selectedSessionId,
                    selected.definition.exerciseId,
                    started.executionStartedAtUs,
                    started.executionStartedAtEpochMs,
                )
                val setRecord=SetRecord(
                    setId,
                    selectedExecutionId,
                    setOrdinal,
                    firstFrame.timestampUs,
                    actualLoad,
                    now,
                )
                activeSession=sessionRecord
                activeExecution=executionRecord
                activeSet=setRecord
                ProductionPoseFrameProcessor(
                    config=config,
                    repository=repository,
                    session=sessionRecord,
                    execution=executionRecord,
                    set=setRecord,
                    feedback=feedback,
                )
            },
        )
    }

    override fun frameConsumer(
        listener:(WorkoutRuntimeSnapshot)->Unit,
    ):FrameConsumer<MPImage> =
        FrameConsumer { frame ->
            val analyzer=currentAnalyzer?:return@FrameConsumer
            try {
                val result=analyzer.analyze(frame)
                if(recoveryCheckpointGate.shouldPersist(result.pipeline.lifecycleState)){
                    val set=activeSet
                    if(set!=null){
                        flowRepository.saveActiveSetCheckpoint(set.setId)
                    }
                }
                listener(
                    WorkoutRuntimeSnapshot(
                        cameraGuidance=result.pipeline.cameraGuidance,
                        lifecycleState=result.pipeline.lifecycleState,
                        trackingState=result.pipeline.tracking.state,
                        repCount=result.repCount,
                        cueText=latestCueText,
                    )
                )
            } catch(t:IllegalStateException) {
                if(currentAnalyzer===analyzer) throw t
            }
        }

    override fun endSet(onCompleted:(CompletedSetContext?)->Unit){
        if(worker.isShutdown){
            onCompleted(null)
            return
        }
        worker.execute{
            val analyzer=currentAnalyzer
            val session=activeSession
            val execution=activeExecution
            val set=activeSet
            val config=activeConfig
            if(
                analyzer==null||
                session==null||
                execution==null||
                set==null||
                config==null
            ){
                onCompleted(null)
                return@execute
            }
            analyzer.finishSet(wallClock())
            runCatching{calibrationLifecycle.onCompletedSet(set.setId,config)}
            synchronized(this){
                if(currentAnalyzer===analyzer){
                    currentAnalyzer=null
                    activeSession=null
                    activeExecution=null
                    activeSet=null
                    activeConfig=null
                    recoveryCheckpointGate.reset()
                    latestCueText=null
                }
            }
            onCompleted(CompletedSetContext(session,execution,set))
        }
    }

    override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
        if(!worker.isShutdown){
            worker.execute{flowRepository.saveRestCheckpoint(checkpoint)}
        }
    }

    override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit){
        if(worker.isShutdown){
            onLoaded(null)
            return
        }
        worker.execute{onLoaded(flowRepository.loadRestCheckpoint())}
    }

    override fun loadActiveSetRecovery(onLoaded:(ActiveSetRecovery?)->Unit){
        if(worker.isShutdown){
            onLoaded(null)
            return
        }
        worker.execute{onLoaded(flowRepository.loadActiveSetRecovery())}
    }

    override fun markActiveSetInterrupted(
        setId:String,
        recoveredAtEpochMs:Long,
        committedReps:Int,
    ){
        if(!worker.isShutdown){
            worker.execute{
                flowRepository.markInterruptedSet(
                    setId,recoveredAtEpochMs,committedReps
                )
            }
        }
    }

    override fun clearRestCheckpoint(){
        if(!worker.isShutdown){
            worker.execute{flowRepository.clearRestCheckpoint()}
        }
    }

    override fun resetPersonalCalibration(
        exerciseId:String,
        onCompleted:(Boolean)->Unit,
    ){
        if(worker.isShutdown){
            onCompleted(false)
            return
        }
        worker.execute{
            val result=runCatching{
                val selected=InitialExerciseProfiles.resolveByExternalId(exerciseId)
                    ?:error("Unsupported exercise_id: "+exerciseId)
                val generic=AnalysisConfigResolver.resolve(
                    selected.definition,
                    selected.profile,
                    selected.equipment,
                )
                calibrationLifecycle.resetTarget(generic)
            }.getOrDefault(false)
            onCompleted(result)
        }
    }

    override fun clearPersonalCalibration(onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){
            onCompleted(false)
            return
        }
        worker.execute{
            val result=runCatching{calibrationRepository.clearActive()}.isSuccess
            onCompleted(result)
        }
    }

    override fun exportChatGptContext(
        currentSetId:String,
        onResult:(Result<String>)->Unit,
    ){
        if(worker.isShutdown){
            onResult(
                Result.failure(
                    IllegalStateException("Workout runtime is closed")
                )
            )
            return
        }
        worker.execute{
            onResult(runCatching{chatGptExporter.export(currentSetId)})
        }
    }

    override fun close(){
        currentAnalyzer=null
        cameraMotionSignals.clear()
        ttsFeedback.close()
        worker.shutdown()
        runCatching{worker.awaitTermination(2,TimeUnit.SECONDS)}
        poseAnalyzer.close()
        database.close()
    }

    private fun loadCalibrationSync()=
        if(worker.isShutdown)null
        else if(Thread.currentThread()===workerThread){
            calibrationRepository.loadActive()
        }else{
            worker.submit(Callable{calibrationRepository.loadActive()}).get()
        }
}
