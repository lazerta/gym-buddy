package com.gymbuddy.domain.engine

import com.gymbuddy.domain.coaching.*
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.ViewClass

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
    val invalidAttempts:List<InvalidAttemptEvidence> = emptyList(),
)

class MovementInterpretationEngine(
    private val config: AnalysisConfig,
    private val signalExtractor: MovementSignalExtractor = MovementSignalExtractor(),
    private val primitiveInterpreter: MovementPrimitiveInterpreter = MovementPrimitiveInterpreter(),
    private val repDetector: TemporalRepDetector = TemporalRepDetector(
        stepConfigs = RepDetectorConfig.fromSequence(
            config.exerciseProfile.movementPrimitiveSequence
        )
    ),
    private val evidenceBuilder: RepEvidenceBuilder = RepEvidenceBuilder(),
    private val formEngine: FormAnalysisEngine = FormAnalysisEngine(),
    cueEngine: CueEngine? = null,
    private val idNamespace:String?=null,
) {
    private val cueEngine:CueEngine = cueEngine ?: CueEngine(
        config.exerciseProfile.cuePolicy,
        config.exerciseProfile.formRuleSet,
        idNamespace,
    )
    private val signalHistory = ArrayDeque<MovementSignalFrame>()
    private val primitiveHistory = ArrayDeque<MovementPrimitiveFrame>()
    private var lastTimestampUs: Long? = null
    private var interruptionOpen = false

    fun reset() {
        lastTimestampUs = null
        interruptionOpen = false
        signalHistory.clear()
        primitiveHistory.clear()
        signalExtractor.reset()
        primitiveInterpreter.reset()
        repDetector.reset()
        cueEngine.reset()
    }

    fun process(
        timestampUs: Long,
        pose: NormalizedPose,
        observedViewClass: ViewClass? = config.preferredViewClass,
    ): MovementEngineOutput {
        val last = lastTimestampUs
        if (last != null && timestampUs <= last) return pausedOutput(timestampUs)
        lastTimestampUs = timestampUs
        val signals = signalExtractor.extract(
            timestampUs,pose,resolvedSignalProfile()
        )
        val progressIds=config.exerciseProfile.movementPrimitiveSequence.steps
            .flatMap{it.progressSignalIds}.distinct()
        val unavailableProgress=progressIds.mapNotNull{id->signals.values[id]?.takeUnless{it.isKnown}}
        val missingProgress=progressIds.any{it !in signals.values}
        if(missingProgress||unavailableProgress.isNotEmpty()){
            val warmupOnly=!missingProgress&&unavailableProgress.all{
                it.unknownReason==SignalUnknownReason.INSUFFICIENT_HISTORY
            }
            return interruptAcceptedTimestamp(timestampUs,resetSignals=!warmupOnly)
                .copy(signals=signals)
        }
        interruptionOpen=false
        signalHistory.add(signals)
        while(signalHistory.size>1500)signalHistory.removeFirst()
        val primitives=primitiveInterpreter.interpret(
            signals,config.exerciseProfile.movementPrimitiveSequence
        )
        primitiveHistory.add(primitives)
        while(primitiveHistory.size>1500)primitiveHistory.removeFirst()
        val repEvents=repDetector.update(primitives)
        val invalid=invalidAttempts(repEvents)
        val evidence=mutableListOf<RepEvidence>()
        val forms=mutableListOf<FormObservation>()
        val cues=mutableListOf<CueEvent>()
        val responses=mutableListOf<CueResponse>()
        val signalSnapshot=signalHistory.toList()
        val primitiveSnapshot=primitiveHistory.toList()
        val completed=repEvents.filter{it.kind==RepCompletionKind.COMPLETED}
        completed.forEach{event->
            val rep=evidenceBuilder.build(
                event,signalSnapshot,config,idNamespace,primitiveSnapshot
            )
            val obs=formEngine.analyze(rep,AnalysisConfigResolver.forObservedView(config,observedViewClass))
            val cueDecision=cueEngine.evaluate(rep,obs)
            evidence+=rep
            forms+=obs
            cues+=cueDecision.cues
            responses+=cueDecision.responses
        }
        val through=completed.maxOfOrNull{it.completedAtUs}
        if(through!=null){
            while(signalHistory.isNotEmpty()&&signalHistory.first().timestampUs<=through)signalHistory.removeFirst()
            while(primitiveHistory.isNotEmpty()&&primitiveHistory.first().timestampUs<=through)primitiveHistory.removeFirst()
        }
        return MovementEngineOutput(
            timestampUs,signals,primitives,repEvents,evidence,
            forms,cues,responses,paused=false,invalidAttempts=invalid
        )
    }

    fun onInterruption(timestampUs:Long):MovementEngineOutput{
        val last=lastTimestampUs
        if(last!=null&&timestampUs<=last)return pausedOutput(timestampUs)
        lastTimestampUs=timestampUs
        return interruptAcceptedTimestamp(timestampUs)
    }

    private fun interruptAcceptedTimestamp(
        timestampUs:Long,
        resetSignals:Boolean=true,
    ):MovementEngineOutput{
        if(interruptionOpen)return pausedOutput(timestampUs)
        interruptionOpen=true
        val events=repDetector.onInterruption(timestampUs)
        if(resetSignals)signalExtractor.reset()
        primitiveInterpreter.reset()
        signalHistory.clear()
        primitiveHistory.clear()
        cueEngine.onInterruption()
        return MovementEngineOutput(
            timestampUs,null,null,events,emptyList(),emptyList(),
            emptyList(),emptyList(),paused=true,invalidAttempts=invalidAttempts(events)
        )
    }

    private fun invalidAttempts(events:List<RepDetectionEvent>)=events
        .filter{it.kind==RepCompletionKind.INVALID_ATTEMPT}
        .map{event->
            val base="attempt-${event.stepId}-${event.startedAtUs}-${event.completedAtUs}"
            InvalidAttemptEvidence(
                attemptId=idNamespace?.let{"$it/$base"}?:base,
                stepId=event.stepId,
                primitive=event.primitive,
                startedAtUs=event.startedAtUs,
                endedAtUs=event.completedAtUs,
                reason=requireNotNull(event.invalidReason),
                minConfidence=event.minConfidence,
            )
        }

    private fun resolvedSignalProfile()=
        config.exerciseProfile.signalProfile.copy(
            definitions=config.exerciseProfile.signalProfile.definitions.map{d->
                d.copy(parameters=config.resolvedSignalParameters[d.signalId]?:d.parameters)
            }
        )

    private fun pausedOutput(ts:Long)=MovementEngineOutput(
        ts,null,null,emptyList(),emptyList(),emptyList(),emptyList(),
        emptyList(),paused=true
    )
}
