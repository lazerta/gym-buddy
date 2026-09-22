package com.gymbuddy.app

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.engine.*
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.tracking.*

interface CueFeedbackSink { fun onCue(cue:CueEvent); fun clear() {} }
object NoOpCueFeedbackSink: CueFeedbackSink { override fun onCue(cue:CueEvent) = Unit }
class CompositeCueFeedbackSink(private vararg val sinks:CueFeedbackSink):CueFeedbackSink{override fun onCue(cue:CueEvent)=sinks.forEach{it.onCue(cue)};override fun clear()=sinks.forEach{it.clear()}}

data class ProductionFrameResult(val poseFrame:PoseFrame,val pipeline:ProductionPipelineResult,val repCount:Int)

class ProductionPoseFrameProcessor(
    config:AnalysisConfig,
    private val repository:EvidenceRepository?=null,
    private val session:WorkoutSessionRecord?=null,
    private val execution:ExerciseExecutionRecord?=null,
    private val set:SetRecord?=null,
    private val feedback:CueFeedbackSink=NoOpCueFeedbackSink,
    private val pipeline:ProductionMovementPipeline=ProductionMovementPipeline(config),
){
    private var observable=0;private var degraded=0;private var paused=0;private var unknown=0
    private var reps=0;private var assisted=0;private var uncertain=0
    init{
        if(repository!=null){require(session!=null&&execution!=null&&set!=null);repository.ensureSession(session);repository.ensureExecution(execution);repository.openSet(set,config)}
    }
    fun process(frame:PoseFrame,context:TrackingObservationContext=TrackingObservationContext()):ProductionFrameResult{
        val result=pipeline.process(frame,context)
        when(result.tracking.state){TrackingQualityState.OBSERVABLE->observable++;TrackingQualityState.DEGRADED->degraded++;TrackingQualityState.PAUSED->paused++;TrackingQualityState.UNKNOWN->unknown++}
        result.movement.repEvidence.forEach{rep->
            reps++;when(rep.classification){com.gymbuddy.domain.movement.RepClassification.ASSISTED->assisted++;com.gymbuddy.domain.movement.RepClassification.UNCERTAIN->uncertain++;else->Unit}
            val setId=set?.setId
            if(repository!=null&&setId!=null){repository.persistRep(setId,rep);val forms=result.movement.formObservations.filter{it.repId==rep.repId};forms.forEach{repository.persistFormObservation(setId,it)};result.movement.cueEvents.filter{it.repId==rep.repId}.forEach{cue->val obs=forms.firstOrNull{it.ruleId==cue.ruleId};repository.persistCueEvent(setId,cue,obs?.observationId)};result.movement.cueResponses.filter{it.repId==rep.repId}.forEach{repository.persistCueResponse(setId,it)};persistTracking()}
        }
        result.movement.cueEvents.forEach(feedback::onCue)
        return ProductionFrameResult(frame,result,reps)
    }
    fun finishSet(endedAtUs:Long){val r=repository;val s=set;if(r!=null&&s!=null){persistTracking();r.finishSet(SetSummary(s.setId,endedAtUs,reps,assisted,uncertain))};feedback.clear()}
    private fun persistTracking(){val r=repository;val s=set;if(r!=null&&s!=null)r.upsertTrackingSummary(TrackingQualitySummary(s.setId,observable,degraded,paused,unknown))}
}
