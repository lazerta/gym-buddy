package com.gymbuddy.domain.profile

data class ExerciseBaselineKey(
    val exerciseProfileId: String,
    val exerciseProfileVersion: Int,
    val equipmentProfileId: String? = null,
    val viewClass: ViewClass? = null,
) {
    init {
        require(exerciseProfileId.isNotBlank()) { "exerciseProfileId must not be blank" }
        require(exerciseProfileVersion > 0) { "exerciseProfileVersion must be > 0" }
        require(equipmentProfileId == null || equipmentProfileId.isNotBlank()) {
            "equipmentProfileId must be null or non-blank"
        }
    }
}

data class BaselineStatistic(
    val median: Double? = null,
    val lowerBound: Double? = null,
    val upperBound: Double? = null,
    val sampleCount: Int,
    val sessionCount: Int,
    val confidence: Double,
) {
    init {
        require(listOfNotNull(median, lowerBound, upperBound).all { it.isFinite() }) {
            "baseline values must be finite when present"
        }
        if (lowerBound != null && median != null) {
            require(lowerBound <= median) { "lowerBound must be <= median" }
        }
        if (upperBound != null && median != null) {
            require(median <= upperBound) { "median must be <= upperBound" }
        }
        if (lowerBound != null && upperBound != null) {
            require(lowerBound <= upperBound) { "lowerBound must be <= upperBound" }
        }
        require(sampleCount >= 0) { "sampleCount must be >= 0" }
        require(sessionCount >= 0) { "sessionCount must be >= 0" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence must be within [0, 1]"
        }
    }
}

data class ExerciseBaseline(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val key: ExerciseBaselineKey,
    val metricStatistics: Map<String, BaselineStatistic>,
) : VersionedProfile {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(profileVersion > 0) { "profileVersion must be > 0" }
        require(semanticHash.isNotBlank()) { "semanticHash must not be blank" }
        require(metricStatistics.keys.none { it.isBlank() }) {
            "metric ids must not be blank"
        }
    }
}

data class PersonalCalibrationProfile(
    val calibrationProfileId: String,
    val profileVersion: Int,
    val semanticHash: String,
    val sourceConfidence: Double,
    val normalizedBodyGeometry: Map<String, Double?> = emptyMap(),
    val cameraSetupPreferences: Map<String, Double?> = emptyMap(),
    val exerciseBaselines: List<ExerciseBaseline> = emptyList(),
    val equipmentAssociations: Set<String> = emptySet(),
    val lateralityBaseline: Map<String, Double?> = emptyMap(),
    val cueEffectiveness: Map<String, Double?> = emptyMap(),
    val evidenceReferences: Set<String> = emptySet(),
) {
    init {
        require(calibrationProfileId.isNotBlank()) { "calibrationProfileId must not be blank" }
        require(profileVersion > 0) { "profileVersion must be > 0" }
        require(semanticHash.isNotBlank()) { "semanticHash must not be blank" }
        require(sourceConfidence.isFinite() && sourceConfidence in 0.0..1.0) {
            "sourceConfidence must be within [0, 1]"
        }
        validateOptionalMap(normalizedBodyGeometry, "normalizedBodyGeometry")
        validateOptionalMap(cameraSetupPreferences, "cameraSetupPreferences")
        validateOptionalMap(lateralityBaseline, "lateralityBaseline")
        validateOptionalMap(cueEffectiveness, "cueEffectiveness")
        require(equipmentAssociations.none { it.isBlank() }) {
            "equipmentAssociations must not contain blank values"
        }
        require(evidenceReferences.none { it.isBlank() }) {
            "evidenceReferences must not contain blank values"
        }
        require(exerciseBaselines.map { it.key }.distinct().size == exerciseBaselines.size) {
            "exerciseBaselines must not contain duplicate keys"
        }
    }

    companion object {
        fun create(
            calibrationProfileId: String,
            profileVersion: Int,
            sourceConfidence: Double,
            normalizedBodyGeometry: Map<String, Double?> = emptyMap(),
            cameraSetupPreferences: Map<String, Double?> = emptyMap(),
            exerciseBaselines: List<ExerciseBaseline> = emptyList(),
            equipmentAssociations: Set<String> = emptySet(),
            lateralityBaseline: Map<String, Double?> = emptyMap(),
            cueEffectiveness: Map<String, Double?> = emptyMap(),
            evidenceReferences: Set<String> = emptySet(),
        ): PersonalCalibrationProfile {
            val semanticHash = PersonalCalibrationSemanticHash.compute(
                sourceConfidence = sourceConfidence,
                normalizedBodyGeometry = normalizedBodyGeometry,
                cameraSetupPreferences = cameraSetupPreferences,
                exerciseBaselines = exerciseBaselines,
                equipmentAssociations = equipmentAssociations,
                lateralityBaseline = lateralityBaseline,
                cueEffectiveness = cueEffectiveness,
                evidenceReferences = evidenceReferences,
            )
            return PersonalCalibrationProfile(
                calibrationProfileId = calibrationProfileId,
                profileVersion = profileVersion,
                semanticHash = semanticHash,
                sourceConfidence = sourceConfidence,
                normalizedBodyGeometry = normalizedBodyGeometry,
                cameraSetupPreferences = cameraSetupPreferences,
                exerciseBaselines = exerciseBaselines,
                equipmentAssociations = equipmentAssociations,
                lateralityBaseline = lateralityBaseline,
                cueEffectiveness = cueEffectiveness,
                evidenceReferences = evidenceReferences,
            )
        }
    }

    private fun validateOptionalMap(values: Map<String, Double?>, field: String) {
        require(values.keys.none { it.isBlank() }) { "$field keys must not be blank" }
        require(values.values.filterNotNull().all { it.isFinite() }) {
            "$field values must be finite when known"
        }
    }
}

data class PersonalCalibrationVersionRef(
    val calibrationProfileId: String,
    val profileVersion: Int,
    val semanticHash: String,
) {
    init {
        require(calibrationProfileId.isNotBlank()) { "calibrationProfileId must not be blank" }
        require(profileVersion > 0) { "profileVersion must be > 0" }
        require(semanticHash.isNotBlank()) { "semanticHash must not be blank" }
    }

    companion object {
        fun from(profile: PersonalCalibrationProfile): PersonalCalibrationVersionRef =
            PersonalCalibrationVersionRef(
                calibrationProfileId = profile.calibrationProfileId,
                profileVersion = profile.profileVersion,
                semanticHash = profile.semanticHash,
            )
    }
}
