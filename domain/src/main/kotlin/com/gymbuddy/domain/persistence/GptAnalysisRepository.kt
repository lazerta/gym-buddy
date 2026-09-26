package com.gymbuddy.domain.persistence

data class GptAnalysisRecord(
    val analysisId:String,
    val setId:String,
    val schemaVersion:Int,
    val modelLabel:String,
    val createdAtEpochMs:Long,
    val sourceSetIds:Set<String>,
    val summary:String,
    val recommendations:List<String> = emptyList(),
){
    init{
        require(analysisId.isNotBlank())
        require(setId.isNotBlank())
        require(schemaVersion>0)
        require(modelLabel.isNotBlank())
        require(createdAtEpochMs>=0L)
        require(sourceSetIds.isNotEmpty()&&setId in sourceSetIds)
        require(sourceSetIds.none{it.isBlank()})
        require(summary.isNotBlank())
        require(recommendations.none{it.isBlank()})
    }
}

interface GptAnalysisRepository {
    fun append(record:GptAnalysisRecord)
    fun listForSet(setId:String):List<GptAnalysisRecord>
}
