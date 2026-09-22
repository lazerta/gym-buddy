package com.gymbuddy.domain

data class ExerciseDefinition(
    val exerciseId: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val movementFamily: MovementFamily,
) {
    init {
        require(exerciseId.isNotBlank()) { "exerciseId is required" }
        require(displayName.isNotBlank()) { "displayName is required" }
        require(aliases.none { it.isBlank() }) { "aliases cannot contain blank values" }
    }
}

data class CameraProfile(
    val cameraProfileId: String,
    val version: Int,
    val preferredViewClass: ViewClass,
    val allowedViewClasses: Set<ViewClass>,
    val allowedLensFacing: Set<LensFacing>,
    val requiredLandmarks: Set<String>,
    val minRequiredVisibleFraction: Double,
    val minLandmarkConfidence: Double,
    val minIdentityMargin: Double,
    val minFrameFill: Double,
    val maxFrameFill: Double,
) {
    init {
        require(cameraProfileId.isNotBlank()) { "cameraProfileId is required" }
        require(version > 0) { "camera profile version must be > 0" }
        require(allowedViewClasses.isNotEmpty()) { "at least one view class is required" }
        require(preferredViewClass in allowedViewClasses) {
            "preferred view must be one of the allowed view classes"
        }
        require(allowedLensFacing.isNotEmpty()) { "at least one lens facing is required" }
        require(requiredLandmarks.isNotEmpty()) { "requiredLandmarks cannot be empty" }
        require(requiredLandmarks.none { it.isBlank() }) { "requiredLandmarks cannot contain blank IDs" }
        require(minRequiredVisibleFraction in 0.0..1.0) {
            "minRequiredVisibleFraction must be in [0, 1]"
        }
        require(minLandmarkConfidence in 0.0..1.0) {
            "minLandmarkConfidence must be in [0, 1]"
        }
        require(minIdentityMargin in 0.0..1.0) {
            "minIdentityMargin must be in [0, 1]"
        }
        require(minFrameFill in 0.0..1.0 && maxFrameFill in 0.0..1.0) {
            "frame fill bounds must be in [0, 1]"
        }
        require(minFrameFill < maxFrameFill) {
            "minFrameFill must be < maxFrameFill"
        }
    }
}

data class SignalDefinition(
    val signalId: String,
    val kind: SignalKind,
    val unit: MetricUnit,
    val requiredLandmarks: Set<String>,
    val minimumConfidence: Double,
    val parameters: Map<String, Double> = emptyMap(),
) {
    init {
        require(signalId.isNotBlank()) { "signalId is required" }
        require(requiredLandmarks.isNotEmpty()) { "signal $signalId requires landmarks" }
        require(requiredLandmarks.none { it.isBlank() }) {
            "signal $signalId contains blank landmark IDs"
        }
        require(minimumConfidence in 0.0..1.0) {
            "signal $signalId minimumConfidence must be in [0, 1]"
        }
        require(parameters.keys.none { it.isBlank() }) {
            "signal $signalId parameter keys cannot be blank"
        }
        require(parameters.values.all { it.isFinite() }) {
            "signal $signalId parameters must be finite"
        }
    }
}

data class SignalProfile(
    val signalProfileId: String,
    val version: Int,
    val signalDefinitions: List<SignalDefinition>,
) {
    init {
        require(signalProfileId.isNotBlank()) { "signalProfileId is required" }
        require(version > 0) { "signal profile version must be > 0" }
        require(signalDefinitions.isNotEmpty()) { "at least one signal is required" }
        requireUnique(signalDefinitions.map { it.signalId }, "signalId")
    }

    fun signal(signalId: String): SignalDefinition? =
        signalDefinitions.firstOrNull { it.signalId == signalId }
}

data class MovementPrimitiveSpec(
    val primitiveId: String,
    val type: MovementPrimitiveType,
    val progressSignalId: String,
    val parameters: Map<String, Double>,
) {
    init {
        require(primitiveId.isNotBlank()) { "primitiveId is required" }
        require(type != MovementPrimitiveType.UNKNOWN) { "UNKNOWN is not a valid configured primitive" }
        require(progressSignalId.isNotBlank()) { "progressSignalId is required" }
        require(parameters.isNotEmpty()) {
            "primitive $primitiveId must declare behavior parameters explicitly"
        }
        require(parameters.keys.none { it.isBlank() }) {
            "primitive parameter keys cannot be blank"
        }
        require(parameters.values.all { it.isFinite() }) {
            "primitive parameters must be finite"
        }
    }
}

