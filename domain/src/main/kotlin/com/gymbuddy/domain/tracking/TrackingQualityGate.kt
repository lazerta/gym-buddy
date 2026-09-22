package com.gymbuddy.domain.tracking

import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.profile.ViewClass

enum class TrackingQualityState { OBSERVABLE, DEGRADED, PAUSED, UNKNOWN }
enum class TrackingQualityReason {
    OK,
    IDENTITY_UNSAFE,
    TARGET_NOT_AVAILABLE,
    REQUIRED_LANDMARKS_MISSING,
    LOW_LANDMARK_CONFIDENCE,
    FRAMING_INVALID,
    WRONG_VIEW,
    CAMERA_DISTURBANCE,
    CONTINUITY_GAP,
    NON_MONOTONIC_TIMESTAMP,
    LANDMARK_OUT_OF_FRAME,
    INSUFFICIENT_EVIDENCE,
    CAMERA_NOT_READY,
}

data class TrackingObservationContext(val observedViewClass:ViewClass?=null,val cameraMotionScore:Double?=null){init{cameraMotionScore?.let{require(it.isFinite()&&it in 0.0..1.0)}}}
data class TrackingQualityResult(val state:TrackingQualityState,val reason:TrackingQualityReason,val targetCandidateIndex:Int?,val requiredVisibleFraction:Double?,val frameFill:Double?){val allowsBiomechanics:Boolean get()=state==TrackingQualityState.OBSERVABLE||state==TrackingQualityState.DEGRADED}

class TrackingQualityGate(private val cameraDisturbanceThreshold:Double=.55,private val degradedMargin:Double=.15){
    private var lastAcceptedTimestampUs:Long?=null
    private var lastFrameTimestampUs:Long?=null
    init{require(cameraDisturbanceThreshold in 0.0..1.0);require(degradedMargin in 0.0..1.0)}
    fun reset(){lastAcceptedTimestampUs=null;lastFrameTimestampUs=null}
    fun evaluate(frame:PoseFrame,lock:PrimarySubjectLockResult,cameraProfile:CameraProfile,context:TrackingObservationContext=TrackingObservationContext()):TrackingQualityResult{
        val previousFrameTimestamp=lastFrameTimestampUs
        if(previousFrameTimestamp!=null&&frame.timestampUs<=previousFrameTimestamp)return TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.NON_MONOTONIC_TIMESTAMP,lock.targetCandidateIndex,null,null)
        lastFrameTimestampUs=frame.timestampUs
        if(lock.state!=PrimarySubjectLockState.LOCKED){lastAcceptedTimestampUs=null;return TrackingQualityResult(if(lock.state==PrimarySubjectLockState.TARGET_LOST)TrackingQualityState.UNKNOWN else TrackingQualityState.PAUSED,if(lock.state==PrimarySubjectLockState.TARGET_LOST)TrackingQualityReason.TARGET_NOT_AVAILABLE else TrackingQualityReason.IDENTITY_UNSAFE,null,null,null)}
        val targetIndex=lock.targetCandidateIndex?:return unknown(TrackingQualityReason.INSUFFICIENT_EVIDENCE)
        val target=frame.candidates.firstOrNull{it.candidateIndex==targetIndex}?:return paused(targetIndex,TrackingQualityReason.TARGET_NOT_AVAILABLE)
        if(context.cameraMotionScore!=null&&context.cameraMotionScore>=cameraDisturbanceThreshold){lastAcceptedTimestampUs=null;return paused(targetIndex,TrackingQualityReason.CAMERA_DISTURBANCE)}
        val lastTimestamp=lastAcceptedTimestampUs
        if(lastTimestamp!=null&&frame.timestampUs-lastTimestamp>cameraProfile.maxTrackingGapMs*1_000L){lastAcceptedTimestampUs=null;return paused(targetIndex,TrackingQualityReason.CONTINUITY_GAP)}
        val observedView=context.observedViewClass
        if(observedView!=null&&observedView !in cameraProfile.allowedViewClasses){lastAcceptedTimestampUs=null;return paused(targetIndex,TrackingQualityReason.WRONG_VIEW)}
        val fill=frameFill(target)?:run{lastAcceptedTimestampUs=null;return paused(targetIndex,TrackingQualityReason.INSUFFICIENT_EVIDENCE)}
        if(fill !in cameraProfile.frameFillRange){lastAcceptedTimestampUs=null;return TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.FRAMING_INVALID,targetIndex,null,fill)}
        val required=cameraProfile.requiredLandmarks
        val presentCount=required.count{target.normalized(it.toLandmarkId())!=null}
        if(presentCount<required.size){lastAcceptedTimestampUs=null;return TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.REQUIRED_LANDMARKS_MISSING,targetIndex,presentCount.toDouble()/required.size,fill)}
        val outOfFrame=required.any{req->val o=target.normalized(req.toLandmarkId())?:return@any false;o.position.x !in 0.0..1.0||o.position.y !in 0.0..1.0}
        if(outOfFrame){lastAcceptedTimestampUs=null;return TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.LANDMARK_OUT_OF_FRAME,targetIndex,null,fill)}
        val reliableCount=required.count{req->val o=target.normalized(req.toLandmarkId())?:return@count false;val v=o.visibility?.let{it>=req.minVisibility}?:false;val p=req.minPresence?.let{t->o.presence?.let{it>=t}?:false}?:true;v&&p}
        val fraction=reliableCount.toDouble()/required.size
        if(fraction<cameraProfile.minVisibleRequiredFraction){lastAcceptedTimestampUs=null;return TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.LOW_LANDMARK_CONFIDENCE,targetIndex,fraction,fill)}
        lastAcceptedTimestampUs=frame.timestampUs
        val degradedThreshold=(1.0-degradedMargin).coerceAtLeast(cameraProfile.minVisibleRequiredFraction)
        return if(fraction<degradedThreshold)TrackingQualityResult(TrackingQualityState.DEGRADED,TrackingQualityReason.LOW_LANDMARK_CONFIDENCE,targetIndex,fraction,fill) else TrackingQualityResult(TrackingQualityState.OBSERVABLE,TrackingQualityReason.OK,targetIndex,fraction,fill)
    }
    private fun frameFill(target:PoseSubjectCandidate):Double?{val points=target.normalizedLandmarks.values.map{it.position};if(points.size<2)return null;val width=(points.maxOf{it.x}-points.minOf{it.x}).coerceAtLeast(0.0);val height=(points.maxOf{it.y}-points.minOf{it.y}).coerceAtLeast(0.0);return maxOf(width,height).coerceIn(0.0,1.0)}
    private fun paused(targetIndex:Int?,reason:TrackingQualityReason)=TrackingQualityResult(TrackingQualityState.PAUSED,reason,targetIndex,null,null)
    private fun unknown(reason:TrackingQualityReason)=TrackingQualityResult(TrackingQualityState.UNKNOWN,reason,null,null,null)
}
private fun com.gymbuddy.domain.profile.LandmarkRequirement.toLandmarkId():PoseLandmarkId=PoseLandmarkId.entries.firstOrNull{it.wireName==landmarkId}?:error("CameraProfile references unsupported landmark id: $landmarkId")
