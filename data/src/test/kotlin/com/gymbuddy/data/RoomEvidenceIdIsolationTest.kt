package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.evidence.FormAnalysisEngine
import com.gymbuddy.domain.evidence.RepEvidenceBuilder
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepDetectionEvent
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoomEvidenceIdIsolationTest {
    private val context:Context
        get()=ApplicationProvider.getApplicationContext()

    @Test
    fun twoSetsWithIdenticalRepTimestampsPersistIndependently(){
        val db=Room.inMemoryDatabaseBuilder(
            context,GymBuddyDatabase::class.java
        ).allowMainThreadQueries().build()
        try{
            val repository=RoomEvidenceRepository(db.evidenceDao())
            val bundle=InitialExerciseProfiles.inclineDumbbellPress
            val config=AnalysisConfigResolver.resolve(
                bundle.definition,bundle.profile,bundle.equipment
            )
            val event=RepDetectionEvent(
                ordinal=1,
                stepId="cycle",
                primitive=MovementPrimitive.PRESS,
                startedAtUs=500_000L,
                completedAtUs=2_000_000L,
                kind=RepCompletionKind.COMPLETED,
                classification=RepClassification.NORMAL,
                minConfidence=.95,
                maxAssistance=0.0,
            )

            fun persist(setId:String,sessionId:String,epoch:Long){
                val executionId=setId+"-exec"
                repository.ensureSession(
                    WorkoutSessionRecord(sessionId,0L,epoch)
                )
                repository.ensureExecution(
                    ExerciseExecutionRecord(
                        executionId,
                        sessionId,
                        bundle.definition.exerciseId,
                        0L,
                        epoch,
                    )
                )
                repository.openSet(
                    SetRecord(setId,executionId,1,0L,null,epoch),
                    config,
                )
                val rep=RepEvidenceBuilder().build(
                    event,emptyList(),config,setId
                )
                val observations=FormAnalysisEngine().analyze(rep,config)
                repository.persistCompletedRepBundle(
                    setId,rep,observations,emptyList(),emptyList()
                )
            }

            persist("set-a","session-a",10_000L)
            persist("set-b","session-b",20_000L)

            val a=requireNotNull(repository.loadSet("set-a"))
            val b=requireNotNull(repository.loadSet("set-b"))
            assertNotEquals(a.reps.single().repId,b.reps.single().repId)
            assertTrue(
                a.observations.map{it.observationId}.toSet()
                    .intersect(b.observations.map{it.observationId}.toSet())
                    .isEmpty()
            )
        }finally{
            db.close()
        }
    }
}
