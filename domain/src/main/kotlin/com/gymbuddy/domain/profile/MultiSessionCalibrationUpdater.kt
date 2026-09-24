package com.gymbuddy.domain.profile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class CalibrationSessionEligibility {
    ELIGIBLE,
    TRACKING_POOR,
    OBSERVABILITY_POOR,
    IDENTITY_UNCERTAIN,
    CAMERA_DISTURBANCE,
    INTERRUPTED,
    FORM_DEVIATION,
    LOW_CONFIDENCE,
}

data class CalibrationSessionEvidence(
    val evidenceReference: String,
    val sequence: Long,
    val key: ExerciseBaselineKey,
    val eligibility: CalibrationSessionEligibility,
    val metricSamples: Map<String, List<Double>>,
) {
    init {
        require(evidenceReference.isNotBlank()) { "evidenceReference must not be blank" }
        require(sequence >= 0) { "sequence must be >= 0" }
        require(metricSamples.keys.none { it.isBlank() }) { "metric ids must not be blank" }
        require(metricSamples.values.flatten().all { it.isFinite() }) {
            "metric samples must be finite"
        }
    }
}

enum class CalibrationUpdateMode { INCREMENTAL, REBUILD_TARGET }
enum class CalibrationUpdateStatus {
    PROPOSED,
    INSUFFICIENT_ELIGIBLE_SESSIONS,
    NO_SUPPORTED_METRICS,
}
enum class CalibrationEvidenceRejection {
    CONTEXT_MISMATCH,
    SESSION_NOT_ELIGIBLE,
    NO_SUPPORTED_METRICS,
}

data class MultiSessionCalibrationPolicy(
    // Product safety minimum, not a claim that three sessions is universally sufficient
    // for every exercise/metric. Confidence remains deliberately moderate at this point.
    val minimumEligibleSessions: Int = 3,
    val minimumSessionsForOutlierScreening: Int = 5,
    // Common robust modified-z screening threshold based on median/MAD.
    val modifiedZOutlierThreshold: Double = 3.5,
    // A drift proposal requires repeated recent sessions in the same direction.
    val driftConsecutiveSessions: Int = 3,
    val confidenceAtMinimumSessions: Double = .50,
    val confidenceGainPerAdditionalSession: Double = .08,
    val maximumBaselineConfidence: Double = .90,
) {
    init {
        require(minimumEligibleSessions >= 2)
        require(minimumSessionsForOutlierScreening >= minimumEligibleSessions)
        require(modifiedZOutlierThreshold.isFinite() && modifiedZOutlierThreshold > 0.0)
        require(driftConsecutiveSessions >= minimumEligibleSessions)
        require(confidenceAtMinimumSessions in 0.0..1.0)
        require(confidenceGainPerAdditionalSession >= 0.0)
        require(maximumBaselineConfidence in confidenceAtMinimumSessions..1.0)
    }
}

data class CalibrationUpdateProposal(
    val status: CalibrationUpdateStatus,
    val candidateProfile: PersonalCalibrationProfile?,
    val acceptedEvidenceReferences: Set<String>,
    val rejectedEvidence: Map<String, CalibrationEvidenceRejection>,
    val outlierEvidenceReferences: Set<String>,
    val driftMetricIds: Set<String>,
)

