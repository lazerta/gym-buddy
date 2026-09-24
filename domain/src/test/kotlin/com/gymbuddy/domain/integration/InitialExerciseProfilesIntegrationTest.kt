package com.gymbuddy.domain.integration

import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.pose.*
import com.gymbuddy.domain.engine.ProductionMovementPipeline
import com.gymbuddy.domain.profile.*
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.domain.tracking.TrackingQualityReason
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class InitialExerciseProfilesIntegrationTest {
    @Test fun everyShippedProfileProducesKnownProgressAndCleanCompletedRep() {
        InitialExerciseProfiles.all.forEach { bundle ->
            val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
            val extractor = MovementSignalExtractor()
            val interpreter = MovementPrimitiveInterpreter()
            val detector = TemporalRepDetector(stepConfigs = RepDetectorConfig.fromSequence(bundle.profile.movementPrimitiveSequence))
            val history = mutableListOf<MovementSignalFrame>()
            var completed: RepDetectionEvent? = null
            val startAngle = when (bundle.definition.exerciseId) {
                "incline_dumbbell_press" -> 160.0
                "smith_machine_squat" -> 170.0
                else -> 20.0
            }
            val endAngle = when (bundle.definition.exerciseId) {
                "incline_dumbbell_press" -> 70.0
                "smith_machine_squat" -> 90.0
                else -> 90.0
            }
            val mid = (startAngle + endAngle) / 2.0
            listOf(
                0L to startAngle,
                120_000L to startAngle,
                500_000L to mid,
                1_000_000L to endAngle,
                1_500_000L to mid,
                2_000_000L to startAngle,
            ).forEach { (ts, angle) ->
                val signals = extractor.extract(ts, poseFor(bundle.profile.signalProfile, angle, angle), bundle.profile.signalProfile)
                history += signals
                assertTrue("${bundle.definition.exerciseId} left progress UNKNOWN at $ts", signals.values.getValue("left_progress").isKnown)
                assertTrue("${bundle.definition.exerciseId} right progress UNKNOWN at $ts", signals.values.getValue("right_progress").isKnown)
                completed = detector.update(interpreter.interpret(signals, bundle.profile.movementPrimitiveSequence))
                    .firstOrNull { it.kind == RepCompletionKind.COMPLETED } ?: completed
            }
            val event = assertNotNull(completed).let { completed!! }
            assertEquals(RepClassification.NORMAL, event.classification)
            val rep = RepEvidenceBuilder().build(event, history, config)
            val timing = rep.metrics.getValue("bilateral_timing_ms").value as EvidenceValue.Known
            assertEquals(0.0, timing.value, 0.0)
            val observation = FormAnalysisEngine().analyze(rep, bundle.profile.formRuleSet)
                .single { it.ruleId == "bilateral_asymmetry" }
            assertEquals(FormObservationState.OK, observation.state)
        }
    }

    @Test fun everyShippedProfileCompletesRepThroughProductionPipeline() {
        InitialExerciseProfiles.all.forEach { bundle ->
            val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
            val pipeline = ProductionMovementPipeline(config)
            val startAngle = when (bundle.definition.exerciseId) {
                "incline_dumbbell_press" -> 160.0
                "smith_machine_squat" -> 170.0
                else -> 20.0
            }
            val endAngle = when (bundle.definition.exerciseId) {
                "incline_dumbbell_press" -> 70.0
                "smith_machine_squat" -> 90.0
                else -> 90.0
            }
            val mid = (startAngle + endAngle) / 2.0
            var completed = 0
            listOf(
                0L to startAngle,
                120_000L to startAngle,
                240_000L to startAngle,
                500_000L to mid,
                750_000L to endAngle,
                1_000_000L to mid,
                1_250_000L to startAngle,
            ).forEachIndexed { index, (ts, angle) ->
                val result = pipeline.process(PoseFrame(index.toLong(), ts, 640, 480, PoseFrameSource.VIDEO, listOf(candidateFor(bundle.definition.exerciseId, angle))))
                completed += result.movement.repEvidence.size
            }
            assertEquals("${bundle.definition.exerciseId} production pipeline rep count", 1, completed)
        }
    }

    @Test fun materialCameraDisturbanceDuringPartialRepRequiresFreshGuidanceAndCannotStitchRep() {
        val bundle = InitialExerciseProfiles.dumbbellLateralRaise
        val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
        val pipeline = ProductionMovementPipeline(config)
        var completed = 0

        fun process(ts:Long, angle:Double, cameraMotionScore:Double?=null) =
            pipeline.process(
                PoseFrame(ts, ts, 640, 480, PoseFrameSource.VIDEO, listOf(candidateFor(bundle.definition.exerciseId, angle))),
                TrackingObservationContext(cameraMotionScore=cameraMotionScore),
            ).also { completed += it.movement.repEvidence.size }

        process(0,20.0)
        process(120_000,20.0)
        process(240_000,20.0)
        val active=process(500_000,55.0)
        assertEquals(SetLifecycleState.ACTIVE_SET,active.lifecycleState)

        process(650_000,75.0)
        val disturbed=process(700_000,75.0,.90)
        assertEquals(TrackingQualityReason.CAMERA_DISTURBANCE,disturbed.tracking.reason)
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE,disturbed.lifecycleState)
        assertEquals(0,completed)

        val reacquire1=process(800_000,20.0)
        assertEquals(SetLifecycleState.CAMERA_GUIDANCE,reacquire1.lifecycleState)
        val reacquire2=process(920_000,20.0)
        assertNotEquals(SetLifecycleState.ACTIVE_SET,reacquire2.lifecycleState)

        process(1_040_000,20.0)
        process(1_300_000,55.0)
        process(1_540_000,90.0)
        process(1_780_000,55.0)
        process(2_020_000,20.0)

        assertEquals(1,completed)
    }

    private fun candidateFor(exerciseId: String, angle: Double): PoseSubjectCandidate {
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
                points[PoseLandmarkId.RIGHT_WRIST] = rotatePoint(re, rs, -angle, .12)
            }
            "smith_machine_squat" -> {
                val lk = doubleArrayOf(lh[0] - .015, .72)
                val rk = doubleArrayOf(rh[0] + .015, .72)
                points[PoseLandmarkId.LEFT_KNEE] = lk
                points[PoseLandmarkId.RIGHT_KNEE] = rk
                points[PoseLandmarkId.LEFT_ANKLE] = rotatePoint(lk, lh, angle, .15)
                points[PoseLandmarkId.RIGHT_ANKLE] = rotatePoint(rk, rh, -angle, .15)
            }
            else -> {
                points[PoseLandmarkId.LEFT_ELBOW] = rotatePoint(ls, lh, angle, .16)
                points[PoseLandmarkId.RIGHT_ELBOW] = rotatePoint(rs, rh, -angle, .16)
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

    private fun poseFor(profile: SignalProfile, leftAngle: Double, rightAngle: Double): NormalizedPose {
        val landmarks = mutableMapOf<PoseLandmarkId, BodyLocalLandmark>()
        fun add(definition: SignalDefinition, angle: Double, side: Double) {
            val ids = definition.orderedLandmarkIds.map { name -> PoseLandmarkId.entries.first { it.wireName == name } }
            val a = ids[0]; val b = ids[1]; val c = ids[2]
            val bx = side * 2.0
            val radians = Math.toRadians(angle)
            fun put(id: PoseLandmarkId, x: Double, y: Double) {
                landmarks[id] = BodyLocalLandmark(id, x, y, 0.0, .95, .95)
            }
            put(b, bx, 0.0)
            put(a, bx + 1.0, 0.0)
            put(c, bx + cos(radians), sin(radians))
        }
        add(profile.definitions.first { it.signalId == "left_progress" }, leftAngle, -1.0)
        add(profile.definitions.first { it.signalId == "right_progress" }, rightAngle, 1.0)
        return NormalizedPose(0, 1.0, landmarks)
    }
}
