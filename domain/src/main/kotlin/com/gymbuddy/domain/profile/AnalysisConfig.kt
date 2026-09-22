package com.gymbuddy.domain.profile

data class AnalysisProvenance(
    val exerciseDefinitionId: String,
    val exerciseDefinitionVersion: Int,
    val exerciseDefinitionSemanticHash: String,
    val exerciseProfile: ProfileVersionRef,
    val equipmentProfile: ProfileVersionRef?,
    val personalCalibrationProfile: PersonalCalibrationVersionRef?,
)

data class AnalysisConfig(
    val exerciseDefinition: ExerciseDefinition,
    val exerciseProfile: ExerciseProfile,
    val equipmentProfile: EquipmentProfile?,
    val personalCalibrationProfile: PersonalCalibrationProfile?,
    val preferredViewClass: ViewClass,
    val resolvedSignalParameters: Map<String, Map<String, Double>>,
    val activeExerciseBaseline: ExerciseBaseline?,
    val provenance: AnalysisProvenance,
)

object AnalysisConfigResolver {
    fun resolve(
        exerciseDefinition: ExerciseDefinition,
        exerciseProfile: ExerciseProfile,
        equipmentProfile: EquipmentProfile? = null,
        personalCalibrationProfile: PersonalCalibrationProfile? = null,
    ): AnalysisConfig {
        require(exerciseDefinition.exerciseId == exerciseProfile.exerciseId) {
            "ExerciseDefinition and ExerciseProfile exerciseId must match"
        }

        val preferredView = resolveEquipmentView(exerciseProfile, equipmentProfile)
        val signalParameters = resolveSignalParameters(exerciseProfile, equipmentProfile)
        val baseline = resolveCalibrationBaseline(
            exerciseProfile = exerciseProfile,
            equipmentProfile = equipmentProfile,
            preferredView = preferredView,
            calibration = personalCalibrationProfile,
        )

        return AnalysisConfig(
            exerciseDefinition = exerciseDefinition,
            exerciseProfile = exerciseProfile,
            equipmentProfile = equipmentProfile,
            personalCalibrationProfile = personalCalibrationProfile,
            preferredViewClass = preferredView,
            resolvedSignalParameters = signalParameters,
            activeExerciseBaseline = baseline,
            provenance = AnalysisProvenance(
                exerciseDefinitionId = exerciseDefinition.exerciseId,
                exerciseDefinitionVersion = exerciseDefinition.definitionVersion,
                exerciseDefinitionSemanticHash = exerciseDefinition.semanticHash,
                exerciseProfile = ProfileVersionRef.from(exerciseProfile),
                equipmentProfile = equipmentProfile?.let(ProfileVersionRef::from),
                personalCalibrationProfile = personalCalibrationProfile?.let(
                    PersonalCalibrationVersionRef::from
                ),
            ),
        )
    }

    private fun resolveEquipmentView(
        exerciseProfile: ExerciseProfile,
        equipmentProfile: EquipmentProfile?,
    ): ViewClass {
        if (equipmentProfile == null) return exerciseProfile.cameraProfile.preferredViewClass
        validateEquipmentCompatibility(exerciseProfile, equipmentProfile)

        val override = equipmentProfile.preferredViewOverride
            ?: return exerciseProfile.cameraProfile.preferredViewClass
        require(override in exerciseProfile.cameraProfile.allowedViewClasses) {
            "EquipmentProfile preferred view must be allowed by ExerciseProfile CameraProfile"
        }
        return override
    }

    private fun resolveSignalParameters(
        exerciseProfile: ExerciseProfile,
        equipmentProfile: EquipmentProfile?,
    ): Map<String, Map<String, Double>> {
        val generic = exerciseProfile.signalProfile.definitions.associate { definition ->
            definition.signalId to definition.parameters.toMap()
        }.toMutableMap()

        if (equipmentProfile == null) return generic.toMap()
        validateEquipmentCompatibility(exerciseProfile, equipmentProfile)

        equipmentProfile.signalParameterOverrides.forEach { (signalId, overrides) ->
            val base = generic[signalId]
                ?: error("EquipmentProfile cannot override undeclared signal $signalId")
            require(overrides.keys.all { it in base }) {
                "EquipmentProfile cannot introduce hidden signal parameters for $signalId"
            }
            generic[signalId] = base + overrides
        }
        return generic.toMap()
    }

    private fun validateEquipmentCompatibility(
        exerciseProfile: ExerciseProfile,
        equipmentProfile: EquipmentProfile,
    ) {
        require(exerciseProfile.exerciseId in equipmentProfile.compatibleExerciseIds) {
            "EquipmentProfile is not compatible with this exercise"
        }
        require(equipmentProfile.equipmentType in exerciseProfile.compatibleEquipmentTypes) {
            "EquipmentProfile equipmentType is not allowed by ExerciseProfile"
        }
    }

    private fun resolveCalibrationBaseline(
        exerciseProfile: ExerciseProfile,
        equipmentProfile: EquipmentProfile?,
        preferredView: ViewClass,
        calibration: PersonalCalibrationProfile?,
    ): ExerciseBaseline? {
        if (calibration == null) return null

        val supportedMetricIds = exerciseProfile.metricProfile.metrics.map { it.metricId }.toSet()
        calibration.exerciseBaselines.forEach { baseline ->
            require(baseline.metricStatistics.keys.all { it in supportedMetricIds }) {
                "PersonalCalibrationProfile cannot add unsupported metrics"
            }
        }

        val exact = calibration.exerciseBaselines.filter { baseline ->
            baseline.key.exerciseProfileId == exerciseProfile.profileId &&
                baseline.key.exerciseProfileVersion == exerciseProfile.profileVersion &&
                baseline.key.equipmentProfileId == equipmentProfile?.profileId &&
                (baseline.key.viewClass == null || baseline.key.viewClass == preferredView)
        }
        require(exact.size <= 1) {
            "PersonalCalibrationProfile contains ambiguous baselines for this analysis context"
        }
        return exact.singleOrNull()
    }
}
