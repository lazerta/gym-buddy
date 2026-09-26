package com.gymbuddy.app.controller

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingQualityState
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Executor
import org.junit.Test

/** Tests current production controller behavior with a controlled runtime boundary.
 * These are not Android UI rendering, Room, native-camera or process-kill tests.
 */
class Step3ReviewRepairTest {
    @Test fun pendingEquipmentSaveMustNotPrepareWithStaleContext() {
        val runtime=Runtime()
        val controller=WorkoutController(runtime)
        controller.editEquipment(RAISE)
        controller.updateEquipmentLabel("Bench B")
        controller.saveEquipmentContext()
        val pending=requireNotNull(runtime.equipmentWrite)
        controller.selectExercise(RAISE)
        pending.third(true)
        // It is valid either to defer selection or to snapshot the requested
        // context. Preparing generic/previous equipment is not a valid outcome.
        check(runtime.starts.isEmpty() || runtime.starts.single().equipmentContext==pending.second) {
            "Selected while equipment save pending; analysis context=${runtime.starts.single().equipmentContext}, saved=${pending.second.contextId}"
        }
    }

    @Test fun acknowledgedEquipmentSaveUsesTheSelectedContext() {
        val runtime=Runtime();val controller=WorkoutController(runtime)
        controller.editEquipment(RAISE);controller.updateEquipmentLabel("Bench B");controller.saveEquipmentContext()
        val pending=requireNotNull(runtime.equipmentWrite)
        pending.third(true)
        controller.selectExercise(RAISE)
        check(runtime.starts.single().equipmentContext==pending.second)
    }

    @Test fun failedEquipmentSaveDoesNotBecomeAnAnalysisContext() {
        val runtime=Runtime();val controller=WorkoutController(runtime)
        controller.editEquipment(RAISE);controller.updateEquipmentLabel("Bench B");controller.saveEquipmentContext()
        runtime.equipmentWrite!!.third(false)
        check((controller.uiState.value as WorkoutUiState.ExerciseSelection).errorMessage!=null)
        controller.selectExercise(RAISE)
        check(runtime.starts.single().equipmentContext==null)
    }

    @Test fun exerciseSummaryRetainsBothRecurringObservations() {
        val runtime=Runtime()
        // Independent fixture: both supported raise rules deviate on two distinct
        // completed reps, with reliable evidence and no interrupted tracking.
        val summary=EvidenceSummaryEngine { it }.summarize(twoRuleEvidence())
        check(summary.recurringRuleIds.toSet()==setOf(ASYMMETRY,ELEVATION))
        runtime.completed=runtime.completed.copy(coachingSummary=summary)
        val controller=WorkoutController(runtime)
        complete(controller)
        val ui=controller.uiState.value as WorkoutUiState.Summary
        check(ui.recurringEvidence.distinct().size==summary.recurringRuleIds.distinct().size) {
            "Engine retained ${summary.recurringRuleIds}; exercise summary retained ${ui.recurringEvidence}"
        }
    }

    @Test fun neutralCompletedSetDoesNotInventAnIssue() {
        val runtime=Runtime();runtime.completed=runtime.completed.copy(coachingSummary=SetCoachingSummary())
        val controller=WorkoutController(runtime);complete(controller)
        val ui=controller.uiState.value as WorkoutUiState.Summary
        check(ui.recurringEvidence.isEmpty())
        check(ui.evidenceSummary=="Repeat the same setup.")
    }

    @Test fun failedRestEditMustBeReportedOrRetainDurablePlan() {
        val runtime=Runtime()
        runtime.rest=restWithLoad(20.0)
        val controller=WorkoutController(runtime)
        runtime.rejectRestWrites=true
        controller.updateNextLoad("25")
        val shown=controller.uiState.value as WorkoutUiState.Rest
        // The acknowledged failure preserves the old durable record. The draft
        // must be explicitly unsaved, not presented as a committed new load.
        val restarted=WorkoutController(runtime).uiState.value as WorkoutUiState.Rest
        check(shown.plannedNextLoadText==restarted.plannedNextLoadText || shown.errorMessage!=null || shown.busy) {
            "Failed write: current UI=${shown.plannedNextLoadText}, recovered=${restarted.plannedNextLoadText}, error=${shown.errorMessage}, busy=${shown.busy}"
        }
    }

    @Test fun successfulRestEditSurvivesControllerRecreation() {
        val runtime=Runtime();runtime.rest=restWithLoad(20.0)
        val controller=WorkoutController(runtime);controller.updateNextLoad("25")
        val recovered=WorkoutController(runtime).uiState.value as WorkoutUiState.Rest
        check(recovered.plannedNextLoadText=="25")
        check(recovered.previousActualLoadText.startsWith("20"))
    }

    @Test fun otherExerciseSearchAndSubstitutionRemainIndependentOfDay() {
        val runtime=Runtime();val controller=WorkoutController(runtime,WorkoutDay.LEGS)
        controller.beginSubstitution("smith_machine_squat")
        controller.updateExerciseSearch("db lateral")
        val results=controller.uiState.value as WorkoutUiState.ExerciseSelection
        check(results.searchResults.single().exerciseId==RAISE)
        controller.selectOtherExercise(RAISE)
        check(runtime.starts.single().actualExerciseId==RAISE)
        check(runtime.starts.single().plannedExerciseId=="smith_machine_squat")
    }

