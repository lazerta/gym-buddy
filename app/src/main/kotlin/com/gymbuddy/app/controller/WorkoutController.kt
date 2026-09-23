package com.gymbuddy.app.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.WorkoutRuntimeGateway
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.domain.lifecycle.SetLifecycleState
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
    private var actualLoadText=""
    private var currentRepCount=0
    private var latestCue:String?=null
    private var selectedDay=runCatching{
        WorkoutDay.valueOf(savedStateHandle.get<String>(KEY_DAY)?:WorkoutDay.PUSH.name)
    }.getOrDefault(WorkoutDay.PUSH)

    private val _uiState=MutableStateFlow<WorkoutUiState>(selectionState())
    val uiState:StateFlow<WorkoutUiState> = _uiState.asStateFlow()

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
        selectedExerciseId=bundle.definition.exerciseId
        setNumber=1
        actualLoadText=""
        currentRepCount=0
        latestCue=null
        completedSets.clear()
        runtime.beginExercise(bundle.definition.exerciseId)
        runtime.beginSet(setNumber)
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
        val current=_uiState.value as? WorkoutUiState.ActiveSet?:return
        runtime.endSet()
        val focus=current.cue?:"Repeat the same setup."
        completedSets+=CompletedSetUiState(
            setNumber=current.setNumber,
            reps=current.repCount,
            actualLoadText=current.actualLoadText,
            focus=focus,
        )
        _uiState.value=WorkoutUiState.Rest(
            exerciseId=current.exerciseId,
            exerciseName=current.exerciseName,
            completedSetNumber=current.setNumber,
            previousReps=current.repCount,
            previousActualLoadText=current.actualLoadText,
            focus=focus,
            plannedNextLoadText=current.actualLoadText.takeUnless{it=="—"}.orEmpty(),
            restStartedAtEpochMs=clock.nowEpochMs(),
        )
    }

    @Synchronized
    fun updateNextLoad(value:String){
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        if(value.isNotEmpty()&&value.toDoubleOrNull()==null)return
        _uiState.value=current.copy(plannedNextLoadText=value)
    }

    @Synchronized
    fun nextSet(){
        val current=_uiState.value as? WorkoutUiState.Rest?:return
        setNumber=current.completedSetNumber+1
        actualLoadText=current.plannedNextLoadText.trim()
        currentRepCount=0
        latestCue=null
        runtime.beginSet(setNumber)
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
            ?:"Movement evidence saved locally."
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
        completedSets.clear()
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
                actualLoadText=actualLoadText.ifBlank{"—"},
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
