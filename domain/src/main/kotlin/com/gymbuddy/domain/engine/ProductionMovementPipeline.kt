package com.gymbuddy.domain.engine

import com.gymbuddy.domain.camera.CameraGuidanceEngine
import com.gymbuddy.domain.camera.PersonalCameraPriorCodec
import com.gymbuddy.domain.camera.PoseViewEstimator
import com.gymbuddy.domain.lifecycle.SetLifecycleController
import com.gymbuddy.domain.lifecycle.SetLifecycleState
import com.gymbuddy.domain.movement.*
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.ViewClass
import com.gymbuddy.domain.tracking.*

data class ProductionPipelineResult(
    val timestampUs: Long,
    val lock: PrimarySubjectLockResult,
    val tracking: TrackingQualityResult,
    val movement: MovementEngineOutput,
    val cameraGuidance: CameraGuidanceAction,
    val lifecycleState: SetLifecycleState,
    val observedViewClass: ViewClass?,
)

class ProductionMovementPipeline(
    private val config: AnalysisConfig,
    private val subjectLock: PrimarySubjectLock = PrimarySubjectLock(),
    private val trackingGate: TrackingQualityGate = TrackingQualityGate(),
    private val normalizer: CoordinateNormalizer = CoordinateNormalizer(),
    private val evidenceIdNamespace:String?=null,
    private val engine: MovementInterpretationEngine =
        MovementInterpretationEngine(config,idNamespace=evidenceIdNamespace),
    private val cameraGuidanceEngine: CameraGuidanceEngine = CameraGuidanceEngine(),
    private val lifecycle: SetLifecycleController = SetLifecycleController(),
    private val viewEstimator: PoseViewEstimator = PoseViewEstimator(),
) {
    private var lastFrameTimestampUs: Long? = null
    private var lastLock = PrimarySubjectLockResult(
        PrimarySubjectLockState.TARGET_LOST,null,null,null,0
    )
    private val interruptionGate = InterruptionEpisodeGate()

    fun reset() {
        lastFrameTimestampUs = null
        lastLock = PrimarySubjectLockResult(
            PrimarySubjectLockState.TARGET_LOST,null,null,null,0
        )
        interruptionGate.reset()
        subjectLock.reset()
        trackingGate.reset()
        cameraGuidanceEngine.reset()
        lifecycle.reset()
        engine.reset()
    }

    fun manualEnd(): SetLifecycleState = lifecycle.manualEnd()
    fun finalized(): SetLifecycleState = lifecycle.finalized()

    fun process(
        frame: PoseFrame,
        context: TrackingObservationContext = TrackingObservationContext(),
    ): ProductionPipelineResult {
        val previous = lastFrameTimestampUs
        if (previous != null && frame.timestampUs <= previous) {
            val tracking = TrackingQualityResult(
                TrackingQualityState.PAUSED,
                TrackingQualityReason.NON_MONOTONIC_TIMESTAMP,
                lastLock.targetCandidateIndex,
                null,
                null,
            )
            return result(
                frame.timestampUs,
                lastLock,
                tracking,
                pausedMovement(frame.timestampUs),
                CameraGuidanceAction.CANNOT_ASSESS,
                context.observedViewClass,
            )
        }
        lastFrameTimestampUs = frame.timestampUs
        val lock = subjectLock.update(frame)
        lastLock = lock
        val target = lock.targetCandidateIndex?.let { idx ->
            frame.candidates.firstOrNull { it.candidateIndex == idx }
        }
        val effectiveContext =
            if (context.observedViewClass == null && target != null) {
                context.copy(observedViewClass = viewEstimator.estimate(target))
            } else context

        val priorLifecycle = lifecycle.state
        val observedView=effectiveContext.observedViewClass
            ?:config.preferredViewClass
        val personalPrior=PersonalCameraPriorCodec.resolve(
            calibration=config.personalCalibrationProfile,
            profile=config.exerciseProfile.cameraProfile,
            equipmentProfileId=config.equipmentProfile?.profileId,
            viewClass=observedView,
        )
        val guidance = cameraGuidanceEngine.evaluate(
            frame,
            lock,
            config.exerciseProfile.cameraProfile,
            effectiveContext,
            personalPrior,
        )
        val lifecycleAfterGuidance = lifecycle.onCameraGuidance(guidance)
        if (
            priorLifecycle != SetLifecycleState.CAMERA_GUIDANCE &&
            lifecycleAfterGuidance == SetLifecycleState.CAMERA_GUIDANCE &&
            priorLifecycle != SetLifecycleState.ACTIVE_SET
        ) {
            engine.reset()
            interruptionGate.reset()
        }
        if (lifecycleAfterGuidance == SetLifecycleState.CAMERA_GUIDANCE) {
            val tracking = TrackingQualityResult(
                TrackingQualityState.PAUSED,
                TrackingQualityReason.CAMERA_NOT_READY,
                lock.targetCandidateIndex,
                null,
                null,
            )
            return result(
                frame.timestampUs,
                lock,
                tracking,
                pausedMovement(frame.timestampUs),
                guidance,
                effectiveContext.observedViewClass,
            )
        }

        val tracking = trackingGate.evaluate(
            frame,
            lock,
            config.exerciseProfile.cameraProfile,
            effectiveContext,
        )
        if (
            tracking.reason == TrackingQualityReason.CAMERA_DISTURBANCE ||
            tracking.reason == TrackingQualityReason.WRONG_VIEW
        ) {
            val priorState = lifecycle.state
            val invalidated = lifecycle.invalidateCameraSetup()
            if (
                priorState != SetLifecycleState.CAMERA_GUIDANCE &&
                invalidated == SetLifecycleState.CAMERA_GUIDANCE
            ) {
                cameraGuidanceEngine.reset()
                return result(
                    frame.timestampUs,
                    lock,
                    tracking,
                    interruptOnce(frame.timestampUs),
                    guidance,
                    effectiveContext.observedViewClass,
                )
            }
        }
        if (!tracking.allowsBiomechanics) {
            return result(
                frame.timestampUs,
                lock,
                tracking,
                interruptOnce(frame.timestampUs),
                guidance,
                effectiveContext.observedViewClass,
            )
        }
        val candidate = target
        if (candidate == null) {
            val paused = TrackingQualityResult(
                TrackingQualityState.PAUSED,
                TrackingQualityReason.TARGET_NOT_AVAILABLE,
                lock.targetCandidateIndex,
                null,
                null,
            )
            return result(
                frame.timestampUs,
                lock,
                paused,
                interruptOnce(frame.timestampUs),
                guidance,
                effectiveContext.observedViewClass,
            )
        }
        return when (val normalized = normalizer.normalize(candidate)) {
            is CoordinateNormalizationResult.Valid -> {
                interruptionGate.reset()
                val movement = engine.process(frame.timestampUs,normalized.pose)
                lifecycle.onMovement(movement.primitives)
                result(
                    frame.timestampUs,
                    lock,
                    tracking,
                    movement,
                    guidance,
                    effectiveContext.observedViewClass,
                )
            }
            is CoordinateNormalizationResult.Unknown -> {
                val paused = TrackingQualityResult(
                    TrackingQualityState.PAUSED,
                    TrackingQualityReason.INSUFFICIENT_EVIDENCE,
                    lock.targetCandidateIndex,
                    null,
                    null,
                )
                result(
                    frame.timestampUs,
                    lock,
                    paused,
                    interruptOnce(frame.timestampUs),
                    guidance,
                    effectiveContext.observedViewClass,
                )
            }
        }
    }

    private fun interruptOnce(ts:Long):MovementEngineOutput =
        if(interruptionGate.onInvalid())engine.onInterruption(ts)
        else pausedMovement(ts)

    private fun result(
        ts:Long,
        lock:PrimarySubjectLockResult,
        tracking:TrackingQualityResult,
        movement:MovementEngineOutput,
        guidance:CameraGuidanceAction,
        observedViewClass:ViewClass?,
    )=ProductionPipelineResult(
        ts,lock,tracking,movement,guidance,lifecycle.state,observedViewClass
    )

    private fun pausedMovement(ts: Long) = MovementEngineOutput(
        ts,null,null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),true
    )
}
