package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.*
import kotlin.math.abs

sealed interface EvidenceValue {
    data class Known(val value:Double,val confidence:Double?):EvidenceValue
    data class Unknown(val reason:String):EvidenceValue
}
data class SignalEvidence(val signalId:String,val unit:SignalUnit,val min:Double?,val max:Double?,val mean:Double?,val last:Double?,val confidence:Double?)
data class MetricEvidence(val metricId:String,val unit:SignalUnit,val value:EvidenceValue)
enum class AssistanceAssessmentState { NOT_ASSESSED, OBSERVED }
data class RepPhaseInterval(
    val phase:PrimitivePhase,
    val startedAtUs:Long,
    val endedAtUs:Long,
    val confidence:Double?,
){
    init{
        require(phase!=PrimitivePhase.UNKNOWN)
        require(startedAtUs>=0L&&endedAtUs>=startedAtUs)
        confidence?.let{require(it.isFinite()&&it in 0.0..1.0)}
    }
    val durationUs:Long get()=endedAtUs-startedAtUs
}
data class InvalidAttemptEvidence(
    val attemptId:String,
    val stepId:String,
    val primitive:MovementPrimitive,
    val startedAtUs:Long,
    val endedAtUs:Long,
    val reason:RepInvalidReason,
    val minConfidence:Double?,
){
    init{
        require(attemptId.isNotBlank()&&stepId.isNotBlank())
        require(startedAtUs>=0L&&endedAtUs>=startedAtUs)
        minConfidence?.let{require(it.isFinite()&&it in 0.0..1.0)}
    }
}
data class RepEvidence(
    val repId:String,
    val ordinal:Int,
    val stepId:String,
    val primitive:MovementPrimitive,
    val startedAtUs:Long,
    val completedAtUs:Long,
    val classification:RepClassification,
    val signals:Map<String,SignalEvidence>,
    val metrics:Map<String,MetricEvidence>,
    val provenance:AnalysisProvenance,
    val phaseIntervals:List<RepPhaseInterval> = emptyList(),
    val assistanceAssessment:AssistanceAssessmentState=AssistanceAssessmentState.NOT_ASSESSED,
    val assistanceScore:Double?=null,
){
    init{
        assistanceScore?.let{require(it.isFinite()&&it in 0.0..1.0)}
        require((assistanceAssessment==AssistanceAssessmentState.OBSERVED)==(assistanceScore!=null))
    }
}

class RepEvidenceBuilder {
    fun build(
        event:RepDetectionEvent,
        frames:List<MovementSignalFrame>,
        config:AnalysisConfig,
        idNamespace:String?=null,
        primitiveFrames:List<MovementPrimitiveFrame> = emptyList(),
    ):RepEvidence{
        require(event.kind==RepCompletionKind.COMPLETED)
        val relevant=frames.filter{it.timestampUs in event.startedAtUs..event.completedAtUs}
        val signals=config.exerciseProfile.signalProfile.definitions.associate{d->
            val samples=relevant.mapNotNull{it.values[d.signalId]}.filter{it.value!=null}
            d.signalId to if(samples.isEmpty()) {
                SignalEvidence(d.signalId,d.unit,null,null,null,null,null)
            } else {
                val vals=samples.map{it.value!!}
                SignalEvidence(
                    d.signalId,d.unit,vals.minOrNull(),vals.maxOrNull(),vals.average(),
                    vals.last(),samples.mapNotNull{it.confidence}.minOrNull()
                )
            }
        }
        val metrics=config.exerciseProfile.metricProfile.metrics.associate{m->
            m.metricId to MetricEvidence(
                m.metricId,m.unit,metricValue(m,signals,relevant)
            )
        }
        val baseId="rep-"+event.ordinal+"-"+event.completedAtUs
        val repId=idNamespace?.let{it+"/"+baseId}?:baseId
        val phases=phaseIntervals(event,primitiveFrames)
        return RepEvidence(
            repId,event.ordinal,event.stepId,event.primitive,event.startedAtUs,
            event.completedAtUs,event.classification!!,signals,metrics,config.provenance,
            phaseIntervals=phases,
            assistanceAssessment=if(event.maxAssistance==null) AssistanceAssessmentState.NOT_ASSESSED else AssistanceAssessmentState.OBSERVED,
            assistanceScore=event.maxAssistance,
        )
    }

