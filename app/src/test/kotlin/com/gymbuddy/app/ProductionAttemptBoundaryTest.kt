package com.gymbuddy.app

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** These tests drive the real pose, movement, lifecycle and persistence orchestration.
 * Only durable storage and feedback ports are substituted for fault injection. */
class ProductionAttemptBoundaryTest {
    @Test fun cameraSetupDoesNotAllocateAnAttemptBeforeMovementBegins() {
        val replay=Replay()
        assertEquals("Constructing camera setup must not consume a durable set ordinal",0,replay.store.opened)
        repeat(4) { replay.step() }
        assertEquals("Readiness and arming are not an attempted set",0,replay.store.opened)
        val active=replay.step(55.0)
        assertEquals(SetLifecycleState.ACTIVE_SET,active.pipeline.lifecycleState)
        assertEquals("Persist the attempt once movement has actually started",1,replay.store.opened)
        replay.step(90.0);replay.step(55.0);replay.step()
        assertEquals(1,replay.store.opened)
    }

    @Test fun retryCommitsCueEvidenceButDoesNotSpeakItAfterTrackingLoss() {
        val replay=Replay()
        replay.failAtFirstCue()
        replay.step(motion=.9)
        assertTrue("The failed bundle must be saved, not discarded",replay.store.savedCues.isNotEmpty())
        assertTrue("Storage recovery must not replay an old correction into a paused frame",replay.feedback.delivered.isEmpty())
    }

    @Test fun finalizationCommitsPendingEvidenceWithoutSpeakingAnOldCorrection() {
        val replay=Replay()
        replay.failAtFirstCue()
        replay.processor.finishSet(replay.ts,10_000L)
        assertTrue(replay.store.savedCues.isNotEmpty())
        assertTrue("Finalization is not a live coaching window",replay.feedback.delivered.isEmpty())
    }

    private class Store {
        var opened=0
        var failCueOnce=false
        val savedCues=mutableListOf<CueEvidenceLink>()
        val repository=Proxy.newProxyInstance(EvidenceRepository::class.java.classLoader,
            arrayOf(EvidenceRepository::class.java)) { _,method,args ->
            when(method.name) {
                "openSet" -> opened++
                "persistCompletedRepBundle" -> {
                    @Suppress("UNCHECKED_CAST")
                    val cues=args!![3] as List<CueEvidenceLink>
                    if(failCueOnce && cues.isNotEmpty()) {
                        failCueOnce=false
                        throw IllegalStateException("Injected cue-bundle transaction failure")
                    }
                    savedCues+=cues
                }
            }
            null
        } as EvidenceRepository
    }
    private class Feedback:CueFeedbackSink {
        val delivered=mutableListOf<CueEvent>()
        override fun onCue(cue:CueEvent) { delivered+=cue }
    }
    private class Replay {
        val store=Store()
        val feedback=Feedback()
        private val b=InitialExerciseProfiles.dumbbellLateralRaise
        private val config=AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment)
        val processor=ProductionPoseFrameProcessor(config,store.repository,
            WorkoutSessionRecord("session",0L),
            ExerciseExecutionRecord("execution","session",b.definition.exerciseId,0L),
            SetRecord("set","execution",1,0L),feedback)
        var ts=0L
        fun step(left:Double=20.0,right:Double=left,motion:Double?=null):ProductionFrameResult {
            val time=ts;ts+=120_000L
            return processor.process(PoseFrame(time,time,640,480,PoseFrameSource.VIDEO,listOf(candidate(left,right))),
                TrackingObservationContext(observedViewClass=ViewClass.FRONT,cameraMotionScore=motion))
        }
        fun failAtFirstCue() {
            store.failCueOnce=true
            var failure:IllegalStateException?=null
            try {
                repeat(6) {
                    repeat(4) { step() }
                    step(55.0);step(90.0,76.0);step(55.0);step()
                }
            } catch(e:IllegalStateException) { failure=e }
            assertEquals("The fixture must fail at a real production cue bundle",
                "Injected cue-bundle transaction failure",failure?.message)
            assertTrue(feedback.delivered.isEmpty())
        }
    }
    companion object {
        private fun candidate(leftAngle:Double,rightAngle:Double):PoseSubjectCandidate {
            val points=mutableMapOf(
                PoseLandmarkId.LEFT_SHOULDER to doubleArrayOf(.4,.3),
                PoseLandmarkId.RIGHT_SHOULDER to doubleArrayOf(.6,.3),
                PoseLandmarkId.LEFT_HIP to doubleArrayOf(.43,.6),
                PoseLandmarkId.RIGHT_HIP to doubleArrayOf(.57,.6),
                PoseLandmarkId.NOSE to doubleArrayOf(.5,.2),
                PoseLandmarkId.LEFT_EYE to doubleArrayOf(.48,.19),
                PoseLandmarkId.RIGHT_EYE to doubleArrayOf(.52,.19),
            )
            fun arm(shoulder:PoseLandmarkId,hip:PoseLandmarkId,elbow:PoseLandmarkId,wrist:PoseLandmarkId,angle:Double) {
                val s=points.getValue(shoulder);val h=points.getValue(hip)
                val radians=kotlin.math.atan2(h[1]-s[1],h[0]-s[0])+Math.toRadians(angle)
                val dx=.16*kotlin.math.cos(radians);val dy=.16*kotlin.math.sin(radians)
                points[elbow]=doubleArrayOf(s[0]+dx,s[1]+dy)
                points[wrist]=doubleArrayOf(s[0]+1.75*dx,s[1]+1.75*dy)
            }
            arm(PoseLandmarkId.LEFT_SHOULDER,PoseLandmarkId.LEFT_HIP,PoseLandmarkId.LEFT_ELBOW,PoseLandmarkId.LEFT_WRIST,leftAngle)
            arm(PoseLandmarkId.RIGHT_SHOULDER,PoseLandmarkId.RIGHT_HIP,PoseLandmarkId.RIGHT_ELBOW,PoseLandmarkId.RIGHT_WRIST,-rightAngle)
            return PoseSubjectCandidate(0,points.mapValues { (id,p) ->
                PoseLandmarkObservation(id,PoseCoordinate3d(p[0],p[1],0.0),.95,.95)
            })
        }
    }
}
