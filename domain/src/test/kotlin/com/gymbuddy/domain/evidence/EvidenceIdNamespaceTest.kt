package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.coaching.CueEngine
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepDetectionEvent
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceIdNamespaceTest {
    @Test
    fun identicalTimebasesProduceDistinctRepObservationAndCueIdsPerSet(){
        val bundle=InitialExerciseProfiles.inclineDumbbellPress
        val config=AnalysisConfigResolver.resolve(
            bundle.definition,bundle.profile,bundle.equipment
        )
        val event=RepDetectionEvent(
            ordinal=1,
            stepId="cycle",
            primitive=MovementPrimitive.PRESS,
            startedAtUs=500_000L,
            completedAtUs=2_000_000L,
            kind=RepCompletionKind.COMPLETED,
            classification=RepClassification.NORMAL,
            minConfidence=.95,
            maxAssistance=0.0,
        )
        val builder=RepEvidenceBuilder()
        val repA=builder.build(event,emptyList(),config,"set-a")
        val repB=builder.build(event,emptyList(),config,"set-b")

        assertNotEquals(repA.repId,repB.repId)
        assertTrue(repA.repId.startsWith("set-a/"))
        assertTrue(repB.repId.startsWith("set-b/"))

        val observationsA=FormAnalysisEngine().analyze(repA,config)
        val observationsB=FormAnalysisEngine().analyze(repB,config)
        assertTrue(
            observationsA.map{it.observationId}.toSet()
                .intersect(observationsB.map{it.observationId}.toSet())
                .isEmpty()
        )

        val rule=bundle.profile.formRuleSet.rules.single{
            it.ruleId=="bilateral_asymmetry"
        }
        fun emittedCue(namespace:String):String{
            val engine=CueEngine(
                bundle.profile.cuePolicy,
                bundle.profile.formRuleSet,
                namespace,
            )
            var cueId:String?=null
            repeat(bundle.profile.cuePolicy.requiredOccurrences){index->
                val rep=repA.copy(
                    repId=namespace+"/rep-"+(index+1),
                    ordinal=index+1,
                    completedAtUs=3_000_000L+index*1_000_000L,
                )
                val observation=FormObservation(
                    observationId=rep.repId+"/obs",
                    repId=rep.repId,
                    ruleId=rule.ruleId,
                    ruleVersion=rule.ruleVersion,
                    state=FormObservationState.DEVIATION,
                    severity=rule.severity,
                    confidence=.95,
                    evidenceValue=(rule.threshold?:.18)+.20,
                )
                engine.evaluate(rep,listOf(observation)).cues
                    .singleOrNull()
                    ?.let{cueId=it.cueId}
            }
            return requireNotNull(cueId)
        }

        val cueA=emittedCue("set-a")
        val cueB=emittedCue("set-b")
        assertNotEquals(cueA,cueB)
        assertTrue(cueA.startsWith("set-a/"))
        assertTrue(cueB.startsWith("set-b/"))
    }
}