    private fun phaseIntervals(
        event:RepDetectionEvent,
        frames:List<MovementPrimitiveFrame>,
    ):List<RepPhaseInterval>{
        val observations=frames.asSequence()
            .filter{it.timestampUs in event.startedAtUs..event.completedAtUs}
            .mapNotNull{it.observations[event.stepId]}
            .filter{it.phase!=PrimitivePhase.UNKNOWN&&it.phase!=PrimitivePhase.SETUP}
            .toList()
        if(observations.isEmpty())return emptyList()
        val out=mutableListOf<RepPhaseInterval>()
        var phase=observations.first().phase
        var start=observations.first().timestampUs
        var confidence=observations.first().confidence
        observations.drop(1).forEach{obs->
            if(obs.phase!=phase){
                out+=RepPhaseInterval(phase,start,obs.timestampUs,confidence)
                phase=obs.phase
                start=obs.timestampUs
                confidence=obs.confidence
            }else{
                confidence=listOfNotNull(confidence,obs.confidence).minOrNull()
            }
        }
        out+=RepPhaseInterval(phase,start,event.completedAtUs,confidence)
        return out
    }

    private fun metricValue(
        def:MetricDefinition,
        signals:Map<String,SignalEvidence>,
        frames:List<MovementSignalFrame>,
    ):EvidenceValue{
        val sources=def.sourceSignalIds.mapNotNull(signals::get)
        if(sources.size!=def.sourceSignalIds.size){
            return EvidenceValue.Unknown("source signal missing")
        }
        val confidence=sources.mapNotNull{it.confidence}.minOrNull()
        val ids=def.sourceSignalIds.toList()
        val value=when(def.aggregation){
            MetricAggregation.MEAN->
                sources.mapNotNull{it.mean}.takeIf{it.size==sources.size}?.average()
            MetricAggregation.MIN->
                sources.mapNotNull{it.min}.takeIf{it.size==sources.size}?.minOrNull()
            MetricAggregation.MAX->
                sources.mapNotNull{it.max}.takeIf{it.size==sources.size}?.maxOrNull()
            MetricAggregation.RANGE->
                if(sources.size==1&&sources[0].min!=null&&sources[0].max!=null){
                    sources[0].max!!-sources[0].min!!
                }else null
            MetricAggregation.ABS_DIFFERENCE->
                if(ids.size==2){
                    frames.mapNotNull{f->
                        val a=f.values[ids[0]]?.value
                        val b=f.values[ids[1]]?.value
                        if(a!=null&&b!=null)abs(a-b)else null
                    }.maxOrNull()
                }else null
            MetricAggregation.LAST->
                sources.mapNotNull{it.last}.takeIf{it.size==sources.size}?.average()
            MetricAggregation.CROSSING_TIME_DIFFERENCE->
                if(ids.size==2){
                    val threshold=def.parameters["threshold"]
                        ?:return EvidenceValue.Unknown("crossing threshold missing")
                    val ta=frames.firstOrNull{
                        (it.values[ids[0]]?.value?:Double.NEGATIVE_INFINITY)>=threshold
                    }?.timestampUs
                    val tb=frames.firstOrNull{
                        (it.values[ids[1]]?.value?:Double.NEGATIVE_INFINITY)>=threshold
                    }?.timestampUs
                    if(ta!=null&&tb!=null)abs(ta-tb)/1000.0 else null
                }else null
        }
        return if(value==null||!value.isFinite()){
            EvidenceValue.Unknown("metric unsupported or insufficient evidence")
        }else{
            EvidenceValue.Known(value,confidence)
        }
    }
}
