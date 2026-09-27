package com.gymbuddy.domain.integration

import com.gymbuddy.domain.movement.BodyLocalLandmark
import com.gymbuddy.domain.movement.MovementSignalExtractor
import com.gymbuddy.domain.movement.NormalizedPose
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.profile.SignalCoordinateSpace
import com.gymbuddy.domain.profile.SignalProfile
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Comparison-only adoption slice inspired by WorkoutVision's use of MediaPipe
 * world-landmark joint angles. No upstream source is vendored and shipped
 * exercise profiles remain unchanged until real-video evidence justifies a
 * coordinate-space promotion.
 */
class WorkoutVisionWorldSignalComparisonTest {
    @Test
    fun lateralRaiseCanCompareNormalizedAndWorldAngleEvidenceWithoutChangingProductionProfile() {
        val production =
            InitialExerciseProfiles.dumbbellLateralRaise.profile.signalProfile
        val worldComparison = SignalProfile(
            profileId = production.profileId + "-world-comparison",
            profileVersion = 1,
            semanticHash = "workoutvision-world-comparison-v1",
            definitions = production.definitions.map {
                it.copy(coordinateSpace = SignalCoordinateSpace.BODY_LOCAL_WORLD)
            },
        )
        val pose = pose(
            normalizedAngleDeg = 20.0,
            worldAngleDeg = 90.0,
        )

        val normalized = MovementSignalExtractor()
            .extract(0, pose, production)
        val world = MovementSignalExtractor()
            .extract(0, pose, worldComparison)

        assertEquals(
            "Shipped profile must keep its existing normalized/body-local semantics",
            0.0,
            normalized.values.getValue("left_progress").value!!,
            1e-9,
        )
        assertEquals(
            20.0,
            normalized.values.getValue("left_arm_elevation_deg").value!!,
            1e-9,
        )
        assertEquals(
            "Comparison profile should read the same movement from world-landmark geometry",
            1.0,
            world.values.getValue("left_progress").value!!,
            1e-9,
        )
        assertEquals(
            90.0,
            world.values.getValue("left_arm_elevation_deg").value!!,
            1e-9,
        )
    }

    @Test
    fun comparisonSourceIsPinnedForReproducibility() {
        assertEquals(
            "285977080e2b7eec01d1fde0a03e976db25a5926",
            WORKOUT_VISION_COMMIT,
        )
        assertEquals(
            "c203db7f6e9af9a39bbcccd1998e2d20c4a29edb",
            WORKOUT_VISION_COUNTING_CORE_BLOB,
        )
    }

    private fun pose(
        normalizedAngleDeg:Double,
        worldAngleDeg:Double,
    ) = NormalizedPose(
        candidateIndex = 0,
        bodyScale = 1.0,
        landmarks = bilateralRaiseLandmarks(normalizedAngleDeg),
        worldLandmarks = bilateralRaiseLandmarks(worldAngleDeg),
    )

    private fun bilateralRaiseLandmarks(
        angleDeg:Double,
    ):Map<PoseLandmarkId,BodyLocalLandmark> = buildMap {
        addSide(
            hip = PoseLandmarkId.LEFT_HIP,
            shoulder = PoseLandmarkId.LEFT_SHOULDER,
            elbow = PoseLandmarkId.LEFT_ELBOW,
            wrist = PoseLandmarkId.LEFT_WRIST,
            baseX = -1.0,
            angleDeg = angleDeg,
            mirror = false,
        )
        addSide(
            hip = PoseLandmarkId.RIGHT_HIP,
            shoulder = PoseLandmarkId.RIGHT_SHOULDER,
            elbow = PoseLandmarkId.RIGHT_ELBOW,
            wrist = PoseLandmarkId.RIGHT_WRIST,
            baseX = 1.0,
            angleDeg = angleDeg,
            mirror = true,
        )
    }

    private fun MutableMap<PoseLandmarkId,BodyLocalLandmark>.addSide(
        hip:PoseLandmarkId,
        shoulder:PoseLandmarkId,
        elbow:PoseLandmarkId,
        wrist:PoseLandmarkId,
        baseX:Double,
        angleDeg:Double,
        mirror:Boolean,
    ) {
        val radians=Math.toRadians(angleDeg)
        val direction=if(mirror)-1.0 else 1.0
        val elbowX=baseX + direction*sin(radians)
        val elbowY=cos(radians)
        fun putLandmark(id:PoseLandmarkId,x:Double,y:Double) {
            put(
                id,
                BodyLocalLandmark(
                    landmarkId=id,
                    x=x,
                    y=y,
                    z=0.0,
                    visibility=.95,
                    presence=.95,
                ),
            )
        }
        putLandmark(shoulder,baseX,0.0)
        putLandmark(hip,baseX,1.0)
        putLandmark(elbow,elbowX,elbowY)
        putLandmark(
            wrist,
            elbowX + direction*.5*sin(radians),
            elbowY + .5*cos(radians),
        )
    }

    companion object {
        private const val WORKOUT_VISION_COMMIT =
            "285977080e2b7eec01d1fde0a03e976db25a5926"
        private const val WORKOUT_VISION_COUNTING_CORE_BLOB =
            "c203db7f6e9af9a39bbcccd1998e2d20c4a29edb"
    }
}
