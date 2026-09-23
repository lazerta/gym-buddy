package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.SetSummary
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomChatGptContextRepositoryTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test
    fun recentComparableHistoryIsSameExerciseFinalizedAndBeforeCurrent(){
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try{
            val evidence=RoomEvidenceRepository(db.evidenceDao())
            addFinalizedSet(
                evidence,"session-old","exec-old","old",1,900,1_000,
                InitialExerciseProfiles.inclineDumbbellPress,
            )
            addFinalizedSet(
                evidence,"session-recent","exec-recent","recent",1,1_900,2_000,
                InitialExerciseProfiles.inclineDumbbellPress,
            )
            addFinalizedSet(
                evidence,"session-other","exec-other","other",1,2_400,2_500,
                InitialExerciseProfiles.smithMachineSquat,
            )
            addFinalizedSet(
                evidence,"session-current","exec-current","current",1,2_900,3_000,
                InitialExerciseProfiles.inclineDumbbellPress,
            )
            addFinalizedSet(
                evidence,"session-future","exec-future","future",1,3_900,4_000,
                InitialExerciseProfiles.inclineDumbbellPress,
            )

            val repository=RoomChatGptContextRepository(db.evidenceDao())
            val current=repository.loadSetContext("current")
            assertNotNull(current)
            assertEquals("exec-current",current!!.execution.executionId)
            assertEquals("session-current",current.session.sessionId)
            assertEquals(3_000L,current.evidence.summary!!.endedAtUs)

            val history=repository.loadRecentComparableSetContexts(
                exerciseId=current.execution.exerciseId,
                currentSetId=current.evidence.set.setId,
                beforeEndedAtUs=current.evidence.summary!!.endedAtUs,
                limit=3,
            )
            assertEquals(listOf("recent","old"),history.map{it.evidence.set.setId})
        }finally{
            db.close()
        }
    }

    private fun addFinalizedSet(
        repository:RoomEvidenceRepository,
        sessionId:String,
        executionId:String,
        setId:String,
        setOrdinal:Int,
        startedAtUs:Long,
        endedAtUs:Long,
        bundle:ExerciseBundle,
    ){
        val config=AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            bundle.equipment,
        )
        repository.ensureSession(WorkoutSessionRecord(sessionId,startedAtUs-100))
        repository.ensureExecution(
            ExerciseExecutionRecord(
                executionId,
                sessionId,
                bundle.definition.exerciseId,
                startedAtUs-50,
            )
        )
        repository.openSet(
            SetRecord(setId,executionId,setOrdinal,startedAtUs),
            config,
        )
        repository.finishSet(SetSummary(setId,endedAtUs,0,0,0))
    }
}
