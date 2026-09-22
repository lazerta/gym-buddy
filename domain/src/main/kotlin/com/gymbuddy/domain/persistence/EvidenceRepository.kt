package com.gymbuddy.domain.persistence

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.coaching.CueResponse
import com.gymbuddy.domain.evidence.FormObservation
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisProvenance

private fun requireId(value:String,field:String){require(value.isNotBlank()){ "$field must not be blank" }}
private fun requireTimestamp(value:Long,field:String){require(value>=0L){ "$field must be >= 0" }}

data class WorkoutSessionRecord(val sessionId:String,val startedAtUs:Long){init{requireId(sessionId,"sessionId");requireTimestamp(startedAtUs,"startedAtUs")}}
data class ExerciseExecutionRecord(val executionId:String,val sessionId:String,val exerciseId:String,val startedAtUs:Long){init{requireId(executionId,"executionId");requireId(sessionId,"sessionId");requireId(exerciseId,"exerciseId");requireTimestamp(startedAtUs,"startedAtUs")}}
data class SetRecord(val setId:String,val executionId:String,val setOrdinal:Int,val startedAtUs:Long){init{requireId(setId,"setId");requireId(executionId,"executionId");require(setOrdinal>0);requireTimestamp(startedAtUs,"startedAtUs")}}
data class TrackingQualitySummary(val setId:String,val observableFrames:Int,val degradedFrames:Int,val pausedFrames:Int,val unknownFrames:Int){init{requireId(setId,"setId");require(observableFrames>=0);require(degradedFrames>=0);require(pausedFrames>=0);require(unknownFrames>=0)}}
data class SetSummary(val setId:String,val endedAtUs:Long,val completedReps:Int,val assistedReps:Int,val uncertainReps:Int){init{requireId(setId,"setId");requireTimestamp(endedAtUs,"endedAtUs");require(completedReps>=0);require(assistedReps>=0);require(uncertainReps>=0);require(assistedReps+uncertainReps<=completedReps)}}

enum class CueDeliveryState { STARTED,COMPLETED,FAILED,CANCELLED }
data class CueDeliveryRecord(val cueId:String,val state:CueDeliveryState){init{requireId(cueId,"cueId")}}
data class CueEvidenceLink(val cue:CueEvent,val observationId:String?)

data class PersistedSetEvidence(
    val set:SetRecord,
    val analysisProvenance:AnalysisProvenance,
    val reps:List<RepEvidence>,
    val observations:List<FormObservation>,
    val cues:List<CueEvent>,
    val responses:List<CueResponse>,
    val tracking:TrackingQualitySummary?,
    val summary:SetSummary?,
    val cueDeliveries:List<CueDeliveryRecord> = emptyList(),
)

interface EvidenceRepository {
    fun ensureSession(record:WorkoutSessionRecord)
    fun ensureExecution(record:ExerciseExecutionRecord)
    fun openSet(record:SetRecord,config:AnalysisConfig)
    fun persistRep(setId:String,evidence:RepEvidence)
    fun persistFormObservation(setId:String,observation:FormObservation)
    fun persistCueEvent(setId:String,cue:CueEvent,observationId:String?=null)
    fun persistCueResponse(setId:String,response:CueResponse)
    fun persistCueDelivery(record:CueDeliveryRecord)
    fun upsertTrackingSummary(summary:TrackingQualitySummary)
    fun finishSet(summary:SetSummary)
    fun loadSet(setId:String):PersistedSetEvidence?

    fun persistCompletedRepBundle(
        setId:String,
        evidence:RepEvidence,
        observations:List<FormObservation>,
        cues:List<CueEvidenceLink>,
        responses:List<CueResponse>,
    ) {
        persistRep(setId,evidence)
        observations.forEach{persistFormObservation(setId,it)}
        cues.forEach{persistCueEvent(setId,it.cue,it.observationId)}
        responses.forEach{persistCueResponse(setId,it)}
    }
}
