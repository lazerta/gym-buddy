package com.gymbuddy.app.controller

import androidx.lifecycle.SavedStateHandle
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.WorkoutRuntimeGateway
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class WorkoutControllerTest {
    @Test
    fun exerciseSelectionMovesDirectlyToCameraSetup(){
        val runtime=FakeRuntime()
        val controller=WorkoutController(runtime,SavedStateHandle(),WorkoutClock{1_000L})

        controller.selectExercise("incline_dumbbell_press")

        val state=controller.uiState.value
        assertTrue(state is WorkoutUiState.CameraSetup)
        assertEquals(listOf("incline_dumbbell_press"),runtime.exercises)
        assertEquals(listOf(1),runtime.sets)
    }

    @Test
    fun readyRemainsSetupAndValidMovementTransitionsToActiveSet(){
        val controller=WorkoutController(FakeRuntime(),SavedStateHandle(),WorkoutClock{1_000L})
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
    fun endSetEntersRestAndNextSetUsesEditedPlannedLoad(){
        val runtime=FakeRuntime()
        val controller=WorkoutController(runtime,SavedStateHandle(),WorkoutClock{5_000L})
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
        assertEquals(listOf(1,2),runtime.sets)

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

    private class FakeRuntime:WorkoutRuntimeGateway{
        val exercises=mutableListOf<String>()
        val sets=mutableListOf<Int>()
        var endedSets=0
        override val analysisExecutor:Executor=Executor{it.run()}
        override fun beginExercise(exerciseId:String){exercises+=exerciseId}
        override fun beginSet(setOrdinal:Int){sets+=setOrdinal}
        override fun endSet(){endedSets++}
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit):FrameConsumer<MPImage> =
            FrameConsumer{_ ->}
        override fun close()=Unit
    }
}
