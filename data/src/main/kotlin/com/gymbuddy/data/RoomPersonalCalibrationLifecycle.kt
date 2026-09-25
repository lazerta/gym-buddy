package com.gymbuddy.data

import com.gymbuddy.domain.camera.PersonalCameraPriorCodec
import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.persistence.PersistedSetEvidence
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.CalibrationSessionEligibility
import com.gymbuddy.domain.profile.CalibrationSessionEvidence
import com.gymbuddy.domain.profile.CalibrationUpdateStatus
import com.gymbuddy.domain.profile.ExerciseBaselineKey
import com.gymbuddy.domain.profile.MultiSessionCalibrationUpdater
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profile.SemanticHash
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ProductionCalibrationEvidencePolicy(
    val minimumMetricConfidence:Double=.60,
    val maximumActiveDegradedFraction:Double=.20,
    val minimumActiveTrackingFrames:Int=3,
    val cameraPriorMinimumSessions:Int=3,
    val cameraPriorConfidenceAtMinimum:Double=.70,
    val cameraPriorConfidenceGainPerAdditionalSession:Double=.05,
    val cameraPriorMaximumConfidence:Double=.90,
    val cameraPriorMinimumTolerance:Double=.02,
    val cameraPriorMaximumTolerance:Double=.10,
){
    init{
        require(minimumMetricConfidence in 0.0..1.0)
        require(maximumActiveDegradedFraction in 0.0..1.0)
        require(minimumActiveTrackingFrames>=1)
        require(cameraPriorMinimumSessions>=2)
        require(cameraPriorConfidenceAtMinimum in 0.0..1.0)
        require(cameraPriorConfidenceGainPerAdditionalSession>=0.0)
        require(cameraPriorMaximumConfidence in cameraPriorConfidenceAtMinimum..1.0)
        require(cameraPriorMinimumTolerance in 0.0..1.0)
        require(cameraPriorMaximumTolerance in cameraPriorMinimumTolerance..1.0)
    }
}

