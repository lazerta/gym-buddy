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
)

@Entity(tableName="analysis_contexts",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class AnalysisContextEntity(@PrimaryKey val setId:String,val exerciseDefinitionId:String,val exerciseDefinitionVersion:Int,val exerciseDefinitionSemanticHash:String,val exerciseProfileId:String,val exerciseProfileVersion:Int,val exerciseProfileSemanticHash:String,val cameraProfileId:String,val cameraProfileVersion:Int,val cameraProfileSemanticHash:String,val signalProfileId:String,val signalProfileVersion:Int,val signalProfileSemanticHash:String,val primitiveProfileId:String,val primitiveProfileVersion:Int,val primitiveProfileSemanticHash:String,val metricProfileId:String,val metricProfileVersion:Int,val metricProfileSemanticHash:String,val formRuleSetId:String,val formRuleSetVersion:Int,val formRuleSetSemanticHash:String,val cuePolicyId:String,val cuePolicyVersion:Int,val cuePolicySemanticHash:String,val equipmentProfileId:String?,val equipmentProfileVersion:Int?,val equipmentProfileSemanticHash:String?,val calibrationProfileId:String?,val calibrationProfileVersion:Int?,val calibrationProfileSemanticHash:String?)

@Entity(tableName="rep_evidence",foreignKeys=[ForeignKey(entity=SetEntity::class,parentColumns=["setId"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)],indices=[Index("setId"),Index(value=["setId","repOrdinal"],unique=true)])
data class RepEvidenceEntity(@PrimaryKey val repId:String,val setId:String,val repOrdinal:Int,val stepId:String,val primitive:String,val startedAtUs:Long,val completedAtUs:Long,val classification:String)

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
data class WorkoutFlowStateEntity(@PrimaryKey val checkpointId:String,val completedSetId:String,@ColumnInfo(defaultValue="'REST'") val state:String,val focus:String,val plannedNextLoadValue:Double?,val plannedNextLoadUnit:String?,val restStartedAtEpochMs:Long)

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
