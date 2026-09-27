package com.gymbuddy.domain.integration

import com.gymbuddy.domain.engine.MovementInterpretationEngine
import com.gymbuddy.domain.movement.CoordinateNormalizationResult
import com.gymbuddy.domain.movement.CoordinateNormalizer
import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.SignalCoordinateSpace
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class WorkoutVisionRealLandmarkReplayTest {
    @Test
    fun pinnedRealPhoneLateralRaiseCountsTenThroughProductionMovementEngine() {
        val fixturePath=System.getenv(FIXTURE_ENV)
        assumeTrue(
            "$FIXTURE_ENV is supplied by simulator-e2e",
            !fixturePath.isNullOrBlank(),
        )
        val fixture=loadFixture(File(requireNotNull(fixturePath)))
        assertEquals(EXPECTED_UPSTREAM_COMMIT,fixture.upstreamCommit)
        assertEquals(EXPECTED_UPSTREAM_BLOB,fixture.upstreamBlob)
        assertEquals(EXPECTED_CLIP,fixture.clip)
        assertEquals(10,fixture.expectedReps)
        assertEquals(439,fixture.frames.size)

        val bundle=InitialExerciseProfiles.dumbbellLateralRaise
        val normalizedEngine=MovementInterpretationEngine(
            AnalysisConfigResolver.resolve(
                bundle.definition,
                bundle.profile,
                bundle.equipment,
            )
        )
        val worldSignalProfile=bundle.profile.signalProfile.copy(
            profileId=bundle.profile.signalProfile.profileId+"-world-replay",
            profileVersion=bundle.profile.signalProfile.profileVersion+1000,
            semanticHash=bundle.profile.signalProfile.semanticHash+"-world-replay",
            definitions=bundle.profile.signalProfile.definitions.map {
                it.copy(coordinateSpace=SignalCoordinateSpace.BODY_LOCAL_WORLD)
            },
        )
        val worldProfile=bundle.profile.copy(
            profileId=bundle.profile.profileId+"-world-replay",
            profileVersion=bundle.profile.profileVersion+1000,
            semanticHash=bundle.profile.semanticHash+"-world-replay",
            signalProfile=worldSignalProfile,
        )
        val worldEngine=MovementInterpretationEngine(
            AnalysisConfigResolver.resolve(
                bundle.definition,
                worldProfile,
                bundle.equipment,
            )
        )
        val normalizer=CoordinateNormalizer()
        var normalizedCount=0
        var worldCount=0

        fixture.frames.forEach { frame ->
            if(!frame.valid) {
                normalizedEngine.onInterruption(frame.timestampUs)
                worldEngine.onInterruption(frame.timestampUs)
                return@forEach
            }
            val result=normalizer.normalize(frame.toCandidate())
            if(result !is CoordinateNormalizationResult.Valid) {
                normalizedEngine.onInterruption(frame.timestampUs)
                worldEngine.onInterruption(frame.timestampUs)
                return@forEach
            }
            normalizedCount += normalizedEngine.process(
                frame.timestampUs,
                result.pose,
                ViewClass.FRONT,
            ).repEvidence.size
            worldCount += worldEngine.process(
                frame.timestampUs,
                result.pose,
                ViewClass.FRONT,
            ).repEvidence.size
        }

        println(
            "WORKOUTVISION_REAL_LANDMARK_REPLAY " +
                "normalized=$normalizedCount world=$worldCount " +
                "expected=${fixture.expectedReps}"
        )
        assertEquals(
            "current shipped body-local signal path on pinned real-phone replay",
            fixture.expectedReps,
            normalizedCount,
        )
        assertEquals(
            "WorkoutVision-inspired world-landmark signal comparison on pinned real-phone replay",
            fixture.expectedReps,
            worldCount,
        )
    }

    private fun loadFixture(file:File):Fixture {
        val lines=file.readLines()
        val metadata=lines.takeWhile { it.startsWith("#") }.associate { line ->
            val p=line.removePrefix("# ").split('=',limit=2)
            p[0] to p.getOrElse(1){""}
        }
        val data=lines
            .dropWhile { it.startsWith("#") }
            .filter { it.isNotBlank() }
        require(data.isNotEmpty())
        val header=data.first().split('\t')
        val indexes=header.withIndex().associate { it.value to it.index }
        val frames=data.drop(1).map { line ->
            val p=line.split('\t')
            require(p.size==header.size) {
                "Malformed real-landmark row with ${p.size}/${header.size} columns"
            }
            Frame(
                timestampUs=p[indexes.getValue("timestamp_us")].toLong(),
                valid=p[indexes.getValue("valid")].toBooleanStrict(),
                normalized=landmarkMap(p,indexes,"i"),
                world=landmarkMap(p,indexes,"w"),
            )
        }
        return Fixture(
            upstreamCommit=requireNotNull(metadata["upstream_commit"]),
            upstreamBlob=requireNotNull(metadata["upstream_blob"]),
            clip=requireNotNull(metadata["clip"]),
            expectedReps=requireNotNull(metadata["expected_reps"]).toInt(),
            frames=frames,
        )
    }

    private fun landmarkMap(
        row:List<String>,
        indexes:Map<String,Int>,
        prefix:String,
    ):Map<PoseLandmarkId,PoseLandmarkObservation> =
        LANDMARKS.mapNotNull { (id,name) ->
            fun read(suffix:String)=
                row[indexes.getValue("${name}_${prefix}${suffix}")]
                    .takeUnless { it=="NA" }
                    ?.toDouble()
            val x=read("x") ?: return@mapNotNull null
            val y=read("y") ?: return@mapNotNull null
            val z=read("z") ?: return@mapNotNull null
            val visibility=read("v")
            id to PoseLandmarkObservation(
                id,
                PoseCoordinate3d(x,y,z),
                visibility,
                null,
            )
        }.toMap()

    private fun Frame.toCandidate()=PoseSubjectCandidate(
        candidateIndex=0,
        normalizedLandmarks=normalized,
        worldLandmarks=world,
    )

    private data class Frame(
        val timestampUs:Long,
        val valid:Boolean,
        val normalized:Map<PoseLandmarkId,PoseLandmarkObservation>,
        val world:Map<PoseLandmarkId,PoseLandmarkObservation>,
    )

    private data class Fixture(
        val upstreamCommit:String,
        val upstreamBlob:String,
        val clip:String,
        val expectedReps:Int,
        val frames:List<Frame>,
    )

    companion object {
        private const val FIXTURE_ENV=
            "GYM_BUDDY_WORKOUTVISION_REAL_LANDMARK_FIXTURE"
        private const val EXPECTED_UPSTREAM_COMMIT=
            "285977080e2b7eec01d1fde0a03e976db25a5926"
        private const val EXPECTED_UPSTREAM_BLOB=
            "5b569d7f16395668fcd6c2e514d1c520e9007818"
        private const val EXPECTED_CLIP=
            "lateral_raise_10_front_mufhhbun.json.gz"
        private val LANDMARKS=listOf(
            PoseLandmarkId.LEFT_SHOULDER to "left_shoulder",
            PoseLandmarkId.RIGHT_SHOULDER to "right_shoulder",
            PoseLandmarkId.LEFT_ELBOW to "left_elbow",
            PoseLandmarkId.RIGHT_ELBOW to "right_elbow",
            PoseLandmarkId.LEFT_WRIST to "left_wrist",
            PoseLandmarkId.RIGHT_WRIST to "right_wrist",
            PoseLandmarkId.LEFT_HIP to "left_hip",
            PoseLandmarkId.RIGHT_HIP to "right_hip",
        )
    }
}
