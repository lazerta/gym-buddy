package com.gymbuddy.app.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.CompletedSetContext
import com.gymbuddy.app.runtime.WorkoutRuntimeGateway
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface WorkoutClock { fun nowEpochMs():Long }
object SystemWorkoutClock:WorkoutClock { override fun nowEpochMs():Long=System.currentTimeMillis() }

class WorkoutController(
    private val runtime:WorkoutRuntimeGateway,
    private val savedStateHandle:SavedStateHandle,
    private val clock:WorkoutClock=SystemWorkoutClock,
):ViewModel(){
    private val completedSets=mutableListOf<CompletedSetUiState>()
    private var selectedExerciseId:String?=null
    private var setNumber=1
    private var actualLoad:LoadSnapshot?=null
    private var currentRepCount=0
    private var latestCue:String?=null
    private var restCheckpoint:RestCheckpoint?=null
    private var newExerciseStarted=false
    private var endingSet=false
    private var selectedDay=runCatching{
        WorkoutDay.valueOf(savedStateHandle.get<String>(KEY_DAY)?:WorkoutDay.PUSH.name)
    }.getOrDefault(WorkoutDay.PUSH)

    private val _uiState=MutableStateFlow<WorkoutUiState>(selectionState())
    val uiState:StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    init{
        runtime.loadRestCheckpoint{checkpoint->
            if(checkpoint!=null)restoreRestCheckpoint(checkpoint)
        }
    }

    val analysisExecutor:Executor
        get()=runtime.analysisExecutor

    fun frameConsumer():FrameConsumer<MPImage> = runtime.frameConsumer(::onRuntimeSnapshot)

    @Synchronized
    fun selectDay(day:WorkoutDay){
        selectedDay=day
        savedStateHandle[KEY_DAY]=day.name
        if(_uiState.value is WorkoutUiState.ExerciseSelection){
            _uiState.value=selectionState()
        }
    }

    @Synchronized
    fun selectExercise(exerciseId:String){
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)
            ?: error("Unsupported exercise_id: $exerciseId")
        newExerciseStarted=true
        runtime.clearRestCheckpoint()
        restCheckpoint=null
        selectedExerciseId=bundle.definition.exerciseId
        setNumber=1
        actualLoad=null
        currentRepCount=0
        latestCue=null
        endingSet=false
        completedSets.clear()
        runtime.beginExercise(bundle.definition.exerciseId)
        runtime.beginSet(setNumber,actualLoad)
        _uiState.value=WorkoutUiState.CameraSetup(
            exerciseId=bundle.definition.exerciseId,
            exerciseName=bundle.definition.displayName,
            instruction=initialCameraInstruction(bundle.profile.cameraProfile.preferredViewClass.name),
            readiness=CameraReadinessUi.SETTING_UP,
            setNumber=setNumber,
        )
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
        runtime.endSet{context->completeSet(context)}
    }

    @Synchronized
    private fun completeSet(context:CompletedSetContext?){
        if(context==null){
            endingSet=false
            return
        }
        val exerciseId=selectedExerciseId?:run{endingSet=false;return}
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:run{endingSet=false;return}
        val finalLoad=context.set.actualLoad
        val focus=latestCue?:"Repeat the same setup."
        val restStarted=clock.nowEpochMs()
        val checkpoint=RestCheckpoint(
            session=context.session,
            execution=context.execution,
            completedSet=context.set,
            previousReps=currentRepCount,
            focus=focus,
            plannedNextLoad=finalLoad,
            restStartedAtEpochMs=restStarted,
        )
        restCheckpoint=checkpoint
        completedSets+=CompletedSetUiState(
            setNumber=context.set.setOrdinal,
            reps=currentRepCount,
            actualLoadText=formatLoadDisplay(finalLoad),
            focus=focus,
        )
        _uiState.value=WorkoutUiState.Rest(
            exerciseId=exerciseId,
            exerciseName=bundle.definition.displayName,
            completedSetNumber=context.set.setOrdinal,
            previousReps=currentRepCount,
            previousActualLoadText=formatLoadDisplay(finalLoad),
            focus=focus,
            plannedNextLoadText=formatLoadInput(finalLoad),
            restStartedAtEpochMs=restStarted,
        )
        runtime.saveRestCheckpoint(checkpoint.toDraft())
        endingSet=false
    }

    @Synchronized
    fun updateNextLoad(value:String){
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        if(value.isNotEmpty()&&value.toDoubleOrNull()==null)return
        val existingUnit=restCheckpoint?.plannedNextLoad?.unit
            ?:restCheckpoint?.completedSet?.actualLoad?.unit
        val planned=parseLoad(value,existingUnit)
        _uiState.value=current.copy(plannedNextLoadText=value)
        restCheckpoint=restCheckpoint?.copy(plannedNextLoad=planned)
        restCheckpoint?.let{runtime.saveRestCheckpoint(it.toDraft())}
    }

    @Synchronized
    fun nextSet(){
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        setNumber=current.completedSetNumber+1
        val existingUnit=restCheckpoint?.plannedNextLoad?.unit
            ?:restCheckpoint?.completedSet?.actualLoad?.unit
        actualLoad=parseLoad(current.plannedNextLoadText,existingUnit)
        currentRepCount=0
        latestCue=null
        endingSet=false
        restCheckpoint=null
        runtime.beginSet(setNumber,actualLoad)
        runtime.clearRestCheckpoint()
        _uiState.value=WorkoutUiState.CameraSetup(
            exerciseId=current.exerciseId,
            exerciseName=current.exerciseName,
            instruction="Recheck the phone position.",
            readiness=CameraReadinessUi.SETTING_UP,
            setNumber=setNumber,
        )
    }

    @Synchronized
    fun finishExercise(){
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        val usefulFocus=completedSets.asReversed()
            .map{it.focus}
            .firstOrNull{it!="Repeat the same setup."}
            ?:current.focus
        restCheckpoint=null
        runtime.clearRestCheckpoint()
        _uiState.value=WorkoutUiState.Summary(
            exerciseId=current.exerciseId,
            exerciseName=current.exerciseName,
            completedSets=completedSets.toList(),
            evidenceSummary=usefulFocus,
        )
    }

    @Synchronized
    fun returnToSelection(){
        selectedExerciseId=null
        currentRepCount=0
        latestCue=null
        actualLoad=null
        restCheckpoint=null
        endingSet=false
        completedSets.clear()
        runtime.clearRestCheckpoint()
        _uiState.value=selectionState()
    }

    @Synchronized
    internal fun onRuntimeSnapshot(snapshot:WorkoutRuntimeSnapshot){
        val exerciseId=selectedExerciseId?:return
        val bundle=InitialExerciseProfiles.resolveByExternalId(exerciseId)?:return
        currentRepCount=snapshot.repCount
        latestCue=snapshot.cueText

        if(snapshot.lifecycleState==SetLifecycleState.ACTIVE_SET||
            snapshot.lifecycleState==SetLifecycleState.POSSIBLE_END||
            snapshot.lifecycleState==SetLifecycleState.FINALIZING){
            _uiState.value=WorkoutUiState.ActiveSet(
                exerciseId=exerciseId,
                exerciseName=bundle.definition.displayName,
                setNumber=setNumber,
                actualLoadText=formatLoadDisplay(actualLoad),
                repCount=snapshot.repCount,
                trackingText=trackingText(snapshot.trackingState),
                cue=snapshot.cueText,
            )
            return
        }

        _uiState.value=WorkoutUiState.CameraSetup(
            exerciseId=exerciseId,
            exerciseName=bundle.definition.displayName,
            instruction=guidanceText(snapshot.cameraGuidance),
            readiness=if(snapshot.cameraGuidance==CameraGuidanceAction.CAMERA_READY)
                CameraReadinessUi.READY else CameraReadinessUi.SETTING_UP,
            setNumber=setNumber,
        )
    }

    @Synchronized
    private fun restoreRestCheckpoint(checkpoint:RestCheckpoint){
        if(newExerciseStarted||_uiState.value !is WorkoutUiState.ExerciseSelection)return
        val bundle=InitialExerciseProfiles.resolveByExternalId(checkpoint.execution.exerciseId)?:return
        runtime.resumeExercise(checkpoint.session,checkpoint.execution)
        selectedExerciseId=checkpoint.execution.exerciseId
        setNumber=checkpoint.completedSet.setOrdinal
        actualLoad=checkpoint.completedSet.actualLoad
        currentRepCount=checkpoint.previousReps
        latestCue=checkpoint.focus.takeUnless{it=="Repeat the same setup."}
        restCheckpoint=checkpoint
        completedSets.clear()
        completedSets+=CompletedSetUiState(
            setNumber=checkpoint.completedSet.setOrdinal,
            reps=checkpoint.previousReps,
            actualLoadText=formatLoadDisplay(checkpoint.completedSet.actualLoad),
            focus=checkpoint.focus,
        )
        _uiState.value=WorkoutUiState.Rest(
            exerciseId=checkpoint.execution.exerciseId,
            exerciseName=bundle.definition.displayName,
            completedSetNumber=checkpoint.completedSet.setOrdinal,
            previousReps=checkpoint.previousReps,
            previousActualLoadText=formatLoadDisplay(checkpoint.completedSet.actualLoad),
            focus=checkpoint.focus,
            plannedNextLoadText=formatLoadInput(checkpoint.plannedNextLoad),
            restStartedAtEpochMs=checkpoint.restStartedAtEpochMs,
        )
    }

    override fun onCleared(){
        runtime.close()
        super.onCleared()
    }

    private fun selectionState()=WorkoutUiState.ExerciseSelection(
        selectedDay=selectedDay,
        exercises=exerciseRows(selectedDay),
    )

    private fun exerciseRows(day:WorkoutDay):List<ExerciseRowUiState>{
        val ids=when(day){
            WorkoutDay.PUSH->setOf("incline_dumbbell_press","dumbbell_lateral_raise")
            WorkoutDay.PULL->emptySet()
            WorkoutDay.LEGS->setOf("smith_machine_squat")
        }
        return InitialExerciseProfiles.all
            .filter{it.definition.exerciseId in ids}
            .map{ExerciseRowUiState(it.definition.exerciseId,it.definition.displayName)}
    }

    private fun parseLoad(value:String,unit:String?=null):LoadSnapshot?=
        value.trim().takeIf{it.isNotEmpty()}?.toDoubleOrNull()?.let{LoadSnapshot(it,unit)}

    private fun formatLoadInput(load:LoadSnapshot?):String=
        load?.value?.let(::formatNumber).orEmpty()

    private fun formatLoadDisplay(load:LoadSnapshot?):String{
        if(load==null)return "—"
        val base=formatNumber(load.value)
        return load.unit?.let{"$base $it"}?:base
    }

    private fun formatNumber(value:Double):String=
        if(value%1.0==0.0)value.toLong().toString() else value.toString()

    private fun RestCheckpoint.toDraft()=RestCheckpointDraft(
        completedSetId=completedSet.setId,
        focus=focus,
        plannedNextLoad=plannedNextLoad,
        restStartedAtEpochMs=restStartedAtEpochMs,
    )

    private fun initialCameraInstruction(viewName:String):String =
        "Move phone to a ${viewName.lowercase(Locale.US).replace('_','-')} view."

    private fun guidanceText(action:CameraGuidanceAction):String=when(action){
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

    private fun trackingText(state:TrackingQualityState):String=when(state){
        TrackingQualityState.OBSERVABLE,TrackingQualityState.DEGRADED->"Tracking"
        TrackingQualityState.PAUSED,TrackingQualityState.UNKNOWN->"Tracking paused"
    }

    companion object {
        private const val KEY_DAY="selected_day"
    }
}
