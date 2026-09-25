package com.gymbuddy.domain.integration

import com.gymbuddy.domain.coaching.CueEngine
import com.gymbuddy.domain.coaching.CueResponseState
import com.gymbuddy.domain.evidence.FormAnalysisEngine
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.evidence.SignalEvidence
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HarnessFormCueContractTest {
    @Test
    fun harnessFalseCueAndPersistenceContractsMatchProductionEngines() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by the read-only harness workflow",
            !fixturePath.isNullOrBlank(),
        )

        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_HARNESS_COMMIT,fixture.harnessCommit)
        assertEquals(225,fixture.cases.size)

        fixture.cases.forEach { contract ->
            val rules=FormRuleSet(
                profileId="harness-form",
                profileVersion=1,
                semanticHash="harness-form-v1",
                rules=listOf(
                    FormRule(
                        ruleId="harness_issue",
                        ruleVersion=1,
                        evidenceSignalIds=setOf("issue"),
                        minConfidence=.60,
                        severity=FormRuleSeverity.MINOR,
                        comparison=FormComparison.MAX_VALUE,
                        threshold=contract.threshold,
                    )
                ),
            )
            val cue=CueEngine(
                CuePolicy(
                    profileId="harness-cue",
                    profileVersion=1,
                    semanticHash="harness-cue-v1",
                    persistenceWindowReps=3,
                    requiredOccurrences=2,
                    cooldownMs=15_000,
                    maxRepeatedIdenticalCues=2,
                ),
                rules,
            )
            val form=FormAnalysisEngine()

            var anyCue=false
            var sawImprovedResponse=false

            contract.evidence.forEachIndexed { index,value ->
                val timestampUs=(index+1)*2_000_000L
                val rep=rep(
                    id="${contract.key}-r${index+1}",
                    ordinal=index+1,
                    timestampUs=timestampUs,
                    value=value,
                )
                val observations=form.analyze(rep,rules)
                val decision=cue.evaluate(rep,observations)

                anyCue = anyCue || decision.cues.isNotEmpty()
                sawImprovedResponse = sawImprovedResponse ||
                    decision.responses.any {
                        it.state==CueResponseState.IMPROVED
                    }
            }

            assertEquals(
                "${contract.key}: harness cue/no-cue contract",
                contract.expectedAnyCue,
                anyCue,
            )
            if(contract.expectImprovedResponse) {
                assertTrue(
                    "${contract.key}: expected post-cue improvement response",
                    sawImprovedResponse,
                )
            }
        }
    }

    private fun rep(
        id:String,
        ordinal:Int,
        timestampUs:Long,
        value:Double,
    )=RepEvidence(
        repId=id,
        ordinal=ordinal,
        stepId="cycle",
        primitive=MovementPrimitive.PRESS,
        startedAtUs=timestampUs-1_000_000L,
        completedAtUs=timestampUs,
        classification=RepClassification.NORMAL,
        signals=mapOf(
            "issue" to SignalEvidence(
                signalId="issue",
                unit=SignalUnit.NORMALIZED,
                min=value,
                max=value,
                mean=value,
                last=value,
                confidence=.95,
            )
        ),
        metrics=emptyMap(),
        provenance=provenance(),
    )

    private fun provenance():AnalysisProvenance {
        val ref=ProfileVersionRef("harness",1,"harness-v1")
        return AnalysisProvenance(
            "harness-exercise",
            1,
            "harness-exercise-v1",
            ref,
            ref,
            ref,
            ref,
            ref,
            ref,
            ref,
            null,
            null,
        )
    }

    private fun loadFixture(file:File):Fixture {
        val lines=file.readLines()
        val metadata=lines
            .takeWhile { it.startsWith("#") }
            .associate { line ->
                val parts=line
                    .removePrefix("# ")
                    .split('=',limit=2)
                parts[0] to parts.getOrElse(1){""}
            }

        val cases=lines
            .dropWhile { it.startsWith("#") }
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val p=line.split('\t')
                require(p.size==8) {
                    "Malformed harness form/cue row: $line"
                }
                Contract(
                    key=p[0],
                    kind=p[1],
                    exerciseId=p[2],
                    family=p[3],
                    threshold=p[4].toDouble(),
                    expectedAnyCue=p[5].toBooleanStrict(),
                    expectImprovedResponse=p[6].toBooleanStrict(),
                    evidence=p[7].split(',').map(String::toDouble),
                )
            }

        return Fixture(
            harnessCommit=requireNotNull(metadata["harness_commit"]),
            cases=cases,
        )
    }

    private data class Fixture(
        val harnessCommit:String,
        val cases:List<Contract>,
    )

    private data class Contract(
        val key:String,
        val kind:String,
        val exerciseId:String,
        val family:String,
        val threshold:Double,
        val expectedAnyCue:Boolean,
        val expectImprovedResponse:Boolean,
        val evidence:List<Double>,
    )

    companion object {
        private const val FIXTURE_ENV=
            "GYM_BUDDY_HARNESS_FORM_CUE_FIXTURE"
        private const val EXPECTED_HARNESS_COMMIT=
            "d8a6e9569f424c4d97ddde5d1c41df93201f163d"
    }
}
