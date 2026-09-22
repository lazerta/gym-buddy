package com.gymbuddy.domain

data class EquipmentProfile(
    val equipmentProfileId: String,
    val profileVersion: Int,
    val semanticHash: String,
    val equipmentKind: EquipmentKind,
    val compatibleExerciseProfileIds: Set<String>,
    val preferredViewClassOverride: ViewClass? = null,
    val signalParameterOverrides: Map<String, Map<String, Double>> = emptyMap(),
) {
    init {
        require(equipmentProfileId.isNotBlank()) { "equipmentProfileId is required" }
        require(profileVersion > 0) { "equipment profileVersion must be > 0" }
        require(isSha256(semanticHash)) { "equipment semanticHash must be lowercase SHA-256" }
        require(compatibleExerciseProfileIds.isNotEmpty()) {
            "equipment must declare compatible exercise profiles"
        }
        require(compatibleExerciseProfileIds.none { it.isBlank() }) {
            "compatible exercise profile IDs cannot be blank"
        }
        require(signalParameterOverrides.keys.none { it.isBlank() }) {
            "equipment signal override IDs cannot be blank"
        }
        require(signalParameterOverrides.values.flatMap { it.values }.all { it.isFinite() }) {
            "equipment signal override values must be finite"
        }
    }

    companion object {
        fun create(
            equipmentProfileId: String,
            profileVersion: Int,
            equipmentKind: EquipmentKind,
            compatibleExerciseProfileIds: Set<String>,
            preferredViewClassOverride: ViewClass? = null,
            signalParameterOverrides: Map<String, Map<String, Double>> = emptyMap(),
        ): EquipmentProfile {
            val hash = SemanticHashing.equipmentProfileHash(
                equipmentProfileId = equipmentProfileId,
                equipmentKind = equipmentKind,
                compatibleExerciseProfileIds = compatibleExerciseProfileIds,
                preferredViewClassOverride = preferredViewClassOverride,
                signalParameterOverrides = signalParameterOverrides,
            )
            return EquipmentProfile(
                equipmentProfileId = equipmentProfileId,
                profileVersion = profileVersion,
                semanticHash = hash,
                equipmentKind = equipmentKind,
                compatibleExerciseProfileIds = compatibleExerciseProfileIds.toSet(),
                preferredViewClassOverride = preferredViewClassOverride,
                signalParameterOverrides = signalParameterOverrides.mapValues { (_, v) -> v.toMap() },
            )
        }
    }
}

data class MetricBaseline(
    val median: Double,
    val medianAbsoluteDeviation: Double?,
    val sampleCount: Int,
    val sessionCount: Int,
    val confidence: Double,
) {
    init {
        require(median.isFinite()) { "baseline median must be finite" }
        require(medianAbsoluteDeviation == null || medianAbsoluteDeviation.isFinite()) {
            "baseline MAD must be finite when present"
        }
        require(medianAbsoluteDeviation == null || medianAbsoluteDeviation >= 0.0) {
            "baseline MAD must be >= 0"
        }
        require(sampleCount > 0) { "baseline sampleCount must be > 0" }
        require(sessionCount > 0) { "baseline sessionCount must be > 0" }
        require(confidence in 0.0..1.0) { "baseline confidence must be in [0, 1]" }
    }
}

data class ExerciseBaseline(
    val exerciseProfileId: String,
    val compatibleProfileVersion: Int,
    val equipmentProfileId: String? = null,
    val viewClass: ViewClass? = null,
    val metricBaselines: Map<String, MetricBaseline>,
) {
    init {
        require(exerciseProfileId.isNotBlank()) { "exerciseProfileId is required" }
        require(compatibleProfileVersion > 0) { "compatibleProfileVersion must be > 0" }
        require(equipmentProfileId == null || equipmentProfileId.isNotBlank()) {
            "equipmentProfileId cannot be blank"
        }
        require(metricBaselines.isNotEmpty()) { "exercise baseline must contain metrics" }
        require(metricBaselines.keys.none { it.isBlank() }) { "baseline metric IDs cannot be blank" }
    }
}