class MultiSessionCalibrationUpdater(
    private val policy: MultiSessionCalibrationPolicy = MultiSessionCalibrationPolicy(),
) {
    fun propose(
        calibrationProfileId: String,
        currentProfile: PersonalCalibrationProfile?,
        targetKey: ExerciseBaselineKey,
        supportedMetricIds: Set<String>,
        sessions: List<CalibrationSessionEvidence>,
        mode: CalibrationUpdateMode = CalibrationUpdateMode.INCREMENTAL,
    ): CalibrationUpdateProposal {
        require(calibrationProfileId.isNotBlank())
        require(currentProfile == null || currentProfile.calibrationProfileId == calibrationProfileId) {
            "currentProfile calibrationProfileId mismatch"
        }
        require(supportedMetricIds.isNotEmpty() && supportedMetricIds.none { it.isBlank() })
        require(sessions.map { it.evidenceReference }.distinct().size == sessions.size) {
            "sessions must not contain duplicate evidenceReference values"
        }

        val rejected = linkedMapOf<String, CalibrationEvidenceRejection>()
        val context = sessions.filter { session ->
            val accepted = session.key == targetKey
            if (!accepted) {
                rejected[session.evidenceReference] = CalibrationEvidenceRejection.CONTEXT_MISMATCH
            }
            accepted
        }
        val qualityEligible = context.filter { session ->
            val accepted = session.eligibility == CalibrationSessionEligibility.ELIGIBLE
            if (!accepted) {
                rejected[session.evidenceReference] =
                    CalibrationEvidenceRejection.SESSION_NOT_ELIGIBLE
            }
            accepted
        }
        val eligible = qualityEligible.filter { session ->
            val accepted = session.metricSamples.any { (metricId, values) ->
                metricId in supportedMetricIds && values.isNotEmpty()
            }
            if (!accepted) {
                rejected[session.evidenceReference] =
                    CalibrationEvidenceRejection.NO_SUPPORTED_METRICS
            }
            accepted
        }.sortedWith(compareBy<CalibrationSessionEvidence>({ it.sequence }, { it.evidenceReference }))

        if (eligible.size < policy.minimumEligibleSessions) {
            return CalibrationUpdateProposal(
                status = CalibrationUpdateStatus.INSUFFICIENT_ELIGIBLE_SESSIONS,
                candidateProfile = null,
                acceptedEvidenceReferences = emptySet(),
                rejectedEvidence = rejected,
                outlierEvidenceReferences = emptySet(),
                driftMetricIds = emptySet(),
            )
        }

        val currentBaseline = if (mode == CalibrationUpdateMode.INCREMENTAL) {
            currentProfile?.exerciseBaselines?.singleOrNull { it.key == targetKey }
        } else {
            null
        }

        val acceptedRefs = linkedSetOf<String>()
        val outlierRefs = linkedSetOf<String>()
        val driftMetricIds = linkedSetOf<String>()
        val statistics = linkedMapOf<String, BaselineStatistic>()

        supportedMetricIds.sorted().forEach { metricId ->
            // Aggregate to one robust value per session first so high-volume sessions
            // cannot dominate low-volume sessions.
            val metricSessions = eligible.mapNotNull { session ->
                val samples = session.metricSamples[metricId].orEmpty()
                if (samples.isEmpty()) null else SessionMetric(session, median(samples))
            }
            if (metricSessions.size < policy.minimumEligibleSessions) return@forEach

            val existing = currentBaseline?.metricStatistics?.get(metricId)
            val driftWindow = coherentDriftWindow(existing, metricSessions)
            val retained = if (driftWindow != null) {
                driftMetricIds += metricId
                driftWindow
            } else {
                rejectOutliers(metricSessions).also { kept ->
                    val keptRefs = kept.map { it.session.evidenceReference }.toSet()
                    metricSessions.asSequence()
                        .filter { it.session.evidenceReference !in keptRefs }
                        .mapTo(outlierRefs) { it.session.evidenceReference }
                }
            }
            if (retained.size < policy.minimumEligibleSessions) return@forEach

            retained.mapTo(acceptedRefs) { it.session.evidenceReference }
            val values = retained.map { it.sessionMedian }.sorted()
            val rawSampleCount =
                retained.sumOf { it.session.metricSamples.getValue(metricId).size }
            statistics[metricId] = BaselineStatistic(
                median = median(values),
                lowerBound = quantile(values, .25),
                upperBound = quantile(values, .75),
                sampleCount = rawSampleCount,
                sessionCount = retained.size,
                confidence = confidenceFor(retained.size),
            )
        }

        if (statistics.isEmpty()) {
            return CalibrationUpdateProposal(
                status = CalibrationUpdateStatus.NO_SUPPORTED_METRICS,
                candidateProfile = null,
                acceptedEvidenceReferences = emptySet(),
                rejectedEvidence = rejected,
                outlierEvidenceReferences = outlierRefs,
                driftMetricIds = driftMetricIds,
            )
        }

        val nextProfileVersion = (currentProfile?.profileVersion ?: 0) + 1
        val baseline = ExerciseBaseline(
            profileId = baselineProfileId(calibrationProfileId, targetKey),
            profileVersion = nextProfileVersion,
            semanticHash = baselineSemanticHash(targetKey, statistics),
            key = targetKey,
            metricStatistics = statistics,
        )
        val baselines = currentProfile?.exerciseBaselines.orEmpty()
            .filterNot { it.key == targetKey } + baseline
        val baselineConfidence = statistics.values.map { it.confidence }.average()
        val candidate = PersonalCalibrationProfile.create(
            calibrationProfileId = calibrationProfileId,
            profileVersion = nextProfileVersion,
            sourceConfidence = max(
                currentProfile?.sourceConfidence ?: 0.0,
                baselineConfidence,
            ),
            normalizedBodyGeometry = currentProfile?.normalizedBodyGeometry.orEmpty(),
            cameraSetupPreferences = currentProfile?.cameraSetupPreferences.orEmpty(),
            exerciseBaselines = baselines,
            equipmentAssociations =
                currentProfile?.equipmentAssociations.orEmpty() +
                    listOfNotNull(targetKey.equipmentProfileId),
            lateralityBaseline = currentProfile?.lateralityBaseline.orEmpty(),
            cueEffectiveness = currentProfile?.cueEffectiveness.orEmpty(),
            evidenceReferences =
                currentProfile?.evidenceReferences.orEmpty() + acceptedRefs,
        )
        return CalibrationUpdateProposal(
            status = CalibrationUpdateStatus.PROPOSED,
            candidateProfile = candidate,
            acceptedEvidenceReferences = acceptedRefs,
            rejectedEvidence = rejected,
            outlierEvidenceReferences = outlierRefs,
            driftMetricIds = driftMetricIds,
        )
    }

    fun resetTargetBaseline(
        currentProfile: PersonalCalibrationProfile,
        targetKey: ExerciseBaselineKey,
    ): PersonalCalibrationProfile {
        val filtered = currentProfile.exerciseBaselines.filterNot { it.key == targetKey }
        if (filtered.size == currentProfile.exerciseBaselines.size) return currentProfile
        return PersonalCalibrationProfile.create(
            calibrationProfileId = currentProfile.calibrationProfileId,
            profileVersion = currentProfile.profileVersion + 1,
            sourceConfidence = currentProfile.sourceConfidence,
            normalizedBodyGeometry = currentProfile.normalizedBodyGeometry,
            cameraSetupPreferences = currentProfile.cameraSetupPreferences,
            exerciseBaselines = filtered,
            equipmentAssociations = currentProfile.equipmentAssociations,
            lateralityBaseline = currentProfile.lateralityBaseline,
            cueEffectiveness = currentProfile.cueEffectiveness,
            evidenceReferences = currentProfile.evidenceReferences,
        )
    }

    private data class SessionMetric(
        val session: CalibrationSessionEvidence,
        val sessionMedian: Double,
    )

    private fun coherentDriftWindow(
        existing: BaselineStatistic?,
        sessions: List<SessionMetric>,
    ): List<SessionMetric>? {
        if (existing?.lowerBound == null || existing.upperBound == null) return null
        if (sessions.size < policy.driftConsecutiveSessions) return null
        val recent = sessions.takeLast(policy.driftConsecutiveSessions)
        val allAbove = recent.all { it.sessionMedian > existing.upperBound }
        val allBelow = recent.all { it.sessionMedian < existing.lowerBound }
        return if (allAbove || allBelow) recent else null
    }

    private fun rejectOutliers(values: List<SessionMetric>): List<SessionMetric> {
        if (values.size < policy.minimumSessionsForOutlierScreening) return values
        val center = median(values.map { it.sessionMedian })
        val mad = median(values.map { abs(it.sessionMedian - center) })
        if (mad <= 1e-12) return values
        return values.filter { point ->
            val modifiedZ = .6745 * abs(point.sessionMedian - center) / mad
            modifiedZ <= policy.modifiedZOutlierThreshold
        }
    }

    private fun confidenceFor(sessionCount: Int): Double = min(
        policy.maximumBaselineConfidence,
        policy.confidenceAtMinimumSessions +
            (sessionCount - policy.minimumEligibleSessions).coerceAtLeast(0) *
            policy.confidenceGainPerAdditionalSession,
    )

    private fun baselineProfileId(
        calibrationProfileId: String,
        key: ExerciseBaselineKey,
    ): String = "personal-baseline-" + SemanticHash.sha256(
        calibrationProfileId,
        key.exerciseProfileId,
        key.exerciseProfileVersion.toString(),
        key.equipmentProfileId.orEmpty(),
        key.viewClass?.name.orEmpty(),
    ).take(16)

    private fun baselineSemanticHash(
        key: ExerciseBaselineKey,
        statistics: Map<String, BaselineStatistic>,
    ): String {
        val parts = mutableListOf(
            "exercise-baseline-semantic-v1",
            key.exerciseProfileId,
            key.exerciseProfileVersion.toString(),
            key.equipmentProfileId.orEmpty(),
            key.viewClass?.name.orEmpty(),
        )
        statistics.toSortedMap().forEach { (metricId, statistic) ->
            parts += metricId
            parts += statistic.median.toString()
            parts += statistic.lowerBound.toString()
            parts += statistic.upperBound.toString()
            parts += statistic.sampleCount.toString()
            parts += statistic.sessionCount.toString()
            parts += statistic.confidence.toString()
        }
        return SemanticHash.sha256(*parts.toTypedArray())
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    private fun quantile(
        sortedValues: List<Double>,
        probability: Double,
    ): Double {
        require(sortedValues.isNotEmpty())
        require(probability in 0.0..1.0)
        if (sortedValues.size == 1) return sortedValues.single()
        val index = probability * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = min(lower + 1, sortedValues.lastIndex)
        val fraction = index - lower
        return sortedValues[lower] * (1.0 - fraction) +
            sortedValues[upper] * fraction
    }
}