    @Test fun completionRowsIgnoreAnotherWorkout() {
        val runtime=Runtime()
        runtime.selection=WorkoutSelectionSnapshot(activeSessionId="current",completions=listOf(
            WorkoutExerciseCompletionRecord("previous",RAISE,9,1000),
            WorkoutExerciseCompletionRecord("current",RAISE,2,2000)))
        val state=WorkoutController(runtime).uiState.value as WorkoutUiState.ExerciseSelection
        check(state.exercises.single{it.exerciseId==RAISE}.completedSets==2)
    }

    @Test fun activeCameraInstructionKeepsEndSetReachable() {
        val runtime=Runtime();val controller=WorkoutController(runtime)
        controller.selectExercise(RAISE);active(controller)
        controller.onRuntimeSnapshot(WorkoutRuntimeSnapshot(CameraGuidanceAction.CANNOT_ASSESS,
            SetLifecycleState.CAMERA_GUIDANCE,TrackingQualityState.PAUSED,2,null))
        val state=controller.uiState.value as WorkoutUiState.ActiveSet
        check(!state.cameraInstruction.isNullOrBlank())
        controller.endSet()
        check(controller.uiState.value is WorkoutUiState.Rest)
    }

    // F03 is verified by the real rendered Compose regression in androidTest.

    private fun complete(controller:WorkoutController) {
        controller.selectExercise(RAISE);active(controller);controller.endSet();controller.finishExercise()
    }
    private fun active(controller:WorkoutController)=controller.onRuntimeSnapshot(WorkoutRuntimeSnapshot(
        CameraGuidanceAction.CAMERA_READY,SetLifecycleState.ACTIVE_SET,TrackingQualityState.OBSERVABLE,2,null))

    private class Runtime:WorkoutRuntimeGateway {
        override val analysisExecutor=Executor { it.run() }
        var rest:RestCheckpoint?=null
        var rejectRestWrites=false
        var selection=WorkoutSelectionSnapshot()
        var equipmentWrite:Triple<String,EquipmentContextRecord,(Boolean)->Unit>?=null
        val starts=mutableListOf<ExerciseStartRequest>()
        var completed=CompletedSetContext(SESSION,EXECUTION,SET,SetSummary(SET.setId,400,2,0,0,1000))
        override fun beginExercise(exerciseId:String)=Unit
        override fun beginExercise(request:ExerciseStartRequest){starts+=request}
        override fun resumeExercise(session:WorkoutSessionRecord,execution:ExerciseExecutionRecord)=Unit
        override fun beginSet(setOrdinal:Int,actualLoad:LoadSnapshot?)=Unit
        override fun endSet(onCompleted:(CompletedSetContext?)->Unit)=onCompleted(completed)
        override fun frameConsumer(listener:(WorkoutRuntimeSnapshot)->Unit)=FrameConsumer<MPImage>{}
        override fun loadWorkoutSelection(onLoaded:(WorkoutSelectionSnapshot)->Unit)=onLoaded(selection)
        override fun loadRestCheckpoint(onLoaded:(RestCheckpoint?)->Unit)=onLoaded(rest)
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft) {
            saveRestCheckpoint(checkpoint){}
        }
        override fun saveRestCheckpoint(checkpoint:RestCheckpointDraft,onCompleted:(Boolean)->Unit) {
            if(!rejectRestWrites)rest=RestCheckpoint(SESSION,EXECUTION,SET,2,checkpoint.focus,
                checkpoint.plannedNextLoad,checkpoint.restStartedAtEpochMs,clockAnchor=checkpoint.clockAnchor)
            onCompleted(!rejectRestWrites)
        }
        override fun rememberEquipmentContext(exerciseId:String,record:EquipmentContextRecord,onCompleted:(Boolean)->Unit) {
            equipmentWrite=Triple(exerciseId,record,onCompleted)
        }
        override fun markExerciseCompleted(exerciseId:String,completedSets:Int,completedAtEpochMs:Long,onCompleted:(Boolean)->Unit)=onCompleted(true)
        override fun clearRestCheckpoint()=Unit
        override fun exportChatGptContext(currentSetId:String,onResult:(Result<String>)->Unit)=Unit
        override fun close()=Unit
    }

    companion object {
        private const val RAISE="dumbbell_lateral_raise"
        private const val ASYMMETRY="bilateral_asymmetry"
        private const val ELEVATION="lateral_raise_over_elevation"
        private val SESSION=WorkoutSessionRecord("session",0,100)
        private val EXECUTION=ExerciseExecutionRecord("execution",SESSION.sessionId,RAISE,0,100)
        private val SET=SetRecord("set",EXECUTION.executionId,1,0,LoadSnapshot(20.0,"lb",LoadBasis.PER_IMPLEMENT),100)
        private fun restWithLoad(value:Double)=RestCheckpoint(SESSION,EXECUTION,SET,2,"Repeat the same setup.",
            LoadSnapshot(value,"lb",LoadBasis.PER_IMPLEMENT,LoadSource.PLANNED),1000)
        private fun twoRuleEvidence():PersistedSetEvidence {
            val b=InitialExerciseProfiles.dumbbellLateralRaise
            val p=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment).provenance
            val reps=(1..2).map{n->RepEvidence("rep-$n",n,"cycle",MovementPrimitive.RAISE,
                n*100L,n*100L+50,RepClassification.NORMAL,emptyMap(),emptyMap(),p)}
            val observations=reps.flatMap{rep->listOf(ASYMMETRY,ELEVATION).map{rule->
                FormObservation("${rep.repId}-$rule",rep.repId,rule,1,FormObservationState.DEVIATION,
                    FormRuleSeverity.MINOR,.95,.3)}}
            return PersistedSetEvidence(SET,p,reps,observations,emptyList(),emptyList(),null,SetSummary(SET.setId,400,2,0,0,1000))
        }
    }
}
