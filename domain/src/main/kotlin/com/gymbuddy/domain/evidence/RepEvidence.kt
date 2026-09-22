package com.gymbuddy.domain.evidence

import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.*

sealed interface EvidenceValue {
    data class Known(val value: Double, val confidence: Double?) : EvidenceValue
    data class Unknown(val reason: String) : EvidenceValue
}

data class SignalEvidence(val signalId: String, val unit: SignalUnit, val min: Double?, val max: Double?, val mean: Double?, val last: Double?, val confidence: Double?)
data class MetricEvidence(val metricId: String, val unit: SignalUnit, val value: EvidenceValue)

data class RepEvidence(
    val repId: String,
    val ordinal: Int,
    val stepId: String,
    val primitive: MovementPrimitive,
    val startedAtUs: Long,
    val completedAtUs: Long,
    val classification: RepClassification,
    val signals: Map<String, SignalEvidence>,
    val metrics: Map<String, MetricEvidence>,
    val provenance: AnalysisProvenance,
)

class RepEvidenceBuilder {
    fun build(event: RepDetectionEvent, frames: List<MovementSignalFrame>, config: AnalysisConfig): RepEvidence {
        require(event.kind == RepCompletionKind.COMPLETED)
        val relevant = frames.filter { it.timestampUs in event.startedAtUs..event.completedAtUs }
        val signals = config.exerciseProfile.signalProfile.definitions.associate { d ->
            val samples = relevant.mapNotNull { it.values[d.signalId] }.filter { it.value != null }
            d.signalId to if (samples.isEmpty()) SignalEvidence(d.signalId, d.unit, null, null, null, null, null) else {
                val vals = samples.map { it.value!! }; SignalEvidence(d.signalId, d.unit, vals.minOrNull(), vals.maxOrNull(), vals.average(), vals.last(), samples.mapNotNull { it.confidence }.minOrNull())
            }
        }
        val metrics = config.exerciseProfile.metricProfile.metrics.associate { m ->
            val sources = m.sourceSignalIds.mapNotNull(signals::get)
            val value = metricValue(m, sources)
            m.metricId to MetricEvidence(m.metricId, m.unit, value)
        }
        return RepEvidence("rep-${event.ordinal}-${event.completedAtUs}", event.ordinal, event.stepId, event.primitive, event.startedAtUs, event.completedAtUs, event.classification!!, signals, metrics, config.provenance)
    }

    private fun metricValue(def: MetricDefinition, sources: List<SignalEvidence>): EvidenceValue {
        if (sources.size != def.sourceSignalIds.size) return EvidenceValue.Unknown("source signal missing")
        val conf = sources.mapNotNull { it.confidence }.minOrNull()
        val v = when (def.aggregation) {
            MetricAggregation.MEAN -> sources.mapNotNull { it.mean }.takeIf { it.size == sources.size }?.average()
            MetricAggregation.MIN -> sources.mapNotNull { it.min }.takeIf { it.size == sources.size }?.minOrNull()
            MetricAggregation.MAX -> sources.mapNotNull { it.max }.takeIf { it.size == sources.size }?.maxOrNull()
            MetricAggregation.RANGE -> if (sources.size == 1 && sources[0].min != null && sources[0].max != null) sources[0].max!! - sources[0].min!! else null
            MetricAggregation.ABS_DIFFERENCE -> if (sources.size == 2 && sources[0].mean != null && sources[1].mean != null) kotlin.math.abs(sources[0].mean!! - sources[1].mean!!) else null
            MetricAggregation.LAST -> sources.mapNotNull { it.last }.takeIf { it.size == sources.size }?.average()
        }
        return if (v == null || !v.isFinite()) EvidenceValue.Unknown("metric unsupported or insufficient evidence") else EvidenceValue.Known(v, conf)
    }
}
