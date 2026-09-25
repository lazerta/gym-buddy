package com.gymbuddy.app.runtime

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.*
import com.gymbuddy.data.*
import com.gymbuddy.domain.evidence.EvidenceSummaryEngine
import com.gymbuddy.domain.export.ChatGptContextExporter
import com.gymbuddy.domain.persistence.*
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
    private val dao=database.evidenceDao()
    private val repository=RoomEvidenceRepository(dao)
    private val flowRepository=RoomWorkoutFlowRepository(dao)
    private val productRepository=RoomWorkoutProductRepository(dao)
    private val gptAnalysisRepository=RoomGptAnalysisRepository(dao)
    private val calibrationRepository=RoomPersonalCalibrationRepository(dao)
    private val calibrationLifecycle=RoomPersonalCalibrationLifecycle(dao)
    private val configResolver=RuntimeAnalysisConfigResolver(::loadCalibrationSync)
    private val evidenceSummaryEngine=EvidenceSummaryEngine(CueTextCatalog::text)
    private val chatGptExporter=ChatGptContextExporter(RoomChatGptContextRepository(dao))
    private val ttsFeedback=TextToSpeechCueSink(context.applicationContext){delivery->
        if(!worker.isShutdown){
            runCatching{worker.execute{repository.persistCueDelivery(delivery)}}
        }
    }

    @Volatile private var currentAnalyzer:ProductionFrameAnalyzer?=null
    @Volatile private var latestCueText:String?=null
    private var bundle:ExerciseBundle?=null
    private var startRequest:ExerciseStartRequest?=null
    private val workoutIdentity=WorkoutSessionIdentity()
    private var activeSession:WorkoutSessionRecord?=null
    private var activeExecution:ExerciseExecutionRecord?=null
    private var activeSet:SetRecord?=null
    private var activeConfig:AnalysisConfig?=null

    override val analysisExecutor:Executor get()=worker

    @Synchronized
    override fun beginExercise(exerciseId:String){
        beginExercise(ExerciseStartRequest(actualExerciseId=exerciseId,plannedExerciseId=exerciseId))
    }

    @Synchronized
    override fun beginExercise(request:ExerciseStartRequest){
        check(currentAnalyzer==null){"Cannot change exercise while a set analyzer is active"}
        val resolved=InitialExerciseProfiles.resolveByExternalId(request.actualExerciseId)
            ?:error("Unsupported exercise_id: ${request.actualExerciseId}")
        restoreActiveWorkoutSessionIfNeeded()
        val equipment=request.equipmentContext?.let{context->
            val base=requireNotNull(resolved.equipment){"Exercise has no equipment profile to specialize"}
            context.specialize(base)
        }?:resolved.equipment
        bundle=resolved.copy(equipment=equipment)
        startRequest=request.copy(actualExerciseId=resolved.definition.exerciseId)
        val identity=workoutIdentity.beginExercise(resolved.definition.exerciseId)
        productRepository.setActiveSession(identity.sessionId)
        request.equipmentContext?.let(productRepository::rememberEquipmentContext)
        productRepository.rememberExerciseSelection(
            ExercisePreferenceRecord(
                exerciseId=resolved.definition.exerciseId,
                lastSelectedAtEpochMs=wallClock(),
                equipmentContextId=request.equipmentContext?.contextId,
            )
        )
        activeSession=null
        activeExecution=null
        activeSet=null
        activeConfig=null
        latestCueText=null
    }

    @Synchronized
    override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord){
        check(currentAnalyzer==null){"Cannot resume while a set analyzer is active"}
        require(execution.sessionId==session.sessionId)
        val resolved=InitialExerciseProfiles.resolveByExternalId(execution.exerciseId)
            ?:error("Unsupported exercise_id: ${execution.exerciseId}")
        val context=execution.equipmentContextId?.let{id->
            productRepository.loadSelectionSnapshot().equipmentContexts.firstOrNull{it.contextId==id}
        }
        val equipment=context?.let{ctx->resolved.equipment?.let(ctx::specialize)}?:resolved.equipment
        bundle=resolved.copy(equipment=equipment)
        startRequest=ExerciseStartRequest(
            actualExerciseId=execution.exerciseId,
            plannedExerciseId=execution.plannedExerciseId,
            equipmentContext=context,
        )
        workoutIdentity.resume(
            sessionId=session.sessionId,
            sessionStartedAtUs=session.startedAtUs,
            executionId=execution.executionId,
            executionStartedAtUs=execution.startedAtUs,
            sessionStartedAtEpochMs=session.startedAtEpochMs,
            executionStartedAtEpochMs=execution.startedAtEpochMs,
        )
        productRepository.setActiveSession(session.sessionId)
        activeSession=null
        activeExecution=null
        activeSet=null
        activeConfig=null
        latestCueText=null
    }

    @Synchronized
    override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?){
        beginSet(setOrdinal,actualLoad,actualLoad?.copy(source=LoadSource.PLANNED))
    }

    @Synchronized
    override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?,plannedLoad:LoadSnapshot?){
        require(setOrdinal>0)
        check(currentAnalyzer==null){"A set analyzer is already active"}
        val selected=requireNotNull(bundle){"beginExercise or resumeExercise must be called before beginSet"}
        val request=requireNotNull(startRequest)
        val selectedSessionId=requireNotNull(workoutIdentity.sessionId)
        val selectedExecutionId=requireNotNull(workoutIdentity.executionId)
        val setId="$selectedExecutionId:set:$setOrdinal"
        val config=configResolver.resolve(selected)
        activeConfig=config
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
                TrackingObservationContext(cameraMotionScore=cameraMotionSignals.consume(frame.timestampUs))
            },
            processorFactory={firstFrame->
                val now=wallClock()
                val started=workoutIdentity.ensureStarted(firstFrame.timestampUs,now)
                val sessionRecord=WorkoutSessionRecord(
                    selectedSessionId,started.sessionStartedAtUs,started.sessionStartedAtEpochMs
                )
                val executionRecord=ExerciseExecutionRecord(
                    executionId=selectedExecutionId,
                    sessionId=selectedSessionId,
                    exerciseId=selected.definition.exerciseId,
                    startedAtUs=started.executionStartedAtUs,
                    startedAtEpochMs=started.executionStartedAtEpochMs,
                    plannedExerciseId=request.plannedExerciseId,
                    equipmentContextId=request.equipmentContext?.contextId,
                )
                val setRecord=SetRecord(
                    setId=setId,
                    executionId=selectedExecutionId,
                    setOrdinal=setOrdinal,
                    startedAtUs=firstFrame.timestampUs,
                    actualLoad=actualLoad,
                    startedAtEpochMs=now,
                    plannedLoad=plannedLoad,
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

    override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage> =
        FrameConsumer { frame ->
            val analyzer=currentAnalyzer
            if(analyzer==null){
                frame.image.close()
                return@FrameConsumer
            }
            try{
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
            }catch(t:IllegalStateException){
                if(currentAnalyzer===analyzer)throw t
            }
        }

    override fun endSet(onCompleted:(CompletedSetContext?)->Unit){
        if(worker.isShutdown){onCompleted(null);return}
        worker.execute{
            val analyzer=currentAnalyzer
            val session=activeSession
            val execution=activeExecution
            val set=activeSet
            val config=activeConfig
            if(analyzer==null||session==null||execution==null||set==null||config==null){
                onCompleted(null);return@execute
            }
            val persisted=try{
                analyzer.finishSet(wallClock())
                requireNotNull(repository.loadSet(set.setId)){
                    "Finalization completed without durable set evidence"
                }.also{requireNotNull(it.summary){"Finalization completed without a durable summary"}}
            }catch(_:Exception){
                onCompleted(null)
                return@execute
            }
            runCatching{calibrationLifecycle.onCompletedSet(set.setId,config)}
            val coaching=evidenceSummaryEngine.summarize(persisted)
            synchronized(this){
                if(currentAnalyzer===analyzer){
                    currentAnalyzer=null
                    activeSession=null
                    activeExecution=null
                    activeSet=null
                    activeConfig=null
                    latestCueText=null
                }
            }
            onCompleted(CompletedSetContext(session,execution,set,persisted.summary,coaching))
        }
    }

    override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
        if(!worker.isShutdown)worker.execute{flowRepository.saveRestCheckpoint(checkpoint)}
    }
    override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit){
        if(worker.isShutdown){onLoaded(null);return}
        worker.execute{onLoaded(flowRepository.loadRestCheckpoint())}
    }
    override fun loadActiveSetRecovery(onLoaded:(ActiveSetRecovery?)->Unit){
        if(worker.isShutdown){onLoaded(null);return}
        worker.execute{onLoaded(flowRepository.loadActiveSetRecovery())}
    }
    override fun markActiveSetInterrupted(setId:String,recoveredAtEpochMs:Long,committedReps:Int){
        if(!worker.isShutdown)worker.execute{
            flowRepository.markInterruptedSet(setId,recoveredAtEpochMs,committedReps)
        }
    }
    override fun clearRestCheckpoint(){if(!worker.isShutdown)worker.execute{flowRepository.clearRestCheckpoint()}}

    override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit){
        if(worker.isShutdown){onLoaded(WorkoutSelectionSnapshot());return}
        worker.execute{onLoaded(productRepository.loadSelectionSnapshot())}
    }
    override fun setExerciseFavorite(exerciseId:String,favorite:Boolean,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{onCompleted(runCatching{productRepository.setFavorite(exerciseId,favorite)}.isSuccess)}
    }
    override fun rememberEquipmentContext(record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{onCompleted(runCatching{productRepository.rememberEquipmentContext(record)}.isSuccess)}
    }
    override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{
            val ok=runCatching{
                productRepository.rememberEquipmentContext(record)
                productRepository.rememberExerciseSelection(
                    ExercisePreferenceRecord(
                        exerciseId=exerciseId,
                        lastSelectedAtEpochMs=productRepository.loadSelectionSnapshot().preferences
                            .firstOrNull{it.exerciseId==exerciseId}?.lastSelectedAtEpochMs?:0L,
                        equipmentContextId=record.contextId,
                    )
                )
            }.isSuccess
            onCompleted(ok)
        }
    }
    override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{
            val sessionId=workoutIdentity.sessionId?:productRepository.loadSelectionSnapshot().activeSessionId
            val ok=runCatching{
                requireNotNull(sessionId)
                productRepository.markExerciseCompleted(
                    WorkoutExerciseCompletionRecord(sessionId,exerciseId,completedSets,completedAtEpochMs)
                )
            }.isSuccess
            onCompleted(ok)
        }
    }
    @Synchronized
    override fun startNewWorkout(onCompleted:(Boolean)->Unit){
        if(currentAnalyzer!=null){onCompleted(false);return}
        workoutIdentity.reset()
        startRequest=null
        bundle=null
        activeSession=null
        activeExecution=null
        activeSet=null
        activeConfig=null
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{
            val ok=runCatching{
                // Completion badges are workout-scoped by activeSessionId; retain prior
                // workout metadata so history/debugging is not destructively rewritten.
                productRepository.setActiveSession(null)
                flowRepository.clearRestCheckpoint()
            }.isSuccess
            onCompleted(ok)
        }
    }

    override fun resetPersonalCalibration(exerciseId:String,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{
            val result=runCatching{
                val selected=InitialExerciseProfiles.resolveByExternalId(exerciseId)
                    ?:error("Unsupported exercise_id: $exerciseId")
                val generic=AnalysisConfigResolver.resolve(selected.definition,selected.profile,selected.equipment)
                calibrationLifecycle.resetTarget(generic)
            }.getOrDefault(false)
            onCompleted(result)
        }
    }
    override fun clearPersonalCalibration(onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{onCompleted(runCatching{calibrationRepository.clearActive()}.isSuccess)}
    }

    override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit){
        if(worker.isShutdown){
            onResult(Result.failure(IllegalStateException("Workout runtime is closed")));return
        }
        worker.execute{onResult(runCatching{chatGptExporter.export(currentSetId)})}
    }
    override fun appendGptAnalysis(record:GptAnalysisRecord,onCompleted:(Boolean)->Unit){
        if(worker.isShutdown){onCompleted(false);return}
        worker.execute{onCompleted(runCatching{gptAnalysisRepository.append(record)}.isSuccess)}
    }
    override fun loadGptAnalyses(setId:String,onLoaded:(List<GptAnalysisRecord>)->Unit){
        if(worker.isShutdown){onLoaded(emptyList());return}
        worker.execute{onLoaded(gptAnalysisRepository.listForSet(setId))}
    }

    @Synchronized
    override fun close(){
        if(worker.isShutdown)return
        currentAnalyzer=null
        worker.execute{
            cameraMotionSignals.clear()
            try{ttsFeedback.close()}finally{
                try{poseAnalyzer.close()}finally{database.close()}
            }
        }
        worker.shutdown()
    }

    private fun restoreActiveWorkoutSessionIfNeeded(){
        if(workoutIdentity.sessionId!=null)return
        val activeId=productRepository.loadSelectionSnapshot().activeSessionId?:return
        val stored=dao.session(activeId)
        if(stored==null){
            productRepository.setActiveSession(null)
            return
        }
        workoutIdentity.resumeSession(stored.sessionId,stored.startedAtUs,stored.startedAtEpochMs)
    }

    private fun loadCalibrationSync()=
        if(worker.isShutdown)null
        else if(Thread.currentThread()===workerThread)calibrationRepository.loadActive()
        else worker.submit(Callable{calibrationRepository.loadActive()}).get()
}
