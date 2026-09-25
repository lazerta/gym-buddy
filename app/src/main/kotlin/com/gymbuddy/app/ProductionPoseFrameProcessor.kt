package com.gymbuddy.app

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.engine.*
import com.gymbuddy.domain.evidence.FormObservation
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.tracking.*

interface CueFeedbackSink {
    fun onCue(cue:CueEvent)
    fun clear() {}
}
object NoOpCueFeedbackSink: CueFeedbackSink {
    override fun onCue(cue:CueEvent)=Unit
}
class CompositeCueFeedbackSink(
    private vararg val sinks:CueFeedbackSink,
):CueFeedbackSink{
    override fun onCue(cue:CueEvent)=sinks.forEach{it.onCue(cue)}
    override fun clear()=sinks.forEach{it.clear()}
}

data class ProductionFrameResult(
    val poseFrame:PoseFrame,
    val pipeline:ProductionPipelineResult,
    val repCount:Int,
)

class ProductionPoseFrameProcessor(
    config:AnalysisConfig,
    private val repository:EvidenceRepository?=null,
    private val session:WorkoutSessionRecord?=null,
    private val execution:ExerciseExecutionRecord?=null,
    private val set:SetRecord?=null,
    private val feedback:CueFeedbackSink=NoOpCueFeedbackSink,
    private val pipeline:ProductionMovementPipeline=ProductionMovementPipeline(
        config,
        evidenceIdNamespace=set?.setId,
    ),
){
    private var observable=0
    private var degraded=0
    private var paused=0
    private var unknown=0

    private var activeObservable=0
    private var activeDegraded=0
    private var activePaused=0
    private var activeUnknown=0
    private var interruptionEpisodes=0
    private var cameraDisturbanceEpisodes=0
    private var activeStarted=false
    private var interruptionOpen=false
    private var stableView:ViewClass?=null
    private var viewConflict=false
    private var activeFrameFillSum=0.0
    private var activeFrameFillCount=0

    // At most one frame's completed bundles can be pending: a new frame is
    // not accepted until the preceding durable write has succeeded.
    private data class PendingRepBundle(
        val rep:RepEvidence,
        val forms:List<FormObservation>,
        val cues:List<CueEvidenceLink>,
        val responses:List<CueResponse>,
    )
    private val pendingReps=java.util.ArrayDeque<PendingRepBundle>()

    private var reps=0
    private var assisted=0
    private var uncertain=0

    init{
        if(repository!=null){
            require(session!=null&&execution!=null&&set!=null)
            repository.ensureSession(session)
            repository.ensureExecution(execution)
            repository.openSet(set,config)
        }
    }

    fun process(
        frame:PoseFrame,
        context:TrackingObservationContext=TrackingObservationContext(),
    ):ProductionFrameResult{
        flushPendingReps()
        val result=pipeline.process(frame,context)

        when(result.tracking.state){
            TrackingQualityState.OBSERVABLE->observable++
            TrackingQualityState.DEGRADED->degraded++
            TrackingQualityState.PAUSED->paused++
            TrackingQualityState.UNKNOWN->unknown++
        }

        if(
            result.lifecycleState==SetLifecycleState.ACTIVE_SET||
            result.lifecycleState==SetLifecycleState.POSSIBLE_END||
            result.lifecycleState==SetLifecycleState.FINALIZING
        ){
            activeStarted=true
        }

        if(activeStarted){
            when(result.tracking.state){
                TrackingQualityState.OBSERVABLE->activeObservable++
                TrackingQualityState.DEGRADED->activeDegraded++
                TrackingQualityState.PAUSED->activePaused++
                TrackingQualityState.UNKNOWN->activeUnknown++
            }

            if(result.tracking.allowsBiomechanics){
                interruptionOpen=false
                val view=result.observedViewClass
                if(view!=null){
                    if(stableView==null)stableView=view
                    else if(stableView!=view)viewConflict=true
                }
                val fill=result.tracking.frameFill
                if(fill!=null){
                    activeFrameFillSum+=fill
                    activeFrameFillCount++
                }
            }else if(result.movement.paused&&!interruptionOpen){
                interruptionEpisodes++
                if(result.tracking.reason==TrackingQualityReason.CAMERA_DISTURBANCE){
                    cameraDisturbanceEpisodes++
                }
                interruptionOpen=true
            }
        }

        if(result.movement.paused)feedback.clear()
        result.movement.repEvidence.forEach{rep->
            val forms=result.movement.formObservations.filter{it.repId==rep.repId}
            val cues=result.movement.cueEvents.filter{it.repId==rep.repId}.map{cue->
                CueEvidenceLink(cue,forms.firstOrNull{it.ruleId==cue.ruleId}?.observationId)
            }
            pendingReps.addLast(PendingRepBundle(
                rep,forms,cues,result.movement.cueResponses.filter{it.repId==rep.repId},
            ))
        }
        flushPendingReps()
        return ProductionFrameResult(frame,result,reps)
    }

    fun finishSet(
        endedAtUs:Long,
        endedAtEpochMs:Long=0L,
    ){
        flushPendingReps()
        pipeline.manualEnd()
        val r=repository
        val s=set
        if(r!=null&&s!=null){
            persistTracking()
            r.finishSet(
                SetSummary(
                    s.setId,endedAtUs,reps,assisted,uncertain,endedAtEpochMs
                )
            )
        }
        pipeline.finalized()
        feedback.clear()
    }

    private fun flushPendingReps(){
        var committed=false
        while(pendingReps.isNotEmpty()){
            val bundle=pendingReps.first
            val setId=set?.setId
            if(repository!=null&&setId!=null){
                repository.persistCompletedRepBundle(
                    setId,bundle.rep,bundle.forms,bundle.cues,bundle.responses,
                )
            }
            // Remove only after the atomic bundle commit. A failed write retains
            // the original IDs and evidence for retry, including during finish.
            pendingReps.removeFirst()
            reps++
            when(bundle.rep.classification){
                com.gymbuddy.domain.movement.RepClassification.ASSISTED->assisted++
                com.gymbuddy.domain.movement.RepClassification.UNCERTAIN->uncertain++
                else->Unit
            }
            committed=true
            bundle.cues.forEach{feedback.onCue(it.cue)}
        }
        if(committed)persistTracking()
    }

    private fun persistTracking(){
        val r=repository
        val s=set
        if(r!=null&&s!=null){
            r.upsertTrackingSummary(
                TrackingQualitySummary(
                    setId=s.setId,
                    observableFrames=observable,
                    degradedFrames=degraded,
                    pausedFrames=paused,
                    unknownFrames=unknown,
                    activeObservableFrames=activeObservable,
                    activeDegradedFrames=activeDegraded,
                    activePausedFrames=activePaused,
                    activeUnknownFrames=activeUnknown,
                    interruptionEpisodes=interruptionEpisodes,
                    cameraDisturbanceEpisodes=cameraDisturbanceEpisodes,
                    observedViewClass=if(viewConflict)null else stableView,
                    activeFrameFillMean=
                        if(activeFrameFillCount==0)null
                        else activeFrameFillSum/activeFrameFillCount,
                )
            )
        }
    }
}
