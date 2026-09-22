package com.gymbuddy.domain

import java.security.MessageDigest

internal object SemanticHashing {
    fun exerciseProfileHash(profile: ExerciseProfile): String = hash(
        listOf(
            "exerciseId=${profile.exerciseId}",
            "profileId=${profile.profileId}",
            "lateralityMode=${profile.lateralityMode}",
            "camera=${camera(profile.cameraProfile)}",
            "signals=${signalProfile(profile.signalProfile)}",
            "primitives=${primitiveSequence(profile.movementPrimitiveSequence)}",
            "metrics=${metricProfile(profile.metricProfile)}",
            "rules=${formRuleSet(profile.formRuleSet)}",
            "cue=${cuePolicy(profile.cuePolicy)}",
            "equipmentKinds=${sortedEnums(profile.supportedEquipmentKinds)}",
            "capabilities=${profile.capabilities.sorted().joinToString(",")}",
        )
    )

    fun equipmentProfileHash(
        equipmentProfileId: String,
        equipmentKind: EquipmentKind,
        compatibleExerciseProfileIds: Set<String>,
        preferredViewClassOverride: ViewClass?,
        signalParameterOverrides: Map<String, Map<String, Double>>,
    ): String = hash(
        listOf(
            "equipmentProfileId=$equipmentProfileId",
            "equipmentKind=$equipmentKind",
            "compatible=${compatibleExerciseProfileIds.sorted().joinToString(",")}",
            "viewOverride=${preferredViewClassOverride ?: "null"}",
            "signalOverrides=${nestedDoubleMap(signalParameterOverrides)}",
        )
    )

    fun personalCalibrationHash(
        sourceConfidence: Double?,
        normalizedBodyGeometry: Map<String, Double?>,
        cameraSetupPreferences: Map<String, CameraSetupPreference>,
        exerciseBaselines: List<ExerciseBaseline>,
        equipmentAssociations: Map<String, String>,
        lateralityBaseline: Map<String, Double?>,
        cueEffectiveness: Map<String, Double>,
        evidenceReferences: List<String>,
    ): String = hash(
        listOf(
            "sourceConfidence=${numberOrNull(sourceConfidence)}",
            "bodyGeometry=${nullableDoubleMap(normalizedBodyGeometry)}",
            "cameraPreferences=${cameraSetupPreferences.toSortedMap().entries.joinToString(";") { (k, v) -> "$k:${v.preferredViewClass}:${numberOrNull(v.preferredFrameFill)}" }}",
            "baselines=${exerciseBaselines.sortedWith(compareBy<ExerciseBaseline> { it.exerciseProfileId }.thenBy { it.compatibleProfileVersion }.thenBy { it.equipmentProfileId ?: "" }.thenBy { it.viewClass?.name ?: "" }).joinToString(";") { baseline(it) }}",
            "equipmentAssociations=${equipmentAssociations.toSortedMap().entries.joinToString(";") { "${it.key}:${it.value}" }}",
            "laterality=${nullableDoubleMap(lateralityBaseline)}",
            "cueEffectiveness=${doubleMap(cueEffectiveness)}",
            "evidence=${evidenceReferences.sorted().joinToString(",")}",
        )
    )

    private fun camera(profile: CameraProfile): String = listOf(
        profile.cameraProfileId,
        profile.version.toString(),
        profile.preferredViewClass.name,
        sortedEnums(profile.allowedViewClasses),
        sortedEnums(profile.allowedLensFacing),
        profile.requiredLandmarks.sorted().joinToString(","),
        normalized(profile.minRequiredVisibleFraction),
        normalized(profile.minLandmarkConfidence),
        normalized(profile.minIdentityMargin),
        normalized(profile.minFrameFill),
        normalized(profile.maxFrameFill),
    ).joinToString("|")

    private fun signalProfile(profile: SignalProfile): String = listOf(
        profile.signalProfileId,
        profile.version.toString(),
        profile.signalDefinitions.sortedBy { it.signalId }.joinToString(";") {
            listOf(
                it.signalId,
                it.kind.name,
                it.unit.name,
                it.requiredLandmarks.sorted().joinToString(","),
                normalized(it.minimumConfidence),
                doubleMap(it.parameters),
            ).joinToString(":")
        },
    ).joinToString("|")

    private fun primitiveSequence(sequence: MovementPrimitiveSequence): String = listOf(
        sequence.sequenceId,
        sequence.version.toString(),
        sequence.primitives.joinToString(";") {
            listOf(
                it.primitiveId,
                it.type.name,
                it.progressSignalId,
                doubleMap(it.parameters),
            ).joinToString(":")
        },
    ).joinToString("|")

    private fun metricProfile(profile: MetricProfile): String = listOf(
        profile.metricProfileId,
        profile.version.toString(),
        profile.metricDefinitions.sortedBy { it.metricId }.joinToString(";") {
            "${it.metricId}:${it.sourceSignalIds.sorted().joinToString(",")}:${it.unit}"
        },
    ).joinToString("|")

    private fun formRuleSet(ruleSet: FormRuleSet): String = listOf(
        ruleSet.formRuleSetId,
        ruleSet.version.toString(),
        ruleSet.rules.sortedBy { it.ruleId }.joinToString(";") {
            listOf(
                it.ruleId,
                it.version.toString(),
                it.sourceSignalIds.sorted().joinToString(","),
                it.comparator.name,
                normalized(it.threshold),
                numberOrNull(it.upperThreshold),
                normalized(it.minimumConfidence),
                it.severity.name,
                it.cueKey,
            ).joinToString(":")
        },
    ).joinToString("|")

    private fun cuePolicy(policy: CuePolicy): String = listOf(
        policy.cuePolicyId,
        policy.version.toString(),
        policy.persistenceWindowReps.toString(),
        policy.requiredHitsInWindow.toString(),
        policy.cooldownReps.toString(),
        policy.maxImmediateCues.toString(),
    ).joinToString("|")

    private fun baseline(value: ExerciseBaseline): String = listOf(
        value.exerciseProfileId,
        value.compatibleProfileVersion.toString(),
        value.equipmentProfileId ?: "null",
        value.viewClass?.name ?: "null",
        value.metricBaselines.toSortedMap().entries.joinToString(",") { (key, metric) ->
            "$key=${normalized(metric.median)}:${numberOrNull(metric.medianAbsoluteDeviation)}:${metric.sampleCount}:${metric.sessionCount}:${normalized(metric.confidence)}"
        },
    ).joinToString("|")

    private fun nestedDoubleMap(value: Map<String, Map<String, Double>>): String =
        value.toSortedMap().entries.joinToString(";") { (key, nested) -> "$key{${doubleMap(nested)}}" }

    private fun doubleMap(value: Map<String, Double>): String =
        value.toSortedMap().entries.joinToString(",") { "${it.key}=${normalized(it.value)}" }

    private fun nullableDoubleMap(value: Map<String, Double?>): String =
        value.toSortedMap().entries.joinToString(",") { "${it.key}=${numberOrNull(it.value)}" }

    private fun numberOrNull(value: Double?): String = value?.let(::normalized) ?: "null"

    private fun normalized(value: Double): String = java.lang.Double.toHexString(value)

    private fun <T : Enum<T>> sortedEnums(values: Set<T>): String =
        values.map { it.name }.sorted().joinToString(",")

    private fun hash(parts: List<String>): String {
        val canonical = parts.joinToString(separator = "\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
