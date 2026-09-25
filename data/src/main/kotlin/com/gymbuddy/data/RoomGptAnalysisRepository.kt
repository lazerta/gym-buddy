package com.gymbuddy.data

import com.gymbuddy.domain.persistence.GptAnalysisRecord
import com.gymbuddy.domain.persistence.GptAnalysisRepository
import java.nio.charset.StandardCharsets
import java.util.Base64

class RoomGptAnalysisRepository(
    private val dao:EvidenceDao,
):GptAnalysisRepository{
    override fun append(record:GptAnalysisRecord){
        record.sourceSetIds.forEach{id->requireNotNull(dao.set(id)){"missing GPT analysis source set: $id"}}
        require(dao.set(record.setId)!=null)
        dao.insertGptAnalysis(
            GptAnalysisEntity(
                analysisId=record.analysisId,
                setId=record.setId,
                schemaVersion=record.schemaVersion,
                modelLabel=record.modelLabel,
                createdAtEpochMs=record.createdAtEpochMs,
                sourceSetIdsPayload=encode(record.sourceSetIds.sorted()),
                summary=record.summary,
                recommendationsPayload=encode(record.recommendations),
            )
        )
    }

    override fun listForSet(setId:String):List<GptAnalysisRecord>{
        require(setId.isNotBlank())
        return dao.gptAnalysesForSet(setId).map{
            GptAnalysisRecord(
                analysisId=it.analysisId,
                setId=it.setId,
                schemaVersion=it.schemaVersion,
                modelLabel=it.modelLabel,
                createdAtEpochMs=it.createdAtEpochMs,
                sourceSetIds=decode(it.sourceSetIdsPayload).toSet(),
                summary=it.summary,
                recommendations=decode(it.recommendationsPayload),
            )
        }
    }

    private fun encode(values:List<String>):String=values.joinToString("."){
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decode(payload:String):List<String>{
        if(payload.isEmpty())return emptyList()
        return payload.split('.').map{
            String(Base64.getUrlDecoder().decode(it),StandardCharsets.UTF_8)
        }
    }
}
