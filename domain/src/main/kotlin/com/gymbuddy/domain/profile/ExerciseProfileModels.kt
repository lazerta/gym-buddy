package com.gymbuddy.domain.profile

private fun requireIdentifier(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
}

private fun requireVersion(value: Int, field: String = "profileVersion") {
    require(value > 0) { "$field must be > 0" }
}

private fun requireSemanticHash(value: String) {
    require(value.isNotBlank()) { "semanticHash must not be blank" }
}

private fun requireUnitInterval(value: Double, field: String) {
    require(value.isFinite() && value in 0.0..1.0) { "$field must be finite and within [0, 1]" }
}

interface VersionedProfile {
    val profileId: String
    val profileVersion: Int
    val semanticHash: String
}

data class ProfileVersionRef(
    val profileId: String,
    val profileVersion: Int,
    val semanticHash: String,
) {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
    }

    companion object {
        fun from(profile: VersionedProfile): ProfileVersionRef = ProfileVersionRef(
            profileId = profile.profileId,
            profileVersion = profile.profileVersion,
            semanticHash = profile.semanticHash,
        )
    }
}

enum class MovementFamily {
    PRESS,
    PULL,
    SQUAT,
    HINGE,
    RAISE,
    CURL,
    EXTENSION,
    CORE,
    LOCOMOTION,
    TRANSITION,
    HOLD,
    OTHER,
}

data class ExerciseDefinition(
    val exerciseId: String,
    val definitionVersion: Int,
    val semanticHash: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val movementFamily: MovementFamily,
) {
    init {
        requireIdentifier(exerciseId, "exerciseId")
        requireVersion(definitionVersion, "definitionVersion")
        requireSemanticHash(semanticHash)
        requireIdentifier(displayName, "displayName")
        require(aliases.none { it.isBlank() }) { "aliases must not contain blank values" }
    }
}

enum class ViewClass { FRONT, FRONT_OBLIQUE, SIDE, SIDE_OBLIQUE, REAR, REAR_OBLIQUE }
enum class LensFacing { FRONT, BACK }
enum class CameraGuidanceAction { MOVE_LEFT, MOVE_RIGHT, MOVE_CLOSER, MOVE_FARTHER, RAISE_CAMERA, LOWER_CAMERA, ADJUST_ANGLE, CAMERA_READY, CANNOT_ASSESS }

data class NumericRange(val min: Double, val max: Double) {
    init {
        require(min.isFinite() && max.isFinite()) { "range endpoints must be finite" }
        require(min <= max) { "range min must be <= max" }
    }
    operator fun contains(value: Double): Boolean = value in min..max
}

data class LandmarkRequirement(val landmarkId: String, val minVisibility: Double, val minPresence: Double? = null) {
    init {
        requireIdentifier(landmarkId, "landmarkId")
        requireUnitInterval(minVisibility, "minVisibility")
        minPresence?.let { requireUnitInterval(it, "minPresence") }
    }
}

data class CameraProfile(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val preferredViewClass: ViewClass,
    val allowedViewClasses: Set<ViewClass>,
    val allowedLensFacing: Set<LensFacing>,
    val requiredLandmarks: Set<LandmarkRequirement>,
    val frameFillRange: NumericRange,
    val minVisibleRequiredFraction: Double,
    val maxTrackingGapMs: Long,
    val guidanceActions: Set<CameraGuidanceAction>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(allowedViewClasses.isNotEmpty()) { "allowedViewClasses must not be empty" }
        require(preferredViewClass in allowedViewClasses) { "preferredViewClass must be allowed" }
        require(allowedLensFacing.isNotEmpty()) { "allowedLensFacing must not be empty" }
        require(requiredLandmarks.isNotEmpty()) { "requiredLandmarks must not be empty" }
        require(requiredLandmarks.map { it.landmarkId }.distinct().size == requiredLandmarks.size) { "requiredLandmarks must have unique landmark ids" }
        require(frameFillRange.min >= 0.0 && frameFillRange.max <= 1.0) { "frameFillRange must remain within [0, 1]" }
        requireUnitInterval(minVisibleRequiredFraction, "minVisibleRequiredFraction")
        require(maxTrackingGapMs >= 0L) { "maxTrackingGapMs must be >= 0" }
        require(CameraGuidanceAction.CAMERA_READY in guidanceActions) { "guidanceActions must include CAMERA_READY" }
        require(CameraGuidanceAction.CANNOT_ASSESS in guidanceActions) { "guidanceActions must include CANNOT_ASSESS" }
    }
}

