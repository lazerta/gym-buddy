package com.gymbuddy.domain.engine

import com.gymbuddy.domain.movement.CoordinateNormalizationResult
import com.gymbuddy.domain.movement.CoordinateNormalizer
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.tracking.*

data class ProductionPipelineResult(
    val timestampUs: Long,
    val lock: PrimarySubjectLockResult,
    val tracking: TrackingQualityResult,
    val movement: MovementEngineOutput,
)

class ProductionMovementPipeline(
    private val config: AnalysisConfig,
    private val subjectLock: PrimarySubjectLock = PrimarySubjectLock(),
    private val trackingGate: TrackingQualityGate = TrackingQualityGate(),
    private val normalizer: CoordinateNormalizer = CoordinateNormalizer(),
    private val engine: MovementInterpretationEngine = MovementInterpretationEngine(config),
) {
    private var lastFrameTimestampUs: Long? = null
    private var lastLock = PrimarySubjectLockResult(PrimarySubjectLockState.TARGET_LOST,null,null,null,0)

    fun reset() {
        lastFrameTimestampUs = null
        lastLock = PrimarySubjectLockResult(PrimarySubjectLockState.TARGET_LOST,null,null,null,0)
        subjectLock.reset(); trackingGate.reset()
    }

    fun process(frame: PoseFrame, context: TrackingObservationContext = TrackingObservationContext()): ProductionPipelineResult {
        val previous = lastFrameTimestampUs
        if (previous != null && frame.timestampUs <= previous) {
            val tracking = TrackingQualityResult(TrackingQualityState.PAUSED, TrackingQualityReason.NON_MONOTONIC_TIMESTAMP, lastLock.targetCandidateIndex, null, null)
            return ProductionPipelineResult(frame.timestampUs,lastLock,tracking,pausedMovement(frame.timestampUs))
        }
        lastFrameTimestampUs = frame.timestampUs
        val lock = subjectLock.update(frame)
        lastLock = lock
        val tracking = trackingGate.evaluate(frame,lock,config.exerciseProfile.cameraProfile,context)
        if (!tracking.allowsBiomechanics) {
            return ProductionPipelineResult(frame.timestampUs,lock,tracking,engine.onInterruption(frame.timestampUs))
        }
        val targetIndex = lock.targetCandidateIndex
        val candidate = targetIndex?.let { idx -> frame.candidates.firstOrNull { it.candidateIndex == idx } }
        if (candidate == null) {
            val paused = TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.TARGET_NOT_AVAILABLE,targetIndex,null,null)
            return ProductionPipelineResult(frame.timestampUs,lock,paused,engine.onInterruption(frame.timestampUs))
        }
        return when (val normalized = normalizer.normalize(candidate)) {
            is CoordinateNormalizationResult.Valid -> ProductionPipelineResult(frame.timestampUs,lock,tracking,engine.process(frame.timestampUs,normalized.pose))
            is CoordinateNormalizationResult.Unknown -> {
                val paused = TrackingQualityResult(TrackingQualityState.PAUSED,TrackingQualityReason.INSUFFICIENT_EVIDENCE,targetIndex,null,null)
                ProductionPipelineResult(frame.timestampUs,lock,paused,engine.onInterruption(frame.timestampUs))
            }
        }
    }

    private fun pausedMovement(ts: Long) = MovementEngineOutput(ts,null,null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),true)
}
