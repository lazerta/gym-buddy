package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.CompletedSetContext
import com.gymbuddy.app.runtime.WorkoutRuntimeGateway
import com.gymbuddy.app.runtime.WorkoutRuntimeSnapshot
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.CompletedSetRecord
import com.gymbuddy.domain.persistence.ActiveSetRecovery
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.LoadSource
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
    fun invalidLoadsCannotCrashOrCorruptTheRestCheckpoint(){
        val runtime=FakeRuntime(restToLoad=checkpoint(
            actual=LoadSnapshot(40.0,"lb"),planned=LoadSnapshot(45.0,"lb"),
            restStartedAt=123_000L,reps=8,
        ))
        val controller=WorkoutController(runtime)
        val previousWrites=runtime.savedRest.size
        listOf("-1","NaN","Infinity","1e309").forEach { value ->
            controller.updateNextLoad(value)
            assertEquals("45",(controller.uiState.value as WorkoutUiState.Rest).plannedNextLoadText)
        }
        assertEquals(previousWrites,runtime.savedRest.size)
    }

    @Test
    fun cameraReacquisitionDuringAnActiveSetKeepsEndSetAvailable(){
        val runtime=FakeRuntime()
        val controller=WorkoutController(runtime)
        controller.selectExercise("dumbbell_lateral_raise")
        controller.onRuntimeSnapshot(WorkoutRuntimeSnapshot(
            CameraGuidanceAction.CAMERA_READY,SetLifecycleState.ACTIVE_SET,
            TrackingQualityState.OBSERVABLE,3,null))
        controller.onRuntimeSnapshot(WorkoutRuntimeSnapshot(
            CameraGuidanceAction.ADJUST_ANGLE,SetLifecycleState.CAMERA_GUIDANCE,
            TrackingQualityState.PAUSED,3,null))
        val state=controller.uiState.value
        assertTrue("A camera bump must not remove the manual End Set fallback",state is WorkoutUiState.ActiveSet)
        assertEquals(3,(state as WorkoutUiState.ActiveSet).repCount)
        assertEquals("Tracking paused",state.trackingText)
        controller.endSet()
        assertEquals(1,runtime.endedSets)
        assertTrue(controller.uiState.value is WorkoutUiState.Rest)
    }



    @Test fun restoredExerciseSummaryKeepsAllCompletedSets(){
        val last=checkpoint(null,null,10_000L,8).copy(
            completedSet=SetRecord("set2","exec",2,500L),
            completedSets=listOf(
                CompletedSetRecord(SetRecord("set1","exec",1,200L,LoadSnapshot(35.0,"lb")),10,"Keep both sides moving together."),
                CompletedSetRecord(SetRecord("set2","exec",2,500L),8),
            ),
        )
        val controller=WorkoutController(FakeRuntime(restToLoad=last))
        controller.finishExercise()
        val summary=controller.uiState.value as WorkoutUiState.Summary
        assertEquals(listOf(1,2),summary.completedSets.map{it.setNumber})
        assertEquals(listOf(10,8),summary.completedSets.map{it.reps})
        assertEquals("35 lb",summary.completedSets.first().actualLoadText)
        assertEquals("Keep both sides moving together.",summary.evidenceSummary)
    }

    @Test fun pendingFinalizationRecoveryUsesCommittedWallClockNotRestartTime(){
        val recovery=ActiveSetRecovery(
            WorkoutSessionRecord("session",0L),
            ExerciseExecutionRecord("exec","session","smith_machine_squat",0L),
            SetRecord("set","exec",1,0L),5,true,endedAtEpochMs=50_000L,
        )
        val runtime=FakeRuntime(activeRecovery=recovery)
        val controller=WorkoutController(runtime,clock=WorkoutClock{120_000L})
        assertEquals(50_000L,(controller.uiState.value as WorkoutUiState.Rest).restStartedAtEpochMs)
        assertEquals(50_000L,runtime.savedRest.single().restStartedAtEpochMs)
    }

    @Test fun nextSetSetupRetainsThePreviousDurableRestCheckpointUntilActuallyActive(){
        val runtime=FakeRuntime(restToLoad=checkpoint(null,LoadSnapshot(40.0),10_000L,8))
        val controller=WorkoutController(runtime)
        val clears=runtime.clearedRest
        controller.nextSet()
        assertEquals("Setup must not erase the only durable continuation",clears,runtime.clearedRest)
        assertEquals(2,(controller.uiState.value as WorkoutUiState.CameraSetup).setNumber)
    }

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
        assertEquals(listOf(1 to null,2 to LoadSnapshot(25.0,source=LoadSource.CARRIED_FROM_PLAN)),runtime.sets)

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
        assertEquals("25 · from plan",active.actualLoadText)
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
    fun interruptedActiveSetIsExplicitlyMarkedAndRestartsThroughCameraSetup(){
        val recovery=ActiveSetRecovery(
            session=WorkoutSessionRecord("session",100L),
            execution=ExerciseExecutionRecord("exec","session","smith_machine_squat",100L),
            set=SetRecord("interrupted","exec",1,200L,LoadSnapshot(40.0,"lb")),
            committedReps=3,
            finalized=false,
        )
        val runtime=FakeRuntime(activeRecovery=recovery)
        val controller=WorkoutController(runtime,WorkoutDay.LEGS,WorkoutClock{50_000L})

        val state=controller.uiState.value as WorkoutUiState.CameraSetup
        assertEquals(2,state.setNumber)
        assertEquals("smith_machine_squat",state.exerciseId)
        assertEquals(listOf(2 to LoadSnapshot(40.0,"lb")),runtime.sets)
        assertEquals(Triple("interrupted",50_000L,3),runtime.markedInterrupted)
        assertEquals("exec",runtime.resumedExecutionId)
    }

    @Test
    fun finalizedSetPendingRestRecoversIntoRestWithoutStartingDuplicateSet(){
        val recovery=ActiveSetRecovery(
            session=WorkoutSessionRecord("session",100L),
            execution=ExerciseExecutionRecord("exec","session","smith_machine_squat",100L),
            set=SetRecord("finalized","exec",1,200L,LoadSnapshot(40.0,"lb")),
            committedReps=5,
            finalized=true,
        )
        val runtime=FakeRuntime(activeRecovery=recovery)
        val controller=WorkoutController(runtime,WorkoutDay.LEGS,WorkoutClock{60_000L})

        val rest=controller.uiState.value as WorkoutUiState.Rest
        assertEquals(5,rest.previousReps)
        assertEquals("40 lb",rest.previousActualLoadText)
        assertEquals(60_000L,rest.restStartedAtEpochMs)
        assertTrue(runtime.sets.isEmpty())
        assertEquals("finalized",runtime.savedRest.single().completedSetId)
    }

    @Test
    fun exerciseSelectionCannotErasePendingRecoveryMarker(){
        val recovery=ActiveSetRecovery(
            session=WorkoutSessionRecord("session",100L),
            execution=ExerciseExecutionRecord(
                "exec","session","smith_machine_squat",100L
            ),
            set=SetRecord("recover-me","exec",1,200L),
            committedReps=2,
            finalized=false,
        )
        val runtime=FakeRuntime(
            activeRecovery=recovery,
            delayRestLoad=true,
        )
        val controller=WorkoutController(
            runtime,WorkoutDay.PUSH,WorkoutClock{70_000L}
        )

        controller.selectExercise("incline_dumbbell_press")
        assertTrue(runtime.exercises.isEmpty())
        assertTrue(runtime.sets.isEmpty())

        runtime.completeRestLoad()

        val state=controller.uiState.value as WorkoutUiState.CameraSetup
        assertEquals("smith_machine_squat",state.exerciseId)
        assertEquals(2,state.setNumber)
        assertEquals(Triple("recover-me",70_000L,2),runtime.markedInterrupted)
    }

    @Test
    fun summaryCalibrationResetTargetsCurrentExercise(){
        val runtime=FakeRuntime(
            restToLoad=checkpoint(
                actual=LoadSnapshot(40.0,"lb"),
                planned=LoadSnapshot(45.0,"lb"),
                restStartedAt=123_000L,
                reps=8,
            )
        )
        val controller=WorkoutController(
            runtime,WorkoutDay.LEGS,WorkoutClock{999_000L}
        )
        controller.finishExercise()

        var success=false
        controller.resetPersonalCalibration{success=it}

        assertTrue(success)
        assertEquals("smith_machine_squat",runtime.resetCalibrationExerciseId)
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
        private val activeRecovery:ActiveSetRecovery?=null,
        private val delayRestLoad:Boolean=false,
    ):WorkoutRuntimeGateway{
        val exercises=mutableListOf<String>()
        val sets=mutableListOf<Pair<Int,LoadSnapshot?>>()
        val savedRest=mutableListOf<RestCheckpointDraft>()
        var endedSets=0
        var clearedRest=0
        var resumedExecutionId:String?=null
        var exportedSetId:String?=null
        var markedInterrupted:Triple<String,Long,Int>?=null
        var resetCalibrationExerciseId:String?=null
        private var pendingRestCallback:((RestCheckpoint?)->Unit)?=null
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
            if(delayRestLoad)pendingRestCallback=onLoaded
            else onLoaded(restToLoad)
        }

        fun completeRestLoad(){
            val callback=requireNotNull(pendingRestCallback)
            pendingRestCallback=null
            callback(restToLoad)
        }

        override fun loadActiveSetRecovery(onLoaded:(ActiveSetRecovery?)->Unit){
            onLoaded(activeRecovery)
        }

        override fun markActiveSetInterrupted(
            setId:String,
            recoveredAtEpochMs:Long,
            committedReps:Int,
        ){
            markedInterrupted=Triple(setId,recoveredAtEpochMs,committedReps)
        }

        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit){
            clearRestCheckpoint();onCompleted(true)
        }
        override fun clearRestCheckpoint(){clearedRest++}

        override fun resetPersonalCalibration(
            exerciseId:String,
            onCompleted:(Boolean)->Unit,
        ){
            resetCalibrationExerciseId=exerciseId
            onCompleted(true)
        }

        override fun clearPersonalCalibration(onCompleted:(Boolean)->Unit){
            onCompleted(true)
        }

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