enum class SignalKind { JOINT_ANGLE, NORMALIZED_POINT_DISTANCE, BODY_LOCAL_DISPLACEMENT, VELOCITY, DIRECTION, REVERSAL, PHASE_DWELL, ROM_PROXY, BILATERAL_TIMING, TRAJECTORY_DEVIATION, HOLD_DURATION, CONFIDENCE }
enum class SignalUnit { UNITLESS, NORMALIZED, DEGREES, RADIANS, METERS, METERS_PER_SECOND, MILLISECONDS, SECONDS }

data class SignalDefinition(
    val signalId: String,
    val kind: SignalKind,
    val unit: SignalUnit,
    val requiredLandmarkIds: Set<String>,
    val parameters: Map<String, Double> = emptyMap(),
    val orderedLandmarkIds: List<String> = emptyList(),
) {
    init {
        requireIdentifier(signalId, "signalId")
        require(requiredLandmarkIds.none { it.isBlank() }) { "requiredLandmarkIds must not contain blank values" }
        require(parameters.keys.none { it.isBlank() }) { "signal parameter names must not be blank" }
        require(parameters.values.all { it.isFinite() }) { "signal parameters must be finite" }
        require(orderedLandmarkIds.none { it.isBlank() }) {
            "orderedLandmarkIds must not contain blank values"
        }
        require(orderedLandmarkIds.distinct().size == orderedLandmarkIds.size) {
            "orderedLandmarkIds must be unique"
        }
        require(orderedLandmarkIds.all { it in requiredLandmarkIds }) {
            "orderedLandmarkIds must be a subset of requiredLandmarkIds"
        }
    }
}

data class SignalProfile(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val definitions: List<SignalDefinition>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(definitions.isNotEmpty()) { "SignalProfile must define at least one signal" }
        require(definitions.map { it.signalId }.distinct().size == definitions.size) { "SignalProfile signal ids must be unique" }
    }
}

enum class MovementPrimitive { PRESS, PULL, SQUAT, HINGE, RAISE, CURL, EXTENSION, CORE, LOCOMOTION, TRANSITION, HOLD, UNKNOWN }

data class MovementPrimitiveStep(
    val stepId: String,
    val primitive: MovementPrimitive,
    val progressSignalIds: List<String>,
    val parameters: Map<String, Double> = emptyMap(),
) {
    init {
        requireIdentifier(stepId, "stepId")
        require(primitive != MovementPrimitive.UNKNOWN) { "UNKNOWN cannot be used as a configured movement primitive" }
        require(progressSignalIds.isNotEmpty()) { "progressSignalIds must not be empty" }
        require(progressSignalIds.none { it.isBlank() }) { "progressSignalIds must not contain blank values" }
        require(parameters.keys.none { it.isBlank() }) { "primitive parameter names must not be blank" }
        require(parameters.values.all { it.isFinite() }) { "primitive parameters must be finite" }
    }
}

data class MovementPrimitiveSequence(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val steps: List<MovementPrimitiveStep>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(steps.isNotEmpty()) { "MovementPrimitiveSequence must not be empty" }
        require(steps.map { it.stepId }.distinct().size == steps.size) { "movement primitive step ids must be unique" }
    }
}

data class MetricDefinition(val metricId: String, val sourceSignalIds: Set<String>, val unit: SignalUnit) {
    init {
        requireIdentifier(metricId, "metricId")
        require(sourceSignalIds.isNotEmpty()) { "sourceSignalIds must not be empty" }
        require(sourceSignalIds.none { it.isBlank() }) { "sourceSignalIds must not contain blank values" }
    }
}

data class MetricProfile(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val metrics: List<MetricDefinition>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(metrics.map { it.metricId }.distinct().size == metrics.size) { "metric ids must be unique" }
    }
}

