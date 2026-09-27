package com.gymbuddy.domain.integration

import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.MovementPrimitiveInterpreter
import com.gymbuddy.domain.movement.MovementSignalExtractor
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.movement.RepCompletionKind
import com.gymbuddy.domain.movement.RepDetectorConfig
import com.gymbuddy.domain.movement.TemporalRepDetector
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.SignalCoordinateSpace
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class WorkoutVisionRealHumanLateralRaiseReplayTest {
    @Test
    fun labeledWorkoutVisionLateralRaiseReplaysThroughProductionTemporalCore() {
        val fixturePath = System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by simulator workflow",
            !fixturePath.isNullOrBlank(),
        )
        val fixture = load(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_SOURCE_COMMIT, fixture.sourceCommit)
        assertEquals(EXPECTED_SOURCE_BLOB, fixture.sourceBlob)
        assertEquals(10, fixture.expectedReps)

        val bundle = InitialExerciseProfiles.dumbbellLateralRaise
        val worldProfile = bundle.profile.signalProfile.copy(
            profileId = bundle.profile.signalProfile.profileId + "-world-replay",
            profileVersion = 1,
            semanticHash = "workoutvision-lateral-raise-world-replay-v1",
            definitions = bundle.profile.signalProfile.definitions.map {
                it.copy(coordinateSpace = SignalCoordinateSpace.BODY_LOCAL_WORLD)
            },
        )
        val sequence = bundle.profile.movementPrimitiveSequence
        val extractor = MovementSignalExtractor()
        val interpreter = MovementPrimitiveInterpreter()
        val detector = TemporalRepDetector(
            stepConfigs = RepDetectorConfig.fromSequence(sequence)
        )
        val progressIds = sequence.steps.flatMap { it.progressSignalIds }.distinct()
        var completed = 0
        var interruptions = 0

        fixture.frames.forEach { frame ->
            val pose = NormalizedPose(
                candidateIndex = 0,
                bodyScale = 1.0,
                landmarks = emptyMap(),
                worldLandmarks = frame.landmarks,
            )
            val signals = extractor.extract(frame.timestampUs, pose, worldProfile)
            val observable = progressIds.all { id ->
                signals.values[id]?.isKnown == true
            }
            if (!observable) {
                detector.onInterruption(frame.timestampUs)
                extractor.reset()
                interpreter.reset()
                interruptions++
            } else {
                completed += detector.update(
                    interpreter.interpret(signals, sequence)
                ).count { it.kind == RepCompletionKind.COMPLETED }
            }
        }

        println(
            "WORKOUTVISION_REAL_HUMAN_LATERAL_RAISE_REPLAY " +
                "completed=$completed interruptions=$interruptions " +
                "frames=${fixture.frames.size}"
        )
        assertEquals(fixture.expectedReps, completed)
    }

    private fun load(file: File): Fixture {
        val lines = file.readLines()
        val metadata = lines
            .takeWhile { it.startsWith("#") }
            .associate { line ->
                val parts = line.removePrefix("# ").split('=', limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
        val body = lines
            .dropWhile { it.startsWith("#") }
            .filter { it.isNotBlank() }
        val header = body.first().split('\t')
        val index = header.withIndex().associate { it.value to it.index }
        val names = listOf(
            "left_shoulder",
            "right_shoulder",
            "left_elbow",
            "right_elbow",
            "left_hip",
            "right_hip",
        )
        val frames = body.drop(1).map { line ->
            val parts = line.split('\t', ignoreCase = false, limit = header.size)
            fun value(column: String): Double? =
                index[column]
                    ?.let { parts.getOrNull(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?.toDouble()

            val landmarks = buildMap {
                names.forEach { name ->
                    val x = value("${name}_x")
                    val y = value("${name}_y")
                    val z = value("${name}_z")
                    if (x != null && y != null && z != null) {
                        val id = PoseLandmarkId.entries.first { it.wireName == name }
                        put(
                            id,
                            BodyLocalLandmark(
                                id,
                                x,
                                y,
                                z,
                                value("${name}_visibility"),
                                value("${name}_presence"),
                            )
                        )
                    }
                }
            }
            ReplayFrame(
                parts[index.getValue("timestamp_us")].toLong(),
                landmarks,
            )
        }
        return Fixture(
            sourceCommit = requireNotNull(metadata["source_commit"]),
            sourceBlob = requireNotNull(metadata["source_blob"]),
            expectedReps = requireNotNull(metadata["expected_reps"]).toInt(),
            frames = frames,
        )
    }

    private data class ReplayFrame(
        val timestampUs: Long,
        val landmarks: Map<PoseLandmarkId, BodyLocalLandmark>,
    )

    private data class Fixture(
        val sourceCommit: String,
        val sourceBlob: String,
        val expectedReps: Int,
        val frames: List<ReplayFrame>,
    )

    companion object {
        private const val FIXTURE_ENV =
            "GYM_BUDDY_WORKOUTVISION_LATERAL_RAISE_FIXTURE"
        private const val EXPECTED_SOURCE_COMMIT =
            "285977080e2b7eec01d1fde0a03e976db25a5926"
        private const val EXPECTED_SOURCE_BLOB =
            "5b569d7f16395668fcd6c2e514d1c520e9007818"
    }
}
