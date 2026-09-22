package com.gymbuddy.domain.profile

private fun requireIdentifier(value: String, field: String) { require(value.isNotBlank()) { "$field must not be blank" } }
private fun requireVersion(value: Int, field: String = "profileVersion") { require(value > 0) { "$field must be > 0" } }
private fun requireSemanticHash(value: String) { require(value.isNotBlank()) { "semanticHash must not be blank" } }
private fun requireUnitInterval(value: Double, field: String) { require(value.isFinite() && value in 0.0..1.0) { "$field must be finite and within [0, 1]" } }

interface VersionedProfile { val profileId:String; val profileVersion:Int; val semanticHash:String }
data class ProfileVersionRef(val profileId:String,val profileVersion:Int,val semanticHash:String){init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash)};companion object{fun from(profile:VersionedProfile)=ProfileVersionRef(profile.profileId,profile.profileVersion,profile.semanticHash)}}

enum class MovementFamily { PRESS,PULL,SQUAT,HINGE,RAISE,CURL,EXTENSION,CORE,LOCOMOTION,TRANSITION,HOLD,OTHER }
data class ExerciseDefinition(val exerciseId:String,val definitionVersion:Int,val semanticHash:String,val displayName:String,val aliases:Set<String> = emptySet(),val movementFamily:MovementFamily){init{requireIdentifier(exerciseId,"exerciseId");requireVersion(definitionVersion,"definitionVersion");requireSemanticHash(semanticHash);requireIdentifier(displayName,"displayName");require(aliases.none{it.isBlank()})}}

enum class ViewClass { FRONT,FRONT_OBLIQUE,SIDE,SIDE_OBLIQUE,REAR,REAR_OBLIQUE }
enum class LensFacing { FRONT,BACK }
enum class CameraGuidanceAction { MOVE_LEFT,MOVE_RIGHT,MOVE_CLOSER,MOVE_FARTHER,RAISE_CAMERA,LOWER_CAMERA,ADJUST_ANGLE,CAMERA_READY,CANNOT_ASSESS }
data class NumericRange(val min:Double,val max:Double){init{require(min.isFinite()&&max.isFinite());require(min<=max)};operator fun contains(value:Double)=value in min..max}
data class LandmarkRequirement(val landmarkId:String,val minVisibility:Double,val minPresence:Double?=null){init{requireIdentifier(landmarkId,"landmarkId");requireUnitInterval(minVisibility,"minVisibility");minPresence?.let{requireUnitInterval(it,"minPresence")}}}
data class CameraProfile(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val preferredViewClass:ViewClass,val allowedViewClasses:Set<ViewClass>,val allowedLensFacing:Set<LensFacing>,val requiredLandmarks:Set<LandmarkRequirement>,val frameFillRange:NumericRange,val minVisibleRequiredFraction:Double,val maxTrackingGapMs:Long,val guidanceActions:Set<CameraGuidanceAction>):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(allowedViewClasses.isNotEmpty());require(preferredViewClass in allowedViewClasses);require(allowedLensFacing.isNotEmpty());require(requiredLandmarks.isNotEmpty());require(requiredLandmarks.map{it.landmarkId}.distinct().size==requiredLandmarks.size);require(frameFillRange.min>=0.0&&frameFillRange.max<=1.0);requireUnitInterval(minVisibleRequiredFraction,"minVisibleRequiredFraction");require(maxTrackingGapMs>=0);require(CameraGuidanceAction.CAMERA_READY in guidanceActions);require(CameraGuidanceAction.CANNOT_ASSESS in guidanceActions)}}

enum class SignalKind { JOINT_ANGLE,NORMALIZED_POINT_DISTANCE,BODY_LOCAL_DISPLACEMENT,VELOCITY,DIRECTION,REVERSAL,PHASE_DWELL,ROM_PROXY,BILATERAL_TIMING,TRAJECTORY_DEVIATION,HOLD_DURATION,CONFIDENCE }
enum class SignalUnit { UNITLESS,NORMALIZED,DEGREES,RADIANS,METERS,METERS_PER_SECOND,MILLISECONDS,SECONDS }
data class SignalDefinition(val signalId:String,val kind:SignalKind,val unit:SignalUnit,val requiredLandmarkIds:Set<String>,val parameters:Map<String,Double> = emptyMap(),val orderedLandmarkIds:List<String> = emptyList()){
    init{
        requireIdentifier(signalId,"signalId");require(requiredLandmarkIds.none{it.isBlank()});require(parameters.keys.none{it.isBlank()});require(parameters.values.all{it.isFinite()});require(orderedLandmarkIds.none{it.isBlank()});require(orderedLandmarkIds.distinct().size==orderedLandmarkIds.size);require(orderedLandmarkIds.all{it in requiredLandmarkIds})
        if(kind==SignalKind.JOINT_ANGLE&&unit==SignalUnit.NORMALIZED){require(parameters.containsKey("scale")||parameters.containsKey("value_scale")){"normalized JOINT_ANGLE requires an explicit scale"}}
    }
}
data class SignalProfile(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val definitions:List<SignalDefinition>):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(definitions.isNotEmpty());require(definitions.map{it.signalId}.distinct().size==definitions.size)}}

