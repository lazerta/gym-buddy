package com.gymbuddy.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSessionCalibrationUpdaterTest {
    private val pressDumbbell = ExerciseBaselineKey(
        exerciseProfileId = "press-profile",
        exerciseProfileVersion = 3,
        equipmentProfileId = "dumbbell",
        viewClass = ViewClass.SIDE_OBLIQUE,
    )
    private val pressSmith = pressDumbbell.copy(equipmentProfileId = "smith")
    private val otherKey = ExerciseBaselineKey(
        exerciseProfileId = "raise-profile",
        exerciseProfileVersion = 3,
        equipmentProfileId = "dumbbell",
        viewClass = ViewClass.FRONT,
    )
    private val updater = MultiSessionCalibrationUpdater()

    @Test fun singleSessionCannotRewriteCalibration() {
        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = profile(),
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(session("s1", 1, .80)),
        )
        assertEquals(
            CalibrationUpdateStatus.INSUFFICIENT_ELIGIBLE_SESSIONS,
            proposal.status,
        )
        assertNull(proposal.candidateProfile)
    }

    @Test fun trackingPoorSessionIsExcludedFromCandidateEvidence() {
        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = profile(),
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                session("s1", 1, .80),
                session(
                    "bad",
                    2,
                    5.0,
                    eligibility = CalibrationSessionEligibility.TRACKING_POOR,
                ),
                session("s2", 3, .81),
                session("s3", 4, .79),
            ),
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, proposal.status)
        assertEquals(
            CalibrationEvidenceRejection.SESSION_NOT_ELIGIBLE,
            proposal.rejectedEvidence["bad"],
        )
        val candidate = requireNotNull(proposal.candidateProfile)
        val statistic = candidate.exerciseBaselines.single()
            .metricStatistics.getValue("rom")
        assertEquals(3, statistic.sessionCount)
        assertEquals(.80, statistic.median!!, 1e-12)
        assertFalse("bad" in candidate.evidenceReferences)
    }

    @Test fun isolatedOutlierSessionCannotDragBaseline() {
        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = profile(),
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                session("s1", 1, .80),
                session("s2", 2, .81),
                session("s3", 3, .79),
                session("s4", 4, .805),
                session("outlier", 5, 1.50),
            ),
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, proposal.status)
        assertTrue("outlier" in proposal.outlierEvidenceReferences)
        val statistic = requireNotNull(proposal.candidateProfile)
            .exerciseBaselines.single()
            .metricStatistics.getValue("rom")
        assertEquals(4, statistic.sessionCount)
        assertTrue(requireNotNull(statistic.median) < .82)
    }

    @Test fun equipmentChangeNeverMixesBaselines() {
        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = profile(),
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                session("d1", 1, .80),
                session("d2", 2, .81),
                session("d3", 3, .79),
                session("m1", 4, 1.20, key = pressSmith),
                session("m2", 5, 1.18, key = pressSmith),
            ),
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, proposal.status)
        assertEquals(
            CalibrationEvidenceRejection.CONTEXT_MISMATCH,
            proposal.rejectedEvidence["m1"],
        )
        assertEquals(
            pressDumbbell,
            requireNotNull(proposal.candidateProfile)
                .exerciseBaselines.single().key,
        )
    }

    @Test fun coherentRecentShiftIsAcceptedAsLongTermDrift() {
        val current = profile(baseline())
        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = current,
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                session("old1", 1, .80),
                session("old2", 2, .81),
                session("old3", 3, .79),
                session("new1", 4, .88),
                session("new2", 5, .89),
                session("new3", 6, .90),
            ),
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, proposal.status)
        assertTrue("rom" in proposal.driftMetricIds)
        assertEquals(
            setOf("new1", "new2", "new3"),
            proposal.acceptedEvidenceReferences,
        )
        val candidate = requireNotNull(proposal.candidateProfile)
        val statistic = candidate.exerciseBaselines.single { it.key == pressDumbbell }
            .metricStatistics.getValue("rom")
        assertEquals(3, statistic.sessionCount)
        assertEquals(.89, statistic.median!!, 1e-12)
        assertEquals(current.profileVersion + 1, candidate.profileVersion)
        assertEquals(current.normalizedBodyGeometry, candidate.normalizedBodyGeometry)
        assertEquals(current.cameraSetupPreferences, candidate.cameraSetupPreferences)
        assertEquals(current.cueEffectiveness, candidate.cueEffectiveness)
    }

    @Test fun resetAndRebuildAffectOnlyTargetBaseline() {
        val current = profile(
            baseline(),
            baseline(otherKey, .60, .55, .65).copy(
                profileId = "other",
                semanticHash = "other-hash",
            ),
        )
        val reset = updater.resetTargetBaseline(current, pressDumbbell)
        assertEquals(current.profileVersion + 1, reset.profileVersion)
        assertEquals(1, reset.exerciseBaselines.size)
        assertEquals(otherKey, reset.exerciseBaselines.single().key)

        val rebuilt = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = reset,
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                session("r1", 10, .84),
                session("r2", 11, .85),
                session("r3", 12, .86),
            ),
            mode = CalibrationUpdateMode.REBUILD_TARGET,
        )
        assertEquals(CalibrationUpdateStatus.PROPOSED, rebuilt.status)
        val candidate = requireNotNull(rebuilt.candidateProfile)
        assertEquals(reset.profileVersion + 1, candidate.profileVersion)
        assertEquals(2, candidate.exerciseBaselines.size)
        assertEquals(
            .85,
            candidate.exerciseBaselines.single { it.key == pressDumbbell }
                .metricStatistics.getValue("rom").median!!,
            1e-12,
        )
        assertEquals(
            candidate.semanticHash,
            PersonalCalibrationSemanticHash.compute(candidate),
        )
    }

    @Test fun duplicateEvidenceIsRejectedAndUnsupportedMetricsCannotLeakIn() {
        assertThrows(IllegalArgumentException::class.java) {
            updater.propose(
                calibrationProfileId = "personal",
                currentProfile = profile(),
                targetKey = pressDumbbell,
                supportedMetricIds = setOf("rom"),
                sessions = listOf(
                    session("dup", 1, .80),
                    session("dup", 2, .81),
                    session("s3", 3, .79),
                ),
            )
        }

        val proposal = updater.propose(
            calibrationProfileId = "personal",
            currentProfile = profile(),
            targetKey = pressDumbbell,
            supportedMetricIds = setOf("rom"),
            sessions = listOf(
                unsupportedSession("u1", 1),
                unsupportedSession("u2", 2),
                unsupportedSession("u3", 3),
            ),
        )
        assertEquals(
            CalibrationUpdateStatus.INSUFFICIENT_ELIGIBLE_SESSIONS,
            proposal.status,
        )
        assertNull(proposal.candidateProfile)
    }

    @Test fun proposalHashIsDeterministicAcrossInputOrder() {
        val sessions = listOf(
            session("o1", 1, .80),
            session("o2", 2, .81),
            session("o3", 3, .79),
            session("o4", 4, .805),
            session("o5", 5, .795),
        )
        val first = requireNotNull(
            updater.propose(
                "personal",
                profile(),
                pressDumbbell,
                setOf("rom"),
                sessions,
            ).candidateProfile
        )
        val second = requireNotNull(
            updater.propose(
                "personal",
                profile(),
                pressDumbbell,
                setOf("rom"),
                sessions.reversed(),
            ).candidateProfile
        )
        assertEquals(first.semanticHash, second.semanticHash)
        assertEquals(
            first.exerciseBaselines.single().semanticHash,
            second.exerciseBaselines.single().semanticHash,
        )
    }

    private fun session(
        ref: String,
        sequence: Long,
        value: Double,
        key: ExerciseBaselineKey = pressDumbbell,
        eligibility: CalibrationSessionEligibility =
            CalibrationSessionEligibility.ELIGIBLE,
    ) = CalibrationSessionEvidence(
        evidenceReference = ref,
        sequence = sequence,
        key = key,
        eligibility = eligibility,
        metricSamples = mapOf(
            "rom" to listOf(value - .01, value, value + .01)
        ),
    )

    private fun unsupportedSession(
        ref: String,
        sequence: Long,
    ) = CalibrationSessionEvidence(
        evidenceReference = ref,
        sequence = sequence,
        key = pressDumbbell,
        eligibility = CalibrationSessionEligibility.ELIGIBLE,
        metricSamples = mapOf("hidden" to listOf(99.0)),
    )

    private fun baseline(
        key: ExerciseBaselineKey = pressDumbbell,
        median: Double = .80,
        lower: Double = .78,
        upper: Double = .82,
    ) = ExerciseBaseline(
        profileId = "baseline-old",
        profileVersion = 4,
        semanticHash = "old-hash",
        key = key,
        metricStatistics = mapOf(
            "rom" to BaselineStatistic(
                median = median,
                lowerBound = lower,
                upperBound = upper,
                sampleCount = 30,
                sessionCount = 5,
                confidence = .80,
            )
        ),
    )

    private fun profile(
        vararg baselines: ExerciseBaseline,
    ) = PersonalCalibrationProfile.create(
        calibrationProfileId = "personal",
        profileVersion = 7,
        sourceConfidence = .40,
        normalizedBodyGeometry = mapOf("arm_to_torso" to 1.08),
        cameraSetupPreferences = mapOf("distance" to 2.0),
        exerciseBaselines = baselines.toList(),
        equipmentAssociations = setOf("dumbbell"),
        lateralityBaseline = mapOf("asymmetry" to .04),
        cueEffectiveness = mapOf("cue" to .7),
        evidenceReferences = setOf("bootstrap"),
    )
}
