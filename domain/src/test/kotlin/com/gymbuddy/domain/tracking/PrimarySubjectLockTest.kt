package com.gymbuddy.domain.tracking

import com.gymbuddy.domain.pose.PoseCoordinate3d
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.pose.PoseFrameSource
import com.gymbuddy.domain.pose.PoseLandmarkId
import com.gymbuddy.domain.pose.PoseLandmarkObservation
import com.gymbuddy.domain.pose.PoseSubjectCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimarySubjectLockTest {
    @Test fun onePersonAcquiresAndStaysLocked() {
        val lock = PrimarySubjectLock()
        val first = lock.update(frame(0, person(0, 0.50, 0.52, 1.0)))
        val second = lock.update(frame(100_000, person(3, 0.52, 0.52, 1.0)))
        assertEquals(PrimarySubjectLockState.LOCKED, first.state)
        assertEquals(PrimarySubjectLockState.LOCKED, second.state)
        assertEquals(3, second.targetCandidateIndex)
    }

    @Test fun secondPersonEnteringDoesNotStealLock() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.45, 0.50, 1.0)))
        val result = lock.update(frame(100_000, person(0, 0.46, 0.50, 1.0), person(1, 0.78, 0.48, 1.35)))
        assertEquals(PrimarySubjectLockState.LOCKED, result.state)
        assertEquals(0, result.targetCandidateIndex)
    }

    @Test fun personCrossingBehindTargetDoesNotCauseSwitch() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.40, 0.50, 1.0)))
        lock.update(frame(100_000, person(0, 0.44, 0.50, 1.0), person(1, 0.70, 0.50, 1.30)))
        val crossing = lock.update(frame(200_000, person(0, 0.48, 0.50, 1.0), person(1, 0.51, 0.50, 1.30)))
        assertEquals(PrimarySubjectLockState.LOCKED, crossing.state)
        assertEquals(0, crossing.targetCandidateIndex)
    }

    @Test fun trainerBesideUserAndDemonstratingSameExerciseIsIgnored() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.42, 0.50, 1.0)))
        val beside = lock.update(frame(100_000, person(0, 0.43, 0.50, 1.0), person(1, 0.68, 0.50, 1.32, posePhase = 0.8)))
        val demo = lock.update(frame(200_000, person(0, 0.44, 0.50, 1.0, posePhase = 0.6), person(1, 0.63, 0.50, 1.32, posePhase = 0.6)))
        assertEquals(0, beside.targetCandidateIndex)
        assertEquals(0, demo.targetCandidateIndex)
        assertEquals(PrimarySubjectLockState.LOCKED, demo.state)
    }

    @Test fun briefOcclusionPreservesIdentityAndReacquiresWithDwell() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.50, 0.50, 1.0)))
        val occluded = lock.update(frame(200_000))
        val returning1 = lock.update(frame(300_000, person(4, 0.51, 0.50, 1.0)))
        val returning2 = lock.update(frame(400_000, person(7, 0.52, 0.50, 1.0)))
        assertEquals(PrimarySubjectLockState.TEMPORARILY_OCCLUDED, occluded.state)
        assertEquals(PrimarySubjectLockState.REACQUIRING, returning1.state)
        assertEquals(PrimarySubjectLockState.LOCKED, returning2.state)
        assertEquals(7, returning2.targetCandidateIndex)
    }

    @Test fun targetLeavesThenReturnsRequiresReacquisition() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.48, 0.50, 1.0)))
        assertEquals(PrimarySubjectLockState.TARGET_LOST, lock.update(frame(2_000_000)).state)
        val firstReturn = lock.update(frame(2_100_000, person(2, 0.49, 0.50, 1.0)))
        val secondReturn = lock.update(frame(2_200_000, person(9, 0.50, 0.50, 1.0)))
        assertEquals(PrimarySubjectLockState.REACQUIRING, firstReturn.state)
        assertEquals(PrimarySubjectLockState.LOCKED, secondReturn.state)
    }

    @Test fun subjectSwapNeverSilentlyLocksNewPerson() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.45, 0.50, 1.0)))
        val swap = lock.update(frame(100_000, person(0, 0.46, 0.50, 1.55)))
        assertNotEquals(PrimarySubjectLockState.LOCKED, swap.state)
        assertNull(swap.targetCandidateIndex)
    }

    @Test fun competingSimilarCandidateWithLowMarginBecomesAmbiguous() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.50, 0.50, 1.0)))
        val result = lock.update(frame(100_000, person(0, 0.505, 0.50, 1.0), person(1, 0.507, 0.50, 1.01)))
        assertEquals(PrimarySubjectLockState.TARGET_AMBIGUOUS, result.state)
        assertNull(result.targetCandidateIndex)
        assertTrue((result.identityMargin ?: 1.0) < 0.12)
    }

    @Test fun noFalseSwitchToBystanderWhenOriginalTargetTemporarilyMissing() {
        val lock = PrimarySubjectLock()
        lock.update(frame(0, person(0, 0.42, 0.50, 1.0)))
        val bystanderOnly = lock.update(frame(150_000, person(1, 0.45, 0.50, 1.45)))
        assertNotEquals(PrimarySubjectLockState.LOCKED, bystanderOnly.state)
        assertNull(bystanderOnly.targetCandidateIndex)
    }

    private fun frame(timestampUs: Long, vararg people: PoseSubjectCandidate) = PoseFrame(
        frameId = timestampUs,
        timestampUs = timestampUs,
        width = 1280,
        height = 720,
        source = PoseFrameSource.CAMERA,
        candidates = people.toList(),
    )

    private fun person(index: Int, centerX: Double, centerY: Double, morphology: Double, posePhase: Double = 0.0): PoseSubjectCandidate {
        val shoulderHalf = 0.07 * morphology
        val hipHalf = 0.055 * morphology
        val torso = 0.18 * morphology
        val arm = 0.105 * morphology
        val forearm = 0.095 * morphology
        val thigh = 0.16 * morphology
        val shin = 0.15 * morphology
        val shoulderY = centerY - torso / 2
        val hipY = centerY + torso / 2
        val phaseOffset = posePhase * 0.018

        val points = mapOf(
            PoseLandmarkId.LEFT_SHOULDER to p(centerX - shoulderHalf, shoulderY),
            PoseLandmarkId.RIGHT_SHOULDER to p(centerX + shoulderHalf, shoulderY),
            PoseLandmarkId.LEFT_HIP to p(centerX - hipHalf, hipY),
            PoseLandmarkId.RIGHT_HIP to p(centerX + hipHalf, hipY),
            PoseLandmarkId.LEFT_ELBOW to p(centerX - shoulderHalf - arm, shoulderY + arm * 0.45 + phaseOffset),
            PoseLandmarkId.RIGHT_ELBOW to p(centerX + shoulderHalf + arm, shoulderY + arm * 0.45 + phaseOffset),
            PoseLandmarkId.LEFT_WRIST to p(centerX - shoulderHalf - arm - forearm, shoulderY + arm * 0.40 + phaseOffset * 1.5),
            PoseLandmarkId.RIGHT_WRIST to p(centerX + shoulderHalf + arm + forearm, shoulderY + arm * 0.40 + phaseOffset * 1.5),
            PoseLandmarkId.LEFT_KNEE to p(centerX - hipHalf, hipY + thigh),
            PoseLandmarkId.RIGHT_KNEE to p(centerX + hipHalf, hipY + thigh),
            PoseLandmarkId.LEFT_ANKLE to p(centerX - hipHalf, hipY + thigh + shin),
            PoseLandmarkId.RIGHT_ANKLE to p(centerX + hipHalf, hipY + thigh + shin),
        )
        val landmarks = points.mapValues { (id, pos) ->
            PoseLandmarkObservation(id, pos, visibility = 0.95, presence = 0.95)
        }
        return PoseSubjectCandidate(index, landmarks, confidence = null)
    }

    private fun p(x: Double, y: Double) = PoseCoordinate3d(x, y, 0.0)
}
