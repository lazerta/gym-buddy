package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.CueTextCatalog
import com.gymbuddy.app.runtime.*
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.SemanticHash
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface WorkoutClock {
    fun nowEpochMs():Long
    fun nowElapsedMs():Long=System.nanoTime().coerceAtLeast(0L)/1_000_000L
    fun bootId():String?=null
}
object SystemWorkoutClock:WorkoutClock { override fun nowEpochMs():Long=System.currentTimeMillis() }

class WorkoutController(
    private val runtime:WorkoutRuntimeGateway,
    initialDay:WorkoutDay=WorkoutDay.PUSH,
    private val clock:WorkoutClock=SystemWorkoutClock,
){
    private val completedSets=mutableListOf<CompletedSetUiState>()
    private var selectedExerciseId:String?=null
    private var selectedStartRequest:ExerciseStartRequest?=null
    private var setNumber=1
    private var actualLoad:LoadSnapshot?=null
    private var plannedLoad:LoadSnapshot?=null
    private var currentRepCount=0
    private var latestCue:String?=null
    private var restCheckpoint:RestCheckpoint?=null
    private var newExerciseStarted=false
    private var endingSet=false
    private var lastCompletedSetId:String?=null
    private var selectedDay=initialDay
    private var recoveryPending=true
    private var selectionSnapshot=WorkoutSelectionSnapshot()
    private var priorWorkoutCompletedSets=0
    private var uiGeneration=0L
    private var selectionRequest=0L
    private var closed=false
    private var preparing=false
    private var selectionLoaded=false
    private var deferredRecovery:(()->Unit)?=null
    private val favoriteWrites=mutableSetOf<String>()
    private var equipmentWritePending=false
    private var equipmentEditGeneration=0L
    private var selectionError:String?=null
    private var restWriteRevision=0L
    private var restWritePending=false
    private var restSaveFailed=false

    private var otherExerciseOpen=false
    private var searchQuery=""
    private var substitutionForExerciseId:String?=null
    private var equipmentEditorExerciseId:String?=null
    private var equipmentLabelInput=""
    private val pendingEquipmentContexts=mutableMapOf<String,EquipmentContextRecord>()

    val currentDay:WorkoutDay
        @Synchronized get()=selectedDay

    private val _uiState=MutableStateFlow<WorkoutUiState>(selectionState())
    val uiState:StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    init{
        refreshSelection()
        runtime.loadRestCheckpoint(::onRestCheckpointLoaded)
    }

    val analysisExecutor:Executor get()=runtime.analysisExecutor
    fun frameConsumer():FrameConsumer<MPImage> = runtime.frameConsumer(::onRuntimeSnapshot)

    @Synchronized
    fun selectDay(day:WorkoutDay){
        if(recoveryPending||preparing||closed)return
        selectedDay=day
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    fun selectExercise(exerciseId:String){
        selectExerciseInternal(exerciseId,plannedExerciseId=exerciseId)
    }

    @Synchronized
    fun beginSubstitution(plannedExerciseId:String){
        if(recoveryPending||preparing||closed)return
        requireNotNull(InitialExerciseProfiles.resolveByExternalId(plannedExerciseId))
        otherExerciseOpen=true
        substitutionForExerciseId=plannedExerciseId
        searchQuery=""
        _uiState.value=selectionState()
    }

    @Synchronized
    fun openOtherExercise(){
        if(recoveryPending||preparing||closed)return
        otherExerciseOpen=true
        substitutionForExerciseId=null
        searchQuery=""
        _uiState.value=selectionState()
    }

    @Synchronized
    fun closeOtherExercise(){
        otherExerciseOpen=false
        substitutionForExerciseId=null
        searchQuery=""
        equipmentEditorExerciseId=null
        equipmentLabelInput=""
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    fun updateExerciseSearch(value:String){
        searchQuery=value.take(80)
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    fun selectOtherExercise(exerciseId:String){
        selectExerciseInternal(exerciseId,plannedExerciseId=substitutionForExerciseId)
    }

    @Synchronized
    fun toggleFavorite(exerciseId:String){
        if(recoveryPending||closed||!favoriteWrites.add(exerciseId))return
        val next=!(selectionSnapshot.preferences.firstOrNull{it.exerciseId==exerciseId}?.favorite?:false)
        selectionRequest++ // invalidate reads begun before this write
        runtime.setExerciseFavorite(exerciseId,next){success->
            synchronized(this){
                favoriteWrites.remove(exerciseId)
                selectionRequest++
                if(closed)return@synchronized
                if(success){
                    val current=selectionSnapshot.preferences.firstOrNull{it.exerciseId==exerciseId}
                        ?:ExercisePreferenceRecord(exerciseId)
                    selectionSnapshot=selectionSnapshot.copy(preferences=
                        selectionSnapshot.preferences.filterNot{it.exerciseId==exerciseId}+current.copy(favorite=next))
                }
                selectionError=if(success)null else "Favorite was not saved. Try again."
                if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
            }
        }
    }

    @Synchronized
    fun editEquipment(exerciseId:String){
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:return
        if(bundle.equipment==null)return
        if(recoveryPending||preparing||closed||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        equipmentEditGeneration++
        equipmentEditorExerciseId=exerciseId
        equipmentLabelInput=currentEquipmentContext(exerciseId)?.label.orEmpty()
        _uiState.value=selectionState()
    }

    @Synchronized
    fun updateEquipmentLabel(value:String){
        equipmentLabelInput=value.take(50)
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    fun saveEquipmentContext(){
        if(equipmentWritePending||closed)return
        val exerciseId=equipmentEditorExerciseId?:return
        val label=equipmentLabelInput.trim()
        if(label.isEmpty())return
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:return
        val base=bundle.equipment?:return
        val context=EquipmentContextRecord(
            contextId="equipment-context-"+SemanticHash.sha256(
                base.profileId,label.lowercase(Locale.US)
            ).take(16),
            baseEquipmentProfileId=base.profileId,
            label=label,
            updatedAtEpochMs=clock.nowEpochMs(),
        )
        equipmentWritePending=true
        _uiState.value=selectionState()
        val edit=equipmentEditGeneration
        selectionRequest++
        runtime.rememberEquipmentContext(exerciseId,context){success->
            synchronized(this){
                equipmentWritePending=false
                selectionRequest++
                if(closed)return@synchronized
                if(success){
                    pendingEquipmentContexts[exerciseId]=context
                    val current=selectionSnapshot.preferences.firstOrNull{it.exerciseId==exerciseId}
                        ?:ExercisePreferenceRecord(exerciseId)
                    selectionSnapshot=selectionSnapshot.copy(
                        equipmentContexts=selectionSnapshot.equipmentContexts.filterNot{it.contextId==context.contextId}+context,
                        preferences=selectionSnapshot.preferences.filterNot{it.exerciseId==exerciseId}+
                            current.copy(equipmentContextId=context.contextId))
                    if(edit==equipmentEditGeneration){equipmentEditorExerciseId=null;equipmentLabelInput=""}
                }
                selectionError=if(success)null else "Equipment was not saved. Try again."
                if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
            }
        }
    }

    @Synchronized
    fun startNewWorkout(){
        if(recoveryPending||preparing||equipmentWritePending||closed||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        preparing=true
        _uiState.value=selectionState()
        val generation=++uiGeneration
        selectionRequest++
        runtime.startNewWorkout{success->
            synchronized(this){
                if(closed||uiGeneration!=generation)return@synchronized
                preparing=false
                selectionError=if(success)null else "New workout was not saved. Try again."
                if(!success)_uiState.value=selectionState()
            }
            if(success){
                synchronized(this){
                    if(closed||uiGeneration!=generation)return@synchronized
                    selectionSnapshot=selectionSnapshot.copy(activeSessionId=null,completions=emptyList())
                    pendingEquipmentContexts.clear()
                    _uiState.value=selectionState()
                }
            }
        }
    }

    @Synchronized
    private fun selectExerciseInternal(exerciseId:String,plannedExerciseId:String?){
        if(recoveryPending||preparing||equipmentWritePending||closed||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)
            ?:error("Unsupported exercise_id: $exerciseId")
        val actualId=bundle.definition.exerciseId
        val context=pendingEquipmentContexts[actualId]?:currentEquipmentContext(actualId)
        val request=ExerciseStartRequest(actualId,plannedExerciseId,context)
        newExerciseStarted=true
        selectedStartRequest=request
        selectedExerciseId=actualId
        priorWorkoutCompletedSets=completionFor(actualId)
        setNumber=1
        actualLoad=null
        plannedLoad=null
        currentRepCount=0
        latestCue=null
        restCheckpoint=null
        restWriteRevision++;restWritePending=false;restSaveFailed=false
        endingSet=false
        lastCompletedSetId=null
        completedSets.clear()
        otherExerciseOpen=false
        substitutionForExerciseId=null
        searchQuery=""
        preparing=true
        _uiState.value=selectionState()
        val generation=++uiGeneration
        runtime.prepareExercise(request){success->
            synchronized(this){
                if(closed||generation!=uiGeneration)return@synchronized
                preparing=false
                if(!success){
                    selectedExerciseId=null
                    selectionError="Exercise setup failed. Try again."
                    _uiState.value=selectionState()
                    return@synchronized
                }
                _uiState.value=WorkoutUiState.CameraSetup(
            exerciseId=actualId,
            exerciseName=bundle.definition.displayName,
            instruction=initialCameraInstruction(bundle.profile.cameraProfile.preferredViewClass.name),
            readiness=CameraReadinessUi.SETTING_UP,
            setNumber=setNumber,
                )
            }
        }
    }

    @Synchronized
    fun onCameraPermissionDenied(){
        val current=_uiState.value
        if(current is WorkoutUiState.CameraSetup){
            _uiState.value=current.copy(
                instruction="Allow camera access to continue setup.",
                readiness=CameraReadinessUi.SETTING_UP,
            )
        }
    }

    @Synchronized
    fun endSet(){
        if(endingSet)return
        if(_uiState.value !is WorkoutUiState.ActiveSet)return
        endingSet=true
        runtime.endSet(::completeSet)
    }

    @Synchronized
    private fun completeSet(context:CompletedSetContext?){
        if(context==null){endingSet=false;return}
        val exerciseId=selectedExerciseId?:run{endingSet=false;return}
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:run{endingSet=false;return}
        val finalLoad=context.set.actualLoad
        currentRepCount=context.summary?.completedReps?:currentRepCount
        val focus=context.coachingSummary?.focusText?:latestCue?:"Repeat the same setup."
        val restStarted=context.summary?.endedAtEpochMs?.takeIf{it>0L}?:clock.nowEpochMs()
        val nextPlan=finalLoad?.copy(source=LoadSource.PLANNED)
        val timeAnchor=RestClockAnchor(clock.nowElapsedMs(),clock.bootId())
        plannedLoad=nextPlan
        val checkpoint=RestCheckpoint(
            session=context.session,
            execution=context.execution,
            completedSet=context.set,
            previousReps=currentRepCount,
            focus=focus,
            plannedNextLoad=nextPlan,
            restStartedAtEpochMs=restStarted,
            clockAnchor=timeAnchor,
        )
        restCheckpoint=checkpoint
        lastCompletedSetId=context.set.setId
        completedSets+=CompletedSetUiState(
            setNumber=context.set.setOrdinal,
            reps=currentRepCount,
            actualLoadText=formatLoadDisplay(finalLoad),
            focus=focus,
            setId=context.set.setId,
            coachingSummary=context.coachingSummary,
        )
        _uiState.value=WorkoutUiState.Rest(
            exerciseId=exerciseId,
            exerciseName=bundle.definition.displayName,
            completedSetNumber=context.set.setOrdinal,
            previousReps=currentRepCount,
            previousActualLoadText=formatLoadDisplay(finalLoad),
            focus=focus,
            plannedNextLoadText=formatLoadInput(nextPlan),
            restStartedAtEpochMs=restStarted,
            plannedNextLoadUnit=nextPlan?.unit,
            plannedNextLoadBasis=nextPlan?.basis?:LoadBasis.UNKNOWN,
            plannedNextLoadSource=LoadSource.PLANNED,
            plannedNextResistanceKind=nextPlan?.resistanceKind?:ResistanceKind.UNKNOWN,
            plannedNextMeasurementMode=nextPlan?.measurementMode?:LoadMeasurementMode.UNKNOWN,
            timerAnchor=RestTimerAnchor(0,timeAnchor.elapsedRealtimeMs),
        )
        persistRestDraft()
        endingSet=false
    }

    @Synchronized
    fun updateNextLoad(value:String){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        if(value.isNotEmpty()){
            val parsed=value.toDoubleOrNull()?:return
            if(!parsed.isFinite()||parsed<0.0)return
        }
        updateRestDraft(current.copy(plannedNextLoadText=value))
    }

    @Synchronized
    fun updateNextLoadUnit(value:String){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        updateRestDraft(current.copy(plannedNextLoadUnit=value.trim().take(32).takeIf{it.isNotEmpty()}))
    }

    @Synchronized
    fun updateNextLoadBasis(value:LoadBasis){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        updateRestDraft(current.copy(plannedNextLoadBasis=value))
    }

    @Synchronized
    fun updateNextResistanceKind(value:ResistanceKind){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        updateRestDraft(current.copy(plannedNextResistanceKind=value))
    }

    @Synchronized
    fun updateNextMeasurementMode(value:LoadMeasurementMode){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        updateRestDraft(current.copy(plannedNextMeasurementMode=value))
    }

    private fun loadFromRest(current:WorkoutUiState.Rest)=parseLoad(
        current.plannedNextLoadText,current.plannedNextLoadUnit,current.plannedNextLoadBasis,LoadSource.PLANNED,
        current.plannedNextResistanceKind,current.plannedNextMeasurementMode,
    )

    private fun updateRestDraft(next:WorkoutUiState.Rest){
        plannedLoad=loadFromRest(next)
        _uiState.value=next
        restCheckpoint=restCheckpoint?.copy(plannedNextLoad=plannedLoad)
        persistRestDraft()
    }

    @Synchronized
    fun retryRestSave(){
        if(preparing||closed||restWritePending||_uiState.value !is WorkoutUiState.Rest)return
        persistRestDraft()
    }

    private fun persistRestDraft(){
        val checkpoint=restCheckpoint?:return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        val revision=++restWriteRevision
        val generation=uiGeneration
        restWritePending=true;restSaveFailed=false
        _uiState.value=current.copy(savingLoad=true,loadSaveFailed=false,errorMessage=null)
        runtime.saveRestCheckpoint(checkpoint.toDraft()){success->
            synchronized(this){
                if(closed||generation!=uiGeneration||revision!=restWriteRevision||
                    restCheckpoint?.completedSet?.setId!=checkpoint.completedSet.setId)return@synchronized
                val shown=_uiState.value as? WorkoutUiState.Rest?:return@synchronized
                restWritePending=false;restSaveFailed=!success
                _uiState.value=shown.copy(savingLoad=false,loadSaveFailed=!success,
                    errorMessage=if(success)null else "Changes were not saved. Retry before continuing.")
            }
        }
    }

    @Synchronized
    fun nextSet(){
        if(preparing||closed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        if(restWritePending||restSaveFailed)return
        val ordinal=current.completedSetNumber+1
        val nextPlan=loadFromRest(current)
        val nextActual=nextPlan?.copy(source=LoadSource.CARRIED_FROM_PLAN)
        preparing=true
        _uiState.value=current.copy(busy=true,errorMessage=null)
        val generation=++uiGeneration
        runtime.prepareSet(ordinal,nextActual,nextPlan){success->
            synchronized(this){
                if(closed||generation!=uiGeneration)return@synchronized
                preparing=false
                if(!success){
                    _uiState.value=current.copy(errorMessage="Next set could not be prepared. Try again.")
                    return@synchronized
                }
                setNumber=ordinal;plannedLoad=nextPlan;actualLoad=nextActual
                currentRepCount=0;latestCue=null;endingSet=false;restCheckpoint=null
                _uiState.value=WorkoutUiState.CameraSetup(
                    exerciseId=current.exerciseId,exerciseName=current.exerciseName,
                    instruction="Recheck the phone position.",readiness=CameraReadinessUi.SETTING_UP,
                    setNumber=ordinal,
                )
            }
        }
    }

    @Synchronized
    fun finishExercise(){
        if(preparing||closed||restWritePending||restSaveFailed)return
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        preparing=true
        _uiState.value=current.copy(busy=true,errorMessage=null)
        val generation=uiGeneration
        val totalCompleted=priorWorkoutCompletedSets+completedSets.size
        runtime.markExerciseCompleted(current.exerciseId,totalCompleted,clock.nowEpochMs()){success->
            synchronized(this){
                if(closed||generation!=uiGeneration)return@synchronized
                preparing=false
                if(!success){
                    _uiState.value=current.copy(errorMessage="Exercise completion was not saved. Try again.")
                    return@synchronized
                }
                // Runtime atomically commits completion and clears its REST marker.
                // Do not erase recovery before that transaction has succeeded.
                restCheckpoint=null
                // Aggregate structured evidence, not just the single Rest focus.
                val unresolved=linkedSetOf<String>()
                completedSets.forEach{set->
                    set.coachingSummary?.let{evidence->
                        unresolved.removeAll(evidence.resolvedRuleIds)
                        unresolved.addAll(evidence.recurringRuleIds)
                    }
                }
                val recurring=unresolved.map(CueTextCatalog::text)
                val responses=completedSets.flatMap{it.coachingSummary?.cueResponses.orEmpty()}
                    .distinctBy{it.cueId}.map{response->
                        val outcome=response.state.name.lowercase(Locale.US)
                        "${CueTextCatalog.text(response.ruleId)} — delivered; movement response: $outcome."
                    }
                _uiState.value=WorkoutUiState.Summary(
                    exerciseId=current.exerciseId,exerciseName=current.exerciseName,
                    completedSets=completedSets.toList(),
                    evidenceSummary=recurring.firstOrNull()
                        ?:completedSets.lastOrNull{it.coachingSummary==null&&it.focus!="Repeat the same setup."}?.focus
                        ?:current.focus,recurringEvidence=recurring,
                    cueResponseEvidence=responses,
                )
                refreshSelection()
                lastCompletedSetId?.let{setId->
                    runtime.loadGptAnalyses(setId){records->
                        synchronized(this) history@{
                            if(closed||generation!=uiGeneration||lastCompletedSetId!=setId)return@history
                            val summary=_uiState.value as? WorkoutUiState.Summary?:return@history
                            _uiState.value=summary.copy(savedAnalyses=records.map(::analysisUi))
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun askChatGpt(onResult:(Result<String>)->Unit){
        if(_uiState.value !is WorkoutUiState.Summary){
            onResult(Result.failure(IllegalStateException("Ask ChatGPT is only available from summary")));return
        }
        val setId=lastCompletedSetId
        if(setId==null){
            onResult(Result.failure(IllegalStateException("No completed set is available to export")));return
        }
        runtime.exportChatGptContext(setId,onResult)
    }

    @Synchronized
    fun recordExternalGptAnalysis(
        summary:String,
        recommendations:List<String>,
        modelLabel:String="external-chatgpt",
        onCompleted:(Boolean)->Unit={},
    ){
        if(closed||_uiState.value !is WorkoutUiState.Summary){onCompleted(false);return}
        val setId=lastCompletedSetId?:run{onCompleted(false);return}
        val generation=uiGeneration
        val record=runCatching{
            GptAnalysisRecord(
                analysisId="gpt-${UUID.randomUUID()}",
                setId=setId,
                schemaVersion=1,
                modelLabel=modelLabel,
                createdAtEpochMs=clock.nowEpochMs(),
                sourceSetIds=completedSets.mapNotNull{it.setId}.toSet()+setId,
                summary=summary,
                recommendations=recommendations,
            )
        }.getOrElse{onCompleted(false);return}
        runtime.appendGptAnalysis(record){success->
            if(success&&synchronized(this){!closed&&generation==uiGeneration&&lastCompletedSetId==setId}){
                runtime.loadGptAnalyses(setId){records->
                    synchronized(this){
                        if(closed||generation!=uiGeneration||lastCompletedSetId!=setId)return@synchronized
                        val current=_uiState.value as? WorkoutUiState.Summary?:return@synchronized
                        _uiState.value=current.copy(savedAnalyses=records.map(::analysisUi))
                    }
                }
            }
            onCompleted(success)
        }
    }

    @Synchronized
    fun returnToSelection(){
        if(closed||preparing||_uiState.value !is WorkoutUiState.Summary)return
        uiGeneration++
        selectedExerciseId=null
        selectedStartRequest=null
        currentRepCount=0
        latestCue=null
        actualLoad=null
        plannedLoad=null
        restCheckpoint=null
        restWriteRevision++;restWritePending=false;restSaveFailed=false
        endingSet=false
        lastCompletedSetId=null
        completedSets.clear()
        runtime.clearRestCheckpoint()
        refreshSelection()
        _uiState.value=selectionState()
    }

    @Synchronized
    internal fun onRuntimeSnapshot(snapshot:WorkoutRuntimeSnapshot){
        val exerciseId=selectedExerciseId?:return
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:return
        if(closed||preparing||_uiState.value is WorkoutUiState.Rest||_uiState.value is WorkoutUiState.Summary||
            _uiState.value is WorkoutUiState.ExerciseSelection)return
        currentRepCount=snapshot.repCount
        latestCue=snapshot.cueText

        val active=_uiState.value is WorkoutUiState.ActiveSet||
            snapshot.lifecycleState==SetLifecycleState.ACTIVE_SET||
            snapshot.lifecycleState==SetLifecycleState.POSSIBLE_END||
            snapshot.lifecycleState==SetLifecycleState.FINALIZING
        if(active){
            val cameraInstruction=snapshot.cameraGuidance
                .takeUnless{it==CameraGuidanceAction.CAMERA_READY}
                ?.let(::guidanceText)
            _uiState.value=WorkoutUiState.ActiveSet(
                exerciseId=exerciseId,
                exerciseName=bundle.definition.displayName,
                setNumber=setNumber,
                actualLoadText=formatLoadDisplay(actualLoad),
                repCount=snapshot.repCount,
                trackingText=trackingText(snapshot.trackingState),
                cue=snapshot.cueText,
                cameraInstruction=cameraInstruction,
            )
            return
        }
        _uiState.value=WorkoutUiState.CameraSetup(
            exerciseId=exerciseId,
            exerciseName=bundle.definition.displayName,
            instruction=guidanceText(snapshot.cameraGuidance),
            readiness=if(snapshot.cameraGuidance==CameraGuidanceAction.CAMERA_READY)CameraReadinessUi.READY else CameraReadinessUi.SETTING_UP,
            setNumber=setNumber,
        )
    }

    @Synchronized
    private fun refreshSelection(){
        val request=++selectionRequest
        runtime.loadWorkoutSelection{snapshot->
            synchronized(this){
                if(!closed&&request==selectionRequest)onSelectionSnapshotLoaded(snapshot)
            }
        }
    }

    @Synchronized
    private fun onSelectionSnapshotLoaded(snapshot:WorkoutSelectionSnapshot){
        selectionSnapshot=snapshot
        selectionLoaded=true
        completeRecoveryWhenReady()
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    private fun onRestCheckpointLoaded(checkpoint:RestCheckpoint?){
        if(checkpoint!=null){
            deferredRecovery={restoreRestCheckpoint(checkpoint)}
            completeRecoveryWhenReady()
            return
        }
        runtime.loadActiveSetRecovery(::onActiveSetRecoveryLoaded)
    }

    @Synchronized
    private fun onActiveSetRecoveryLoaded(recovery:ActiveSetRecovery?){
        deferredRecovery={if(recovery!=null)restoreActiveSetRecovery(recovery)}
        completeRecoveryWhenReady()
    }

    private fun completeRecoveryWhenReady(){
        if(closed||!recoveryPending||!selectionLoaded)return
        val action=deferredRecovery?:return
        deferredRecovery=null
        recoveryPending=false
        action()
        if(_uiState.value is WorkoutUiState.ExerciseSelection)_uiState.value=selectionState()
    }

    @Synchronized
    private fun restoreActiveSetRecovery(recovery:ActiveSetRecovery){
        if(newExerciseStarted||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        val bundle=InitialExerciseProfiles.resolveByExternalId(recovery.execution.exerciseId)?:return
        runtime.resumeExercise(recovery.session,recovery.execution)
        selectedExerciseId=recovery.execution.exerciseId
        priorWorkoutCompletedSets=completionFor(recovery.execution.exerciseId)
        selectedStartRequest=ExerciseStartRequest(
            recovery.execution.exerciseId,recovery.execution.plannedExerciseId,currentEquipmentContext(recovery.execution.exerciseId)
        )
        actualLoad=recovery.set.actualLoad
        plannedLoad=recovery.set.plannedLoad
        restoreCompletedHistory(recovery.completedSets)
        latestCue=null
        endingSet=false
        if(recovery.finalized){
            setNumber=recovery.set.setOrdinal
            currentRepCount=recovery.committedReps
            val restStarted=recovery.endedAtEpochMs.takeIf{it>0L}?:clock.nowEpochMs()
            val checkpoint=RestCheckpoint(
                recovery.session,recovery.execution,recovery.set,recovery.committedReps,
                recovery.completedSets.lastOrNull{it.set.setId==recovery.set.setId}?.focus?:"Repeat the same setup.",
                recovery.set.actualLoad?.copy(source=LoadSource.PLANNED),restStarted,
                recovery.completedSets,
            )
            restCheckpoint=checkpoint
            lastCompletedSetId=recovery.set.setId
            if(completedSets.none{it.setNumber==recovery.set.setOrdinal}){
                completedSets+=CompletedSetUiState(
                    recovery.set.setOrdinal,recovery.committedReps,formatLoadDisplay(recovery.set.actualLoad),checkpoint.focus,
                    recovery.set.setId,recovery.completedSets.lastOrNull{it.set.setId==recovery.set.setId}?.coachingSummary
                )
            }
            plannedLoad=checkpoint.plannedNextLoad
            _uiState.value=WorkoutUiState.Rest(
                recovery.execution.exerciseId,bundle.definition.displayName,recovery.set.setOrdinal,
                recovery.committedReps,formatLoadDisplay(recovery.set.actualLoad),checkpoint.focus,
                formatLoadInput(plannedLoad),restStarted,plannedLoad?.unit,plannedLoad?.basis?:LoadBasis.UNKNOWN,LoadSource.RECOVERED,
                plannedNextResistanceKind=plannedLoad?.resistanceKind?:ResistanceKind.UNKNOWN,
                plannedNextMeasurementMode=plannedLoad?.measurementMode?:LoadMeasurementMode.UNKNOWN,
                timerAnchor=timerFor(checkpoint),
            )
            persistRestDraft()
            return
        }
        runtime.markActiveSetInterrupted(recovery.set.setId,clock.nowEpochMs(),recovery.committedReps)
        setNumber=recovery.set.setOrdinal+1
        currentRepCount=0
        restCheckpoint=null
        lastCompletedSetId=null
        runtime.beginSet(setNumber,actualLoad,plannedLoad)
        _uiState.value=WorkoutUiState.CameraSetup(
            recovery.execution.exerciseId,bundle.definition.displayName,
            "Previous set was interrupted. Recheck the phone position.",CameraReadinessUi.SETTING_UP,setNumber,
        )
    }

    @Synchronized
    private fun restoreRestCheckpoint(checkpoint:RestCheckpoint){
        if(newExerciseStarted||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        val bundle=InitialExerciseProfiles.resolveByExternalId(checkpoint.execution.exerciseId)?:return
        runtime.resumeExercise(checkpoint.session,checkpoint.execution)
        selectedExerciseId=checkpoint.execution.exerciseId
        priorWorkoutCompletedSets=completionFor(checkpoint.execution.exerciseId)
        selectedStartRequest=ExerciseStartRequest(
            checkpoint.execution.exerciseId,checkpoint.execution.plannedExerciseId,currentEquipmentContext(checkpoint.execution.exerciseId)
        )
        setNumber=checkpoint.completedSet.setOrdinal
        actualLoad=checkpoint.completedSet.actualLoad
        plannedLoad=checkpoint.plannedNextLoad
        currentRepCount=checkpoint.previousReps
        latestCue=checkpoint.focus.takeUnless{it=="Repeat the same setup."}
        restCheckpoint=checkpoint
        lastCompletedSetId=checkpoint.completedSet.setId
        restoreCompletedHistory(checkpoint.completedSets)
        if(completedSets.none{it.setNumber==checkpoint.completedSet.setOrdinal}){
            completedSets+=CompletedSetUiState(
                checkpoint.completedSet.setOrdinal,checkpoint.previousReps,
                formatLoadDisplay(checkpoint.completedSet.actualLoad),checkpoint.focus,
                checkpoint.completedSet.setId,checkpoint.completedSets.lastOrNull{it.set.setId==checkpoint.completedSet.setId}?.coachingSummary,
            )
        }
        _uiState.value=WorkoutUiState.Rest(
            checkpoint.execution.exerciseId,bundle.definition.displayName,checkpoint.completedSet.setOrdinal,
            checkpoint.previousReps,formatLoadDisplay(checkpoint.completedSet.actualLoad),checkpoint.focus,
            formatLoadInput(plannedLoad),checkpoint.restStartedAtEpochMs,plannedLoad?.unit,
            plannedLoad?.basis?:LoadBasis.UNKNOWN,plannedLoad?.source?:LoadSource.RECOVERED,
            plannedNextResistanceKind=plannedLoad?.resistanceKind?:ResistanceKind.UNKNOWN,
            plannedNextMeasurementMode=plannedLoad?.measurementMode?:LoadMeasurementMode.UNKNOWN,
            timerAnchor=timerFor(checkpoint),
        )
    }

    private fun restoreCompletedHistory(history:List<CompletedSetRecord>){
        completedSets.clear()
        completedSets+=history.sortedBy{it.set.setOrdinal}.map{record->
            CompletedSetUiState(record.set.setOrdinal,record.reps,formatLoadDisplay(record.set.actualLoad),record.focus,
                record.set.setId,record.coachingSummary)
        }
    }

    fun resetPersonalCalibration(onCompleted:(Boolean)->Unit={}){
        val exerciseId=selectedExerciseId
        if(exerciseId==null){onCompleted(false);return}
        runtime.resetPersonalCalibration(exerciseId,onCompleted)
    }

    @Synchronized
    fun close(){closed=true;uiGeneration++;selectionRequest++;runtime.close()}

    private fun selectionState():WorkoutUiState.ExerciseSelection{
        val searchCandidates=InitialExerciseProfiles.all.map{
            ExerciseSearch.Candidate(it.definition.exerciseId,it.definition.displayName,it.definition.aliases)
        }
        val searchResults=if(otherExerciseOpen){
            ExerciseSearch.search(searchQuery,searchCandidates).map{rowFor(it.exerciseId)}
        }else emptyList()
        val recent=selectionSnapshot.preferences.filter{it.lastSelectedAtEpochMs>0L}
            .sortedWith(compareByDescending<ExercisePreferenceRecord>{it.lastSelectedAtEpochMs}.thenBy{it.exerciseId})
            .take(5).mapNotNull{InitialExerciseProfiles.resolveByExternalId(it.exerciseId)?.let{_->rowFor(it.exerciseId)}}
        val favorites=selectionSnapshot.preferences.filter{it.favorite}.sortedBy{it.exerciseId}
            .mapNotNull{InitialExerciseProfiles.resolveByExternalId(it.exerciseId)?.let{_->rowFor(it.exerciseId)}}
        return WorkoutUiState.ExerciseSelection(
            selectedDay=selectedDay,
            exercises=exerciseRows(selectedDay),
            otherExerciseOpen=otherExerciseOpen,
            searchQuery=searchQuery,
            searchResults=searchResults,
            recentExercises=recent,
            favoriteExercises=favorites,
            substitutionForExerciseId=substitutionForExerciseId,
            equipmentEditorExerciseId=equipmentEditorExerciseId,
            equipmentLabelInput=equipmentLabelInput,
            errorMessage=selectionError,
            busy=preparing||equipmentWritePending,
        )
    }

    private fun exerciseRows(day:WorkoutDay):List<ExerciseRowUiState>{
        val ids=when(day){
            WorkoutDay.PUSH->setOf("incline_dumbbell_press","dumbbell_lateral_raise")
            WorkoutDay.PULL->emptySet()
            WorkoutDay.LEGS->setOf("smith_machine_squat")
        }
        return InitialExerciseProfiles.all.filter{it.definition.exerciseId in ids}.map{rowFor(it.definition.exerciseId)}
    }

    private fun rowFor(exerciseId:String):ExerciseRowUiState{
        val bundle=requireNotNull(InitialExerciseProfiles.resolveByExternalId(exerciseId))
        val preference=selectionSnapshot.preferences.firstOrNull{it.exerciseId==exerciseId}
        val context=pendingEquipmentContexts[exerciseId]?:preference?.equipmentContextId?.let{id->
            selectionSnapshot.equipmentContexts.firstOrNull{it.contextId==id}
        }
        return ExerciseRowUiState(
            exerciseId=bundle.definition.exerciseId,
            displayName=bundle.definition.displayName,
            completedSets=completionFor(bundle.definition.exerciseId),
            favorite=preference?.favorite?:false,
            recent=(preference?.lastSelectedAtEpochMs?:0L)>0L,
            equipmentLabel=context?.label,
        )
    }

    private fun completionFor(exerciseId:String)=selectionSnapshot.activeSessionId?.let{sessionId->
        selectionSnapshot.completions.firstOrNull{it.sessionId==sessionId&&it.exerciseId==exerciseId}?.completedSets
    }?:0

    private fun currentEquipmentContext(exerciseId:String):EquipmentContextRecord?{
        pendingEquipmentContexts[exerciseId]?.let{return it}
        val contextId=selectionSnapshot.preferences.firstOrNull{it.exerciseId==exerciseId}?.equipmentContextId?:return null
        return selectionSnapshot.equipmentContexts.firstOrNull{it.contextId==contextId}
    }

    private fun parseLoad(
        value:String,unit:String?=null,basis:LoadBasis=LoadBasis.UNKNOWN,source:LoadSource=LoadSource.UNKNOWN,
        kind:ResistanceKind=ResistanceKind.UNKNOWN,mode:LoadMeasurementMode=LoadMeasurementMode.UNKNOWN,
    ):LoadSnapshot?=value.trim().takeIf{it.isNotEmpty()}?.toDoubleOrNull()?.let{
        LoadSnapshot(it,unit,basis,source,kind,mode)
    }
    private fun formatLoadInput(load:LoadSnapshot?):String=load?.value?.let(::formatNumber).orEmpty()
    private fun formatLoadDisplay(load:LoadSnapshot?):String{
        if(load==null)return "—"
        val base=formatNumber(load.value)+(load.unit?.let{" $it"}?:"")
        val details=listOfNotNull(
            load.basis.takeUnless{it==LoadBasis.UNKNOWN}?.name,
            load.resistanceKind.takeUnless{it==ResistanceKind.UNKNOWN}?.name,
            load.measurementMode.takeUnless{it==LoadMeasurementMode.UNKNOWN}?.name,
        ).joinToString(" · "){it.lowercase(Locale.US).replace('_',' ')}
        val provenance=if(load.source==LoadSource.CARRIED_FROM_PLAN)" · from plan" else ""
        return base+(if(details.isBlank())"" else " · $details")+provenance
    }
    private fun formatNumber(value:Double):String=if(value%1.0==0.0)value.toLong().toString() else value.toString()

    private fun RestCheckpoint.toDraft()=RestCheckpointDraft(
        completedSetId=completedSet.setId,
        focus=focus,
        plannedNextLoad=plannedNextLoad,
        restStartedAtEpochMs=restStartedAtEpochMs,
        clockAnchor=clockAnchor,
    )
    private fun timerFor(checkpoint:RestCheckpoint)=RestTimerAnchor.restore(
        checkpoint.restStartedAtEpochMs,checkpoint.clockAnchor,clock.nowEpochMs(),clock.nowElapsedMs(),clock.bootId(),
    )
    private fun initialCameraInstruction(viewName:String)=
        "Move phone to a ${viewName.lowercase(Locale.US).replace('_','-')} view."
    private fun guidanceText(action:CameraGuidanceAction)=when(action){
        CameraGuidanceAction.MOVE_LEFT->"Move phone left."
        CameraGuidanceAction.MOVE_RIGHT->"Move phone right."
        CameraGuidanceAction.MOVE_CLOSER->"Move phone closer."
        CameraGuidanceAction.MOVE_FARTHER->"Move phone farther away."
        CameraGuidanceAction.RAISE_CAMERA->"Raise the phone."
        CameraGuidanceAction.LOWER_CAMERA->"Lower the phone."
        CameraGuidanceAction.ADJUST_ANGLE->"Adjust the phone angle."
        CameraGuidanceAction.CAMERA_READY->"READY"
        CameraGuidanceAction.CANNOT_ASSESS->"Move phone until your full movement is clearly visible."
    }
    private fun trackingText(state:TrackingQualityState)=when(state){
        TrackingQualityState.OBSERVABLE,TrackingQualityState.DEGRADED->"Tracking"
        TrackingQualityState.PAUSED,TrackingQualityState.UNKNOWN->"Tracking paused"
    }
    private fun analysisUi(record:GptAnalysisRecord)=GptAnalysisUiState(
        record.analysisId,record.modelLabel,record.summary,record.createdAtEpochMs,record.recommendations
    )
}
