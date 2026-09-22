package com.gymbuddy.domain

object ProfileValidation {
    fun validate(profile: ExerciseProfile) {
        val signalIds = profile.signalProfile.signalDefinitions.mapTo(linkedSetOf()) { it.signalId }

        profile.movementPrimitiveSequence.primitives.forEach { primitive ->
            require(primitive.progressSignalId in signalIds) {
                "primitive ${primitive.primitiveId} references unknown signal ${primitive.progressSignalId}"
            }
        }

        profile.metricProfile.metricDefinitions.forEach { metric ->
            val unknown = metric.sourceSignalIds - signalIds
            require(unknown.isEmpty()) {
                "metric ${metric.metricId} references unknown signals ${unknown.sorted()}"
            }
        }

        profile.formRuleSet.rules.forEach { rule ->
            val unknown = rule.sourceSignalIds - signalIds
            require(unknown.isEmpty()) {
                "form rule ${rule.ruleId} references unknown signals ${unknown.sorted()}"
            }
        }

        val genericLandmarks = profile.cameraProfile.requiredLandmarks
        profile.signalProfile.signalDefinitions.forEach { signal ->
            require(genericLandmarks.containsAll(signal.requiredLandmarks)) {
                "signal ${signal.signalId} requires landmarks not present in CameraProfile"
            }
        }

        val expectedHash = SemanticHashing.exerciseProfileHash(profile)
        require(profile.semanticHash == expectedHash) {
            "exercise profile semanticHash does not match behavior-affecting configuration"
        }
    }

    fun validateEquipment(profile: ExerciseProfile, equipment: EquipmentProfile) {
        require(profile.profileId in equipment.compatibleExerciseProfileIds) {
            "equipment ${equipment.equipmentProfileId} is not compatible with ${profile.profileId}"
        }
        require(equipment.equipmentKind in profile.supportedEquipmentKinds) {
            "equipment kind ${equipment.equipmentKind} is not supported by ${profile.profileId}"
        }

        equipment.preferredViewClassOverride?.let { view ->
            require(view in profile.cameraProfile.allowedViewClasses) {
                "equipment cannot select a camera view not allowed by the ExerciseProfile"
            }
        }

        equipment.signalParameterOverrides.forEach { (signalId, overrides) ->
            val signal = requireNotNull(profile.signalProfile.signal(signalId)) {
                "equipment references unknown signal $signalId"
            }
            val unknownParameters = overrides.keys - signal.parameters.keys
            require(unknownParameters.isEmpty()) {
                "equipment cannot introduce undeclared parameters $unknownParameters for $signalId"
            }
        }

        val expectedHash = SemanticHashing.equipmentProfileHash(
            equipmentProfileId = equipment.equipmentProfileId,
            equipmentKind = equipment.equipmentKind,
            compatibleExerciseProfileIds = equipment.compatibleExerciseProfileIds,
            preferredViewClassOverride = equipment.preferredViewClassOverride,
            signalParameterOverrides = equipment.signalParameterOverrides,
        )
        require(equipment.semanticHash == expectedHash) {
            "equipment semanticHash does not match behavior-affecting configuration"
        }
    }

    fun validateCalibration(
        profile: ExerciseProfile,
        equipment: EquipmentProfile?,
        calibration: PersonalCalibrationProfile,
    ) {
        calibration.cameraSetupPreferences[profile.profileId]?.preferredViewClass?.let { view ->
            require(view in profile.cameraProfile.allowedViewClasses) {
                "personal calibration cannot select a camera view not allowed by the ExerciseProfile"
            }
        }

        val metricIds = profile.metricProfile.metricIds()
        calibration.exerciseBaselines
            .filter { it.exerciseProfileId == profile.profileId }
            .forEach { baseline ->
                require(baseline.compatibleProfileVersion == profile.profileVersion) {
                    "personal baseline version is incompatible with the ExerciseProfile"
                }
                require(baseline.equipmentProfileId == null || baseline.equipmentProfileId == equipment?.equipmentProfileId) {
                    "personal baseline equipment does not match the active EquipmentProfile"
                }
                val unsupported = baseline.metricBaselines.keys - metricIds
                require(unsupported.isEmpty()) {
                    "personal calibration cannot invent unsupported metrics ${unsupported.sorted()}"
                }
            }

        val expectedHash = SemanticHashing.personalCalibrationHash(
            sourceConfidence = calibration.sourceConfidence,
            normalizedBodyGeometry = calibration.normalizedBodyGeometry,
            cameraSetupPreferences = calibration.cameraSetupPreferences,
            exerciseBaselines = calibration.exerciseBaselines,
            equipmentAssociations = calibration.equipmentAssociations,
            lateralityBaseline = calibration.lateralityBaseline,
            cueEffectiveness = calibration.cueEffectiveness,
            evidenceReferences = calibration.evidenceReferences,
        )
        require(calibration.semanticHash == expectedHash) {
            "personal calibration semanticHash does not match its content"
        }
    }
}
