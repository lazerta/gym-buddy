package com.gymbuddy.data

import com.gymbuddy.domain.persistence.GptAnalysisRecord
import com.gymbuddy.domain.persistence.GptAnalysisRepository
import java.nio.charset.StandardCharsets
import java.util.Base64

class RoomGptAnalysisRepository(
    private val dao:EvidenceDao,
):GptAnalysisRepository{
    override fun append(record:GptAnalysisRecord){
        val sources=record.sourceSetIds.sorted()
        val recommendations=record.recommendations.toList()
        require(sources.isNotEmpty()&&record.setId in sources&&sources.none{it.isBlank()})
        require(recommendations.none{it.isBlank()})
        dao.appendAnalysis(
            GptAnalysisEntity(
                analysisId=record.analysisId,
                setId=record.setId,
                schemaVersion=record.schemaVersion,
                modelLabel=record.modelLabel,
                createdAtEpochMs=record.createdAtEpochMs,
                sourceSetIdsPayload=encode(sources),
                summary=record.summary,
                recommendationsPayload=encode(recommendations),
            ),sources
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
