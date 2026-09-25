package com.gymbuddy.data

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*

class RoomEvidenceRepository(private val dao:EvidenceDao):EvidenceRepository {
    override fun ensureSession(r:WorkoutSessionRecord){
        val e=dao.session(r.sessionId)
        if(e==null){
            dao.insertSession(WorkoutSessionEntity(r.sessionId,r.startedAtUs,r.startedAtEpochMs))
        }else{
            require(e.startedAtUs==r.startedAtUs&&e.startedAtEpochMs==r.startedAtEpochMs)
        }
    }
    override fun ensureExecution(r:ExerciseExecutionRecord){
        requireNotNull(dao.session(r.sessionId))
        val e=dao.execution(r.executionId)
        if(e==null){
            dao.insertExecution(
                ExerciseExecutionEntity(
                    r.executionId,r.sessionId,r.exerciseId,r.startedAtUs,r.startedAtEpochMs,
                    r.plannedExerciseId,r.equipmentContextId
                )
            )
        }else{
            require(
                e.sessionId==r.sessionId&&
                    e.exerciseId==r.exerciseId&&
                    e.startedAtUs==r.startedAtUs&&
                    e.startedAtEpochMs==r.startedAtEpochMs&&
                    e.plannedExerciseId==r.plannedExerciseId&&
                    e.equipmentContextId==r.equipmentContextId
            )
        }
    }
    override fun openSet(r:SetRecord,c:AnalysisConfig){
        requireNotNull(dao.execution(r.executionId))
        val e=dao.set(r.setId)
        val x=analysisContextEntity(r.setId,c.provenance)
        if(e==null){
            dao.insertSetWithContext(
                SetEntity(
                    r.setId,r.executionId,r.setOrdinal,r.startedAtUs,
                    r.actualLoad?.value,r.actualLoad?.unit,r.startedAtEpochMs,
                    r.actualLoad?.basis?.name?:LoadBasis.UNKNOWN.name,
                    r.actualLoad?.source?.name?:LoadSource.UNKNOWN.name,
                    r.plannedLoad?.value,r.plannedLoad?.unit,
                    r.plannedLoad?.basis?.name?:LoadBasis.UNKNOWN.name,
                    r.plannedLoad?.source?.name?:LoadSource.UNKNOWN.name,
                ),
                x,
            )
        }else{
            require(
                e.executionId==r.executionId&&
                    e.setOrdinal==r.setOrdinal&&
                    e.startedAtUs==r.startedAtUs&&
                    e.startedAtEpochMs==r.startedAtEpochMs&&
                    loadSnapshot(e.actualLoadValue,e.actualLoadUnit,e.actualLoadBasis,e.actualLoadSource)==r.actualLoad&&
                    loadSnapshot(e.plannedLoadValue,e.plannedLoadUnit,e.plannedLoadBasis,e.plannedLoadSource)==r.plannedLoad
            )
            require(dao.analysisContext(r.setId)==x)
        }
    }

    override fun persistRep(setId:String,e:RepEvidence){val bundle=repEntities(setId,e);dao.insertRepBundle(bundle.rep,bundle.signals,bundle.metrics,bundle.phases)}
    override fun persistFormObservation(setId:String,o:FormObservation){require(dao.rep(o.repId)?.setId==setId);dao.insertObservation(observationEntity(setId,o))}
    override fun persistCueEvent(setId:String,c:CueEvent,observationId:String?){validateCue(setId,c,observationId,emptyList());dao.insertCue(cueEntity(setId,c,observationId))}
    override fun persistCueResponse(setId:String,r:CueResponse){dao.insertCueResponse(responseEntity(setId,r,emptyList(),null,null))}
    override fun persistCueDelivery(record:CueDeliveryRecord){
        requireNotNull(dao.cue(record.cueId))
        val current=dao.cueDelivery(record.cueId)?.let{CueDeliveryState.valueOf(it.state)}
        if(current!=null&&current!=CueDeliveryState.STARTED){
            require(current==record.state){"terminal cue delivery state cannot regress"}
            return
        }
        dao.upsertCueDelivery(CueDeliveryEntity(record.cueId,record.state.name))
    }

