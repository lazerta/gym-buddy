package com.gymbuddy.data
import androidx.room.*
@Dao abstract class EvidenceDao {
@Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertSession(e:WorkoutSessionEntity):Long
@Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertExecution(e:ExerciseExecutionEntity):Long
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertSet(e:SetEntity)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertAnalysisContext(e:AnalysisContextEntity)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertRep(e:RepEvidenceEntity)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertSignals(e:List<RepSignalEvidenceEntity>)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertMetrics(e:List<RepMetricEvidenceEntity>)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertObservation(e:FormObservationEntity)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCue(e:CueEventEntity)
@Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCueResponse(e:CueResponseEntity)
@Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertTrackingSummary(e:TrackingQualitySummaryEntity)
@Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertSetSummary(e:SetSummaryEntity)
@Query("SELECT * FROM workout_sessions WHERE sessionId=:id") abstract fun session(id:String):WorkoutSessionEntity?
@Query("SELECT * FROM exercise_executions WHERE executionId=:id") abstract fun execution(id:String):ExerciseExecutionEntity?
@Query("SELECT * FROM sets WHERE setId=:id") abstract fun set(id:String):SetEntity?
@Query("SELECT * FROM analysis_contexts WHERE setId=:id") abstract fun analysisContext(id:String):AnalysisContextEntity?
@Query("SELECT * FROM rep_evidence WHERE repId=:id") abstract fun rep(id:String):RepEvidenceEntity?
@Query("SELECT * FROM rep_evidence WHERE setId=:id AND repOrdinal=:ordinal LIMIT 1") abstract fun repByOrdinal(id:String,ordinal:Int):RepEvidenceEntity?
@Query("SELECT * FROM rep_evidence WHERE setId=:id ORDER BY repOrdinal") abstract fun repsForSet(id:String):List<RepEvidenceEntity>
@Query("SELECT * FROM rep_signal_evidence WHERE repId=:id") abstract fun signalsForRep(id:String):List<RepSignalEvidenceEntity>
@Query("SELECT * FROM rep_metric_evidence WHERE repId=:id") abstract fun metricsForRep(id:String):List<RepMetricEvidenceEntity>
@Query("SELECT * FROM form_observations WHERE observationId=:id") abstract fun observation(id:String):FormObservationEntity?
@Query("SELECT * FROM form_observations WHERE setId=:id") abstract fun observationsForSet(id:String):List<FormObservationEntity>
@Query("SELECT * FROM cue_events WHERE cueId=:id") abstract fun cue(id:String):CueEventEntity?
@Query("SELECT * FROM cue_events WHERE setId=:id") abstract fun cuesForSet(id:String):List<CueEventEntity>
@Query("SELECT * FROM cue_responses WHERE setId=:id") abstract fun responsesForSet(id:String):List<CueResponseEntity>
@Query("SELECT * FROM tracking_quality_summaries WHERE setId=:id") abstract fun trackingSummary(id:String):TrackingQualitySummaryEntity?
@Query("SELECT * FROM set_summaries WHERE setId=:id") abstract fun setSummary(id:String):SetSummaryEntity?
@Transaction open fun insertSetWithContext(s:SetEntity,c:AnalysisContextEntity){insertSet(s);insertAnalysisContext(c)}
@Transaction open fun insertRepBundle(r:RepEvidenceEntity,s:List<RepSignalEvidenceEntity>,m:List<RepMetricEvidenceEntity>){insertRep(r);if(s.isNotEmpty())insertSignals(s);if(m.isNotEmpty())insertMetrics(m)}
}