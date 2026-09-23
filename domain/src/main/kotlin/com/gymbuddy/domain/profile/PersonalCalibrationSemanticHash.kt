package com.gymbuddy.domain.profile

object PersonalCalibrationSemanticHash {
    fun compute(profile: PersonalCalibrationProfile): String = compute(
        sourceConfidence = profile.sourceConfidence,
        normalizedBodyGeometry = profile.normalizedBodyGeometry,
        cameraSetupPreferences = profile.cameraSetupPreferences,
        exerciseBaselines = profile.exerciseBaselines,
        equipmentAssociations = profile.equipmentAssociations,
        lateralityBaseline = profile.lateralityBaseline,
        cueEffectiveness = profile.cueEffectiveness,
        evidenceReferences = profile.evidenceReferences,
    )

    fun compute(
        sourceConfidence: Double,
        normalizedBodyGeometry: Map<String, Double?> = emptyMap(),
        cameraSetupPreferences: Map<String, Double?> = emptyMap(),
        exerciseBaselines: List<ExerciseBaseline> = emptyList(),
        equipmentAssociations: Set<String> = emptySet(),
        lateralityBaseline: Map<String, Double?> = emptyMap(),
        cueEffectiveness: Map<String, Double?> = emptyMap(),
        evidenceReferences: Set<String> = emptySet(),
    ): String {
        val parts = mutableListOf("personal-calibration-semantic-v1")
        parts += listOf("sourceConfidence", number(sourceConfidence))
        appendOptionalMap(parts, "normalizedBodyGeometry", normalizedBodyGeometry)
        appendOptionalMap(parts, "cameraSetupPreferences", cameraSetupPreferences)
        appendOptionalMap(parts, "lateralityBaseline", lateralityBaseline)
        appendOptionalMap(parts, "cueEffectiveness", cueEffectiveness)
        appendSet(parts, "equipmentAssociations", equipmentAssociations)
        appendSet(parts, "evidenceReferences", evidenceReferences)
        exerciseBaselines
            .sortedWith(
                compareBy<ExerciseBaseline>(
                    { it.key.exerciseProfileId },
                    { it.key.exerciseProfileVersion },
                    { it.key.equipmentProfileId ?: "" },
                    { it.key.viewClass?.name ?: "" },
                    { it.profileId },
                    { it.profileVersion },
                    { it.semanticHash },
                )
            )
            .forEach { baseline ->
                parts += "exerciseBaseline"
                parts += baseline.profileId
                parts += baseline.profileVersion.toString()
                parts += baseline.semanticHash
                parts += baseline.key.exerciseProfileId
                parts += baseline.key.exerciseProfileVersion.toString()
                parts += baseline.key.equipmentProfileId.orEmpty()
                parts += baseline.key.viewClass?.name.orEmpty()
                baseline.metricStatistics.toSortedMap().forEach { (metricId, statistic) ->
                    parts += "metricStatistic"
                    parts += metricId
                    parts += optionalNumber(statistic.median)
                    parts += optionalNumber(statistic.lowerBound)
                    parts += optionalNumber(statistic.upperBound)
                    parts += statistic.sampleCount.toString()
                    parts += statistic.sessionCount.toString()
                    parts += number(statistic.confidence)
                }
            }
        return SemanticHash.sha256(*parts.toTypedArray())
    }

    private fun appendOptionalMap(
        parts: MutableList<String>,
        label: String,
        values: Map<String, Double?>,
    ) {
        parts += label
        values.toSortedMap().forEach { (key, value) ->
            parts += key
            parts += optionalNumber(value)
        }
    }

    private fun appendSet(
        parts: MutableList<String>,
        label: String,
        values: Set<String>,
    ) {
        parts += label
        values.sorted().forEach(parts::add)
    }

    private fun optionalNumber(value: Double?): String =
        value?.let(::number) ?: "<unknown>"

    private fun number(value: Double): String =
        if (value == 0.0) "0.0" else value.toString()
}
