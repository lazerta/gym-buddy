package com.gymbuddy.data
import androidx.room.*
import com.gymbuddy.domain.persistence.*

@Dao abstract class EvidenceDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertSession(e:WorkoutSessionEntity):Long
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertExecution(e:ExerciseExecutionEntity):Long
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertSet(e:SetEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertAnalysisContext(e:AnalysisContextEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertRep(e:RepEvidenceEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertSignals(e:List<RepSignalEvidenceEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertMetrics(e:List<RepMetricEvidenceEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertPhases(e:List<RepPhaseEvidenceEntity>)
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertInvalidAttempt(e:InvalidAttemptEvidenceEntity):Long
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
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract fun insertPersonalCalibrationHistory(e:PersonalCalibrationProfileHistoryEntity):Long
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertExercisePreference(e:ExercisePreferenceEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertEquipmentContext(e:EquipmentContextEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertWorkoutExerciseCompletion(e:WorkoutExerciseCompletionEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract fun upsertWorkoutProductState(e:WorkoutProductStateEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) abstract fun insertGptAnalysis(e:GptAnalysisEntity)

    @Query("SELECT * FROM workout_sessions WHERE sessionId=:id") abstract fun session(id:String):WorkoutSessionEntity?
    @Query("SELECT * FROM exercise_executions WHERE executionId=:id") abstract fun execution(id:String):ExerciseExecutionEntity?
    @Query("SELECT * FROM sets WHERE setId=:id") abstract fun set(id:String):SetEntity?
    @Query("SELECT * FROM analysis_contexts WHERE setId=:id") abstract fun analysisContext(id:String):AnalysisContextEntity?
    @Query("SELECT * FROM rep_evidence WHERE repId=:id") abstract fun rep(id:String):RepEvidenceEntity?
    @Query("SELECT * FROM rep_evidence WHERE setId=:id AND repOrdinal=:ordinal LIMIT 1") abstract fun repByOrdinal(id:String,ordinal:Int):RepEvidenceEntity?
    @Query("SELECT * FROM rep_evidence WHERE setId=:id ORDER BY repOrdinal, repId") abstract fun repsForSet(id:String):List<RepEvidenceEntity>
    @Query("SELECT * FROM rep_signal_evidence WHERE repId=:id ORDER BY signalId") abstract fun signalsForRep(id:String):List<RepSignalEvidenceEntity>
    @Query("SELECT * FROM rep_metric_evidence WHERE repId=:id ORDER BY metricId") abstract fun metricsForRep(id:String):List<RepMetricEvidenceEntity>
    @Query("SELECT * FROM rep_phase_evidence WHERE repId=:id ORDER BY phaseOrdinal") abstract fun phasesForRep(id:String):List<RepPhaseEvidenceEntity>
    @Query("SELECT * FROM invalid_attempt_evidence WHERE setId=:id ORDER BY startedAtUs, attemptId") abstract fun invalidAttemptsForSet(id:String):List<InvalidAttemptEvidenceEntity>
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
              (ss.endedAtEpochMs=0 AND (SELECT endedAtEpochMs FROM set_summaries WHERE setId=:currentSetId)>0) OR
              ((ss.endedAtEpochMs>0)=((SELECT endedAtEpochMs FROM set_summaries WHERE setId=:currentSetId)>0) AND (
                  (CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END)<:beforeEndedAtEpochMs OR
                  ((CASE WHEN ss.endedAtEpochMs>0 THEN ss.endedAtEpochMs ELSE ss.endedAtUs END)=:beforeEndedAtEpochMs AND s.setId<:currentSetId)
              ))
          )
        ORDER BY (ss.endedAtEpochMs>0) DESC, ss.endedAtEpochMs DESC,
            (CASE WHEN ss.endedAtEpochMs=0 THEN ss.endedAtUs ELSE 0 END) DESC, s.setId DESC
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
              (ws.startedAtEpochMs=0 AND (SELECT startedAtEpochMs FROM workout_sessions WHERE sessionId=:currentSessionId)>0) OR
              ((ws.startedAtEpochMs>0)=((SELECT startedAtEpochMs FROM workout_sessions WHERE sessionId=:currentSessionId)>0) AND (
                  (CASE WHEN ws.startedAtEpochMs>0 THEN ws.startedAtEpochMs ELSE ws.startedAtUs END)<:beforeSessionStartedAtEpochMs OR
                  ((CASE WHEN ws.startedAtEpochMs>0 THEN ws.startedAtEpochMs ELSE ws.startedAtUs END)=:beforeSessionStartedAtEpochMs AND ws.sessionId<:currentSessionId)
              ))
          )
        GROUP BY ws.sessionId
        ORDER BY (ws.startedAtEpochMs>0) DESC, ws.startedAtEpochMs DESC,
            (CASE WHEN ws.startedAtEpochMs=0 THEN ws.startedAtUs ELSE 0 END) DESC, ws.sessionId DESC
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

    @Query("""
        SELECT EXISTS(SELECT 1 FROM interrupted_sets i JOIN sets s ON s.setId=i.setId
            JOIN exercise_executions e ON e.executionId=s.executionId WHERE e.sessionId=:sessionId)
    """)
    abstract fun sessionHasInterruptedAttempt(sessionId:String):Boolean

    /** Reserve an immutable version and publish it atomically. A failed active-slot
     * write must not leave an orphan history version, and concurrent writers cannot
     * publish an active payload different from the historical payload. */
    @Transaction open fun publishPersonalCalibration(e:PersonalCalibrationProfileEntity) {
        val existing=personalCalibrationHistory(e.calibrationProfileId,e.profileVersion)
        if(existing==null){
            val inserted=insertPersonalCalibrationHistory(PersonalCalibrationProfileHistoryEntity(
                e.calibrationProfileId,e.profileVersion,e.semanticHash,e.payload))
            check(inserted!=-1L){"calibration version publication conflict"}
        }else{
            require(existing.semanticHash==e.semanticHash && existing.payload==e.payload){
                "published calibration version is immutable"
            }
        }
        upsertPersonalCalibration(e)
    }

    @Query("SELECT * FROM workout_flow_states WHERE checkpointId=:id") abstract fun workoutFlowState(id:String):WorkoutFlowStateEntity?
    @Query("DELETE FROM workout_flow_states WHERE checkpointId=:id") abstract fun deleteWorkoutFlowState(id:String)
    @Query("DELETE FROM workout_flow_states WHERE checkpointId=:checkpointId AND completedSetId=:setId") abstract fun deleteWorkoutFlowStateForSet(checkpointId:String,setId:String)
    @Query("SELECT * FROM interrupted_sets WHERE setId=:setId") abstract fun interruptedSet(setId:String):InterruptedSetEntity?
    @Query("SELECT * FROM personal_calibration_profiles WHERE slotId=:slotId") abstract fun personalCalibration(slotId:String):PersonalCalibrationProfileEntity?
    @Query("SELECT * FROM personal_calibration_profile_history WHERE calibrationProfileId=:profileId AND profileVersion=:profileVersion") abstract fun personalCalibrationHistory(profileId:String,profileVersion:Int):PersonalCalibrationProfileHistoryEntity?
    @Query("DELETE FROM personal_calibration_profiles WHERE slotId=:slotId") abstract fun deletePersonalCalibration(slotId:String)

    @Query("SELECT * FROM exercise_preferences ORDER BY favorite DESC, lastSelectedAtEpochMs DESC, exerciseId") abstract fun exercisePreferences():List<ExercisePreferenceEntity>
    @Query("SELECT * FROM equipment_contexts ORDER BY updatedAtEpochMs DESC, contextId") abstract fun equipmentContexts():List<EquipmentContextEntity>
    @Query("SELECT * FROM workout_exercise_completions WHERE sessionId=:sessionId ORDER BY completedAtEpochMs, exerciseId") abstract fun workoutCompletions(sessionId:String):List<WorkoutExerciseCompletionEntity>
    @Query("DELETE FROM workout_exercise_completions WHERE sessionId=:sessionId") abstract fun deleteWorkoutCompletions(sessionId:String)
    @Query("SELECT * FROM workout_product_state WHERE slotId=:slotId") abstract fun workoutProductState(slotId:String):WorkoutProductStateEntity?
    @Query("SELECT * FROM gpt_analyses WHERE setId=:setId ORDER BY createdAtEpochMs, analysisId") abstract fun gptAnalysesForSet(setId:String):List<GptAnalysisEntity>

    @Query("SELECT * FROM gpt_analyses WHERE analysisId=:id") abstract fun gptAnalysis(id:String):GptAnalysisEntity?

    @Transaction open fun readProductSnapshot():WorkoutSelectionSnapshot{
        val active=workoutProductState("active")?.activeSessionId
        return WorkoutSelectionSnapshot(active,
            exercisePreferences().map{ExercisePreferenceRecord(it.exerciseId,it.favorite,it.lastSelectedAtEpochMs,it.equipmentContextId)},
            active?.let(::workoutCompletions).orEmpty().map{WorkoutExerciseCompletionRecord(it.sessionId,it.exerciseId,it.completedSets,it.completedAtEpochMs)},
            equipmentContexts().map{EquipmentContextRecord(it.contextId,it.baseEquipmentProfileId,it.label,it.updatedAtEpochMs)})
    }

    @Transaction open fun rememberSelection(record:ExercisePreferenceRecord){
        val current=exercisePreferences().firstOrNull{it.exerciseId==record.exerciseId}
        upsertExercisePreference(ExercisePreferenceEntity(record.exerciseId,current?.favorite?:record.favorite,
            maxOf(current?.lastSelectedAtEpochMs?:0,record.lastSelectedAtEpochMs),
            record.equipmentContextId?:current?.equipmentContextId))
    }

    @Transaction open fun changeFavorite(id:String,favorite:Boolean){
        require(id.isNotBlank())
        val current=exercisePreferences().firstOrNull{it.exerciseId==id}
        upsertExercisePreference(ExercisePreferenceEntity(id,favorite,current?.lastSelectedAtEpochMs?:0,current?.equipmentContextId))
    }

    @Transaction open fun rememberEquipmentForExercise(id:String,record:EquipmentContextRecord){
        require(id.isNotBlank())
        val existing=equipmentContexts().firstOrNull{it.contextId==record.contextId}
        require(existing==null||existing.baseEquipmentProfileId==record.baseEquipmentProfileId){"equipment identity cannot change base profile"}
        upsertEquipmentContext(EquipmentContextEntity(record.contextId,record.baseEquipmentProfileId,record.label,record.updatedAtEpochMs))
        rememberSelection(ExercisePreferenceRecord(id,equipmentContextId=record.contextId))
    }

    @Transaction open fun startNewWorkout(){
        // Roll back the active-session slot too if clearing recovery fails.
        upsertWorkoutProductState(WorkoutProductStateEntity("active",null))
        deleteWorkoutFlowState("active")
    }

    @Transaction open fun completeExerciseFromHistory(sessionId:String,exerciseId:String,epochMs:Long){
        requireNotNull(session(sessionId))
        val count=completedSetIdsForSessionExercise(sessionId,exerciseId).size
        require(count>0){"exercise has no completed sets"}
        val previous=workoutCompletions(sessionId).firstOrNull{it.exerciseId==exerciseId}
        upsertWorkoutExerciseCompletion(WorkoutExerciseCompletionEntity(sessionId,exerciseId,count,
            maxOf(previous?.completedAtEpochMs?:0,epochMs)))
        val checkpoint=workoutFlowState("active")
        val checkpointExecution=checkpoint?.let{set(it.completedSetId)}?.let{execution(it.executionId)}
        if(checkpointExecution?.sessionId==sessionId && checkpointExecution.exerciseId==exerciseId){
            deleteWorkoutFlowState("active")
        }
    }

    @Transaction open fun appendAnalysis(record:GptAnalysisEntity,sourceIds:List<String>){
        require(record.setId in sourceIds)
        sourceIds.forEach{id->
            requireNotNull(set(id)){"missing GPT analysis source set: $id"}
            requireNotNull(setSummary(id)){"GPT analysis source must be completed: $id"}
        }
        val existing=gptAnalysis(record.analysisId)
        if(existing!=null){
            require(existing==record){"published GPT analysis is immutable"}
            return // retry of the same immutable publication, not a second analysis
        }
        insertGptAnalysis(record)
    }

    @Transaction open fun insertSetWithContext(s:SetEntity,c:AnalysisContextEntity){
        insertSet(s)
        insertAnalysisContext(c)
        upsertWorkoutFlowState(WorkoutFlowStateEntity(
            checkpointId="active",
            completedSetId=s.setId,
            state="ACTIVE_SET",
            focus="Active set in progress.",
            plannedNextLoadValue=null,
            plannedNextLoadUnit=null,
            plannedNextLoadBasis="UNKNOWN",
            plannedNextLoadSource="UNKNOWN",
            restStartedAtEpochMs=0L,
        ))
    }
    @Transaction open fun insertRepBundle(r:RepEvidenceEntity,s:List<RepSignalEvidenceEntity>,m:List<RepMetricEvidenceEntity>,p:List<RepPhaseEvidenceEntity> = emptyList()){
        insertRep(r);if(s.isNotEmpty())insertSignals(s);if(m.isNotEmpty())insertMetrics(m);if(p.isNotEmpty())insertPhases(p)
    }
    @Transaction open fun insertCompletedRepBundle(r:RepEvidenceEntity,s:List<RepSignalEvidenceEntity>,m:List<RepMetricEvidenceEntity>,p:List<RepPhaseEvidenceEntity>,o:List<FormObservationEntity>,c:List<CueEventEntity>,responses:List<CueResponseEntity>){
        insertRepBundle(r,s,m,p)
        if(o.isNotEmpty())insertObservations(o)
        if(c.isNotEmpty())insertCues(c)
        if(responses.isNotEmpty())insertCueResponses(responses)
    }
    @Transaction open fun markInterruptedAndClearFlow(e:InterruptedSetEntity,checkpointId:String){
        upsertInterruptedSet(e)
        deleteWorkoutFlowStateForSet(checkpointId,e.setId)
    }
}
