package com.gymbuddy.domain

data class ExerciseProfile(
    val exerciseId: String,
    val profileId: String,
    val profileVersion: Int,
    val semanticHash: String,
    val lateralityMode: LateralityMode,
    val cameraProfile: CameraProfile,
    val signalProfile: SignalProfile,
    val movementPrimitiveSequence: MovementPrimitiveSequence,
    val metricProfile: MetricProfile,
    val formRuleSet: FormRuleSet,
    val cuePolicy: CuePolicy,
    val supportedEquipmentKinds: Set<EquipmentKind>,
    val capabilities: Set<String>,
) {
    init {
        require(exerciseId.isNotBlank()) { "exerciseId is required" }
        require(profileId.isNotBlank()) { "profileId is required" }
        require(profileVersion > 0) { "profileVersion must be > 0" }
        require(isSha256(semanticHash)) { "semanticHash must be lowercase SHA-256" }
        require(supportedEquipmentKinds.isNotEmpty()) { "supportedEquipmentKinds cannot be empty" }
        require(capabilities.none { it.isBlank() }) { "capabilities cannot contain blank values" }
    }

    fun provenance(): ProfileProvenance = ProfileProvenance(
        profileId = profileId,
        profileVersion = profileVersion,
        semanticHash = semanticHash,
    )

    companion object {
        fun create(
            exerciseId: String,
            profileId: String,
            profileVersion: Int,
            lateralityMode: LateralityMode,
            cameraProfile: CameraProfile,
            signalProfile: SignalProfile,
            movementPrimitiveSequence: MovementPrimitiveSequence,
            metricProfile: MetricProfile,
            formRuleSet: FormRuleSet,
            cuePolicy: CuePolicy,
            supportedEquipmentKinds: Set<EquipmentKind>,
            capabilities: Set<String>,
        ): ExerciseProfile {
            val draft = ExerciseProfile(
                exerciseId = exerciseId,
                profileId = profileId,
                profileVersion = profileVersion,
                semanticHash = "0".repeat(64),
                lateralityMode = lateralityMode,
                cameraProfile = cameraProfile,
                signalProfile = signalProfile,
                movementPrimitiveSequence = movementPrimitiveSequence,
                metricProfile = metricProfile,
                formRuleSet = formRuleSet,
                cuePolicy = cuePolicy,
                supportedEquipmentKinds = supportedEquipmentKinds.toSet(),
                capabilities = capabilities.toSet(),
            )
            val hash = SemanticHashing.exerciseProfileHash(draft)
            val result = draft.copy(semanticHash = hash)
            ProfileValidation.validate(result)
            return result
        }
    }
}

data class ProfileProvenance(
    val profileId: String,
    val profileVersion: Int,
    val semanticHash: String,
) {
    init {
        require(profileId.isNotBlank()) { "profileId is required" }
        require(profileVersion > 0) { "profileVersion must be > 0" }
        require(isSha256(semanticHash)) { "semanticHash must be lowercase SHA-256" }
    }
}

data class AnalysisConfig(
    val exerciseDefinition: ExerciseDefinition,
    val exerciseProfile: ExerciseProfile,
    val cameraProfile: CameraProfile,
    val signalProfile: SignalProfile,
    val movementPrimitiveSequence: MovementPrimitiveSequence,
    val metricProfile: MetricProfile,
    val formRuleSet: FormRuleSet,
    val cuePolicy: CuePolicy,
    val exerciseProfileProvenance: ProfileProvenance,
    val equipmentProfile: EquipmentProfile?,
    val equipmentProvenance: ProfileProvenance?,
    val personalCalibrationProfile: PersonalCalibrationProfile?,
    val personalCalibrationProvenance: ProfileProvenance?,
    val exerciseBaseline: ExerciseBaseline?,
    val cameraSetupPreference: CameraSetupPreference?,
)

internal fun requireUnique(values: List<String>, label: String) {
    require(values.size == values.toSet().size) { "$label values must be unique" }
}

internal fun isSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