    override fun persistInvalidAttempt(setId:String,attempt:InvalidAttemptEvidence){
        requireNotNull(dao.set(setId))
        val inserted=dao.insertInvalidAttempt(
            InvalidAttemptEvidenceEntity(
                attempt.attemptId,setId,attempt.stepId,attempt.primitive.name,
                attempt.startedAtUs,attempt.endedAtUs,attempt.reason.name,attempt.minConfidence,
            )
        )
        if(inserted==-1L){
            val existing=dao.invalidAttemptsForSet(setId).singleOrNull{it.attemptId==attempt.attemptId}
            require(existing!=null&&existing.reason==attempt.reason.name&&existing.startedAtUs==attempt.startedAtUs&&existing.endedAtUs==attempt.endedAtUs){
                "persisted invalid attempt is immutable"
            }
        }
    }

    override fun persistCompletedRepBundle(setId:String,evidence:RepEvidence,observations:List<FormObservation>,cues:List<CueEvidenceLink>,responses:List<CueResponse>){
        val bundle=repEntities(setId,evidence)
        require(observations.all{it.repId==evidence.repId})
        val observationEntities=observations.map{observationEntity(setId,it)}
        val cueEntities=cues.map{link->validateCue(setId,link.cue,link.observationId,observations);cueEntity(setId,link.cue,link.observationId)}
        val responseEntities=responses.map{responseEntity(setId,it,cues.map{link->link.cue},evidence.repId,evidence.completedAtUs)}
        dao.insertCompletedRepBundle(
            bundle.rep,bundle.signals,bundle.metrics,bundle.phases,
            observationEntities,cueEntities,responseEntities,
        )
    }

    override fun upsertTrackingSummary(s:TrackingQualitySummary){
        requireNotNull(dao.set(s.setId))
        dao.upsertTrackingSummary(
            TrackingQualitySummaryEntity(
                setId=s.setId,
                observableFrames=s.observableFrames,
                degradedFrames=s.degradedFrames,
                pausedFrames=s.pausedFrames,
                unknownFrames=s.unknownFrames,
                activeObservableFrames=s.activeObservableFrames,
                activeDegradedFrames=s.activeDegradedFrames,
                activePausedFrames=s.activePausedFrames,
                activeUnknownFrames=s.activeUnknownFrames,
                interruptionEpisodes=s.interruptionEpisodes,
                cameraDisturbanceEpisodes=s.cameraDisturbanceEpisodes,
                observedViewClass=s.observedViewClass?.name,
                activeFrameFillMean=s.activeFrameFillMean,
            )
        )
    }
    override fun finishSet(s:SetSummary){
        requireNotNull(dao.set(s.setId))
        val reps=dao.repsForSet(s.setId)
        require(s.completedReps==reps.size)
        require(s.assistedReps==reps.count{it.classification==RepClassification.ASSISTED.name})
        require(s.uncertainReps==reps.count{it.classification==RepClassification.UNCERTAIN.name})
        dao.upsertSetSummary(
            SetSummaryEntity(
                s.setId,s.endedAtUs,s.completedReps,s.assistedReps,s.uncertainReps,
                s.endedAtEpochMs,
            )
        )
    }

