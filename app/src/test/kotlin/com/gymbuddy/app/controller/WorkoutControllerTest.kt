package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.CompletedSetContext
import com.gymbuddy.app.runtime.WorkoutRuntimeGateway
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.RestCheckpoint
import com.gymbuddy.domain.persistence.RestCheckpointDraft
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class WorkoutControllerTest {

    @Test
    fun controllerIsPlainMvcControllerNotAndroidxViewModel(){
        assertEquals(Any::class.java,WorkoutController::class.java.superclass)
    }

    @Test
    fun selectedDayIsExplicitControllerStateForActivityRestoration(){
        val controller=WorkoutController(FakeRuntime(),WorkoutDay.LEGS,WorkoutClock{1_000L})
        assertEquals(WorkoutDay.LEGS,controller.currentDay)
        controller.selectDay(WorkoutDay.PUSH)
        assertEquals(WorkoutDay.PUSH,controller.currentDay)
    }
    @Test
    fun exerciseSelectionMovesDirectlyToCameraSetup(){
        val runtime=FakeRuntime()
        val controller=WorkoutController(runtime,WorkoutDay.PUSH,WorkoutClock{1_000L})

        controller.selectExercise("incline_dumbbell_press")

        val state=controller.uiState.value
        assertTrue(state is WorkoutUiState.CameraSetup)
        assertEquals(listOf("incline_dumbbell_press"),runtime.exercises)
        assertEquals(listOf(1 to null),runtime.sets)
    }

    @Test
    fun readyRemainsSetupAndValidMovementTransitionsToActiveSet(){
        val controller=WorkoutController(FakeRuntime(),WorkoutDay.PUSH,WorkoutClock{1_000L})
        controller.selectExercise("smith_machine_squat")

        controller.onRuntimeSnapshot(
            WorkoutRuntimeSnapshot(
                CameraGuidanceAction.CAMERA_READY,
                SetLifecycleState.WAITING,
                TrackingQualityState.OBSERVABLE,
                0,
                null,
            )
        )
        val ready=controller.uiState.value as WorkoutUiState.CameraSetup
        assertEquals(CameraReadinessUi.READY,ready.readiness)

        controller.onRuntimeSnapshot(
            WorkoutRuntimeSnapshot(
                CameraGuidanceAction.CAMERA_READY,
                SetLifecycleState.ACTIVE_SET,
                TrackingQualityState.OBSERVABLE,
                3,
                "Keep both sides moving together.",
            )
        )
        val active=controller.uiState.value as WorkoutUiState.ActiveSet
        assertEquals(3,active.repCount)
        assertEquals("Keep both sides moving together.",active.cue)
    }

    @Test
    fun endSetEntersRestAndNextSetSnapshotsEditedPlannedLoad(){
        val runtime=FakeRuntime()
        val controller=WorkoutController(runtime,WorkoutDay.PUSH,WorkoutClock{5_000L})
        controller.selectExercise("dumbbell_lateral_raise")
        controller.onRuntimeSnapshot(
            WorkoutRuntimeSnapshot(
                CameraGuidanceAction.CAMERA_READY,
                SetLifecycleState.ACTIVE_SET,
                TrackingQualityState.OBSERVABLE,
                10,
                null,
            )
        )

        controller.endSet()
        val rest=controller.uiState.value as WorkoutUiState.Rest
        assertEquals(10,rest.previousReps)
        assertEquals(5_000L,rest.restStartedAtEpochMs)
        assertEquals(1,runtime.endedSets)

        controller.updateNextLoad("25")
        controller.nextSet()
        assertEquals(listOf(1 to null,2 to LoadSnapshot(25.0)),runtime.sets)

        controller.onRuntimeSnapshot(
            WorkoutRuntimeSnapshot(
                CameraGuidanceAction.CAMERA_READY,
                SetLifecycleState.ACTIVE_SET,
                TrackingQualityState.OBSERVABLE,
                1,
                null,
            )
        )
        val active=controller.uiState.value as WorkoutUiState.ActiveSet
        assertEquals("25",active.actualLoadText)
    }

    @Test
    fun persistedRestRestoresTimerExecutionAndKeepsActualSeparateFromPlanned(){
        val checkpoint=checkpoint(
            actual=LoadSnapshot(40.0,"lb"),
            planned=LoadSnapshot(45.0,"lb"),
            restStartedAt=123_000L,
            reps=8,
        )
        val runtime=FakeRuntime(restToLoad=checkpoint)
        val controller=WorkoutController(runtime,WorkoutDay.PUSH,WorkoutClock{999_000L})

        val rest=controller.uiState.value as WorkoutUiState.Rest
        assertEquals(123_000L,rest.restStartedAtEpochMs)
        assertEquals("40 lb",rest.previousActualLoadText)
        assertEquals("45",rest.plannedNextLoadText)
        assertEquals(8,rest.previousReps)
        assertEquals("exec",runtime.resumedExecutionId)

        controller.updateNextLoad("50")
        val updated=runtime.savedRest.last()
        assertEquals(50.0,updated.plannedNextLoad!!.value,0.0)
        assertEquals("lb",updated.plannedNextLoad!!.unit)
        assertEquals(40.0,checkpoint.completedSet.actualLoad!!.value,0.0)
        assertEquals("40 lb",(controller.uiState.value as WorkoutUiState.Rest).previousActualLoadText)
    }

    @Test
    fun askChatGptExportsMostRecentRestoredSetFromSummary(){
        val runtime=FakeRuntime(
            restToLoad=checkpoint(
                actual=LoadSnapshot(40.0,"lb"),
                planned=LoadSnapshot(45.0,"lb"),
                restStartedAt=123_000L,
                reps=8,
            )
        )
        val controller=WorkoutController(runtime,WorkoutDay.PUSH,WorkoutClock{999_000L})
        controller.finishExercise()

        var exported:String?=null
        controller.askChatGpt{result->exported=result.getOrThrow()}

        assertEquals("set",runtime.exportedSetId)
        assertEquals("export:set",exported)
    }

    private fun checkpoint(
        actual:LoadSnapshot?,
        planned:LoadSnapshot?,
        restStartedAt:Long,
        reps:Int,
    )=RestCheckpoint(
        session=WorkoutSessionRecord("session",100),
        execution=ExerciseExecutionRecord("exec","session","smith_machine_squat",100),
        completedSet=SetRecord("set","exec",1,200,actual),
        previousReps=reps,
        focus="Repeat the same setup.",
        plannedNextLoad=planned,
        restStartedAtEpochMs=restStartedAt,
    )

    private class FakeRuntime(
        private val restToLoad:RestCheckpoint?=null,
    ):WorkoutRuntimeGateway{
        val exercises=mutableListOf<String>()
        val sets=mutableListOf<Pair<Int,LoadSnapshot?>>()
        val savedRest=mutableListOf<RestCheckpointDraft>()
        var endedSets=0
        var resumedExecutionId:String?=null
        var exportedSetId:String?=null
        private var currentExercise="incline_dumbbell_press"
        private var currentSetOrdinal=1
        private var currentLoad:LoadSnapshot?=null

        override val analysisExecutor:Executor=Executor{it.run()}

        override fun beginExercise(exerciseId:String){
            exercises+=exerciseId
            currentExercise=exerciseId
        }

        override fun resumeExercise(
            session:WorkoutSessionRecord,
            execution:ExerciseExecutionRecord,
        ){
            resumedExecutionId=execution.executionId
            currentExercise=execution.exerciseId
        }

        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?){
            sets+=setOrdinal to actualLoad
            currentSetOrdinal=setOrdinal
            currentLoad=actualLoad
        }

        override fun endSet(onCompleted:(CompletedSetContext?)->Unit){
            endedSets++
            onCompleted(
                CompletedSetContext(
                    WorkoutSessionRecord("session",100),
                    ExerciseExecutionRecord("exec","session",currentExercise,100),
                    SetRecord("set-$currentSetOrdinal","exec",currentSetOrdinal,200,currentLoad),
                )
            )
        }

        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage> =
            FrameConsumer{_ ->}

        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft){
            savedRest+=checkpoint
        }

        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit){
            onLoaded(restToLoad)
        }

        override fun clearRestCheckpoint()=Unit

        override fun exportChatGptContext(
            currentSetId:String,
            onResult:(Result<String>)->Unit,
        ){
            exportedSetId=currentSetId
            onResult(Result.success("export:"+currentSetId))
        }

        override fun close()=Unit
    }
}
