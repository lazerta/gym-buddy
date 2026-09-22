package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test

class RepEvidenceRegressionTest {
    @Test fun asymmetryUsesMaximumPairedDifferenceNotDifferenceOfMeans() {
        val bundle = InitialExerciseProfiles.inclineDumbbellPress
        val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
        fun frame(ts: Long, left: Double, right: Double) = MovementSignalFrame(ts, mapOf(
            "left_progress" to MovementSignalObservation("left_progress", SignalUnit.NORMALIZED, left, .9),
            "right_progress" to MovementSignalObservation("right_progress", SignalUnit.NORMALIZED, right, .9),
        ))
        val frames = listOf(frame(0, .1, .1), frame(100_000, .9, .5), frame(200_000, .1, .5))
        val event = RepDetectionEvent(1, "cycle", MovementPrimitive.PRESS, 0, 200_000, RepCompletionKind.COMPLETED, RepClassification.NORMAL, null, .9, null)
        val rep = RepEvidenceBuilder().build(event, frames, config)
        val asymmetry = rep.metrics.getValue("bilateral_asymmetry").value as EvidenceValue.Known
        assertEquals(.4, asymmetry.value, 1e-9)
        assertEquals(FormObservationState.DEVIATION, FormAnalysisEngine().analyze(rep, bundle.profile.formRuleSet)
            .single { it.ruleId == "bilateral_asymmetry" }.state)
    }
}
