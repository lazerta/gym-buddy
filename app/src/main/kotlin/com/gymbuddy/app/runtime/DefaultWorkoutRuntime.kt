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
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
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
    private val ttsFeedback=TextToSpeechCueSink(context.applicationContext){delivery->
        if(!worker.isShutdown){
            runCatching{worker.execute{repository.persistCueDelivery(delivery)}}
        }
    }

    @Volatile private var currentAnalyzer:ProductionFrameAnalyzer?=null
    @Volatile private var latestCueText:String?=null
    @Volatile private var sessionStartedAtUs:Long?=null
    private var bundle:ExerciseBundle?=null
    private var sessionId:String?=null
    private var executionId:String?=null

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
        latestCueText=null
    }

    @Synchronized
    override fun beginSet(setOrdinal:Int){
        require(setOrdinal>0)
        check(currentAnalyzer==null){"A set analyzer is already active"}
        val selected=requireNotNull(bundle){"beginExercise must be called before beginSet"}
        val selectedSessionId=requireNotNull(sessionId)
        val selectedExecutionId=requireNotNull(executionId)
        val config=AnalysisConfigResolver.resolve(selected.definition,selected.profile,selected.equipment)
        latestCueText=null
        val visual=CallbackVisualFeedbackSink(
            onShow={text->latestCueText=text},
            onClear={latestCueText=null},
        )
        val feedback=CompositeCueFeedbackSink(visual,ttsFeedback)

        currentAnalyzer=ProductionFrameAnalyzer(poseAnalyzer){firstFrame->
            val started=sessionStartedAtUs?:firstFrame.timestampUs.also{sessionStartedAtUs=it}
            ProductionPoseFrameProcessor(
                config=config,
                repository=repository,
                session=WorkoutSessionRecord(selectedSessionId,started),
                execution=ExerciseExecutionRecord(
                    selectedExecutionId,
                    selectedSessionId,
                    selected.definition.exerciseId,
                    started,
                ),
                set=SetRecord(
                    "$selectedExecutionId:set:$setOrdinal",
                    selectedExecutionId,
                    setOrdinal,
                    firstFrame.timestampUs,
                ),
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

    @Synchronized
    override fun endSet(){
        val analyzer=currentAnalyzer?:return
        analyzer.finishSet()
        currentAnalyzer=null
        latestCueText=null
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
