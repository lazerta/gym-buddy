package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.ProductionMovementPipeline
import com.gymbuddy.domain.engine.ProductionPipelineResult
import com.gymbuddy.domain.evidence.FormObservationState
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.tracking.TrackingObservationContext
import org.junit.Assert.*
import org.junit.Test

/** Boundary regressions use the real subject lock, camera gate, signal extractor,
 * detector, evidence builder, and form engine. No expected rep events are inputs. */
class ProductionRuntimeBoundaryRegressionTest {
    private val bundle = InitialExerciseProfiles.dumbbellLateralRaise

    private class Replay(val pipeline: ProductionMovementPipeline,
                         val frame: (Long, Double, Double) -> PoseFrame) {
        var ts = 0L
        val outputs = mutableListOf<ProductionPipelineResult>()
        fun step(angle: Double = 20.0, rightAngle: Double = angle,
                 view: ViewClass = ViewClass.FRONT, motion: Double? = null): ProductionPipelineResult {
            val result = pipeline.process(frame(ts, angle, rightAngle),
                TrackingObservationContext(observedViewClass = view, cameraMotionScore = motion))
            ts += 120_000L
            outputs += result
            return result
        }
        fun cycle(view: ViewClass = ViewClass.FRONT, rightBottom: Double = 90.0) {
            repeat(4) { step(view = view) }
            step(55.0, view = view)
            step(90.0, rightBottom, view)
            step(55.0, view = view)
            step(view = view)
        }
        fun reps() = outputs.flatMap { it.movement.repEvidence }
        fun asymmetry() = outputs.flatMap { it.movement.formObservations }
            .filter { it.ruleId == "bilateral_asymmetry" }
    }

    private fun replay(personal: PersonalCalibrationProfile? = null): Replay {
        val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment, personal)
        return Replay(ProductionMovementPipeline(config, evidenceIdNamespace = "set-under-review")) { ts, a, b ->
            PoseFrame(ts, ts, 640, 480, PoseFrameSource.VIDEO,
                listOf(candidateFor(bundle.definition.exerciseId, a, b)))
        }
    }

    @Test fun manualEndWorksWhileAnActiveAttemptIsReacquiringTheCamera() {
        val replay=replay()
        replay.cycle()
        replay.step(motion=.9)
        assertEquals(com.gymbuddy.domain.lifecycle.SetLifecycleState.FINALIZING,replay.pipeline.manualEnd())
        assertEquals(com.gymbuddy.domain.lifecycle.SetLifecycleState.ENDED,replay.pipeline.finalized())
    }

    @Test fun committedRepOrdinalSurvivesFailedCameraReacquisition() {
        val replay = replay()
        replay.cycle()
        assertEquals(listOf(1), replay.reps().map { it.ordinal })
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE, replay.step(motion = .9).lifecycleState)
        repeat(3) { replay.step() }
        // A valid reacquisition followed by one invalid view must discard only
        // the new partial attempt, never the already committed rep numbering.
        replay.step(view = ViewClass.REAR)
        replay.cycle()
        assertEquals(listOf(1, 2), replay.reps().map { it.ordinal })
        assertEquals(2, replay.reps().map { it.repId }.toSet().size)
    }

    @Test fun movementCannotStitchAcrossTwoDifferentAllowedCameraViews() {
        val replay = replay()
        repeat(4) { replay.step() }
        replay.step(55.0)
        replay.step(90.0, view = ViewClass.FRONT_OBLIQUE)
        replay.step(55.0, view = ViewClass.FRONT_OBLIQUE)
        replay.step(view = ViewClass.FRONT_OBLIQUE)
        assertTrue("A changed projection is not a continuous rep", replay.reps().isEmpty())
        replay.cycle(view = ViewClass.FRONT_OBLIQUE)
        assertEquals(listOf(1), replay.reps().map { it.ordinal })
    }

    @Test fun alternateViewCannotUsePreferredViewPersonalThreshold() {
        val replay = replay(personal(ViewClass.FRONT))
        replay.cycle(view = ViewClass.FRONT_OBLIQUE, rightBottom = 76.0)
        assertEquals(1, replay.reps().size)
        assertEquals(FormObservationState.DEVIATION, replay.asymmetry().single().state)
    }

    @Test fun alternateViewUsesItsOwnPersonalThreshold() {
        val replay = replay(personal(ViewClass.FRONT_OBLIQUE))
        replay.cycle(view = ViewClass.FRONT_OBLIQUE, rightBottom = 76.0)
        assertEquals(1, replay.reps().size)
        assertEquals(FormObservationState.OK, replay.asymmetry().single().state)
    }

    private fun personal(view: ViewClass): PersonalCalibrationProfile = PersonalCalibrationProfile.create(
        calibrationProfileId = "view-scoped", profileVersion = 1, sourceConfidence = .9,
        exerciseBaselines = listOf(ExerciseBaseline(
            profileId = "raise-baseline", profileVersion = 1, semanticHash = "raise-baseline-v1",
            key = ExerciseBaselineKey(bundle.profile.profileId, bundle.profile.profileVersion,
                bundle.equipment?.profileId, view),
            metricStatistics = mapOf("bilateral_asymmetry" to
                BaselineStatistic(.14, .08, .22, 30, 5, .9)),
        )),
    )

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