    override fun loadSet(setId:String):PersistedSetEvidence?{
        val s=dao.set(setId)?:return null
        val ctx=dao.analysisContext(setId)?:return null
        val reps=dao.repsForSet(setId).map{r->
            val sig=dao.signalsForRep(r.repId).associate{
                it.signalId to SignalEvidence(
                    it.signalId,SignalUnit.valueOf(it.unit),it.minValue,it.maxValue,
                    it.meanValue,it.lastValue,it.confidence
                )
            }
            val met=dao.metricsForRep(r.repId).associate{m->
                m.metricId to MetricEvidence(
                    m.metricId,
                    SignalUnit.valueOf(m.unit),
                    if(m.known)EvidenceValue.Known(requireNotNull(m.value),m.confidence)
                    else EvidenceValue.Unknown(requireNotNull(m.unknownReason)),
                )
            }
            val phases=dao.phasesForRep(r.repId).map{phase->
                RepPhaseInterval(
                    PrimitivePhase.valueOf(phase.phase),phase.startedAtUs,phase.endedAtUs,phase.confidence
                )
            }
            RepEvidence(
                r.repId,r.repOrdinal,r.stepId,MovementPrimitive.valueOf(r.primitive),
                r.startedAtUs,r.completedAtUs,RepClassification.valueOf(r.classification),
                sig,met,ctx.toAnalysisProvenance(),
                phaseIntervals=phases,
                assistanceAssessment=AssistanceAssessmentState.valueOf(r.assistanceAssessment),
                assistanceScore=r.assistanceScore,
            )
        }
        val obs=dao.observationsForSet(setId).map{
            FormObservation(
                it.observationId,it.repId,it.ruleId,it.ruleVersion,
                FormObservationState.valueOf(it.state),FormRuleSeverity.valueOf(it.severity),
                it.confidence,it.evidenceValue
            )
        }
        val cueRows=dao.cuesForSet(setId)
        val cues=cueRows.map{CueEvent(it.cueId,it.ruleId,it.repId,it.emittedAtUs,it.severity)}
        val cueObservationIds=cueRows.associate{it.cueId to it.observationId}
        val responses=dao.responsesForSet(setId).map{
            CueResponse(
                it.cueId,it.repId,
                if(it.state=="PERSISTED")CueResponseState.UNCHANGED else CueResponseState.valueOf(it.state)
            )
        }
        val deliveries=dao.deliveriesForSet(setId).map{
            CueDeliveryRecord(it.cueId,CueDeliveryState.valueOf(it.state))
        }
        val tr=dao.trackingSummary(setId)?.let{
            TrackingQualitySummary(
                setId=it.setId,
                observableFrames=it.observableFrames,
                degradedFrames=it.degradedFrames,
                pausedFrames=it.pausedFrames,
                unknownFrames=it.unknownFrames,
                activeObservableFrames=it.activeObservableFrames,
                activeDegradedFrames=it.activeDegradedFrames,
                activePausedFrames=it.activePausedFrames,
                activeUnknownFrames=it.activeUnknownFrames,
                interruptionEpisodes=it.interruptionEpisodes,
                cameraDisturbanceEpisodes=it.cameraDisturbanceEpisodes,
                observedViewClass=it.observedViewClass?.let(ViewClass::valueOf),
                activeFrameFillMean=it.activeFrameFillMean,
            )
        }
        val sum=dao.setSummary(setId)?.let{
            SetSummary(
                it.setId,it.endedAtUs,it.completedReps,it.assistedReps,it.uncertainReps,
                it.endedAtEpochMs,
            )
        }
        return PersistedSetEvidence(
            SetRecord(
                s.setId,s.executionId,s.setOrdinal,s.startedAtUs,
                loadSnapshot(s.actualLoadValue,s.actualLoadUnit,s.actualLoadBasis,s.actualLoadSource),s.startedAtEpochMs,
                loadSnapshot(s.plannedLoadValue,s.plannedLoadUnit,s.plannedLoadBasis,s.plannedLoadSource),
            ),
            ctx.toAnalysisProvenance(),reps,obs,cues,responses,tr,sum,deliveries,cueObservationIds,
            dao.invalidAttemptsForSet(setId).map{row->
                InvalidAttemptEvidence(
                    row.attemptId,row.stepId,MovementPrimitive.valueOf(row.primitive),
                    row.startedAtUs,row.endedAtUs,RepInvalidReason.valueOf(row.reason),row.minConfidence,
                )
            },
        )
    }

    private fun loadSnapshot(
        value:Double?,unit:String?,basis:String=LoadBasis.UNKNOWN.name,source:String=LoadSource.UNKNOWN.name,
    ):LoadSnapshot?{
        require(value!=null||unit==null)
        return value?.let{LoadSnapshot(it,unit,LoadBasis.valueOf(basis),LoadSource.valueOf(source))}
    }

