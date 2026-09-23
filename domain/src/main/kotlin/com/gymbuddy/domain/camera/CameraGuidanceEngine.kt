package com.gymbuddy.domain.camera

import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.tracking.PrimarySubjectLockResult
import com.gymbuddy.domain.tracking.PrimarySubjectLockState
import com.gymbuddy.domain.tracking.TrackingObservationContext

class CameraGuidanceEngine(
    private val readyDwellFrames:Int=2,
    private val knownSetupReadyDwellFrames:Int=1,
) {
    init {
        require(readyDwellFrames >= 1)
        require(knownSetupReadyDwellFrames >= 1)
    }
    private var consecutiveReadyFrames=0
    private var lastTargetIndex:Int?=null
    fun reset(){consecutiveReadyFrames=0;lastTargetIndex=null}

    fun evaluate(
        frame:PoseFrame,
        lock:PrimarySubjectLockResult,
        profile:CameraProfile,
        context:TrackingObservationContext=TrackingObservationContext(),
        personalPrior:PersonalCameraPrior?=null,
    ):CameraGuidanceAction {
        if(lock.state!=PrimarySubjectLockState.LOCKED)return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        val targetIndex=lock.targetCandidateIndex?:return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        val target=frame.candidates.firstOrNull{it.candidateIndex==targetIndex}?:return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        val observedView=context.observedViewClass?:return notReady(profile,CameraGuidanceAction.ADJUST_ANGLE)
        if(observedView !in profile.allowedViewClasses)return notReady(profile,CameraGuidanceAction.ADJUST_ANGLE)

        val observations=profile.requiredLandmarks.mapNotNull{req->
            val id=PoseLandmarkId.entries.firstOrNull{it.wireName==req.landmarkId}?:return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
            target.normalized(id)?.let{req to it}
        }
        if(observations.size!=profile.requiredLandmarks.size)return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        if(observations.any{(_,lm)->lm.position.x !in 0.0..1.0||lm.position.y !in 0.0..1.0})return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        val reliable=observations.count{(req,lm)->
            val v=lm.visibility?.let{it>=req.minVisibility}?:false
            val p=req.minPresence?.let{t->lm.presence?.let{it>=t}?:false}?:true
            v&&p
        }
        if(reliable.toDouble()/profile.requiredLandmarks.size<profile.minVisibleRequiredFraction)return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)

        val points=target.normalizedLandmarks.values.map{it.position}
        if(points.size<2)return notReady(profile,CameraGuidanceAction.CANNOT_ASSESS)
        val fill=maxOf(points.maxOf{it.x}-points.minOf{it.x},points.maxOf{it.y}-points.minOf{it.y})
        if(fill<profile.frameFillRange.min)return notReady(profile,CameraGuidanceAction.MOVE_CLOSER)
        if(fill>profile.frameFillRange.max)return notReady(profile,CameraGuidanceAction.MOVE_FARTHER)

        if(lastTargetIndex!=targetIndex){consecutiveReadyFrames=0;lastTargetIndex=targetIndex}
        consecutiveReadyFrames++
        val requiredDwell=if(
            personalPrior?.matchesKnownSetup(profile,observedView,fill)==true
        ){
            minOf(readyDwellFrames,knownSetupReadyDwellFrames)
        }else{
            readyDwellFrames
        }
        return if(consecutiveReadyFrames>=requiredDwell)CameraGuidanceAction.CAMERA_READY else CameraGuidanceAction.CANNOT_ASSESS
    }

    private fun notReady(profile:CameraProfile,action:CameraGuidanceAction):CameraGuidanceAction {
        consecutiveReadyFrames=0
        return if(action in profile.guidanceActions)action else CameraGuidanceAction.CANNOT_ASSESS
    }
}
