package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test

class Step3StructuredSummaryTest {
    @Test fun allRecurringRulesAreRetainedButOnlyOneIsFocus(){
        val summary=EvidenceSummaryEngine().summarize(fixture())
        assertEquals(setOf("a","b"),summary.recurringRuleIds.toSet())
        assertEquals("a",summary.focusRuleId)
    }
    @Test fun completedDeliveryExposesObservedResponse(){
        val summary=EvidenceSummaryEngine().summarize(fixture(CueDeliveryState.COMPLETED,true))
        assertEquals(setOf("a"),summary.resolvedRuleIds)
        assertEquals(CueResponseState.IMPROVED,summary.cueResponses.single().state)
        assertEquals(listOf("b"),summary.recurringRuleIds)
    }
    @Test fun cancelledFailedAndMerelyStartedAudioCannotClaimResponse(){
        listOf(CueDeliveryState.CANCELLED,CueDeliveryState.FAILED,CueDeliveryState.STARTED).forEach{delivery->
            val summary=EvidenceSummaryEngine().summarize(fixture(delivery,true))
            assertTrue(summary.cueResponses.isEmpty());assertTrue(summary.resolvedRuleIds.isEmpty())
        }
    }
    @Test fun interruptedEvidenceCannotClaimImprovement(){
        val evidence=fixture(CueDeliveryState.COMPLETED,true).copy(tracking=TrackingQualitySummary("set",3,0,1,0,interruptionEpisodes=1))
        val summary=EvidenceSummaryEngine().summarize(evidence)
        assertTrue(summary.recurringRuleIds.isEmpty());assertTrue(summary.resolvedRuleIds.isEmpty())
        assertEquals(CueResponseState.UNKNOWN,summary.cueResponses.single().state)
    }
    @Test fun orphanResponseIsNotTreatedAsImprovement(){
        val summary=EvidenceSummaryEngine().summarize(fixture(CueDeliveryState.COMPLETED,true).copy(
            responses=listOf(CueResponse("cue","nonexistent",CueResponseState.IMPROVED))))
        assertEquals(CueResponseState.UNKNOWN,summary.cueResponses.single().state)
        assertTrue(summary.resolvedRuleIds.isEmpty())
    }
    private fun fixture(delivery:CueDeliveryState?=null,improved:Boolean=false):PersistedSetEvidence{
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        val p=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment).provenance
        val reps=(1..if(improved)3 else 2).map{n->RepEvidence("r$n",n,"cycle",MovementPrimitive.RAISE,
            n*100L,n*100L+50,RepClassification.NORMAL,emptyMap(),emptyMap(),p)}
        val forms=reps.flatMap{rep->listOf("a","b").map{rule->FormObservation("${rep.repId}-$rule",rep.repId,
            rule,1,if(improved&&rep.ordinal==3&&rule=="a")FormObservationState.OK else FormObservationState.DEVIATION,
            FormRuleSeverity.MINOR,.95,.5)}}
        val cues=if(delivery==null)emptyList() else listOf(CueEvent("cue","a","r2",250,"MINOR"))
        return PersistedSetEvidence(SetRecord("set","e",1,0),p,reps,forms,cues,
            if(improved)listOf(CueResponse("cue","r3",CueResponseState.IMPROVED))else emptyList(),
            null,SetSummary("set",400,reps.size,0,0),delivery?.let{listOf(CueDeliveryRecord("cue",it))}.orEmpty())
    }
}
