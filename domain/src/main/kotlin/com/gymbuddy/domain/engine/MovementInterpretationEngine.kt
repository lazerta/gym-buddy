package com.gymbuddy.domain.engine

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.AnalysisConfig

data class MovementEngineOutput(
    val timestampUs: Long,
    val signals: MovementSignalFrame?,
    val primitives: MovementPrimitiveFrame?,
    val repEvents: List<RepDetectionEvent>,
    val repEvidence: List<RepEvidence>,
    val formObservations: List<FormObservation>,
    val cueEvents: List<CueEvent>,
    val cueResponses: List<CueResponse>,
    val paused: Boolean,
)

class MovementInterpretationEngine(
    private val config: AnalysisConfig,
    private val signalExtractor: MovementSignalExtractor = MovementSignalExtractor(),
    private val primitiveInterpreter: MovementPrimitiveInterpreter = MovementPrimitiveInterpreter(),
    private val repDetector: TemporalRepDetector = TemporalRepDetector(),
    private val evidenceBuilder: RepEvidenceBuilder = RepEvidenceBuilder(),
    private val formEngine: FormAnalysisEngine = FormAnalysisEngine(),
    private val cueEngine: CueEngine = CueEngine(config.exerciseProfile.cuePolicy),
) {
    private val signalHistory = ArrayDeque<MovementSignalFrame>()
    private var lastTimestampUs: Long? = null

    fun process(timestampUs: Long, pose: NormalizedPose): MovementEngineOutput {
        val last = lastTimestampUs
        if (last != null && timestampUs <= last) return pausedOutput(timestampUs)
        lastTimestampUs = timestampUs
        val signals = signalExtractor.extract(timestampUs, pose, resolvedSignalProfile())
        signalHistory.add(signals)
        while (signalHistory.size > 1500) signalHistory.removeFirst()
        val primitives = primitiveInterpreter.interpret(signals, config.exerciseProfile.movementPrimitiveSequence)
        val repEvents = repDetector.update(primitives)
        val evidence = mutableListOf<RepEvidence>()
        val forms = mutableListOf<FormObservation>()
        val cues = mutableListOf<CueEvent>()
        val responses = mutableListOf<CueResponse>()
        repEvents.filter { it.kind == RepCompletionKind.COMPLETED }.forEach { event ->
            val rep = evidenceBuilder.build(event, signalHistory.toList(), config)
            val obs = formEngine.analyze(rep, config.exerciseProfile.formRuleSet)
            val cueDecision = cueEngine.evaluate(rep, obs)
            evidence += rep; forms += obs; cues += cueDecision.cues; responses += cueDecision.responses
            while (signalHistory.isNotEmpty() && signalHistory.first().timestampUs <= event.completedAtUs) signalHistory.removeFirst()
        }
        return MovementEngineOutput(timestampUs, signals, primitives, repEvents, evidence, forms, cues, responses, paused = false)
    }

    fun onInterruption(timestampUs: Long): MovementEngineOutput {
        val last = lastTimestampUs
        if (last != null && timestampUs <= last) return pausedOutput(timestampUs)
        lastTimestampUs = timestampUs
        val invalid = repDetector.onInterruption(timestampUs)
        signalExtractor.reset(); primitiveInterpreter.reset(); signalHistory.clear(); cueEngine.onInterruption()
        return MovementEngineOutput(timestampUs, null, null, invalid, emptyList(), emptyList(), emptyList(), emptyList(), paused = true)
    }

    private fun resolvedSignalProfile() = config.exerciseProfile.signalProfile.copy(
        definitions = config.exerciseProfile.signalProfile.definitions.map { d ->
            d.copy(parameters = config.resolvedSignalParameters[d.signalId] ?: d.parameters)
        }
    )
    private fun pausedOutput(ts: Long) = MovementEngineOutput(ts,null,null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),paused=true)
}
