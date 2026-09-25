package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.ProductionMovementPipeline
import com.gymbuddy.domain.engine.ProductionPipelineResult
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import org.junit.Test

/** The coarse landmark gate may pass while one required movement signal is
 * UNKNOWN. These tests exercise both gates together, not a second rep engine. */
class MovementSignalInterruptionIntegrationTest {
    private inner class Replay(val bundle:ExerciseBundle=InitialExerciseProfiles.dumbbellLateralRaise) {
        val pipeline=ProductionMovementPipeline(AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment))
        var ts=0L
        val outputs=mutableListOf<ProductionPipelineResult>()
        val start=when(bundle.definition.exerciseId) {
            "incline_dumbbell_press"->160.0
            "smith_machine_squat"->170.0
            else->20.0
        }
        val end=when(bundle.definition.exerciseId) {
            "incline_dumbbell_press"->70.0
            else->90.0
        }
        val middle=(start+end)/2.0
        fun step(angle:Double=start,right:Double=angle,weak:PoseLandmarkId?=null):ProductionPipelineResult {
            val candidate=candidateFor(bundle.definition.exerciseId,angle,right)
            val observed=if(weak==null)candidate else candidate.copy(normalizedLandmarks=
                candidate.normalizedLandmarks.mapValues { (id,lm)->
                    if(id==weak)lm.copy(visibility=.2,presence=.2) else lm
                })
            val result=pipeline.process(PoseFrame(ts,ts,640,480,PoseFrameSource.VIDEO,listOf(observed)),
                TrackingObservationContext(observedViewClass=bundle.profile.cameraProfile.preferredViewClass))
            ts+=120_000L;outputs+=result
            return result
        }
        fun ready() { repeat(4) { step() } }
        fun cycle(rightBottom:Double=end) { ready();step(middle);step(end,rightBottom);step(middle);step() }
    }

    @Test fun unavailablePrimarySignalPausesAllThreeProductionProfiles() {
        InitialExerciseProfiles.all.forEach { bundle->
            val r=Replay(bundle);r.ready();r.step(r.middle)
            val weak=if(bundle.definition.exerciseId=="smith_machine_squat")PoseLandmarkId.LEFT_KNEE else PoseLandmarkId.LEFT_ELBOW
            val paused=r.step(r.end,weak=weak)
            check(paused.movement.paused && !paused.tracking.allowsBiomechanics) {
                "${bundle.definition.exerciseId}: primary signal UNKNOWN but production reports valid movement/tracking"
            }
            val invalid=paused.movement.repEvents.count { it.kind==RepCompletionKind.INVALID_ATTEMPT }
            check(invalid==1) { "An incomplete attempt must be invalidated once" }
            repeat(3) { check(r.step(r.end,weak=weak).movement.repEvents.isEmpty()) }
            r.step(r.middle);r.step()
            check(r.outputs.flatMap { it.movement.repEvidence }.isEmpty()) { "Partial motion crossed an UNKNOWN signal gap" }
            r.cycle()
            check(r.outputs.flatMap { it.movement.repEvidence }.map { it.ordinal }==listOf(1))
        }
    }

    @Test fun cuePersistenceCannotCrossAnUnknownPrimarySignal() {
        val r=Replay()
        r.cycle(76.0)
        check(r.outputs.flatMap { it.movement.cueEvents }.isEmpty())
        r.step(weak=PoseLandmarkId.LEFT_ELBOW)
        r.cycle(76.0)
        check(r.outputs.flatMap { it.movement.cueEvents }.isEmpty()) {
            "A single post-gap deviation was combined with pre-gap evidence to speak a cue"
        }
        r.cycle(76.0)
        check(r.outputs.flatMap { it.movement.cueEvents }.size==1)
    }

    @Test fun unknownPrimarySignalRequiresANewStableStartDwell() {
        val r=Replay();r.ready()
        r.step(weak=PoseLandmarkId.LEFT_ELBOW)
        r.step() // One frame is not the required stable-start interval.
        r.step(r.middle);r.step(r.end);r.step(r.middle);r.step()
        check(r.outputs.flatMap { it.movement.repEvidence }.isEmpty()) {
            "The interpreter reused arming from before the signal interruption"
        }
        r.cycle()
        check(r.outputs.flatMap { it.movement.repEvidence }.map { it.ordinal }==listOf(1))
    }

    @Test fun optionalLandmarkLossDoesNotInvalidateObservableMovement() {
        val r=Replay();r.ready();r.step(r.middle)
        val output=r.step(r.end,weak=PoseLandmarkId.LEFT_WRIST)
        // Lateral-raise progress uses hip/shoulder/elbow, not wrist. The profile
        // still meets its required-visible fraction, so abstaining would overgate.
        check(output.tracking.allowsBiomechanics && !output.movement.paused)
        r.step(r.middle);r.step()
        check(r.outputs.flatMap { it.movement.repEvidence }.size==1)
    }

    private fun candidateFor(exerciseId: String, angle: Double, rightAngle: Double = angle): PoseSubjectCandidate {
        val front = exerciseId == "dumbbell_lateral_raise"
        val ls = if (front) doubleArrayOf(.40, .30) else doubleArrayOf(.49, .30)
        val rs = if (front) doubleArrayOf(.60, .30) else doubleArrayOf(.51, .30)
        val lh = if (front) doubleArrayOf(.43, .60) else doubleArrayOf(.49, .60)
        val rh = if (front) doubleArrayOf(.57, .60) else doubleArrayOf(.51, .60)
        fun obs(id: PoseLandmarkId, p: DoubleArray) = PoseLandmarkObservation(id, PoseCoordinate3d(p[0], p[1], 0.0), .95, .95)
        fun rotatePoint(vertex: DoubleArray, reference: DoubleArray, degrees: Double, length: Double): DoubleArray {
            val vx = reference[0] - vertex[0]
            val vy = reference[1] - vertex[1]
            val base = kotlin.math.atan2(vy, vx)
            val r = Math.toRadians(degrees)
            return doubleArrayOf(vertex[0] + length * kotlin.math.cos(base + r), vertex[1] + length * kotlin.math.sin(base + r))
        }
        val points = mutableMapOf(
            PoseLandmarkId.LEFT_SHOULDER to ls,
            PoseLandmarkId.RIGHT_SHOULDER to rs,
            PoseLandmarkId.LEFT_HIP to lh,
            PoseLandmarkId.RIGHT_HIP to rh,
        )
        when (exerciseId) {
            "incline_dumbbell_press" -> {
                val le = doubleArrayOf(ls[0] - .10, .42)
                val re = doubleArrayOf(rs[0] + .10, .42)
                points[PoseLandmarkId.LEFT_ELBOW] = le
                points[PoseLandmarkId.RIGHT_ELBOW] = re
                points[PoseLandmarkId.LEFT_WRIST] = rotatePoint(le, ls, angle, .12)
                points[PoseLandmarkId.RIGHT_WRIST] = rotatePoint(re, rs, -rightAngle, .12)
            }
            "smith_machine_squat" -> {
                val lk = doubleArrayOf(lh[0] - .015, .72)
                val rk = doubleArrayOf(rh[0] + .015, .72)
                points[PoseLandmarkId.LEFT_KNEE] = lk
                points[PoseLandmarkId.RIGHT_KNEE] = rk
                points[PoseLandmarkId.LEFT_ANKLE] = rotatePoint(lk, lh, angle, .15)
                points[PoseLandmarkId.RIGHT_ANKLE] = rotatePoint(rk, rh, -rightAngle, .15)
            }
            else -> {
                points[PoseLandmarkId.LEFT_ELBOW] = rotatePoint(ls, lh, angle, .16)
                points[PoseLandmarkId.RIGHT_ELBOW] = rotatePoint(rs, rh, -rightAngle, .16)
                val le = points.getValue(PoseLandmarkId.LEFT_ELBOW)
                val re = points.getValue(PoseLandmarkId.RIGHT_ELBOW)
                val lv = doubleArrayOf(le[0] - ls[0], le[1] - ls[1])
                val rv = doubleArrayOf(re[0] - rs[0], re[1] - rs[1])
                points[PoseLandmarkId.LEFT_WRIST] = doubleArrayOf(le[0] + lv[0] * .75, le[1] + lv[1] * .75)
                points[PoseLandmarkId.RIGHT_WRIST] = doubleArrayOf(re[0] + rv[0] * .75, re[1] + rv[1] * .75)
                points[PoseLandmarkId.NOSE] = doubleArrayOf(.50, .20)
                points[PoseLandmarkId.LEFT_EYE] = doubleArrayOf(.48, .19)
                points[PoseLandmarkId.RIGHT_EYE] = doubleArrayOf(.52, .19)
            }
        }
        return PoseSubjectCandidate(0, points.mapValues { (id, point) -> obs(id, point) })
    }

}
