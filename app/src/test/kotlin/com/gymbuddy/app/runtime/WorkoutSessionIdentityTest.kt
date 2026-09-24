package com.gymbuddy.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSessionIdentityTest {
    @Test
    fun exerciseSelectionsShareWorkoutSessionButUseDistinctExecutions() {
        val ids=ArrayDeque(listOf("session-id","exec-1","exec-2"))
        val identity=WorkoutSessionIdentity { ids.removeFirst() }

        val first=identity.beginExercise("incline_dumbbell_press")
        assertEquals("session-id",first.sessionId)
        assertEquals("exec-1",first.executionId)
        assertNull(identity.sessionStartedAtUs)

        val firstTimes=identity.ensureStarted(100L)
        assertEquals(100L,firstTimes.sessionStartedAtUs)
        assertEquals(100L,firstTimes.executionStartedAtUs)

        val second=identity.beginExercise("smith_machine_squat")
        assertEquals(first.sessionId,second.sessionId)
        assertNotEquals(first.executionId,second.executionId)
        val nextTimes=identity.ensureStarted(500L)
        assertEquals(100L,nextTimes.sessionStartedAtUs)
        assertEquals(500L,nextTimes.executionStartedAtUs)
    }

    @Test
    fun resumedWorkoutKeepsSessionForNextExercise() {
        val ids=ArrayDeque(listOf("next-exec"))
        val identity=WorkoutSessionIdentity { ids.removeFirst() }
        identity.resume(
            sessionId="persisted-session",
            sessionStartedAtUs=100L,
            executionId="persisted-exec",
            executionStartedAtUs=200L,
        )

        val next=identity.beginExercise("smith_machine_squat")
        val times=identity.ensureStarted(900L)

        assertEquals("persisted-session",next.sessionId)
        assertEquals("next-exec",next.executionId)
        assertEquals(100L,times.sessionStartedAtUs)
        assertEquals(900L,times.executionStartedAtUs)
    }
}