enum class MovementPrimitive { PRESS,PULL,SQUAT,HINGE,RAISE,CURL,EXTENSION,CORE,LOCOMOTION,TRANSITION,HOLD,UNKNOWN }
data class MovementPrimitiveStep(val stepId:String,val primitive:MovementPrimitive,val progressSignalIds:List<String>,val parameters:Map<String,Double> = emptyMap()){init{requireIdentifier(stepId,"stepId");require(primitive!=MovementPrimitive.UNKNOWN);require(progressSignalIds.isNotEmpty());require(progressSignalIds.none{it.isBlank()});require(parameters.keys.none{it.isBlank()});require(parameters.values.all{it.isFinite()})}}
data class MovementPrimitiveSequence(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val steps:List<MovementPrimitiveStep>):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(steps.isNotEmpty());require(steps.map{it.stepId}.distinct().size==steps.size)}}

enum class MetricAggregation { MEAN,MIN,MAX,RANGE,ABS_DIFFERENCE,LAST,CROSSING_TIME_DIFFERENCE }
data class MetricDefinition(val metricId:String,val sourceSignalIds:Set<String>,val unit:SignalUnit,val aggregation:MetricAggregation = MetricAggregation.MEAN,val parameters:Map<String,Double> = emptyMap()){
    init{requireIdentifier(metricId,"metricId");require(sourceSignalIds.isNotEmpty());require(sourceSignalIds.none{it.isBlank()});require(parameters.keys.none{it.isBlank()});require(parameters.values.all{it.isFinite()});if(aggregation==MetricAggregation.CROSSING_TIME_DIFFERENCE){require(sourceSignalIds.size==2);require(parameters["threshold"]?.isFinite()==true)}}
}
data class MetricProfile(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val metrics:List<MetricDefinition>):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(metrics.map{it.metricId}.distinct().size==metrics.size)}}

enum class FormRuleSeverity { INFO,MINOR,MAJOR }
enum class FormComparison { MAX_VALUE,MIN_VALUE,MAX_ABS_DIFFERENCE,RANGE_AT_MOST }
data class FormRule(val ruleId:String,val ruleVersion:Int,val evidenceSignalIds:Set<String>,val minConfidence:Double,val severity:FormRuleSeverity,val comparison:FormComparison = FormComparison.MAX_VALUE,val threshold:Double?=null){init{requireIdentifier(ruleId,"ruleId");requireVersion(ruleVersion,"ruleVersion");require(evidenceSignalIds.isNotEmpty());require(evidenceSignalIds.none{it.isBlank()});requireUnitInterval(minConfidence,"minConfidence");threshold?.let{require(it.isFinite())}}}
data class FormRuleSet(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val rules:List<FormRule>):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(rules.map{it.ruleId}.distinct().size==rules.size)}}

data class CuePolicy(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val persistenceWindowReps:Int,val requiredOccurrences:Int,val cooldownMs:Long,val maxRepeatedIdenticalCues:Int):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(persistenceWindowReps>0);require(requiredOccurrences in 1..persistenceWindowReps);require(cooldownMs>=0);require(maxRepeatedIdenticalCues>=0)}}

enum class EquipmentType { DUMBBELL,BARBELL,SMITH_MACHINE,SELECTORIZED_MACHINE,CABLE,BODYWEIGHT,OTHER }
data class EquipmentProfile(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val equipmentType:EquipmentType,val compatibleExerciseIds:Set<String>,val preferredViewOverride:ViewClass?=null,val signalParameterOverrides:Map<String,Map<String,Double>> = emptyMap()):VersionedProfile{init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);require(compatibleExerciseIds.isNotEmpty());require(compatibleExerciseIds.none{it.isBlank()});require(signalParameterOverrides.keys.none{it.isBlank()});signalParameterOverrides.values.forEach{p->require(p.keys.none{it.isBlank()});require(p.values.all{it.isFinite()})}}}

enum class LateralityMode { BILATERAL,UNILATERAL,ALTERNATING }
enum class ProfileCapability { CAMERA_GUIDANCE,REP_DETECTION,FORM_ANALYSIS,BILATERAL_TIMING,ASSISTANCE_CLASSIFICATION }
data class ExerciseProfile(override val profileId:String,override val profileVersion:Int,override val semanticHash:String,val exerciseId:String,val lateralityMode:LateralityMode,val compatibleEquipmentTypes:Set<EquipmentType>,val cameraProfile:CameraProfile,val signalProfile:SignalProfile,val movementPrimitiveSequence:MovementPrimitiveSequence,val metricProfile:MetricProfile,val formRuleSet:FormRuleSet,val cuePolicy:CuePolicy,val capabilities:Set<ProfileCapability>):VersionedProfile{
    init{requireIdentifier(profileId,"profileId");requireVersion(profileVersion);requireSemanticHash(semanticHash);requireIdentifier(exerciseId,"exerciseId");require(compatibleEquipmentTypes.isNotEmpty());require(capabilities.isNotEmpty());val signalIds=signalProfile.definitions.map{it.signalId}.toSet();movementPrimitiveSequence.steps.forEach{s->require(s.progressSignalIds.all{it in signalIds})};metricProfile.metrics.forEach{m->require(m.sourceSignalIds.all{it in signalIds})};formRuleSet.rules.forEach{r->require(r.evidenceSignalIds.all{it in signalIds})};require(ProfileCapability.CAMERA_GUIDANCE in capabilities);require(ProfileCapability.REP_DETECTION in capabilities);if(ProfileCapability.BILATERAL_TIMING in capabilities){require(metricProfile.metrics.any{it.aggregation==MetricAggregation.CROSSING_TIME_DIFFERENCE}){"BILATERAL_TIMING capability requires a timing metric"}}}
}