data class MovementPrimitiveSequence(
    val sequenceId: String,
    val version: Int,
    val primitives: List<MovementPrimitiveSpec>,
) {
    init {
        require(sequenceId.isNotBlank()) { "sequenceId is required" }
        require(version > 0) { "movement primitive sequence version must be > 0" }
        require(primitives.isNotEmpty()) { "movement primitive sequence cannot be empty" }
        requireUnique(primitives.map { it.primitiveId }, "primitiveId")
    }
}

data class MetricDefinition(
    val metricId: String,
    val sourceSignalIds: Set<String>,
    val unit: MetricUnit,
) {
    init {
        require(metricId.isNotBlank()) { "metricId is required" }
        require(sourceSignalIds.isNotEmpty()) { "metric $metricId must declare source signals" }
        require(sourceSignalIds.none { it.isBlank() }) {
            "metric $metricId contains blank signal IDs"
        }
    }
}

data class MetricProfile(
    val metricProfileId: String,
    val version: Int,
    val metricDefinitions: List<MetricDefinition>,
) {
    init {
        require(metricProfileId.isNotBlank()) { "metricProfileId is required" }
        require(version > 0) { "metric profile version must be > 0" }
        requireUnique(metricDefinitions.map { it.metricId }, "metricId")
    }

    fun metricIds(): Set<String> = metricDefinitions.mapTo(linkedSetOf()) { it.metricId }
}

data class FormRule(
    val ruleId: String,
    val version: Int,
    val sourceSignalIds: Set<String>,
    val comparator: RuleComparator,
    val threshold: Double,
    val upperThreshold: Double? = null,
    val minimumConfidence: Double,
    val severity: CueSeverity,
    val cueKey: String,
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId is required" }
        require(version > 0) { "form rule version must be > 0" }
        require(sourceSignalIds.isNotEmpty()) { "form rule $ruleId must declare source signals" }
        require(sourceSignalIds.none { it.isBlank() }) {
            "form rule $ruleId contains blank signal IDs"
        }
        require(threshold.isFinite()) { "form rule threshold must be finite" }
        require(upperThreshold == null || upperThreshold.isFinite()) {
            "form rule upperThreshold must be finite when present"
        }
        if (comparator == RuleComparator.OUTSIDE_RANGE) {
            require(upperThreshold != null && threshold < upperThreshold) {
                "OUTSIDE_RANGE requires threshold < upperThreshold"
            }
        }
        require(minimumConfidence in 0.0..1.0) {
            "form rule minimumConfidence must be in [0, 1]"
        }
        require(cueKey.isNotBlank()) { "cueKey is required" }
    }
}

data class FormRuleSet(
    val formRuleSetId: String,
    val version: Int,
    val rules: List<FormRule>,
) {
    init {
        require(formRuleSetId.isNotBlank()) { "formRuleSetId is required" }
        require(version > 0) { "form rule set version must be > 0" }
        requireUnique(rules.map { it.ruleId }, "ruleId")
    }
}

data class CuePolicy(
    val cuePolicyId: String,
    val version: Int,
    val persistenceWindowReps: Int,
    val requiredHitsInWindow: Int,
    val cooldownReps: Int,
    val maxImmediateCues: Int = 1,
) {
    init {
        require(cuePolicyId.isNotBlank()) { "cuePolicyId is required" }
        require(version > 0) { "cue policy version must be > 0" }
        require(persistenceWindowReps > 0) { "persistenceWindowReps must be > 0" }
        require(requiredHitsInWindow in 1..persistenceWindowReps) {
            "requiredHitsInWindow must be within persistenceWindowReps"
        }
        require(cooldownReps >= 0) { "cooldownReps must be >= 0" }
        require(maxImmediateCues == 1) {
            "canonical policy permits exactly one major immediate cue at a time"
        }
    }
}

