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
                beforeEndedAtEpochMs=current.evidence.summary!!.endedAtUs,
                limit=3,
            )
            assertEquals(listOf("recent","old"),history.map{it.evidence.set.setId})
        }finally{
            db.close()
        }
    }

    @Test
    fun recentHistoryUsesWallClockAcrossMonotonicTimebaseReset(){
        val db=Room.inMemoryDatabaseBuilder(
            context,GymBuddyDatabase::class.java
        ).allowMainThreadQueries().build()
        try{
            val evidence=RoomEvidenceRepository(db.evidenceDao())
            addFinalizedSet(
                evidence,
                "session-before-reboot",
                "exec-before-reboot",
                "before-reboot",
                1,
                900_000L,
                1_000_000L,
                InitialExerciseProfiles.inclineDumbbellPress,
                sessionStartedEpochMs=10_000L,
                setStartedEpochMs=10_100L,
                endedAtEpochMs=10_200L,
            )
            addFinalizedSet(
                evidence,
                "session-current",
                "exec-current",
                "current-after-reboot",
                1,
                900L,
                1_000L,
                InitialExerciseProfiles.inclineDumbbellPress,
                sessionStartedEpochMs=20_000L,
                setStartedEpochMs=20_100L,
                endedAtEpochMs=20_200L,
            )
            addFinalizedSet(
                evidence,
                "session-future",
                "exec-future",
                "future-after-reboot",
                1,
                100L,
                200L,
                InitialExerciseProfiles.inclineDumbbellPress,
                sessionStartedEpochMs=30_000L,
                setStartedEpochMs=30_100L,
                endedAtEpochMs=30_200L,
            )

            val repository=RoomChatGptContextRepository(db.evidenceDao())
            val history=repository.loadRecentComparableSetContexts(
                exerciseId="incline_dumbbell_press",
                currentSetId="current-after-reboot",
                beforeEndedAtEpochMs=20_200L,
                limit=3,
            )

            assertEquals(
                listOf("before-reboot"),
                history.map{it.evidence.set.setId},
            )
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
        sessionStartedEpochMs:Long=0L,
        setStartedEpochMs:Long=0L,
        endedAtEpochMs:Long=0L,
    ){
        val config=AnalysisConfigResolver.resolve(
            bundle.definition,
            bundle.profile,
            bundle.equipment,
        )
        repository.ensureSession(
            WorkoutSessionRecord(
                sessionId,
                startedAtUs-100,
                sessionStartedEpochMs,
            )
        )
        repository.ensureExecution(
            ExerciseExecutionRecord(
                executionId,
                sessionId,
                bundle.definition.exerciseId,
                startedAtUs-50,
                sessionStartedEpochMs.takeIf{it>0L}?.plus(50L)?:0L,
            )
        )
        repository.openSet(
            SetRecord(
                setId,
                executionId,
                setOrdinal,
                startedAtUs,
                null,
                setStartedEpochMs,
            ),
            config,
        )
        repository.finishSet(
            SetSummary(
                setId,endedAtUs,0,0,0,endedAtEpochMs
            )
        )
    }
}
