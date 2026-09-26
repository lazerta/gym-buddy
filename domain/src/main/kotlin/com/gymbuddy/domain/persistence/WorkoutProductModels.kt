package com.gymbuddy.domain.persistence

import com.gymbuddy.domain.profile.EquipmentProfile
import com.gymbuddy.domain.profile.SemanticHash

enum class LoadBasis { TOTAL, PER_IMPLEMENT, PER_SIDE, STACK, BODYWEIGHT, UNKNOWN }
// CARRIED_FROM_PLAN is not an independent measurement of performed load.
enum class LoadSource { USER_ENTERED, PLANNED, IMPORTED, RECOVERED, UNKNOWN, CARRIED_FROM_PLAN }
enum class ResistanceKind { UNKNOWN, EXTERNAL_LOAD, ASSISTANCE, BODYWEIGHT }
enum class LoadMeasurementMode { UNKNOWN, IMPLEMENT_MASS, DISPLAY_VALUE, ADDED_LOAD }

data class ExerciseStartRequest(
    val actualExerciseId:String,
    val plannedExerciseId:String?=null,
    val equipmentContext:EquipmentContextRecord?=null,
){
    init{
        require(actualExerciseId.isNotBlank())
        require(plannedExerciseId==null||plannedExerciseId.isNotBlank())
    }
}

data class EquipmentContextRecord(
    val contextId:String,
    val baseEquipmentProfileId:String,
    val label:String,
    val updatedAtEpochMs:Long=0L,
){
    init{
        require(contextId.isNotBlank())
        require(baseEquipmentProfileId.isNotBlank())
        require(label.isNotBlank())
        require(updatedAtEpochMs>=0L)
    }

    fun specialize(base:EquipmentProfile):EquipmentProfile{
        require(base.profileId==baseEquipmentProfileId)
        return base.copy(
            profileId=contextId,
            semanticHash=SemanticHash.sha256(
                "equipment-context-v1",
                base.profileId,
                base.profileVersion.toString(),
                base.semanticHash,
                contextId,
                label,
            ),
        )
    }
}

data class ExercisePreferenceRecord(
    val exerciseId:String,
    val favorite:Boolean=false,
    val lastSelectedAtEpochMs:Long=0L,
    val equipmentContextId:String?=null,
){
    init{
        require(exerciseId.isNotBlank())
        require(lastSelectedAtEpochMs>=0L)
        require(equipmentContextId==null||equipmentContextId.isNotBlank())
    }
}

data class WorkoutExerciseCompletionRecord(
    val sessionId:String,
    val exerciseId:String,
    val completedSets:Int,
    val completedAtEpochMs:Long,
){
    init{
        require(sessionId.isNotBlank())
        require(exerciseId.isNotBlank())
        require(completedSets>=0)
        require(completedAtEpochMs>=0L)
    }
}

data class WorkoutSelectionSnapshot(
    val activeSessionId:String?=null,
    val preferences:List<ExercisePreferenceRecord> = emptyList(),
    val completions:List<WorkoutExerciseCompletionRecord> = emptyList(),
    val equipmentContexts:List<EquipmentContextRecord> = emptyList(),
){
    init{require(activeSessionId==null||activeSessionId.isNotBlank())}
}

interface WorkoutProductRepository {
    fun loadSelectionSnapshot():WorkoutSelectionSnapshot
    fun setActiveSession(sessionId:String?)
    fun rememberExerciseSelection(record:ExercisePreferenceRecord)
    fun setFavorite(exerciseId:String,favorite:Boolean)
    fun rememberEquipmentContext(record:EquipmentContextRecord)
    fun markExerciseCompleted(record:WorkoutExerciseCompletionRecord)
    fun clearWorkoutCompletion(sessionId:String)
}

object ExerciseSearch {
    data class Candidate(
        val exerciseId:String,
        val displayName:String,
        val aliases:Set<String>,
    )

    fun search(query:String,candidates:List<Candidate>):List<Candidate>{
        val q=normalize(query)
        if(q.isBlank())return candidates.sortedBy{it.displayName.lowercase()}
        return candidates.asSequence()
            .map{candidate->candidate to score(q,candidate)}
            .filter{it.second>0}
            .sortedWith(compareByDescending<Pair<Candidate,Int>>{it.second}
                .thenBy{it.first.displayName.lowercase()}
                .thenBy{it.first.exerciseId})
            .map{it.first}
            .distinctBy{it.exerciseId}
            .toList()
    }

    private fun score(q:String,c:Candidate):Int{
        val id=normalize(c.exerciseId)
        val name=normalize(c.displayName)
        val aliases=c.aliases.map(::normalize)
        return when{
            q==id||q==name||q in aliases->100
            name.startsWith(q)||id.startsWith(q)||aliases.any{it.startsWith(q)}->80
            q in name||q in id||aliases.any{q in it}->50
            q.split(' ').filter{it.isNotBlank()}.all{token->
                token in name||token in id||aliases.any{token in it}
            }->30
            else->0
        }
    }

    private fun normalize(value:String)=value.trim().lowercase()
        .replace('_',' ').replace('-',' ')
        .split(Regex("\\s+")).filter{it.isNotBlank()}.joinToString(" ")
}
