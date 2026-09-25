package com.gymbuddy.app

import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.evidence.RepEvidence
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Only the storage/feedback ports are faked; all movement and evidence run in production Kotlin. */
class ProductionProcessorDurabilityTest {
    private class Store {
        val reps=mutableListOf<RepEvidence>()
        var failNextRep=false
        var summary:SetSummary?=null
        val repository=Proxy.newProxyInstance(EvidenceRepository::class.java.classLoader,
            arrayOf(EvidenceRepository::class.java)) { _, method, args ->
            when(method.name){
                "persistCompletedRepBundle" -> {
                    if(failNextRep){failNextRep=false;throw IllegalStateException("Injected storage failure")}
                    val rep=args!![1] as RepEvidence
                    check(reps.none{it.repId==rep.repId}){"Duplicate committed rep"}
                    reps+=rep
                }
                "finishSet" -> {
                    val value=args!![0] as SetSummary
                    check(value.completedReps==reps.size){"Summary includes uncommitted evidence"}
                    summary=value
                }
            }
            null
        } as EvidenceRepository
    }
    private class Feedback:CueFeedbackSink {
        var active:CueEvent?=null
        override fun onCue(cue:CueEvent){active=cue}
        override fun clear(){active=null}
    }
    private class Replay(val store:Store=Store(),val feedback:Feedback=Feedback()) {
        val bundle=InitialExerciseProfiles.dumbbellLateralRaise
        val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)
        val processor=ProductionPoseFrameProcessor(config,store.repository,
            WorkoutSessionRecord("session",0L),
            ExerciseExecutionRecord("exec","session",bundle.definition.exerciseId,0L),
            SetRecord("set","exec",1,0L),feedback)
        var ts=0L
        fun step(angle:Double=20.0,right:Double=angle,motion:Double?=null):ProductionFrameResult {
            val now=ts;ts+=120_000L
            return processor.process(PoseFrame(now,now,640,480,PoseFrameSource.VIDEO,listOf(candidate(angle,right))),
                TrackingObservationContext(observedViewClass=ViewClass.FRONT,cameraMotionScore=motion))
        }
        fun cycle(rightBottom:Double=90.0){
            repeat(4){step()};step(55.0);step(90.0,rightBottom);step(55.0);step()
        }
    }
    @Test fun failedRepTransactionIsRetriedBeforeAnotherFrameIsAccepted(){
        val replay=Replay()
        replay.store.failNextRep=true
        assertThrows(IllegalStateException::class.java){replay.cycle()}
        val after=replay.step()
        assertEquals("No completed evidence may vanish after a transient write failure",1,replay.store.reps.size)
        assertEquals(1,after.repCount)
        replay.cycle()
        assertEquals(listOf(1,2),replay.store.reps.map{it.ordinal})
    }
    @Test fun finalizationFlushesTheFailedLastRepBeforeWritingItsSummary(){
        val replay=Replay()
        replay.store.failNextRep=true
        assertThrows(IllegalStateException::class.java){replay.cycle()}
        replay.processor.finishSet(replay.ts,10_000L)
        assertEquals(1,replay.store.reps.size)
        assertEquals(1,replay.store.summary!!.completedReps)
    }
    @Test fun lossOfTrackingCancelsThePreviouslyDisplayedAndSpokenCue(){
        val replay=Replay()
        repeat(3){replay.cycle(76.0)}
        assertNotNull("The fixture must first trigger a real production cue",replay.feedback.active)
        replay.step(motion=.9)
        assertNull("A stale correction must not remain active during tracking loss",replay.feedback.active)
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
            fun arm(shoulder:PoseLandmarkId,hip:PoseLandmarkId,elbow:PoseLandmarkId,wrist:PoseLandmarkId,degrees:Double){
                val s=points.getValue(shoulder);val h=points.getValue(hip)
                val angle=kotlin.math.atan2(h[1]-s[1],h[0]-s[0])+Math.toRadians(degrees)
                val dx=.16*kotlin.math.cos(angle);val dy=.16*kotlin.math.sin(angle)
                points[elbow]=doubleArrayOf(s[0]+dx,s[1]+dy)
                points[wrist]=doubleArrayOf(s[0]+1.75*dx,s[1]+1.75*dy)
            }
            arm(PoseLandmarkId.LEFT_SHOULDER,PoseLandmarkId.LEFT_HIP,PoseLandmarkId.LEFT_ELBOW,PoseLandmarkId.LEFT_WRIST,leftAngle)
            arm(PoseLandmarkId.RIGHT_SHOULDER,PoseLandmarkId.RIGHT_HIP,PoseLandmarkId.RIGHT_ELBOW,PoseLandmarkId.RIGHT_WRIST,-rightAngle)
            return PoseSubjectCandidate(0,points.mapValues{(id,p)->PoseLandmarkObservation(id,PoseCoordinate3d(p[0],p[1],0.0),.95,.95)})
        }
    }
}