enum class FormRuleSeverity { INFO, MINOR, MAJOR }
data class FormRule(val ruleId: String, val ruleVersion: Int, val evidenceSignalIds: Set<String>, val minConfidence: Double, val severity: FormRuleSeverity) {
    init {
        requireIdentifier(ruleId, "ruleId")
        requireVersion(ruleVersion, "ruleVersion")
        require(evidenceSignalIds.isNotEmpty()) { "evidenceSignalIds must not be empty" }
        require(evidenceSignalIds.none { it.isBlank() }) { "evidenceSignalIds must not contain blank values" }
        requireUnitInterval(minConfidence, "minConfidence")
    }
}

data class FormRuleSet(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val rules: List<FormRule>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(rules.map { it.ruleId }.distinct().size == rules.size) { "form rule ids must be unique" }
    }
}

data class CuePolicy(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val persistenceWindowReps: Int,
    val requiredOccurrences: Int,
    val cooldownMs: Long,
    val maxRepeatedIdenticalCues: Int,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(persistenceWindowReps > 0) { "persistenceWindowReps must be > 0" }
        require(requiredOccurrences in 1..persistenceWindowReps) { "requiredOccurrences must be within persistence window" }
        require(cooldownMs >= 0L) { "cooldownMs must be >= 0" }
        require(maxRepeatedIdenticalCues >= 0) { "maxRepeatedIdenticalCues must be >= 0" }
    }
}

enum class EquipmentType { DUMBBELL, BARBELL, SMITH_MACHINE, SELECTORIZED_MACHINE, CABLE, BODYWEIGHT, OTHER }
data class EquipmentProfile(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val equipmentType: EquipmentType,
    val compatibleExerciseIds: Set<String>,
    val preferredViewOverride: ViewClass? = null,
    val signalParameterOverrides: Map<String, Map<String, Double>> = emptyMap(),
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        require(compatibleExerciseIds.isNotEmpty()) { "compatibleExerciseIds must not be empty" }
        require(compatibleExerciseIds.none { it.isBlank() }) { "compatibleExerciseIds must not contain blank values" }
        require(signalParameterOverrides.keys.none { it.isBlank() }) { "signal override ids must not be blank" }
        signalParameterOverrides.forEach { (_, params) ->
            require(params.keys.none { it.isBlank() }) { "signal override parameter names must not be blank" }
            require(params.values.all { it.isFinite() }) { "signal override values must be finite" }
        }
    }
}

enum class LateralityMode { BILATERAL, UNILATERAL, ALTERNATING }
enum class ProfileCapability { CAMERA_GUIDANCE, REP_DETECTION, FORM_ANALYSIS, BILATERAL_TIMING, ASSISTANCE_CLASSIFICATION }

data class ExerciseProfile(
    override val profileId: String,
    override val profileVersion: Int,
    override val semanticHash: String,
    val exerciseId: String,
    val lateralityMode: LateralityMode,
    val compatibleEquipmentTypes: Set<EquipmentType>,
    val cameraProfile: CameraProfile,
    val signalProfile: SignalProfile,
    val movementPrimitiveSequence: MovementPrimitiveSequence,
    val metricProfile: MetricProfile,
    val formRuleSet: FormRuleSet,
    val cuePolicy: CuePolicy,
    val capabilities: Set<ProfileCapability>,
) : VersionedProfile {
    init {
        requireIdentifier(profileId, "profileId")
        requireVersion(profileVersion)
        requireSemanticHash(semanticHash)
        requireIdentifier(exerciseId, "exerciseId")
        require(compatibleEquipmentTypes.isNotEmpty()) { "compatibleEquipmentTypes must not be empty" }
        require(capabilities.isNotEmpty()) { "capabilities must not be empty" }
        val signalIds = signalProfile.definitions.map { it.signalId }.toSet()
        movementPrimitiveSequence.steps.forEach { step -> require(step.progressSignalIds.all { it in signalIds }) { "movement primitive ${step.stepId} references an undeclared signal" } }
        metricProfile.metrics.forEach { metric -> require(metric.sourceSignalIds.all { it in signalIds }) { "metric ${metric.metricId} references an undeclared signal" } }
        formRuleSet.rules.forEach { rule -> require(rule.evidenceSignalIds.all { it in signalIds }) { "form rule ${rule.ruleId} references an undeclared signal" } }
        require(ProfileCapability.CAMERA_GUIDANCE in capabilities) { "ExerciseProfile must support CAMERA_GUIDANCE" }
        require(ProfileCapability.REP_DETECTION in capabilities) { "ExerciseProfile must support REP_DETECTION" }
    }
}
