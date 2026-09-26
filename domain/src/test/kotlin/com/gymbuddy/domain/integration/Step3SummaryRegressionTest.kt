package com.gymbuddy.domain.integration

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Test

class Step3SummaryRegressionTest {
    private val engine=EvidenceSummaryEngine { "Correct $it" }
    @Test fun twoRowsForOneRepAreNotRecurringEvidence() {
        val e=evidence(listOf(obs("o1","r1"),obs("o2","r1")))
        check(engine.summarize(e).focusRuleId==null) { "One physical rep became a recurring issue" }
    }
    @Test fun interruptedSetDoesNotProduceConfidentNextSetCorrection() {
        val e=evidence(listOf(obs("o1","r1"),obs("o2","r2"))).copy(
            tracking=TrackingQualitySummary("set",5,0,1,0,activeObservableFrames=5,
                activePausedFrames=1,interruptionEpisodes=1))
        check(engine.summarize(e).focusRuleId==null) { "Interrupted evidence was promoted to a confident focus" }
    }
    @Test fun deliveredCorrectionResolvedOnLaterRepIsNotRepeatedAsFocus() {
        val e=evidence(listOf(obs("o1","r1"),obs("o2","r2"),obs("o3","r3",FormObservationState.OK))).copy(
            cues=listOf(CueEvent("cue","bilateral_asymmetry","r2",250L,"MINOR")),
            cueDeliveries=listOf(CueDeliveryRecord("cue",CueDeliveryState.COMPLETED)),
            responses=listOf(CueResponse("cue","r3",CueResponseState.IMPROVED)))
        check(engine.summarize(e).focusRuleId==null) { "Resolved correction was repeated as next-set focus" }
    }
    @Test fun undeliveredAudioDoesNotBecomeAHeardCorrection() {
        val e=evidence(emptyList()).copy(
            cues=listOf(CueEvent("cue","bilateral_asymmetry","r2",250L,"MINOR")),
            cueDeliveries=listOf(CueDeliveryRecord("cue",CueDeliveryState.CANCELLED)))
        check(engine.summarize(e).deliveredCueRuleIds.isEmpty())
        check(engine.summarize(e).focusRuleId==null)
    }
    private fun obs(id:String,rep:String,state:FormObservationState=FormObservationState.DEVIATION)=
        FormObservation(id,rep,"bilateral_asymmetry",1,state,FormRuleSeverity.MINOR,.95,.2)
    private fun evidence(observations:List<FormObservation>):PersistedSetEvidence {
        val b=InitialExerciseProfiles.dumbbellLateralRaise
        val p=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment).provenance
        return PersistedSetEvidence(SetRecord("set","exec",1,0L),p,
            (1..3).map { n -> RepEvidence("r$n",n,"cycle",MovementPrimitive.RAISE,
                n*100L,n*100L+50,RepClassification.NORMAL,emptyMap(),emptyMap(),p) },
            observations,emptyList(),emptyList(),null,SetSummary("set",400,3,0,0,1000))
    }
}
