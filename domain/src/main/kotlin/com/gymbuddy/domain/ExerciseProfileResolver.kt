package com.gymbuddy.domain

object ExerciseProfileResolver {
    fun resolve(
        definition: ExerciseDefinition,
        profile: ExerciseProfile,
        equipment: EquipmentProfile? = null,
        personalCalibration: PersonalCalibrationProfile? = null,
    ): AnalysisConfig {
        require(definition.exerciseId == profile.exerciseId) {
            "ExerciseDefinition and ExerciseProfile exerciseId must match"
        }
        ProfileValidation.validate(profile)

        val resolvedCamera = equipment?.let {
            ProfileValidation.validateEquipment(profile, it)
            applyCameraOverride(profile.cameraProfile, it)
        } ?: profile.cameraProfile

        val resolvedSignals = equipment?.let {
            applySignalOverrides(profile.signalProfile, it)
        } ?: profile.signalProfile

        personalCalibration?.let {
            ProfileValidation.validateCalibration(profile, equipment, it)
        }

        val baseline = personalCalibration?.exerciseBaselines
            ?.filter { it.exerciseProfileId == profile.profileId }
            ?.filter { it.compatibleProfileVersion == profile.profileVersion }
            ?.filter { it.equipmentProfileId == null || it.equipmentProfileId == equipment?.equipmentProfileId }
            ?.filter { it.viewClass == null || it.viewClass == resolvedCamera.preferredViewClass }
            ?.maxByOrNull { candidate ->
                candidate.metricBaselines.values.sumOf { it.sampleCount }
            }

        val cameraPreference = personalCalibration
            ?.cameraSetupPreferences
            ?.get(profile.profileId)

        return AnalysisConfig(
            exerciseDefinition = definition,
            exerciseProfile = profile,
            cameraProfile = resolvedCamera,
            signalProfile = resolvedSignals,
            movementPrimitiveSequence = profile.movementPrimitiveSequence,
            metricProfile = profile.metricProfile,
            formRuleSet = profile.formRuleSet,
            cuePolicy = profile.cuePolicy,
            exerciseProfileProvenance = profile.provenance(),
            equipmentProfile = equipment,
            equipmentProvenance = equipment?.let {
                ProfileProvenance(it.equipmentProfileId, it.profileVersion, it.semanticHash)
            },
            personalCalibrationProfile = personalCalibration,
            personalCalibrationProvenance = personalCalibration?.let {
                ProfileProvenance(it.calibrationProfileId, it.profileVersion, it.semanticHash)
            },
            exerciseBaseline = baseline,
            cameraSetupPreference = cameraPreference,
        )
    }

    private fun applyCameraOverride(
        generic: CameraProfile,
        equipment: EquipmentProfile,
    ): CameraProfile = generic.copy(
        preferredViewClass = equipment.preferredViewClassOverride ?: generic.preferredViewClass,
    )

    private fun applySignalOverrides(
        generic: SignalProfile,
        equipment: EquipmentProfile,
    ): SignalProfile {
        if (equipment.signalParameterOverrides.isEmpty()) return generic

        return generic.copy(
            signalDefinitions = generic.signalDefinitions.map { signal ->
                val overrides = equipment.signalParameterOverrides[signal.signalId]
                if (overrides == null) {
                    signal
                } else {
                    signal.copy(parameters = signal.parameters + overrides)
                }
            }
        )
    }
}
