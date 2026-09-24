package com.gymbuddy.data

import com.gymbuddy.domain.persistence.PersonalCalibrationRepository
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.PersonalCalibrationSemanticHash
import com.gymbuddy.domain.profile.PersonalCalibrationVersionRef

class RoomPersonalCalibrationRepository(
    private val dao:EvidenceDao,
):PersonalCalibrationRepository{
    override fun loadActive():PersonalCalibrationProfile?{
        val entity=dao.personalCalibration(ACTIVE_SLOT)?:return null
        return decodeAndValidate(
            entity.calibrationProfileId,
            entity.profileVersion,
            entity.semanticHash,
            entity.payload,
        )
    }

    fun loadVersion(ref:PersonalCalibrationVersionRef):PersonalCalibrationProfile?{
        val entity=dao.personalCalibrationHistory(
            ref.calibrationProfileId,
            ref.profileVersion,
        )?:return null
        require(entity.semanticHash==ref.semanticHash){
            "historical personal calibration semantic hash mismatch"
        }
        return decodeAndValidate(
            entity.calibrationProfileId,
            entity.profileVersion,
            entity.semanticHash,
            entity.payload,
        )
    }

    override fun saveActive(profile:PersonalCalibrationProfile){
        require(PersonalCalibrationSemanticHash.compute(profile)==profile.semanticHash){
            "personal calibration semantic hash mismatch"
        }
        val payload=PersonalCalibrationProfileBinaryCodec.encode(profile)
        val existing=dao.personalCalibrationHistory(
            profile.calibrationProfileId,
            profile.profileVersion,
        )
        if(existing==null){
            dao.insertPersonalCalibrationHistory(
                PersonalCalibrationProfileHistoryEntity(
                    calibrationProfileId=profile.calibrationProfileId,
                    profileVersion=profile.profileVersion,
                    semanticHash=profile.semanticHash,
                    payload=payload,
                )
            )
        }else{
            require(existing.semanticHash==profile.semanticHash){
                "published calibration version is immutable"
            }
            require(existing.payload==payload){
                "published calibration payload is immutable"
            }
        }
        dao.upsertPersonalCalibration(
            PersonalCalibrationProfileEntity(
                slotId=ACTIVE_SLOT,
                calibrationProfileId=profile.calibrationProfileId,
                profileVersion=profile.profileVersion,
                semanticHash=profile.semanticHash,
                payload=payload,
            )
        )
    }

    override fun clearActive(){dao.deletePersonalCalibration(ACTIVE_SLOT)}

    private fun decodeAndValidate(
        calibrationProfileId:String,
        profileVersion:Int,
        semanticHash:String,
        payload:String,
    ):PersonalCalibrationProfile{
        val profile=PersonalCalibrationProfileBinaryCodec.decode(payload)
        require(profile.calibrationProfileId==calibrationProfileId)
        require(profile.profileVersion==profileVersion)
        require(profile.semanticHash==semanticHash)
        require(PersonalCalibrationSemanticHash.compute(profile)==profile.semanticHash){
            "persisted personal calibration semantic hash mismatch"
        }
        return profile
    }

    companion object { private const val ACTIVE_SLOT="active" }
}
