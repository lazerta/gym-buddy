package com.gymbuddy.domain.export

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.coaching.CueResponse
import com.gymbuddy.domain.coaching.CueResponseState
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservation
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.evidence.MetricEvidence
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.evidence.SignalEvidence
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.CueDeliveryRecord
import com.gymbuddy.domain.persistence.CueDeliveryState
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.PersistedSetEvidence
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.TrackingQualitySummary
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisProvenance
import com.gymbuddy.domain.profile.FormRuleSeverity
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profile.ProfileVersionRef
import com.gymbuddy.domain.profile.SignalUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptContextExporterTest {
    @Test
    fun exportIsDeterministicAcrossInputOrderingAndFiltersNonComparableHistory(){
        val currentA=context("current","exec-current","session-current","incline_dumbbell_press",300,reverse=false)
        val currentB=context("current","exec-current","session-current","incline_dumbbell_press",300,reverse=true)
        val recent=context("recent","exec-recent","session-recent","incline_dumbbell_press",200,reverse=false)
        val older=context("older","exec-older","session-older","incline_dumbbell_press",100,reverse=true)
        val future=context("future","exec-future","session-future","incline_dumbbell_press",400,reverse=false)
        val other=context("other","exec-other","session-other","smith_machine_squat",250,reverse=false)

        val first=ChatGptContextExporter(
            FakeRepository(currentA,listOf(older,future,other,recent))
        ).export("current")
        val second=ChatGptContextExporter(
            FakeRepository(currentB,listOf(recent,other,older,future))
        ).export("current")

        assertEquals(first,second)
        assertTrue(first.startsWith("{\"schema\":\"gym_buddy_chatgpt_context\",\"schema_version\":1"))
        assertTrue(first.contains("\"current_set\""))
        assertTrue(first.contains("\"recent_comparable_history\""))
        assertTrue(first.contains("\"source_references\""))
        assertTrue(first.contains("\"observation_id\":\"obs-current-1\""))
        assertTrue(first.indexOf("\"set_id\":\"recent\"")<first.indexOf("\"set_id\":\"older\""))
        assertFalse(first.contains("\"set_id\":\"future\""))
        assertFalse(first.contains("\"set_id\":\"other\""))
        assertTrue(first.contains("\"recovery_context\":null"))
        assertTrue(first.contains("\"media_reference\":null"))
        assertFalse(first.contains("api_key",ignoreCase=true))
    }

    @Test
    fun exportUsesWallClockChronologyAcrossMonotonicTimebaseReset(){
        val current=context(
            "current","exec-current","session-current",
            "incline_dumbbell_press",
            endedAtUs=1_000L,
            reverse=false,
            endedAtEpochMs=20_000L,
        )
        val oldBeforeReboot=context(
            "old","exec-old","session-old",
            "incline_dumbbell_press",
            endedAtUs=1_000_000L,
            reverse=false,
            endedAtEpochMs=10_000L,
        )
        val future=context(
            "future","exec-future","session-future",
            "incline_dumbbell_press",
            endedAtUs=100L,
            reverse=false,
            endedAtEpochMs=30_000L,
        )

        val exported=ChatGptContextExporter(
            FakeRepository(current,listOf(future,oldBeforeReboot))
        ).export("current")

        assertTrue(exported.contains("\"set_id\":\"old\""))
        assertFalse(exported.contains("\"set_id\":\"future\""))
        assertTrue(exported.contains("\"ended_at_epoch_ms\":20000"))
    }

    @Test
    fun optionalContextIsSourceReferencedAndEscaped(){
        val current=context("current","exec","session","incline_dumbbell_press",300,reverse=false)
        val exported=ChatGptContextExporter(FakeRepository(current,emptyList())).export(
            currentSetId="current",
            recoveryContext=ChatGptContextReference("health:day-1","Sleep \"good\"\nHRV stable"),
            mediaReference=ChatGptContextReference("video:clip-7","local://clip/7"),
        )

        assertTrue(exported.contains("\"source_ref\":\"health:day-1\""))
        assertTrue(exported.contains("Sleep \\\"good\\\"\\nHRV stable"))
        assertTrue(exported.contains("\"source_ref\":\"video:clip-7\""))
        assertTrue(exported.contains("\"optional_context_refs\":[\"health:day-1\",\"video:clip-7\"]"))
    }

    private fun context(
        setId:String,
        executionId:String,
        sessionId:String,
        exerciseId:String,
        endedAtUs:Long,
        reverse:Boolean,
        endedAtEpochMs:Long=0L,
    ):ChatGptSetContext{
        val p=provenance(exerciseId)
        val signalA=SignalEvidence("a_signal",SignalUnit.DEGREES,1.0,2.0,1.5,2.0,.9)
        val signalB=SignalEvidence("b_signal",SignalUnit.DEGREES,3.0,4.0,3.5,4.0,.8)
        val metricA=MetricEvidence("a_metric",SignalUnit.DEGREES,EvidenceValue.Known(12.0,.8))
        val metricB=MetricEvidence("b_metric",SignalUnit.DEGREES,EvidenceValue.Unknown("occluded"))
        fun rep(ordinal:Int)=RepEvidence(
            repId="rep-$setId-$ordinal",
            ordinal=ordinal,
            stepId="cycle",
            primitive=MovementPrimitive.PRESS,
            startedAtUs=ordinal*10L,
            completedAtUs=ordinal*10L+5L,
            classification=RepClassification.NORMAL,
            signals=if(reverse)linkedMapOf("b_signal" to signalB,"a_signal" to signalA)
                else linkedMapOf("a_signal" to signalA,"b_signal" to signalB),
            metrics=if(reverse)linkedMapOf("b_metric" to metricB,"a_metric" to metricA)
                else linkedMapOf("a_metric" to metricA,"b_metric" to metricB),
            provenance=p,
        )
        val rep1=rep(1)
        val rep2=rep(2)
        val obs1=FormObservation(
            "obs-$setId-1",rep1.repId,"bilateral_asymmetry",2,
            FormObservationState.DEVIATION,FormRuleSeverity.MINOR,.9,12.0,
        )
        val obs2=FormObservation(
            "obs-$setId-2",rep2.repId,"bilateral_asymmetry",2,
            FormObservationState.OK,FormRuleSeverity.MINOR,.9,4.0,
        )
        val cue=CueEvent("cue-$setId","bilateral_asymmetry",rep1.repId,15L,"MINOR")
        val response=CueResponse(cue.cueId,rep2.repId,CueResponseState.IMPROVED)
        val evidence=PersistedSetEvidence(
            set=SetRecord(setId,executionId,1,0,LoadSnapshot(40.0,"lb")),
            analysisProvenance=p,
            reps=if(reverse)listOf(rep2,rep1) else listOf(rep1,rep2),
            observations=if(reverse)listOf(obs2,obs1) else listOf(obs1,obs2),
            cues=listOf(cue),
            responses=listOf(response),
            tracking=TrackingQualitySummary(setId,10,2,1,0),
            summary=SetSummary(
                setId,endedAtUs,2,0,0,endedAtEpochMs
            ),
            cueDeliveries=listOf(CueDeliveryRecord(cue.cueId,CueDeliveryState.COMPLETED)),
            cueObservationIds=mapOf(cue.cueId to obs1.observationId),
        )
        return ChatGptSetContext(
            session=WorkoutSessionRecord(sessionId,0),
            execution=ExerciseExecutionRecord(executionId,sessionId,exerciseId,0),
            evidence=evidence,
        )
    }

    private fun provenance(exerciseId:String):AnalysisProvenance{
        fun ref(id:String)=ProfileVersionRef(id,1,"hash-$id")
        return AnalysisProvenance(
            exerciseDefinitionId=exerciseId,
            exerciseDefinitionVersion=1,
            exerciseDefinitionSemanticHash="hash-definition",
            exerciseProfile=ref("exercise"),
            cameraProfile=ref("camera"),
            signalProfile=ref("signal"),
            movementPrimitiveSequence=ref("primitive"),
            metricProfile=ref("metric"),
            formRuleSet=ref("form"),
            cuePolicy=ref("cue"),
            equipmentProfile=ref("equipment"),
            personalCalibrationProfile=null,
        )
    }

    private class FakeRepository(
        private val current:ChatGptSetContext,
        private val history:List<ChatGptSetContext>,
    ):ChatGptContextRepository{
        override fun loadSetContext(setId:String)=current.takeIf{it.evidence.set.setId==setId}
        override fun loadRecentComparableSetContexts(
            exerciseId:String,
            currentSetId:String,
            beforeEndedAtUs:Long,
            limit:Int,
        )=history
    }
}