data class CameraSetupPreference(
    val preferredViewClass: ViewClass? = null,
    val preferredFrameFill: Double? = null,
) {
    init {
        require(preferredFrameFill == null || preferredFrameFill in 0.0..1.0) {
            "preferredFrameFill must be in [0, 1] when present"
        }
    }
}

data class PersonalCalibrationProfile(
    val calibrationProfileId: String,
    val profileVersion: Int,
    val semanticHash: String,
    val sourceConfidence: Double?,
    val normalizedBodyGeometry: Map<String, Double?> = emptyMap(),
    val cameraSetupPreferences: Map<String, CameraSetupPreference> = emptyMap(),
    val exerciseBaselines: List<ExerciseBaseline> = emptyList(),
    val equipmentAssociations: Map<String, String> = emptyMap(),
    val lateralityBaseline: Map<String, Double?> = emptyMap(),
    val cueEffectiveness: Map<String, Double> = emptyMap(),
    val evidenceReferences: List<String> = emptyList(),
) {
    init {
        require(calibrationProfileId.isNotBlank()) { "calibrationProfileId is required" }
        require(profileVersion > 0) { "calibration profileVersion must be > 0" }
        require(isSha256(semanticHash)) { "calibration semanticHash must be lowercase SHA-256" }
        require(sourceConfidence == null || sourceConfidence in 0.0..1.0) {
            "sourceConfidence must be null/UNKNOWN or in [0, 1]"
        }
        require(normalizedBodyGeometry.keys.none { it.isBlank() }) {
            "normalizedBodyGeometry keys cannot be blank"
        }
        require(normalizedBodyGeometry.values.filterNotNull().all { it.isFinite() }) {
            "known body geometry values must be finite"
        }
        require(cameraSetupPreferences.keys.none { it.isBlank() }) {
            "camera preference profile IDs cannot be blank"
        }
        require(equipmentAssociations.keys.none { it.isBlank() } && equipmentAssociations.values.none { it.isBlank() }) {
            "equipment associations cannot contain blank IDs"
        }
        require(lateralityBaseline.values.filterNotNull().all { it.isFinite() }) {
            "known laterality values must be finite"
        }
        require(cueEffectiveness.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "cue effectiveness must be finite and in [0, 1]"
        }
        require(evidenceReferences.none { it.isBlank() }) {
            "evidenceReferences cannot contain blank values"
        }
    }

    companion object {
        fun create(
            calibrationProfileId: String,
            profileVersion: Int,
            sourceConfidence: Double?,
            normalizedBodyGeometry: Map<String, Double?> = emptyMap(),
            cameraSetupPreferences: Map<String, CameraSetupPreference> = emptyMap(),
            exerciseBaselines: List<ExerciseBaseline> = emptyList(),
            equipmentAssociations: Map<String, String> = emptyMap(),
            lateralityBaseline: Map<String, Double?> = emptyMap(),
            cueEffectiveness: Map<String, Double> = emptyMap(),
            evidenceReferences: List<String> = emptyList(),
        ): PersonalCalibrationProfile {
            val hash = SemanticHashing.personalCalibrationHash(
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
                semanticHash = hash,
                sourceConfidence = sourceConfidence,
                normalizedBodyGeometry = normalizedBodyGeometry.toMap(),
                cameraSetupPreferences = cameraSetupPreferences.toMap(),
                exerciseBaselines = exerciseBaselines.toList(),
                equipmentAssociations = equipmentAssociations.toMap(),
                lateralityBaseline = lateralityBaseline.toMap(),
                cueEffectiveness = cueEffectiveness.toMap(),
                evidenceReferences = evidenceReferences.toList(),
            )
        }
    }
}