class RoomPersonalCalibrationLifecycle(
    private val dao:EvidenceDao,
    private val evidenceRepository:RoomEvidenceRepository=RoomEvidenceRepository(dao),
    private val calibrationRepository:RoomPersonalCalibrationRepository=
        RoomPersonalCalibrationRepository(dao),
    private val updater:MultiSessionCalibrationUpdater=MultiSessionCalibrationUpdater(),
    private val historyLimit:Int=12,
    private val policy:ProductionCalibrationEvidencePolicy=
        ProductionCalibrationEvidencePolicy(),
){
    init{require(historyLimit>=3)}

    private data class SessionAggregate(
        val evidence:CalibrationSessionEvidence,
        val setIds:Set<String>,
        val sessionId:String,
        val key:ExerciseBaselineKey,
        val frameFill:Double?,
    )

    fun onCompletedSet(
        setId:String,
        config:AnalysisConfig,
    ):PersonalCalibrationProfile?{
        val currentSet=evidenceRepository.loadSet(setId)?:return safeActive()
        val currentSummary=currentSet.summary?:return safeActive()
        val actualView=currentSet.tracking?.observedViewClass?:return safeActive()
        val execution=dao.execution(currentSet.set.executionId)?:return safeActive()
        val session=dao.session(execution.sessionId)?:return safeActive()
        val supportedMetricIds=config.exerciseProfile.metricProfile.metrics
            .map{it.metricId}
            .toSet()
        if(supportedMetricIds.isEmpty())return safeActive()

        val targetKey=ExerciseBaselineKey(
            exerciseProfileId=config.exerciseProfile.profileId,
            exerciseProfileVersion=config.exerciseProfile.profileVersion,
            equipmentProfileId=config.equipmentProfile?.profileId,
            viewClass=actualView,
        )

        val sessionOrder=
            session.startedAtEpochMs.takeIf{it>0L}?:session.startedAtUs
        val priorSessionIds=dao.recentComparableSessionIds(
            exerciseId=config.exerciseDefinition.exerciseId,
            currentSessionId=session.sessionId,
            beforeSessionStartedAtEpochMs=sessionOrder,
            limit=historyLimit-1,
        )
        val sessionIds=listOf(session.sessionId)+priorSessionIds
        val current=safeActive()
        val accepted=scopedReferences(current,config,targetKey)
        // Reset/rebuild starts a fresh immutable history, never reuses an old v1.
        val profileId=current?.calibrationProfileId?:"personal-local-"+UUID.randomUUID()

        val allAggregates=sessionIds.mapNotNull{sessionId->
            toSessionAggregate(
                sessionId=sessionId,
                config=config,
                targetKey=targetKey,
                supportedMetricIds=supportedMetricIds,
            )
        }
        val movementAggregates=allAggregates.filterNot{aggregate->
            aggregate.evidence.evidenceReference in accepted||
                aggregate.setIds.any{("set:"+it) in accepted}
        }

        val proposal=updater.propose(
            calibrationProfileId=profileId,
            currentProfile=current,
            targetKey=targetKey,
            supportedMetricIds=supportedMetricIds,
            sessions=movementAggregates.map{it.evidence},
        )
        val movementCandidate=
            if(proposal.status==CalibrationUpdateStatus.PROPOSED){
                proposal.candidateProfile
            }else{
                current
            }
        val movementChanged=
            movementCandidate?.semanticHash!=current?.semanticHash

        val combined=applyCameraPrior(
            base=movementCandidate,
            current=current,
            aggregates=allAggregates,
            config=config,
            actualView=actualView,
            keepBaseVersion=movementChanged,
            acceptedReferences=accepted,
            profileId=profileId,
        )

        var finalProfile=combined?:movementCandidate?:current
        if(finalProfile!=null && finalProfile.semanticHash!=current?.semanticHash){
            finalProfile=withReferences(finalProfile,
                finalProfile.evidenceReferences.filterNot(::isLegacySessionReference).toSet()+accepted)
        }
        if(
            finalProfile!=null&&
            finalProfile.semanticHash!=current?.semanticHash
        ){
            calibrationRepository.saveActive(finalProfile)
        }
        return finalProfile
    }

    fun resetTarget(config:AnalysisConfig):Boolean{
        val current=try{
            calibrationRepository.loadActive()
        }catch(_:Throwable){
            calibrationRepository.clearActive()
            return true
        }?:return true

        val filteredBaselines=current.exerciseBaselines.filterNot{baseline->
            baseline.key.exerciseProfileId==config.exerciseProfile.profileId&&
                baseline.key.exerciseProfileVersion==
                    config.exerciseProfile.profileVersion&&
                baseline.key.equipmentProfileId==config.equipmentProfile?.profileId
        }
        val cameraKeys=config.exerciseProfile.cameraProfile.allowedViewClasses
            .flatMap{view->
                PersonalCameraPriorCodec.entryKeys(
                    profile=config.exerciseProfile.cameraProfile,
                    equipmentProfileId=config.equipmentProfile?.profileId,
                    viewClass=view,
                )
            }.toSet()
        val filteredCamera=current.cameraSetupPreferences
            .filterKeys{it !in cameraKeys}

        if(
            filteredBaselines.size==current.exerciseBaselines.size&&
            filteredCamera.size==current.cameraSetupPreferences.size
        ){
            return true
        }
        val reset=PersonalCalibrationProfile.create(
            calibrationProfileId=current.calibrationProfileId,
            profileVersion=current.profileVersion+1,
            sourceConfidence=current.sourceConfidence,
            normalizedBodyGeometry=current.normalizedBodyGeometry,
            cameraSetupPreferences=filteredCamera,
            exerciseBaselines=filteredBaselines,
            equipmentAssociations=current.equipmentAssociations,
            lateralityBaseline=current.lateralityBaseline,
            cueEffectiveness=current.cueEffectiveness,
            evidenceReferences=scopedReferences(current,config,null),
        )
        calibrationRepository.saveActive(reset)
        return true
    }

    private fun toSessionAggregate(
        sessionId:String,
        config:AnalysisConfig,
        targetKey:ExerciseBaselineKey,
        supportedMetricIds:Set<String>,
    ):SessionAggregate?{
        val session=dao.session(sessionId)?:return null
        val setIds=dao.completedSetIdsForSessionExercise(
            sessionId,
            config.exerciseDefinition.exerciseId,
        )
        if(setIds.isEmpty())return null

        val contextSets=setIds.mapNotNull(evidenceRepository::loadSet)
            .filter{it.sameAnalysisContext(config)}
        val sets=contextSets.filter{it.tracking?.observedViewClass==targetKey.viewClass}
        if(sets.isEmpty())return null

        // Quality is assessed before view selection: an interrupted/mixed-view
        // sibling attempt cannot disappear and make this workout appear clean.
        val eligibility=if(dao.sessionHasInterruptedAttempt(sessionId))
            CalibrationSessionEligibility.INTERRUPTED else sessionEligibility(contextSets)
        val metricSamples=linkedMapOf<String,MutableList<Double>>()
        var sawLowConfidence=false

        supportedMetricIds.sorted().forEach{metricId->
            sets.forEach{setEvidence->
                setEvidence.reps
                    .filter{it.classification==RepClassification.NORMAL}
                    .forEach{rep->
                        val known=rep.metrics[metricId]?.value as? EvidenceValue.Known
                            ?:return@forEach
                        val confidence=known.confidence
                        if(
                            confidence==null||
                            confidence<policy.minimumMetricConfidence
                        ){
                            sawLowConfidence=true
                            return@forEach
                        }
                        metricSamples.getOrPut(metricId){mutableListOf()}
                            .add(known.value)
                    }
            }
        }

        val resolvedEligibility=when{
            eligibility!=CalibrationSessionEligibility.ELIGIBLE->eligibility
            metricSamples.values.all{it.isEmpty()}&&sawLowConfidence->
                CalibrationSessionEligibility.LOW_CONFIDENCE
            metricSamples.values.all{it.isEmpty()}->
                CalibrationSessionEligibility.OBSERVABILITY_POOR
            else->CalibrationSessionEligibility.ELIGIBLE
        }
        val sessionOrder=
            session.startedAtEpochMs.takeIf{it>0L}?:session.startedAtUs
        val frameFills=sets.mapNotNull{it.tracking?.activeFrameFillMean}

        return SessionAggregate(
            evidence=CalibrationSessionEvidence(
                evidenceReference=movementReference(sessionId,targetKey),
                sequence=sessionOrder,
                key=targetKey,
                eligibility=resolvedEligibility,
                metricSamples=metricSamples.mapValues{it.value.toList()},
            ),
            setIds=sets.map{it.set.setId}.toSet(),
            sessionId=sessionId,
            key=targetKey,
            frameFill=frameFills.takeIf{it.isNotEmpty()}?.average(),
        )
    }

    private fun sessionEligibility(
        sets:List<PersistedSetEvidence>,
    ):CalibrationSessionEligibility{
        if(sets.any{set->
            set.observations.any{it.state==FormObservationState.DEVIATION}
        }){
            return CalibrationSessionEligibility.FORM_DEVIATION
        }
        if(sets.flatMap{it.observations}.none{
            it.state==FormObservationState.OK
        }){
            return CalibrationSessionEligibility.OBSERVABILITY_POOR
        }

        val tracking=sets.map{it.tracking?:return CalibrationSessionEligibility.OBSERVABILITY_POOR}
        if(tracking.any{it.cameraDisturbanceEpisodes>0}){
            return CalibrationSessionEligibility.CAMERA_DISTURBANCE
        }
        if(tracking.any{it.interruptionEpisodes>0}){
            return CalibrationSessionEligibility.INTERRUPTED
        }

        val activeFrames=tracking.sumOf{
            it.activeObservableFrames+
                it.activeDegradedFrames+
                it.activePausedFrames+
                it.activeUnknownFrames
        }
        if(activeFrames<policy.minimumActiveTrackingFrames){
            return CalibrationSessionEligibility.OBSERVABILITY_POOR
        }
        val paused=tracking.sumOf{it.activePausedFrames+it.activeUnknownFrames}
        if(paused>0){
            return CalibrationSessionEligibility.TRACKING_POOR
        }
        val degraded=tracking.sumOf{it.activeDegradedFrames}
        val degradedFraction=degraded.toDouble()/activeFrames
        if(degradedFraction>policy.maximumActiveDegradedFraction){
            return CalibrationSessionEligibility.TRACKING_POOR
        }
        if(tracking.any{it.observedViewClass==null}){
            return CalibrationSessionEligibility.OBSERVABILITY_POOR
        }
        return CalibrationSessionEligibility.ELIGIBLE
    }

    private fun applyCameraPrior(
        base:PersonalCalibrationProfile?,
        current:PersonalCalibrationProfile?,
        aggregates:List<SessionAggregate>,
        config:AnalysisConfig,
        actualView:ViewClass,
        keepBaseVersion:Boolean,
        acceptedReferences:Set<String>,
        profileId:String,
    ):PersonalCalibrationProfile?{
        val alreadyAccepted=acceptedReferences+base?.evidenceReferences.orEmpty()
        val eligible=aggregates.filter{
            val cameraRef=cameraReference(it.sessionId,it.key)
            it.evidence.eligibility==CalibrationSessionEligibility.ELIGIBLE&&
                it.frameFill!=null&&
                cameraRef !in alreadyAccepted
        }
        if(eligible.size<policy.cameraPriorMinimumSessions)return base

        val fills=eligible.map{requireNotNull(it.frameFill)}.sorted()
        val target=median(fills)
        if(target !in config.exerciseProfile.cameraProfile.frameFillRange)return base

        val tolerance=max(
            policy.cameraPriorMinimumTolerance,
            median(fills.map{abs(it-target)}),
        )
        if(tolerance>policy.cameraPriorMaximumTolerance)return base

        val confidence=min(
            policy.cameraPriorMaximumConfidence,
            policy.cameraPriorConfidenceAtMinimum+
                (eligible.size-policy.cameraPriorMinimumSessions)*
                policy.cameraPriorConfidenceGainPerAdditionalSession,
        )
        val entries=PersonalCameraPriorCodec.entries(
            profile=config.exerciseProfile.cameraProfile,
            equipmentProfileId=config.equipmentProfile?.profileId,
            viewClass=actualView,
            targetFrameFill=target,
            tolerance=tolerance,
            sessionCount=eligible.size,
            confidence=confidence,
        )
        val existing=base?.cameraSetupPreferences.orEmpty()
        if(entries.all{existing[it.key]==it.value})return base

        val version=when{
            base==null->1
            keepBaseVersion->base.profileVersion
            else->base.profileVersion+1
        }
        val evidenceRefs=
            base?.evidenceReferences.orEmpty()+
                eligible.map{cameraReference(it.sessionId,it.key)}
        return PersonalCalibrationProfile.create(
            calibrationProfileId=
                base?.calibrationProfileId?:current?.calibrationProfileId?:profileId,
            profileVersion=version,
            sourceConfidence=max(base?.sourceConfidence?:0.0,confidence),
            normalizedBodyGeometry=base?.normalizedBodyGeometry.orEmpty(),
            cameraSetupPreferences=existing+entries,
            exerciseBaselines=base?.exerciseBaselines.orEmpty(),
            equipmentAssociations=
                base?.equipmentAssociations.orEmpty()+
                    listOfNotNull(config.equipmentProfile?.profileId),
            lateralityBaseline=base?.lateralityBaseline.orEmpty(),
            cueEffectiveness=base?.cueEffectiveness.orEmpty(),
            evidenceReferences=evidenceRefs,
        )
    }

    private fun PersistedSetEvidence.sameAnalysisContext(
        config:AnalysisConfig,
    ):Boolean{
        val expected=config.provenance
        val actual=analysisProvenance
        return actual.exerciseDefinitionId==expected.exerciseDefinitionId&&
            actual.exerciseDefinitionVersion==expected.exerciseDefinitionVersion&&
            actual.exerciseDefinitionSemanticHash==
                expected.exerciseDefinitionSemanticHash&&
            actual.exerciseProfile==expected.exerciseProfile&&
            actual.cameraProfile==expected.cameraProfile&&
            actual.metricProfile==expected.metricProfile&&
            actual.equipmentProfile==expected.equipmentProfile
    }

    private fun safeActive():PersonalCalibrationProfile?=
        runCatching{calibrationRepository.loadActive()}.getOrNull()

    private fun median(values:List<Double>):Double{
        require(values.isNotEmpty())
        val sorted=values.sorted()
        val mid=sorted.size/2
        return if(sorted.size%2==1){
            sorted[mid]
        }else{
            (sorted[mid-1]+sorted[mid])/2.0
        }
    }

    /** Translate legacy global markers only to the contexts they could have
     * trained. Keep consumed evidence scoped after reset, without blocking a
     * different exercise/equipment/view in the same workout. */
    private fun scopedReferences(
        profile:PersonalCalibrationProfile?, config:AnalysisConfig, target:ExerciseBaselineKey?,
    ):Set<String>{
        if(profile==null)return emptySet()
        val keys=profile.exerciseBaselines.map{it.key}.toMutableSet()
        config.exerciseProfile.cameraProfile.allowedViewClasses.forEach { view ->
            if(PersonalCameraPriorCodec.resolve(profile,config.exerciseProfile.cameraProfile,
                config.equipmentProfile?.profileId,view)!=null){
                keys+=ExerciseBaselineKey(config.exerciseProfile.profileId,config.exerciseProfile.profileVersion,
                    config.equipmentProfile?.profileId,view)
            }
        }
        return profile.evidenceReferences.flatMap{ref ->
            when {
                ref.startsWith("movement-session:") && isLegacySessionReference(ref) ->
                    keys.map{movementReference(ref.removePrefix("movement-session:"),it)}
                ref.startsWith("camera-session:") && isLegacySessionReference(ref) ->
                    keys.map{cameraReference(ref.removePrefix("camera-session:"),it)}
                else -> listOf(ref)
            }
        }.toSet()
    }

    private fun withReferences(p:PersonalCalibrationProfile, refs:Set<String>)=PersonalCalibrationProfile.create(
        p.calibrationProfileId,p.profileVersion,p.sourceConfidence,p.normalizedBodyGeometry,
        p.cameraSetupPreferences,p.exerciseBaselines,p.equipmentAssociations,p.lateralityBaseline,
        p.cueEffectiveness,refs,
    )

    companion object {
        private fun contextId(key:ExerciseBaselineKey)=SemanticHash.sha256(
            key.exerciseProfileId,key.exerciseProfileVersion.toString(),
            key.equipmentProfileId.orEmpty(),key.viewClass?.name.orEmpty())
        internal fun movementReference(sessionId:String,key:ExerciseBaselineKey)=
            "movement-session:"+sessionId+":context:"+contextId(key)
        private fun cameraReference(sessionId:String,key:ExerciseBaselineKey)=
            "camera-session:"+sessionId+":context:"+contextId(key)
        private fun isLegacySessionReference(ref:String)=
            (ref.startsWith("movement-session:")||ref.startsWith("camera-session:"))&&!ref.contains(":context:")
    }
}
