package com.gymbuddy.data

import com.gymbuddy.domain.export.ChatGptContextRepository
import com.gymbuddy.domain.export.ChatGptSetContext
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord

class RoomChatGptContextRepository(
    private val dao:EvidenceDao,
):ChatGptContextRepository{
    private val evidenceRepository=RoomEvidenceRepository(dao)

    override fun loadSetContext(setId:String):ChatGptSetContext?{
        val set=dao.set(setId)?:return null
        val execution=dao.execution(set.executionId)?:return null
        val session=dao.session(execution.sessionId)?:return null
        val evidence=evidenceRepository.loadSet(setId)?:return null
        return ChatGptSetContext(
            session=WorkoutSessionRecord(
                session.sessionId,session.startedAtUs,session.startedAtEpochMs
            ),
            execution=ExerciseExecutionRecord(
                execution.executionId,
                execution.sessionId,
                execution.exerciseId,
                execution.startedAtUs,
                execution.startedAtEpochMs,
            ),
            evidence=evidence,
        )
    }

    override fun loadRecentComparableSetContexts(
        exerciseId:String,
        currentSetId:String,
        beforeEndedAtEpochMs:Long,
        limit:Int,
    ):List<ChatGptSetContext>{
        require(exerciseId.isNotBlank())
        require(currentSetId.isNotBlank())
        require(beforeEndedAtEpochMs>=0L)
        require(limit>0)
        return dao.recentComparableSetIds(
            exerciseId=exerciseId,
            currentSetId=currentSetId,
            beforeEndedAtEpochMs=beforeEndedAtEpochMs,
            limit=limit,
        ).mapNotNull(::loadSetContext)
    }
}
