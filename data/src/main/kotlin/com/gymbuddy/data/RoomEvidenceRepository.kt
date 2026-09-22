package com.gymbuddy.data

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.*

class RoomEvidenceRepository(private val dao: EvidenceDao) : EvidenceRepository {
    override fun ensureSession(r: WorkoutSessionRecord) {
        val e=dao.session(r.sessionId)
        if(e==null) dao.insertSession(WorkoutSessionEntity(r.sessionId,r.startedAtUs))
        else require(e.startedAtUs==r.startedAtUs)
    }
    override fun ensureExecution(r: ExerciseExecutionRecord) {
        requireNotNull(dao.session(r.sessionId))
        val e=dao.execution(r.executionId)
        if(e==null) dao.insertExecution(ExerciseExecutionEntity(r.executionId,r.sessionId,r.exerciseId,r.startedAtUs))
        else require(e.sessionId==r.sessionId && e.exerciseId==r.exerciseId && e.startedAtUs==r.startedAtUs)
    }
    override fun openSet(r:SetRecord,c:AnalysisConfig){
        requireNotNull(dao.execution(r.executionId))
        val e=dao.set(r.setId); val x=analysisContextEntity(r.setId,c.provenance)
        if(e==null) dao.insertSetWithContext(SetEntity(r.setId,r.executionId,r.setOrdinal,r.startedAtUs),x)
        else { require(e.executionId==r.executionId&&e.setOrdinal==r.setOrdinal&&e.startedAtUs==r.startedAtUs); require(dao.analysisContext(r.setId)==x) }
    }
    override fun persistRep(setId:String,e:RepEvidence){
        val s=requireNotNull(dao.set(setId)); require(dao.analysisContext(setId)?.toAnalysisProvenance()==e.provenance)
        require(e.completedAtUs>=s.startedAtUs); require(dao.rep(e.repId)==null); require(dao.repByOrdinal(setId,e.ordinal)==null)
        val re=RepEvidenceEntity(e.repId,setId,e.ordinal,e.stepId,e.primitive.name,e.startedAtUs,e.completedAtUs,e.classification.name)
        val sig=e.signals.values.map{RepSignalEvidenceEntity(e.repId,it.signalId,it.unit.name,it.min,it.max,it.mean,it.last,it.confidence)}
        val met=e.metrics.values.map{m->when(val v=m.value){is EvidenceValue.Known->RepMetricEvidenceEntity(e.repId,m.metricId,m.unit.name,true,v.value,v.confidence,null);is EvidenceValue.Unknown->RepMetricEvidenceEntity(e.repId,m.metricId,m.unit.name,false,null,null,v.reason)}}
        dao.insertRepBundle(re,sig,met)
    }
    override fun persistFormObservation(setId:String,o:FormObservation){
        require(dao.rep(o.repId)?.setId==setId)
        dao.insertObservation(FormObservationEntity(o.observationId,setId,o.repId,o.ruleId,o.ruleVersion,o.state.name,o.severity.name,o.confidence,o.evidenceValue))
    }
    override fun persistCueEvent(setId:String,c:CueEvent,observationId:String?){
        require(dao.rep(c.repId)?.setId==setId)
        if(observationId!=null){val o=requireNotNull(dao.observation(observationId));require(o.setId==setId&&o.repId==c.repId&&o.ruleId==c.ruleId)}
        dao.insertCue(CueEventEntity(c.cueId,setId,c.repId,observationId,c.ruleId,c.emittedAtUs,c.severity))
    }
    override fun persistCueResponse(setId:String,r:CueResponse){
        val c=requireNotNull(dao.cue(r.cueId)); val rep=requireNotNull(dao.rep(r.repId))
        require(c.setId==setId&&rep.setId==setId&&rep.completedAtUs>c.emittedAtUs)
        dao.insertCueResponse(CueResponseEntity("${r.cueId}:${r.repId}",setId,r.cueId,r.repId,r.state.name))
    }
    override fun upsertTrackingSummary(s:TrackingQualitySummary){
        requireNotNull(dao.set(s.setId)); dao.upsertTrackingSummary(TrackingQualitySummaryEntity(s.setId,s.observableFrames,s.degradedFrames,s.pausedFrames,s.unknownFrames))
    }
    override fun finishSet(s:SetSummary){
        requireNotNull(dao.set(s.setId)); val reps=dao.repsForSet(s.setId)
        require(s.completedReps==reps.size)
        require(s.assistedReps==reps.count{it.classification==RepClassification.ASSISTED.name})
        require(s.uncertainReps==reps.count{it.classification==RepClassification.UNCERTAIN.name})
        dao.upsertSetSummary(SetSummaryEntity(s.setId,s.endedAtUs,s.completedReps,s.assistedReps,s.uncertainReps))
    }
    override fun loadSet(setId:String):PersistedSetEvidence?{
        val s=dao.set(setId)?:return null; val ctx=dao.analysisContext(setId)?:return null
        val reps=dao.repsForSet(setId).map{r->
            val sig=dao.signalsForRep(r.repId).associate{it.signalId to SignalEvidence(it.signalId,SignalUnit.valueOf(it.unit),it.minValue,it.maxValue,it.meanValue,it.lastValue,it.confidence)}
            val met=dao.metricsForRep(r.repId).associate{m->m.metricId to MetricEvidence(m.metricId,SignalUnit.valueOf(m.unit),if(m.known) EvidenceValue.Known(requireNotNull(m.value),m.confidence) else EvidenceValue.Unknown(requireNotNull(m.unknownReason)))}
            RepEvidence(r.repId,r.repOrdinal,r.stepId,MovementPrimitive.valueOf(r.primitive),r.startedAtUs,r.completedAtUs,RepClassification.valueOf(r.classification),sig,met,ctx.toAnalysisProvenance())
        }
        val obs=dao.observationsForSet(setId).map{FormObservation(it.observationId,it.repId,it.ruleId,it.ruleVersion,FormObservationState.valueOf(it.state),FormRuleSeverity.valueOf(it.severity),it.confidence,it.evidenceValue)}
        val cues=dao.cuesForSet(setId).map{CueEvent(it.cueId,it.ruleId,it.repId,it.emittedAtUs,it.severity)}
        val responses=dao.responsesForSet(setId).map{CueResponse(it.cueId,it.repId,CueResponseState.valueOf(it.state))}
        val tr=dao.trackingSummary(setId)?.let{TrackingQualitySummary(it.setId,it.observableFrames,it.degradedFrames,it.pausedFrames,it.unknownFrames)}
        val sum=dao.setSummary(setId)?.let{SetSummary(it.setId,it.endedAtUs,it.completedReps,it.assistedReps,it.uncertainReps)}
        return PersistedSetEvidence(SetRecord(s.setId,s.executionId,s.setOrdinal,s.startedAtUs),ctx.toAnalysisProvenance(),reps,obs,cues,responses,tr,sum)
    }
}
