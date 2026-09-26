package com.gymbuddy.app

import com.gymbuddy.domain.evidence.InvalidAttemptEvidence
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import java.lang.reflect.Proxy
import org.junit.Test

class ProductionInvalidAttemptRetryTest {
    @Test fun failedInvalidAttemptWriteIsRetriedBeforeAnotherFrame() {
        val r=Replay();r.failAttempt();r.step()
        check(r.saved.single().attemptId==r.failedId) { "Invalid attempt was lost or its ID changed" }
        r.step();check(r.saved.size==1)
    }
    @Test fun finalizationFlushesInvalidAttemptWithoutCountingARep() {
        val r=Replay();r.failAttempt();r.processor.finishSet(r.ts,1000)
        check(r.saved.single().attemptId==r.failedId)
        check(r.summary?.completedReps==0)
    }
    private class Replay {
        var ts=0L
        var shouldFail=true
        var failedId:String?=null
        var summary:SetSummary?=null
        val saved=mutableListOf<InvalidAttemptEvidence>()
        private val repo=Proxy.newProxyInstance(EvidenceRepository::class.java.classLoader,
            arrayOf(EvidenceRepository::class.java)) { _,method,args ->
            when(method.name) {
                "persistInvalidAttempt" -> {
                    val attempt=args!![1] as InvalidAttemptEvidence
                    if(shouldFail){shouldFail=false;failedId=attempt.attemptId;error("injected invalid-attempt write failure")}
                    saved+=attempt
                }
                "finishSet" -> summary=args!![0] as SetSummary
            }
            null
        } as EvidenceRepository
        private val b=InitialExerciseProfiles.dumbbellLateralRaise
        val processor=ProductionPoseFrameProcessor(AnalysisConfigResolver.resolve(b.definition,b.profile,b.equipment),
            repo,WorkoutSessionRecord("session",0),ExerciseExecutionRecord("exec","session",b.definition.exerciseId,0),
            SetRecord("set","exec",1,0))
        fun step(angle:Double=20.0,motion:Double?=null) {
            val t=ts;ts+=120_000
            processor.process(PoseFrame(t,t,640,480,PoseFrameSource.VIDEO,listOf(candidate(angle,angle))),
                TrackingObservationContext(observedViewClass=ViewClass.FRONT,cameraMotionScore=motion))
        }
        fun failAttempt(){
            repeat(4){step()};step(55.0)
            check(runCatching{step(90.0,.9)}.exceptionOrNull()?.message=="injected invalid-attempt write failure")
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
