package com.gymbuddy.data

import com.gymbuddy.domain.persistence.PersonalCalibrationRepository
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.PersonalCalibrationSemanticHash

class RoomPersonalCalibrationRepository(
    private val dao:EvidenceDao,
):PersonalCalibrationRepository{
    override fun loadActive():PersonalCalibrationProfile?{
        val entity=dao.personalCalibration(ACTIVE_SLOT)?:return null
        val profile=PersonalCalibrationProfileBinaryCodec.decode(entity.payload)
        require(profile.calibrationProfileId==entity.calibrationProfileId)
        require(profile.profileVersion==entity.profileVersion)
        require(profile.semanticHash==entity.semanticHash)
        require(PersonalCalibrationSemanticHash.compute(profile)==profile.semanticHash){
            "persisted personal calibration semantic hash mismatch"
        }
        return profile
    }

    override fun saveActive(profile:PersonalCalibrationProfile){
        require(PersonalCalibrationSemanticHash.compute(profile)==profile.semanticHash){
            "personal calibration semantic hash mismatch"
        }
        dao.upsertPersonalCalibration(
            PersonalCalibrationProfileEntity(
                slotId=ACTIVE_SLOT,
                calibrationProfileId=profile.calibrationProfileId,
                profileVersion=profile.profileVersion,
                semanticHash=profile.semanticHash,
                payload=PersonalCalibrationProfileBinaryCodec.encode(profile),
            )
        )
    }

    override fun clearActive(){dao.deletePersonalCalibration(ACTIVE_SLOT)}

    companion object { private const val ACTIVE_SLOT="active" }
}
