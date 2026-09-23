package com.gymbuddy.domain.export

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.coaching.CueResponse
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservation
import com.gymbuddy.domain.evidence.MetricEvidence
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.evidence.SignalEvidence
import com.gymbuddy.domain.persistence.CueDeliveryRecord
import com.gymbuddy.domain.persistence.LoadSnapshot
import com.gymbuddy.domain.persistence.PersistedSetEvidence
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.TrackingQualitySummary
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisProvenance
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef
import com.gymbuddy.domain.profile.ProfileVersionRef

data class ChatGptContextReference(
    val sourceRef:String,
    val content:String,
){
    init{
        require(sourceRef.isNotBlank()){"sourceRef must not be blank"}
        require(content.isNotBlank()){"content must not be blank"}
    }
}

data class ChatGptSetContext(
    val session:WorkoutSessionRecord,
    val execution:ExerciseExecutionRecord,
    val evidence:PersistedSetEvidence,
){
    init{
        require(execution.sessionId==session.sessionId)
        require(evidence.set.executionId==execution.executionId)
        require(evidence.analysisProvenance.exerciseDefinitionId==execution.exerciseId)
    }
}

interface ChatGptContextRepository {
    fun loadSetContext(setId:String):ChatGptSetContext?
    fun loadRecentComparableSetContexts(
        exerciseId:String,
        currentSetId:String,
        beforeEndedAtUs:Long,
        limit:Int,
    ):List<ChatGptSetContext>
}

