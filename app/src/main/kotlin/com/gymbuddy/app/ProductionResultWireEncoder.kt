package com.gymbuddy.app

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.pose.*

object ProductionResultWireEncoder {
    fun encode(result:ProductionFrameResult):Map<String,Any?> = poseWire(result.poseFrame)+mapOf(
        "tracking_state" to result.pipeline.tracking.state.name,
        "tracking_reason" to result.pipeline.tracking.reason.name,
        "camera_guidance" to result.pipeline.cameraGuidance.name,
        "set_lifecycle_state" to result.pipeline.lifecycleState.name,
        "primary_subject_candidate_index" to result.pipeline.lock.targetCandidateIndex,
        "rep_count" to result.repCount,
        "rep_events" to result.pipeline.movement.repEvents.map{mapOf("ordinal" to it.ordinal,"kind" to it.kind.name,"classification" to it.classification?.name,"completed_at_us" to it.completedAtUs)},
        "cues" to result.pipeline.movement.cueEvents.map(CueEvent::cueId),
    )
    private fun poseWire(frame:PoseFrame):Map<String,Any?> = mapOf(
        "mediapipe_timestamp_ms" to frame.timestampUs/1000L,
        "pose_count" to frame.candidates.size,
        "landmarks" to frame.candidates.map{c->ordered(c.normalizedLandmarks)},
        "world_landmarks" to frame.candidates.map{c->ordered(c.worldLandmarks)},
    )
    private fun ordered(map:Map<PoseLandmarkId,PoseLandmarkObservation>)=PoseLandmarkId.entries.mapNotNull{id->map[id]?.let{lm->buildMap<String,Any?>{put("landmark_id",id.wireName);put("x",lm.position.x);put("y",lm.position.y);put("z",lm.position.z);lm.visibility?.let{put("visibility",it)};lm.presence?.let{put("presence",it)}}}}
}
