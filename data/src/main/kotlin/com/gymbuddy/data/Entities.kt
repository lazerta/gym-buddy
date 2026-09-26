package com.gymbuddy.data
import androidx.room.*

@Entity(tableName="workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val sessionId:String,
    val startedAtUs:Long,
    @ColumnInfo(defaultValue="0") val startedAtEpochMs:Long=0L,
)

@Entity(
    tableName="exercise_executions",
    foreignKeys=[ForeignKey(entity=WorkoutSessionEntity::class,parentColumns=["sessionId"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("sessionId")],
)
data class ExerciseExecutionEntity(
    @PrimaryKey val executionId:String,
    val sessionId:String,
    val exerciseId:String,
    val startedAtUs:Long,
    @ColumnInfo(defaultValue="0") val startedAtEpochMs:Long=0L,
    val plannedExerciseId:String?=null,
    val equipmentContextId:String?=null,
)

@Entity(
    tableName="sets",
    foreignKeys=[ForeignKey(entity=ExerciseExecutionEntity::class,parentColumns=["executionId"],childColumns=["executionId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("executionId"),Index(value=["executionId","setOrdinal"],unique=true)],
)
data class SetEntity(
    @PrimaryKey val setId:String,
    val executionId:String,
    val setOrdinal:Int,
    val startedAtUs:Long,
    val actualLoadValue:Double?,
    val actualLoadUnit:String?,
    @ColumnInfo(defaultValue="0") val startedAtEpochMs:Long=0L,
    @ColumnInfo(defaultValue="'UNKNOWN'") val actualLoadBasis:String="UNKNOWN",
    @ColumnInfo(defaultValue="'UNKNOWN'") val actualLoadSource:String="UNKNOWN",
    val plannedLoadValue:Double?=null,
    val plannedLoadUnit:String?=null,
    @ColumnInfo(defaultValue="'UNKNOWN'") val plannedLoadBasis:String="UNKNOWN",
    @ColumnInfo(defaultValue="'UNKNOWN'") val plannedLoadSource:String="UNKNOWN",
)

@Entity(tableName="analysis_contexts",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class AnalysisContextEntity(@PrimaryKey val setId:String,val exerciseDefinitionId:String,val exerciseDefinitionVersion:Int,val exerciseDefinitionSemanticHash:String,val exerciseProfileId:String,val exerciseProfileVersion:Int,val exerciseProfileSemanticHash:String,val cameraProfileId:String,val cameraProfileVersion:Int,val cameraProfileSemanticHash:String,val signalProfileId:String,val signalProfileVersion:Int,val signalProfileSemanticHash:String,val primitiveProfileId:String,val primitiveProfileVersion:Int,val primitiveProfileSemanticHash:String,val metricProfileId:String,val metricProfileVersion:Int,val metricProfileSemanticHash:String,val formRuleSetId:String,val formRuleSetVersion:Int,val formRuleSetSemanticHash:String,val cuePolicyId:String,val cuePolicyVersion:Int,val cuePolicySemanticHash:String,val equipmentProfileId:String?,val equipmentProfileVersion:Int?,val equipmentProfileSemanticHash:String?,val calibrationProfileId:String?,val calibrationProfileVersion:Int?,val calibrationProfileSemanticHash:String?)

@Entity(tableName="rep_evidence",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],indices=[Index("setId"),Index(value=["setId","repOrdinal"],unique=true)])
data class RepEvidenceEntity(
    @PrimaryKey val repId:String,
    val setId:String,
    val repOrdinal:Int,
    val stepId:String,
    val primitive:String,
    val startedAtUs:Long,
    val completedAtUs:Long,
    val classification:String,
    @ColumnInfo(defaultValue="'NOT_ASSESSED'") val assistanceAssessment:String="NOT_ASSESSED",
    val assistanceScore:Double?=null,
)

@Entity(tableName="rep_signal_evidence",primaryKeys=["repId","signalId"],foreignKeys=[ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE)],indices=[Index("repId")])
data class RepSignalEvidenceEntity(val repId:String,val signalId:String,val unit:String,val minValue:Double?,val maxValue:Double?,val meanValue:Double?,val lastValue:Double?,val confidence:Double?)

@Entity(tableName="rep_metric_evidence",primaryKeys=["repId","metricId"],foreignKeys=[ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE)],indices=[Index("repId")])
data class RepMetricEvidenceEntity(val repId:String,val metricId:String,val unit:String,val known:Boolean,val value:Double?,val confidence:Double?,val unknownReason:String?)

@Entity(tableName="form_observations",foreignKeys=[ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],indices=[Index("repId"),Index("setId")])
data class FormObservationEntity(@PrimaryKey val observationId:String,val setId:String,val repId:String,val ruleId:String,val ruleVersion:Int,val state:String,val severity:String,val confidence:Double?,val evidenceValue:Double?)

@Entity(tableName="cue_events",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE)],indices=[Index("setId"),Index("repId")])
data class CueEventEntity(@PrimaryKey val cueId:String,val setId:String,val repId:String,val observationId:String?,val ruleId:String,val emittedAtUs:Long,val severity:String)

@Entity(tableName="cue_responses",foreignKeys=[ForeignKey(entity=CueEventEntity::class,parentColumns=["cueId"],childColumns=["cueId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],indices=[Index("cueId"),Index("repId"),Index("setId")])
data class CueResponseEntity(@PrimaryKey val responseId:String,val setId:String,val cueId:String,val repId:String,val state:String)

@Entity(tableName="cue_deliveries",foreignKeys=[ForeignKey(entity=CueEventEntity::class,parentColumns=["cueId"],childColumns=["cueId"],onDelete=ForeignKey.CASCADE)])
data class CueDeliveryEntity(@PrimaryKey val cueId:String,val state:String)

@Entity(tableName="tracking_quality_summaries",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class TrackingQualitySummaryEntity(
    @PrimaryKey val setId:String,
    val observableFrames:Int,
    val degradedFrames:Int,
    val pausedFrames:Int,
    val unknownFrames:Int,
    @ColumnInfo(defaultValue="0") val activeObservableFrames:Int=0,
    @ColumnInfo(defaultValue="0") val activeDegradedFrames:Int=0,
    @ColumnInfo(defaultValue="0") val activePausedFrames:Int=0,
    @ColumnInfo(defaultValue="0") val activeUnknownFrames:Int=0,
    @ColumnInfo(defaultValue="0") val interruptionEpisodes:Int=0,
    @ColumnInfo(defaultValue="0") val cameraDisturbanceEpisodes:Int=0,
    val observedViewClass:String?=null,
    val activeFrameFillMean:Double?=null,
)

@Entity(tableName="set_summaries",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class SetSummaryEntity(
    @PrimaryKey val setId:String,
    val endedAtUs:Long,
    val completedReps:Int,
    val assistedReps:Int,
    val uncertainReps:Int,
    @ColumnInfo(defaultValue="0") val endedAtEpochMs:Long=0L,
)

@Entity(tableName="workout_flow_states",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["completedSetId"],onDelete=ForeignKey.CASCADE)],indices=[Index("completedSetId")])
data class WorkoutFlowStateEntity(
    @PrimaryKey val checkpointId:String,
    val completedSetId:String,
    @ColumnInfo(defaultValue="'REST'") val state:String,
    val focus:String,
    val plannedNextLoadValue:Double?,
    val plannedNextLoadUnit:String?,
    @ColumnInfo(defaultValue="'UNKNOWN'") val plannedNextLoadBasis:String="UNKNOWN",
    @ColumnInfo(defaultValue="'UNKNOWN'") val plannedNextLoadSource:String="UNKNOWN",
    val restStartedAtEpochMs:Long,
)

@Entity(tableName="interrupted_sets",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class InterruptedSetEntity(@PrimaryKey val setId:String,val recoveredAtEpochMs:Long,val committedReps:Int)

@Entity(tableName="personal_calibration_profiles")
data class PersonalCalibrationProfileEntity(@PrimaryKey val slotId:String,val calibrationProfileId:String,val profileVersion:Int,val semanticHash:String,val payload:String)

@Entity(
    tableName="personal_calibration_profile_history",
    primaryKeys=["calibrationProfileId","profileVersion"],
)
data class PersonalCalibrationProfileHistoryEntity(
    val calibrationProfileId:String,
    val profileVersion:Int,
    val semanticHash:String,
    val payload:String,
)


@Entity(
    tableName="rep_phase_evidence",
    primaryKeys=["repId","phaseOrdinal"],
    foreignKeys=[ForeignKey(entity=RepEvidenceEntity::class,parentColumns=["repId"],childColumns=["repId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("repId")],
)
data class RepPhaseEvidenceEntity(
    val repId:String,
    val phaseOrdinal:Int,
    val phase:String,
    val startedAtUs:Long,
    val endedAtUs:Long,
    val confidence:Double?,
)

@Entity(
    tableName="invalid_attempt_evidence",
    foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("setId")],
)
data class InvalidAttemptEvidenceEntity(
    @PrimaryKey val attemptId:String,
    val setId:String,
    val stepId:String,
    val primitive:String,
    val startedAtUs:Long,
    val endedAtUs:Long,
    val reason:String,
    val minConfidence:Double?,
)

@Entity(tableName="exercise_preferences")
data class ExercisePreferenceEntity(
    @PrimaryKey val exerciseId:String,
    val favorite:Boolean,
    val lastSelectedAtEpochMs:Long,
    val equipmentContextId:String?,
)

@Entity(tableName="equipment_contexts")
data class EquipmentContextEntity(
    @PrimaryKey val contextId:String,
    val baseEquipmentProfileId:String,
    val label:String,
    val updatedAtEpochMs:Long,
)

@Entity(
    tableName="workout_exercise_completions",
    primaryKeys=["sessionId","exerciseId"],
    indices=[Index("sessionId")],
)
data class WorkoutExerciseCompletionEntity(
    val sessionId:String,
    val exerciseId:String,
    val completedSets:Int,
    val completedAtEpochMs:Long,
)

@Entity(tableName="workout_product_state")
data class WorkoutProductStateEntity(
    @PrimaryKey val slotId:String,
    val activeSessionId:String?,
)

@Entity(
    tableName="gpt_analyses",
    foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("setId"),Index(value=["setId","createdAtEpochMs"])],
)
data class GptAnalysisEntity(
    @PrimaryKey val analysisId:String,
    val setId:String,
    val schemaVersion:Int,
    val modelLabel:String,
    val createdAtEpochMs:Long,
    val sourceSetIdsPayload:String,
    val summary:String,
    val recommendationsPayload:String,
)
