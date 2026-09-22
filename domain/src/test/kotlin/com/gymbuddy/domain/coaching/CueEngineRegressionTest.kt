package com.gymbuddy.domain.coaching

import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.*
import org.junit.Assert.*
import org.junit.Test

class CueEngineRegressionTest {
    private val rules = FormRuleSet("rules", 1, "h", listOf(
        FormRule("minor", 1, setOf("a"), .5, FormRuleSeverity.MINOR, FormComparison.MAX_VALUE, .2),
        FormRule("major", 1, setOf("b"), .5, FormRuleSeverity.MAJOR, FormComparison.MAX_VALUE, .2),
    ))

    @Test fun onlyHighestSeverityCueIsEmittedAndWorseningIsMeasured() {
        val engine = CueEngine(CuePolicy("cue", 1, "h", 3, 1, 0, 10), rules)
        val first = engine.evaluate(rep("r1", 1_000_000), listOf(
            obs("r1", "minor", FormRuleSeverity.MINOR, .4),
            obs("r1", "major", FormRuleSeverity.MAJOR, .5),
        ))
        assertEquals(1, first.cues.size)
        assertEquals("major", first.cues.single().ruleId)
        val second = engine.evaluate(rep("r2", 2_000_000), listOf(
            obs("r2", "minor", FormRuleSeverity.MINOR, .4),
            obs("r2", "major", FormRuleSeverity.MAJOR, .7),
        ))
        assertEquals(CueResponseState.WORSENED, second.responses.single().state)
    }

    @Test fun interruptionMakesPendingCueResponseUnknown() {
        val engine = CueEngine(CuePolicy("cue", 1, "h", 3, 1, 0, 10), rules)
        engine.evaluate(rep("r1", 1_000_000), listOf(obs("r1", "major", FormRuleSeverity.MAJOR, .5)))
        engine.onInterruption()
        val decision = engine.evaluate(rep("r2", 2_000_000), listOf(obs("r2", "major", FormRuleSeverity.MAJOR, .1, FormObservationState.OK)))
        assertEquals(CueResponseState.UNKNOWN, decision.responses.single().state)
    }

    private fun rep(id: String, ts: Long) = RepEvidence(id, 1, "cycle", MovementPrimitive.PRESS, 0, ts, RepClassification.NORMAL, emptyMap(), emptyMap(), provenance())
    private fun obs(rep: String, rule: String, severity: FormRuleSeverity, value: Double, state: FormObservationState = FormObservationState.DEVIATION) =
        FormObservation("obs-$rep-$rule", rep, rule, 1, state, severity, .9, value)
    private fun provenance(): AnalysisProvenance {
        val r = ProfileVersionRef("x", 1, "h")
        return AnalysisProvenance("e", 1, "h", r, r, r, r, r, r, r, null, null)
    }
}
