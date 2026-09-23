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
import com.gymbuddy.data.RoomEvidenceRepository
import com.gymbuddy.data.RoomChatGptContextRepository
import com.gymbuddy.data.RoomWorkoutFlowRepository
import com.gymbuddy.domain.export.ChatGptContextExporter
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.frames.FrameConsumer
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultWorkoutRuntime(context:Context):WorkoutRuntimeGateway {
    private val worker:ExecutorService=Executors.newSingleThreadExecutor()
    private val poseAnalyzer=MediaPipePoseAnalyzer(context.applicationContext)
    private val database=GymBuddyDatabaseFactory.create(context.applicationContext)
    private val repository=RoomEvidenceRepository(database.evidenceDao())
    private val flowRepository=RoomWorkoutFlowRepository(database.evidenceDao())
    private val chatGptExporter=ChatGptContextExporter(RoomChatGptContextRepository(database.evidenceDao()))
    private val ttsFeedback=TextToSpeechCueSink(context.applicationContext){delivery->
        if(!worker.isShutdown){
            runCatching{worker.execute{repository.persistCueDelivery(delivery)}}
        }
    }

    @Volatile private var currentAnalyzer:ProductionFrameAnalyzer?=null
    @Volatile private var latestCueText:String?=null
    private var bundle:ExerciseBundle?=null
    private var sessionId:String?=null
    private var executionId:String?=null
    private var sessionStartedAtUs:Long?=null
    private var executionStartedAtUs:Long?=null
    private var activeSession:WorkoutSessionRecord?=null
    private var activeExecution:ExerciseExecutionRecord?=null
    private var activeSet:SetRecord?=null

    override val analysisExecutor:Executor
        get()=worker

    @Synchronized
    override fun beginExercise(exerciseId:String){
        check(currentAnalyzer==null){"Cannot change exercise while a set analyzer is active"}
        val resolved=InitialExerciseProfiles.resolveByExternalId(exerciseId)
            ?: error("Unsupported exercise_id: $exerciseId")
        bundle=resolved
        val newSessionId="camera-${UUID.randomUUID()}"
        sessionId=newSessionId
        executionId="$newSessionId:${resolved.definition.exerciseId}:${UUID.randomUUID()}"
        sessionStartedAtUs=null
        executionStartedAtUs=null
        activeSession=null
        activeExecution=null
        activeSet=null
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
            ?: error("Unsupported exercise_id: ${execution.exerciseId}")
        bundle=resolved
        sessionId=session.sessionId
        executionId=execution.executionId
        sessionStartedAtUs=session.startedAtUs
        executionStartedAtUs=execution.startedAtUs
        activeSession=null
        activeExecution=null
        activeSet=null
        latestCueText=null
    }

    @Synchronized
    override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?){
        require(setOrdinal>0)
        check(currentAnalyzer==null){"A set analyzer is already active"}
        val selected=requireNotNull(bundle){"beginExercise or resumeExercise must be called before beginSet"}
        val selectedSessionId=requireNotNull(sessionId)
        val selectedExecutionId=requireNotNull(executionId)
        val setId="$selectedExecutionId:set:$setOrdinal"
        val config=AnalysisConfigResolver.resolve(selected.definition,selected.profile,selected.equipment)
        latestCueText=null
        activeSession=null
        activeExecution=null
        activeSet=null
        val visual=CallbackVisualFeedbackSink(
            onShow={text->latestCueText=text},
            onClear={latestCueText=null},
        )
        val feedback=CompositeCueFeedbackSink(visual,ttsFeedback)

        currentAnalyzer=ProductionFrameAnalyzer(poseAnalyzer){firstFrame->
            val sessionStarted=sessionStartedAtUs?:firstFrame.timestampUs.also{sessionStartedAtUs=it}
            val executionStarted=executionStartedAtUs?:sessionStarted.also{executionStartedAtUs=it}
            val sessionRecord=WorkoutSessionRecord(selectedSessionId,sessionStarted)
            val executionRecord=ExerciseExecutionRecord(
                selectedExecutionId,
                selectedSessionId,
                selected.definition.exerciseId,
                executionStarted,
            )
            val setRecord=SetRecord(
                setId,
                selectedExecutionId,
                setOrdinal,
                firstFrame.timestampUs,
                actualLoad,
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
        }
    }

    override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage> =
        FrameConsumer { frame ->
            val analyzer=currentAnalyzer?:return@FrameConsumer
            try {
                val result=analyzer.analyze(frame)
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
            if(analyzer==null||session==null||execution==null||set==null){
                onCompleted(null)
                return@execute
            }
            analyzer.finishSet()
            synchronized(this){
                if(currentAnalyzer===analyzer){
                    currentAnalyzer=null
                    activeSession=null
                    activeExecution=null
                    activeSet=null
                    latestCueText=null
                }
            }
            onCompleted(CompletedSetContext(session,execution,set))
        }
    }

    override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
        if(!worker.isShutdown)worker.execute{flowRepository.saveRestCheckpoint(checkpoint)}
    }

    override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit){
        if(worker.isShutdown){
            onLoaded(null)
            return
        }
        worker.execute{onLoaded(flowRepository.loadRestCheckpoint())}
    }

    override fun clearRestCheckpoint(){
        if(!worker.isShutdown)worker.execute{flowRepository.clearRestCheckpoint()}
    }

    override fun exportChatGptContext(
        currentSetId:String,
        onResult:(Result<String>)->Unit,
    ){
        if(worker.isShutdown){
            onResult(Result.failure(IllegalStateException("Workout runtime is closed")))
            return
        }
        worker.execute{
            onResult(runCatching{chatGptExporter.export(currentSetId)})
        }
    }

    override fun close(){
        currentAnalyzer=null
        ttsFeedback.close()
        worker.shutdown()
        runCatching{worker.awaitTermination(2,TimeUnit.SECONDS)}
        poseAnalyzer.close()
        database.close()
    }
}
