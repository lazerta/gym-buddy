package com.gymbuddy.data

import com.gymbuddy.domain.evidence.EvidenceValue
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.CalibrationSessionEligibility
import com.gymbuddy.domain.profile.CalibrationSessionEvidence
import com.gymbuddy.domain.profile.CalibrationUpdateStatus
import com.gymbuddy.domain.profile.ExerciseBaselineKey
import com.gymbuddy.domain.profile.MultiSessionCalibrationUpdater
import com.gymbuddy.domain.profile.PersonalCalibrationProfile

class RoomPersonalCalibrationLifecycle(
    private val dao:EvidenceDao,
    private val evidenceRepository:RoomEvidenceRepository=RoomEvidenceRepository(dao),
    private val calibrationRepository:RoomPersonalCalibrationRepository=RoomPersonalCalibrationRepository(dao),
    private val updater:MultiSessionCalibrationUpdater=MultiSessionCalibrationUpdater(),
    private val historyLimit:Int=12,
){
    init{require(historyLimit>=3)}

    fun onCompletedSet(setId:String,config:AnalysisConfig):PersonalCalibrationProfile?{
        val currentSet=evidenceRepository.loadSet(setId)?:return safeActive()
        val summary=currentSet.summary?:return safeActive()
        val supportedMetricIds=config.exerciseProfile.metricProfile.metrics.map{it.metricId}.toSet()
        if(supportedMetricIds.isEmpty())return safeActive()

        val targetKey=ExerciseBaselineKey(
            exerciseProfileId=config.exerciseProfile.profileId,
            exerciseProfileVersion=config.exerciseProfile.profileVersion,
            equipmentProfileId=config.equipmentProfile?.profileId,
            viewClass=config.preferredViewClass,
        )
        val priorIds=dao.recentComparableSetIds(
            exerciseId=config.exerciseDefinition.exerciseId,
            currentSetId=setId,
            beforeEndedAtUs=summary.endedAtUs,
            limit=historyLimit-1,
        )
        val current=safeActive()
        val alreadyAccepted=current?.evidenceReferences.orEmpty()
        val sessions=(listOf(setId)+priorIds)
            .mapNotNull{candidateId->
                evidenceRepository.loadSet(candidateId)
                    ?.toCalibrationSession(config,targetKey,supportedMetricIds)
            }
            .filter{it.evidenceReference !in alreadyAccepted}

        val proposal=updater.propose(
            calibrationProfileId=current?.calibrationProfileId?:DEFAULT_PROFILE_ID,
            currentProfile=current,
            targetKey=targetKey,
            supportedMetricIds=supportedMetricIds,
            sessions=sessions,
        )
        if(proposal.status!=CalibrationUpdateStatus.PROPOSED)return current
        val candidate=proposal.candidateProfile?:return current
        calibrationRepository.saveActive(candidate)
        return candidate
    }

    fun resetTarget(config:AnalysisConfig):Boolean{
        val current=try{
            calibrationRepository.loadActive()
        }catch(_:Throwable){
            calibrationRepository.clearActive()
            return true
        }?:return true
        val key=ExerciseBaselineKey(
            exerciseProfileId=config.exerciseProfile.profileId,
            exerciseProfileVersion=config.exerciseProfile.profileVersion,
            equipmentProfileId=config.equipmentProfile?.profileId,
            viewClass=config.preferredViewClass,
        )
        val reset=updater.resetTargetBaseline(current,key)
        if(reset!==current)calibrationRepository.saveActive(reset)
        return true
    }

    private fun safeActive():PersonalCalibrationProfile?=
        runCatching{calibrationRepository.loadActive()}.getOrNull()

    private fun com.gymbuddy.domain.persistence.PersistedSetEvidence.toCalibrationSession(
        config:AnalysisConfig,
        targetKey:ExerciseBaselineKey,
        supportedMetricIds:Set<String>,
    ):CalibrationSessionEvidence?{
        val summary=summary?:return null
        if(!sameAnalysisContext(config))return null
        val normalReps=reps.filter{it.classification==RepClassification.NORMAL}
        if(normalReps.isEmpty())return null

        val metricSamples=linkedMapOf<String,List<Double>>()
        supportedMetricIds.sorted().forEach{metricId->
            val values=normalReps.mapNotNull{rep->
                val metric=rep.metrics[metricId]?:return@mapNotNull null
                (metric.value as? EvidenceValue.Known)?.value
            }
            if(values.isNotEmpty())metricSamples[metricId]=values
        }
        if(metricSamples.isEmpty())return null

        return CalibrationSessionEvidence(
            evidenceReference="set:"+set.setId,
            sequence=summary.endedAtUs,
            key=targetKey,
            eligibility=CalibrationSessionEligibility.ELIGIBLE,
            metricSamples=metricSamples,
        )
    }

    private fun com.gymbuddy.domain.persistence.PersistedSetEvidence.sameAnalysisContext(
        config:AnalysisConfig,
    ):Boolean{
        val expected=config.provenance
        val actual=analysisProvenance
        return actual.exerciseDefinitionId==expected.exerciseDefinitionId &&
            actual.exerciseDefinitionVersion==expected.exerciseDefinitionVersion &&
            actual.exerciseDefinitionSemanticHash==expected.exerciseDefinitionSemanticHash &&
            actual.exerciseProfile==expected.exerciseProfile &&
            actual.cameraProfile==expected.cameraProfile &&
            actual.metricProfile==expected.metricProfile &&
            actual.equipmentProfile==expected.equipmentProfile
    }

    companion object{
        private const val DEFAULT_PROFILE_ID="personal-local"
    }
}
