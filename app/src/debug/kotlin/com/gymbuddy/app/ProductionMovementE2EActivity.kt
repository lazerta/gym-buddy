package com.gymbuddy.app

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gymbuddy.domain.engine.ProductionMovementPipeline
import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only positive production movement gate.
 *
 * The simulator E2E separately proves RGB -> MediaPipe -> app transport. This
 * activity proves the deterministic production movement stack on Android with
 * shipped ExerciseProfiles and ordinary PoseFrame inputs, without oracle truth
 * entering the production pipeline.
 */
class ProductionMovementE2EActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Production movement E2E" }
        setContentView(status)

        try {
            val results = JSONArray()
            InitialExerciseProfiles.all.forEach { bundle ->
                val config = AnalysisConfigResolver.resolve(
                    bundle.definition,
                    bundle.profile,
                    bundle.equipment,
                )
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
                var knownSignalFrames = 0
                var lastLifecycle = ""
                listOf(
                    0L to startAngle,
                    120_000L to startAngle,
                    240_000L to startAngle,
                    500_000L to mid,
                    750_000L to endAngle,
                    1_000_000L to mid,
                    1_250_000L to startAngle,
                ).forEachIndexed { index, (ts, angle) ->
                    val result = pipeline.process(
                        PoseFrame(
                            frameId = index.toLong(),
                            timestampUs = ts,
                            width = 640,
                            height = 480,
                            source = PoseFrameSource.VIDEO,
                            candidates = listOf(candidateFor(bundle.definition.exerciseId, angle)),
                        )
                    )
                    if (result.movement.signals?.values?.values?.all { it.isKnown } == true) {
                        knownSignalFrames += 1
                    }
                    completed += result.movement.repEvidence.size
                    lastLifecycle = result.lifecycleState.name
                }
                check(completed == 1) {
                    "${bundle.definition.exerciseId}: expected 1 completed rep, got $completed"
                }
                check(knownSignalFrames > 0) {
                    "${bundle.definition.exerciseId}: no known movement signal frame"
                }
                results.put(
                    JSONObject()
                        .put("exercise_id", bundle.definition.exerciseId)
                        .put("completed_reps", completed)
                        .put("known_signal_frames", knownSignalFrames)
                        .put("final_lifecycle", lastLifecycle)
                )
            }
            val payload = JSONObject()
                .put("schema_version", 1)
                .put("passed", true)
                .put("profiles", results)
            filesDir.resolve(RESULT_FILE).writeText(payload.toString())
            status.text = "PRODUCTION_MOVEMENT_E2E_PASS"
        } catch (t: Throwable) {
            val payload = JSONObject()
                .put("schema_version", 1)
                .put("passed", false)
                .put("error", t.message ?: t::class.java.simpleName)
            filesDir.resolve(RESULT_FILE).writeText(payload.toString())
            status.text = "Production movement E2E failed: ${t.message}"
        }
    }

    private fun candidateFor(exerciseId: String, angle: Double): PoseSubjectCandidate {
        val front = exerciseId == "dumbbell_lateral_raise"
        val ls = if (front) point(.40, .30) else point(.49, .30)
        val rs = if (front) point(.60, .30) else point(.51, .30)
        val lh = if (front) point(.43, .60) else point(.49, .60)
        val rh = if (front) point(.57, .60) else point(.51, .60)
        val points = mutableMapOf(
            PoseLandmarkId.LEFT_SHOULDER to ls,
            PoseLandmarkId.RIGHT_SHOULDER to rs,
            PoseLandmarkId.LEFT_HIP to lh,
            PoseLandmarkId.RIGHT_HIP to rh,
        )
        when (exerciseId) {
            "incline_dumbbell_press" -> {
                val le = point(ls[0] - .10, .42)
                val re = point(rs[0] + .10, .42)
                points[PoseLandmarkId.LEFT_ELBOW] = le
                points[PoseLandmarkId.RIGHT_ELBOW] = re
                points[PoseLandmarkId.LEFT_WRIST] = rotatePoint(le, ls, angle, .12)
                points[PoseLandmarkId.RIGHT_WRIST] = rotatePoint(re, rs, -angle, .12)
            }
            "smith_machine_squat" -> {
                val lk = point(lh[0] - .015, .72)
                val rk = point(rh[0] + .015, .72)
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
                val lv = point(le[0] - ls[0], le[1] - ls[1])
                val rv = point(re[0] - rs[0], re[1] - rs[1])
                points[PoseLandmarkId.LEFT_WRIST] = point(le[0] + lv[0] * .75, le[1] + lv[1] * .75)
                points[PoseLandmarkId.RIGHT_WRIST] = point(re[0] + rv[0] * .75, re[1] + rv[1] * .75)
                points[PoseLandmarkId.NOSE] = point(.50, .20)
                points[PoseLandmarkId.LEFT_EYE] = point(.48, .19)
                points[PoseLandmarkId.RIGHT_EYE] = point(.52, .19)
            }
        }
        val observations = points.mapValues { (id, p) ->
            PoseLandmarkObservation(id, PoseCoordinate3d(p[0], p[1], 0.0), .95, .95)
        }
        return PoseSubjectCandidate(0, observations)
    }

    private fun point(x: Double, y: Double) = doubleArrayOf(x, y)

    private fun rotatePoint(
        vertex: DoubleArray,
        reference: DoubleArray,
        degrees: Double,
        length: Double,
    ): DoubleArray {
        val vx = reference[0] - vertex[0]
        val vy = reference[1] - vertex[1]
        val base = kotlin.math.atan2(vy, vx)
        val radians = Math.toRadians(degrees)
        return point(
            vertex[0] + length * kotlin.math.cos(base + radians),
            vertex[1] + length * kotlin.math.sin(base + radians),
        )
    }

    companion object {
        const val RESULT_FILE = "production-movement-e2e.json"
    }
}