    private data class RepEntities(
        val rep:RepEvidenceEntity,
        val signals:List<RepSignalEvidenceEntity>,
        val metrics:List<RepMetricEvidenceEntity>,
        val phases:List<RepPhaseEvidenceEntity>,
    )
    private fun repEntities(setId:String,e:RepEvidence):RepEntities{
        val s=requireNotNull(dao.set(setId))
        require(dao.analysisContext(setId)?.toAnalysisProvenance()==e.provenance)
        require(e.completedAtUs>=s.startedAtUs)
        require(dao.rep(e.repId)==null)
        require(dao.repByOrdinal(setId,e.ordinal)==null)
        val re=RepEvidenceEntity(
            e.repId,setId,e.ordinal,e.stepId,e.primitive.name,
            e.startedAtUs,e.completedAtUs,e.classification.name,
            e.assistanceAssessment.name,e.assistanceScore,
        )
        val sig=e.signals.values.map{
            RepSignalEvidenceEntity(
                e.repId,it.signalId,it.unit.name,it.min,it.max,it.mean,it.last,it.confidence
            )
        }
        val met=e.metrics.values.map{m->
            when(val v=m.value){
                is EvidenceValue.Known->RepMetricEvidenceEntity(
                    e.repId,m.metricId,m.unit.name,true,v.value,v.confidence,null
                )
                is EvidenceValue.Unknown->RepMetricEvidenceEntity(
                    e.repId,m.metricId,m.unit.name,false,null,null,v.reason
                )
            }
        }
        val phases=e.phaseIntervals.mapIndexed{index,phase->
            RepPhaseEvidenceEntity(
                e.repId,index,phase.phase.name,phase.startedAtUs,phase.endedAtUs,phase.confidence
            )
        }
        return RepEntities(re,sig,met,phases)
    }
    private fun observationEntity(setId:String,o:FormObservation)=FormObservationEntity(
        o.observationId,setId,o.repId,o.ruleId,o.ruleVersion,o.state.name,
        o.severity.name,o.confidence,o.evidenceValue
    )
    private fun cueEntity(setId:String,c:CueEvent,observationId:String?)=CueEventEntity(
        c.cueId,setId,c.repId,observationId,c.ruleId,c.emittedAtUs,c.severity
    )
    private fun validateCue(
        setId:String,
        c:CueEvent,
        observationId:String?,
        pendingObservations:List<FormObservation>,
    ){
        require(
            dao.rep(c.repId)?.setId==setId||
                pendingObservations.any{it.repId==c.repId}
        )
        if(observationId!=null){
            val pending=pendingObservations.firstOrNull{it.observationId==observationId}
            val stored=dao.observation(observationId)
            val o=pending?:stored?.let{
                FormObservation(
                    it.observationId,it.repId,it.ruleId,it.ruleVersion,
                    FormObservationState.valueOf(it.state),FormRuleSeverity.valueOf(it.severity),
                    it.confidence,it.evidenceValue
                )
            }?:error("missing observation")
            require(o.repId==c.repId&&o.ruleId==c.ruleId)
        }
    }
    private fun responseEntity(
        setId:String,
        r:CueResponse,
        pendingCues:List<CueEvent>,
        pendingRepId:String?,
        pendingRepCompletedAtUs:Long?,
    ):CueResponseEntity{
        val c=dao.cue(r.cueId)
        val pending=pendingCues.firstOrNull{it.cueId==r.cueId}
        val storedRep=dao.rep(r.repId)
        val repBelongs=storedRep?.setId==setId||r.repId==pendingRepId
        require((c?.setId==setId||pending!=null)&&repBelongs)
        val emittedAt=c?.emittedAtUs?:pending!!.emittedAtUs
        val completedAt=storedRep?.completedAtUs?:requireNotNull(pendingRepCompletedAtUs)
        require(completedAt>emittedAt)
        return CueResponseEntity(
            r.cueId+":"+r.repId,setId,r.cueId,r.repId,r.state.name
        )
    }
}
