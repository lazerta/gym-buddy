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
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertObservations(e:List<FormObservationEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCue(e:CueEventEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCues(e:List<CueEventEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCueResponse(e:CueResponseEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertCueResponses(e:List<CueResponseEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertCueDelivery(e:CueDeliveryEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertTrackingSummary(e:TrackingQualitySummaryEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertSetSummary(e:SetSummaryEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertWorkoutFlowState(e:WorkoutFlowStateEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertInterruptedSet(e:InterruptedSetEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertPersonalCalibration(e:PersonalCalibrationProfileEntity)

    @Query("SELECT * FROM workout_sessions WHERE sessionId=:id") abstract fun session(id:String):WorkoutSessionEntity?
    @Query("SELECT * FROM exercise_executions WHERE executionId=:id") abstract fun execution(id:String):ExerciseExecutionEntity?
    @Query("SELECT * FROM sets WHERE setId=:id") abstract fun set(id:String):SetEntity?
    @Query("SELECT * FROM analysis_contexts WHERE setId=:id") abstract fun analysisContext(id:String):AnalysisContextEntity?
    @Query("SELECT * FROM rep_evidence WHERE repId=:id") abstract fun rep(id:String):RepEvidenceEntity?
    @Query("SELECT * FROM rep_evidence WHERE setId=:id AND repOrdinal=:ordinal LIMIT 1") abstract fun repByOrdinal(id:String,ordinal:Int):RepEvidenceEntity?
    @Query("SELECT * FROM rep_evidence WHERE setId=:id ORDER BY repOrdinal, repId") abstract fun repsForSet(id:String):List<RepEvidenceEntity>
    @Query("SELECT * FROM rep_signal_evidence WHERE repId=:id ORDER BY signalId") abstract fun signalsForRep(id:String):List<RepSignalEvidenceEntity>
    @Query("SELECT * FROM rep_metric_evidence WHERE repId=:id ORDER BY metricId") abstract fun metricsForRep(id:String):List<RepMetricEvidenceEntity>
    @Query("SELECT * FROM form_observations WHERE observationId=:id") abstract fun observation(id:String):FormObservationEntity?
    @Query("SELECT f.* FROM form_observations f JOIN rep_evidence r ON r.repId=f.repId WHERE f.setId=:id ORDER BY r.repOrdinal, f.observationId") abstract fun observationsForSet(id:String):List<FormObservationEntity>
    @Query("SELECT * FROM cue_events WHERE cueId=:id") abstract fun cue(id:String):CueEventEntity?
    @Query("SELECT * FROM cue_deliveries WHERE cueId=:id") abstract fun cueDelivery(id:String):CueDeliveryEntity?
    @Query("SELECT * FROM cue_events WHERE setId=:id ORDER BY emittedAtUs, cueId") abstract fun cuesForSet(id:String):List<CueEventEntity>
    @Query("SELECT c.* FROM cue_responses c JOIN rep_evidence r ON r.repId=c.repId WHERE c.setId=:id ORDER BY r.repOrdinal, c.responseId") abstract fun responsesForSet(id:String):List<CueResponseEntity>
    @Query("SELECT d.* FROM cue_deliveries d JOIN cue_events c ON c.cueId=d.cueId WHERE c.setId=:id ORDER BY c.emittedAtUs, d.cueId") abstract fun deliveriesForSet(id:String):List<CueDeliveryEntity>
    @Query("SELECT * FROM tracking_quality_summaries WHERE setId=:id") abstract fun trackingSummary(id:String):TrackingQualitySummaryEntity?
    @Query("SELECT * FROM set_summaries WHERE setId=:id") abstract fun setSummary(id:String):SetSummaryEntity?

    @Query("""
        SELECT s.setId
        FROM sets s
        JOIN exercise_executions e ON e.executionId=s.executionId
        JOIN set_summaries ss ON ss.setId=s.setId
        WHERE e.exerciseId=:exerciseId
          AND s.setId!=:currentSetId
          AND (
              (CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END)<:beforeEndedAtEpochMs OR
              ((CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END)=:beforeEndedAtEpochMs AND s.setId<:currentSetId)
          )
        ORDER BY (CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END) DESC, s.setId DESC
        LIMIT :limit
    """)
    abstract fun recentComparableSetIds(
        exerciseId:String,
        currentSetId:String,
        beforeEndedAtEpochMs:Long,
        limit:Int,
    ):List<String>

    @Query("""
        SELECT ws.sessionId
        FROM workout_sessions ws
        JOIN exercise_executions e ON e.sessionId=ws.sessionId
        JOIN sets s ON s.executionId=e.executionId
        JOIN set_summaries ss ON ss.setId=s.setId
        WHERE e.exerciseId=:exerciseId
          AND ws.sessionId!=:currentSessionId
          AND (
              (CASE WHEN ws.startedAtEpochMs>0 THEN ws.startedAtEpochMs ELSE ws.startedAtUs END)<:beforeSessionStartedAtEpochMs OR
              ((CASE WHEN ws.startedAtEpochMs>0 THEN ws.startedAtEpochMs ELSE ws.startedAtUs END)=:beforeSessionStartedAtEpochMs AND ws.sessionId<:currentSessionId)
          )
        GROUP BY ws.sessionId
        ORDER BY (CASE WHEN ws.startedAtEpochMs>0 THEN ws.startedAtEpochMs ELSE ws.startedAtUs END) DESC, ws.sessionId DESC
        LIMIT :limit
    """)
    abstract fun recentComparableSessionIds(
        exerciseId:String,
        currentSessionId:String,
        beforeSessionStartedAtEpochMs:Long,
        limit:Int,
    ):List<String>

    @Query("""
        SELECT s.setId
        FROM sets s
        JOIN exercise_executions e ON e.executionId=s.executionId
        JOIN set_summaries ss ON ss.setId=s.setId
        WHERE e.sessionId=:sessionId
          AND e.exerciseId=:exerciseId
        ORDER BY (CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END), s.setOrdinal, s.setId
    """)
    abstract fun completedSetIdsForSessionExercise(
        sessionId:String,
        exerciseId:String,
    ):List<String>

    @Query("SELECT * FROM workout_flow_states WHERE checkpointId=:id") abstract fun workoutFlowState(id:String):WorkoutFlowStateEntity?
    @Query("DELETE FROM workout_flow_states WHERE checkpointId=:id") abstract fun deleteWorkoutFlowState(id:String)
    @Query("DELETE FROM workout_flow_states WHERE checkpointId=:checkpointId AND completedSetId=:setId") abstract fun deleteWorkoutFlowStateForSet(checkpointId:String,setId:String)
    @Query("SELECT * FROM interrupted_sets WHERE setId=:setId") abstract fun interruptedSet(setId:String):InterruptedSetEntity?
    @Query("SELECT * FROM personal_calibration_profiles WHERE slotId=:slotId") abstract fun personalCalibration(slotId:String):PersonalCalibrationProfileEntity?
    @Query("DELETE FROM personal_calibration_profiles WHERE slotId=:slotId") abstract fun deletePersonalCalibration(slotId:String)

    @Transaction open fun insertSetWithContext(s:SetEntity,c:AnalysisContextEntity){insertSet(s);insertAnalysisContext(c)}
    @Transaction open fun insertRepBundle(r:RepEvidenceEntity,s:List<RepSignalEvidenceEntity>,m:List<RepMetricEvidenceEntity>){insertRep(r);if(s.isNotEmpty())insertSignals(s);if(m.isNotEmpty())insertMetrics(m)}
    @Transaction open fun insertCompletedRepBundle(r:RepEvidenceEntity,s:List<RepSignalEvidenceEntity>,m:List<RepMetricEvidenceEntity>,o:List<FormObservationEntity>,c:List<CueEventEntity>,responses:List<CueResponseEntity>){
        insertRepBundle(r,s,m)
        if(o.isNotEmpty())insertObservations(o)
        if(c.isNotEmpty())insertCues(c)
        if(responses.isNotEmpty())insertCueResponses(responses)
    }
    @Transaction open fun markInterruptedAndClearFlow(e:InterruptedSetEntity,checkpointId:String){
        upsertInterruptedSet(e)
        deleteWorkoutFlowStateForSet(checkpointId,e.setId)
    }
}