class ChatGptContextExporter(
    private val repository:ChatGptContextRepository,
    private val historyLimit:Int=DEFAULT_HISTORY_LIMIT,
){
    init{require(historyLimit>0)}

    fun export(
        currentSetId:String,
        recoveryContext:ChatGptContextReference?=null,
        mediaReference:ChatGptContextReference?=null,
    ):String{
        require(currentSetId.isNotBlank())
        val current=requireNotNull(repository.loadSetContext(currentSetId)){
            "current set not found: $currentSetId"
        }
        val currentSummary=requireNotNull(current.evidence.summary){
            "current set must be finalized before export"
        }
        val history=repository.loadRecentComparableSetContexts(
            exerciseId=current.execution.exerciseId,
            currentSetId=currentSetId,
            beforeEndedAtUs=currentSummary.endedAtUs,
            limit=historyLimit,
        )
            .filter{it.execution.exerciseId==current.execution.exerciseId}
            .filter{it.evidence.set.setId!=currentSetId}
            .filter{context->
                val summary=context.evidence.summary?:return@filter false
                summary.endedAtUs<currentSummary.endedAtUs||
                    (summary.endedAtUs==currentSummary.endedAtUs&&
                        context.evidence.set.setId<currentSetId)
            }
            .sortedWith(
                compareByDescending<ChatGptSetContext>{
                    requireNotNull(it.evidence.summary).endedAtUs
                }.thenByDescending{it.evidence.set.setId}
            )
            .take(historyLimit)

        val allContexts=listOf(current)+history
        return obj(
            "schema" to str(SCHEMA),
            "schema_version" to num(SCHEMA_VERSION),
            "exercise_id" to str(current.execution.exerciseId),
            "current_set" to setContext(current),
            "recent_comparable_history" to arr(history.map(::setContext)),
            "recovery_context" to externalContext(recoveryContext),
            "media_reference" to externalContext(mediaReference),
            "source_references" to sourceReferences(allContexts,recoveryContext,mediaReference),
        )
    }

    private fun setContext(context:ChatGptSetContext):String{
        val evidence=context.evidence
        val reps=evidence.reps.sortedWith(compareBy<RepEvidence>{it.ordinal}.thenBy{it.repId})
        val ordinalByRep=reps.associate{it.repId to it.ordinal}
        val observations=evidence.observations.sortedWith(
            compareBy<FormObservation>{ordinalByRep[it.repId]?:Int.MAX_VALUE}
                .thenBy{it.observationId}
        )
        val cues=evidence.cues.sortedWith(
            compareBy<CueEvent>{it.emittedAtUs}.thenBy{it.cueId}
        )
        val cueTimes=cues.associate{it.cueId to it.emittedAtUs}
        val responses=evidence.responses.sortedWith(
            compareBy<CueResponse>{ordinalByRep[it.repId]?:Int.MAX_VALUE}
                .thenBy{it.cueId}
                .thenBy{it.repId}
        )
        val deliveries=evidence.cueDeliveries.sortedWith(
            compareBy<CueDeliveryRecord>{cueTimes[it.cueId]?:Long.MAX_VALUE}
                .thenBy{it.cueId}
        )

        return obj(
            "source_ref" to obj(
                "session_id" to str(context.session.sessionId),
                "execution_id" to str(context.execution.executionId),
                "set_id" to str(evidence.set.setId),
            ),
            "session_started_at_us" to num(context.session.startedAtUs),
            "execution_started_at_us" to num(context.execution.startedAtUs),
            "exercise_id" to str(context.execution.exerciseId),
            "set" to obj(
                "ordinal" to num(evidence.set.setOrdinal),
                "started_at_us" to num(evidence.set.startedAtUs),
                "actual_load" to load(evidence.set.actualLoad),
                "summary" to setSummary(evidence.summary),
                "tracking" to tracking(evidence.tracking),
            ),
            "analysis_provenance" to provenance(evidence.analysisProvenance),
            "reps" to arr(reps.map(::rep)),
            "form_observations" to arr(observations.map(::observation)),
            "cues" to arr(cues.map{cue(it,evidence.cueObservationIds[it.cueId])}),
            "cue_responses" to arr(responses.map(::response)),
            "cue_deliveries" to arr(deliveries.map(::delivery)),
        )
    }

    private fun provenance(p:AnalysisProvenance):String{
        val equipmentProfileJson:String=p.equipmentProfile?.let(::profileRef)?:"null"
        val personalCalibrationJson:String=
            p.personalCalibrationProfile?.let(::calibrationRef)?:"null"
        return obj(
            "exercise_definition" to obj(
                "id" to str(p.exerciseDefinitionId),
                "version" to num(p.exerciseDefinitionVersion),
                "semantic_hash" to str(p.exerciseDefinitionSemanticHash),
            ),
            "exercise_profile" to profileRef(p.exerciseProfile),
            "camera_profile" to profileRef(p.cameraProfile),
            "signal_profile" to profileRef(p.signalProfile),
            "movement_primitive_sequence" to profileRef(p.movementPrimitiveSequence),
            "metric_profile" to profileRef(p.metricProfile),
            "form_rule_set" to profileRef(p.formRuleSet),
            "cue_policy" to profileRef(p.cuePolicy),
            "equipment_profile" to equipmentProfileJson,
            "personal_calibration_profile" to personalCalibrationJson,
        )
    }

    private fun profileRef(ref:ProfileVersionRef)=obj(
        "id" to str(ref.profileId),
        "version" to num(ref.profileVersion),
        "semantic_hash" to str(ref.semanticHash),
    )

    private fun calibrationRef(ref:PersonalCalibrationVersionRef)=obj(
        "id" to str(ref.calibrationProfileId),
        "version" to num(ref.profileVersion),
        "semantic_hash" to str(ref.semanticHash),
    )

    private fun rep(rep:RepEvidence)=obj(
        "source_ref" to str(rep.repId),
        "ordinal" to num(rep.ordinal),
        "step_id" to str(rep.stepId),
        "primitive" to str(rep.primitive.name),
        "started_at_us" to num(rep.startedAtUs),
        "completed_at_us" to num(rep.completedAtUs),
        "classification" to str(rep.classification.name),
        "signals" to arr(rep.signals.values.sortedBy{it.signalId}.map{signal(rep.repId,it)}),
        "metrics" to arr(rep.metrics.values.sortedBy{it.metricId}.map{metric(rep.repId,it)}),
    )

    private fun signal(repId:String,signal:SignalEvidence)=obj(
        "source_ref" to str(repId+"/signal/"+signal.signalId),
        "signal_id" to str(signal.signalId),
        "unit" to str(signal.unit.name),
        "min" to num(signal.min),
        "max" to num(signal.max),
        "mean" to num(signal.mean),
        "last" to num(signal.last),
        "confidence" to num(signal.confidence),
    )

    private fun metric(repId:String,metric:MetricEvidence)=obj(
        "source_ref" to str(repId+"/metric/"+metric.metricId),
        "metric_id" to str(metric.metricId),
        "unit" to str(metric.unit.name),
        "value" to when(val value=metric.value){
            is EvidenceValue.Known->obj(
                "state" to str("KNOWN"),
                "value" to num(value.value),
                "confidence" to num(value.confidence),
            )
            is EvidenceValue.Unknown->obj(
                "state" to str("UNKNOWN"),
                "reason" to str(value.reason),
            )
        },
    )

    private fun observation(observation:FormObservation)=obj(
        "source_ref" to str(observation.observationId),
        "rep_id" to str(observation.repId),
        "rule_id" to str(observation.ruleId),
        "rule_version" to num(observation.ruleVersion),
        "state" to str(observation.state.name),
        "severity" to str(observation.severity.name),
        "confidence" to num(observation.confidence),
        "evidence_value" to num(observation.evidenceValue),
    )

    private fun cue(cue:CueEvent,observationId:String?)=obj(
        "source_ref" to str(cue.cueId),
        "rep_id" to str(cue.repId),
        "observation_id" to str(observationId),
        "rule_id" to str(cue.ruleId),
        "emitted_at_us" to num(cue.emittedAtUs),
        "severity" to str(cue.severity),
    )

    private fun response(response:CueResponse)=obj(
        "source_ref" to str(response.cueId+":"+response.repId),
        "cue_id" to str(response.cueId),
        "rep_id" to str(response.repId),
        "state" to str(response.state.name),
    )

    private fun delivery(delivery:CueDeliveryRecord)=obj(
        "source_ref" to str(delivery.cueId),
        "cue_id" to str(delivery.cueId),
        "state" to str(delivery.state.name),
    )

    private fun load(load:LoadSnapshot?):String=
        load?.let{
            obj(
                "value" to num(it.value),
                "unit" to str(it.unit),
            )
        }?:"null"

    private fun setSummary(summary:SetSummary?):String=
        summary?.let{
            obj(
                "ended_at_us" to num(it.endedAtUs),
                "completed_reps" to num(it.completedReps),
                "assisted_reps" to num(it.assistedReps),
                "uncertain_reps" to num(it.uncertainReps),
            )
        }?:"null"

    private fun tracking(summary:TrackingQualitySummary?):String=
        summary?.let{
            obj(
                "observable_frames" to num(it.observableFrames),
                "degraded_frames" to num(it.degradedFrames),
                "paused_frames" to num(it.pausedFrames),
                "unknown_frames" to num(it.unknownFrames),
            )
        }?:"null"

    private fun externalContext(context:ChatGptContextReference?):String=
        context?.let{
            obj(
                "source_ref" to str(it.sourceRef),
                "content" to str(it.content),
            )
        }?:"null"

    private fun sourceReferences(
        contexts:List<ChatGptSetContext>,
        recoveryContext:ChatGptContextReference?,
        mediaReference:ChatGptContextReference?,
    ):String{
        val reps=contexts.flatMap{it.evidence.reps}
        val profiles=contexts.flatMap{context->
            val p=context.evidence.analysisProvenance
            buildList{
                add(profileSource("exercise_definition",p.exerciseDefinitionId,p.exerciseDefinitionVersion,p.exerciseDefinitionSemanticHash))
                add(profileSource("exercise_profile",p.exerciseProfile))
                add(profileSource("camera_profile",p.cameraProfile))
                add(profileSource("signal_profile",p.signalProfile))
                add(profileSource("movement_primitive_sequence",p.movementPrimitiveSequence))
                add(profileSource("metric_profile",p.metricProfile))
                add(profileSource("form_rule_set",p.formRuleSet))
                add(profileSource("cue_policy",p.cuePolicy))
                p.equipmentProfile?.let{add(profileSource("equipment_profile",it))}
                p.personalCalibrationProfile?.let{
                    add("personal_calibration_profile/"+it.calibrationProfileId+"@"+it.profileVersion+"#"+it.semanticHash)
                }
            }
        }
        return obj(
            "session_ids" to strings(contexts.map{it.session.sessionId}),
            "execution_ids" to strings(contexts.map{it.execution.executionId}),
            "set_ids" to strings(contexts.map{it.evidence.set.setId}),
            "analysis_context_set_ids" to strings(contexts.map{it.evidence.set.setId}),
            "rep_ids" to strings(reps.map{it.repId}),
            "signal_refs" to strings(reps.flatMap{rep->rep.signals.values.map{rep.repId+"/signal/"+it.signalId}}),
            "metric_refs" to strings(reps.flatMap{rep->rep.metrics.values.map{rep.repId+"/metric/"+it.metricId}}),
            "observation_ids" to strings(contexts.flatMap{it.evidence.observations}.map{it.observationId}),
            "cue_ids" to strings(contexts.flatMap{it.evidence.cues}.map{it.cueId}),
            "cue_response_refs" to strings(contexts.flatMap{it.evidence.responses}.map{it.cueId+":"+it.repId}),
            "cue_delivery_refs" to strings(contexts.flatMap{it.evidence.cueDeliveries}.map{it.cueId}),
            "profile_refs" to strings(profiles),
            "optional_context_refs" to strings(listOfNotNull(recoveryContext?.sourceRef,mediaReference?.sourceRef)),
        )
    }

    private fun profileSource(kind:String,ref:ProfileVersionRef)=
        profileSource(kind,ref.profileId,ref.profileVersion,ref.semanticHash)

    private fun profileSource(kind:String,id:String,version:Int,hash:String)=
        kind+"/"+id+"@"+version+"#"+hash

    private fun strings(values:List<String>)=
        arr(values.distinct().sorted().map(::str))

    private fun obj(vararg fields:Pair<String,String>)=
        fields.joinToString(prefix="{",postfix="}",separator=","){
            str(it.first)+":"+it.second
        }

    private fun arr(values:List<String>)=
        values.joinToString(prefix="[",postfix="]",separator=",")

    private fun str(value:String?):String{
        if(value==null)return "null"
        return buildString{
            append('"')
            value.forEach{c->
                when(c){
                    '"'->append("\\\"")
                    '\\'->append("\\\\")
                    '\b'->append("\\b")
                    '\n'->append("\\n")
                    '\r'->append("\\r")
                    '\t'->append("\\t")
                    else->if(c.code<0x20){
                        append("\\u")
                        append(c.code.toString(16).padStart(4,'0'))
                    }else append(c)
                }
            }
            append('"')
        }
    }

    private fun num(value:Int)=value.toString()
    private fun num(value:Long)=value.toString()
    private fun num(value:Double?)=value?.let{
        require(it.isFinite()){"JSON numbers must be finite"}
        java.lang.Double.toString(it)
    }?:"null"

    companion object {
        const val SCHEMA="gym_buddy_chatgpt_context"
        const val SCHEMA_VERSION=1
        const val DEFAULT_HISTORY_LIMIT=3
    }
}
