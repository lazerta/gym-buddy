package com.gymbuddy.domain.camera

import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.CameraGuidanceAction
import com.gymbuddy.domain.profile.CameraProfile
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profile.SemanticHash
import com.gymbuddy.domain.profile.ViewClass
import kotlin.math.abs

data class PersonalCameraPriorPolicy(
    val minimumSessions: Int = 3,
    val minimumConfidence: Double = .70,
    val maximumFrameFillTolerance: Double = .10,
) {
    init {
        require(minimumSessions >= 2)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(
            maximumFrameFillTolerance.isFinite() &&
                maximumFrameFillTolerance in 0.0..1.0
        )
    }
}

data class PersonalCameraPrior internal constructor(
    val cameraProfileId: String,
    val cameraProfileVersion: Int,
    val cameraProfileSemanticHash: String,
    val equipmentProfileId: String?,
    val viewClass: ViewClass,
    val targetFrameFill: Double,
    val tolerance: Double,
    val sessionCount: Int,
    val confidence: Double,
) {
    init {
        require(cameraProfileId.isNotBlank())
        require(cameraProfileVersion > 0)
        require(cameraProfileSemanticHash.isNotBlank())
        require(equipmentProfileId == null || equipmentProfileId.isNotBlank())
        require(targetFrameFill.isFinite() && targetFrameFill in 0.0..1.0)
        require(tolerance.isFinite() && tolerance in 0.0..1.0)
        require(sessionCount >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun matchesKnownSetup(
        profile: CameraProfile,
        observedView: ViewClass,
        frameFill: Double,
    ): Boolean =
        cameraProfileId == profile.profileId &&
            cameraProfileVersion == profile.profileVersion &&
            cameraProfileSemanticHash == profile.semanticHash &&
            viewClass == observedView &&
            viewClass in profile.allowedViewClasses &&
            frameFill in profile.frameFillRange &&
            abs(frameFill - targetFrameFill) <= tolerance

    fun rankedActions(
        profile: CameraProfile,
        observedView: ViewClass,
        frameFill: Double,
    ): List<CameraGuidanceAction> {
        if (
            cameraProfileId != profile.profileId ||
            cameraProfileVersion != profile.profileVersion ||
            cameraProfileSemanticHash != profile.semanticHash
        ) {
            return emptyList()
        }
        if (observedView !in profile.allowedViewClasses || observedView != viewClass) {
            return supportedOrCannotAssess(
                profile,
                CameraGuidanceAction.ADJUST_ANGLE,
            )
        }

        val primary = when {
            frameFill < profile.frameFillRange.min ->
                CameraGuidanceAction.MOVE_CLOSER
            frameFill > profile.frameFillRange.max ->
                CameraGuidanceAction.MOVE_FARTHER
            frameFill < targetFrameFill - tolerance ->
                CameraGuidanceAction.MOVE_CLOSER
            frameFill > targetFrameFill + tolerance ->
                CameraGuidanceAction.MOVE_FARTHER
            else ->
                CameraGuidanceAction.CAMERA_READY
        }

        if (primary !in profile.guidanceActions) {
            return listOf(CameraGuidanceAction.CANNOT_ASSESS)
        }
        return if (
            primary != CameraGuidanceAction.CAMERA_READY &&
            CameraGuidanceAction.CAMERA_READY in profile.guidanceActions
        ) {
            listOf(primary, CameraGuidanceAction.CAMERA_READY)
        } else {
            listOf(primary)
        }
    }

    private fun supportedOrCannotAssess(
        profile: CameraProfile,
        action: CameraGuidanceAction,
    ): List<CameraGuidanceAction> =
        if (action in profile.guidanceActions) {
            listOf(action)
        } else {
            listOf(CameraGuidanceAction.CANNOT_ASSESS)
        }
}

object PersonalCameraPriorCodec {
    private const val TARGET_FRAME_FILL = "target_frame_fill"
    private const val TOLERANCE = "tolerance"
    private const val SESSION_COUNT = "session_count"
    private const val CONFIDENCE = "confidence"

    fun entries(
        profile: CameraProfile,
        equipmentProfileId: String?,
        viewClass: ViewClass,
        targetFrameFill: Double,
        tolerance: Double,
        sessionCount: Int,
        confidence: Double,
    ): Map<String, Double?> {
        require(viewClass in profile.allowedViewClasses)
        require(targetFrameFill.isFinite() && targetFrameFill in profile.frameFillRange)
        require(tolerance.isFinite() && tolerance in 0.0..1.0)
        require(sessionCount >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(equipmentProfileId == null || equipmentProfileId.isNotBlank())

        val prefix = prefix(profile, equipmentProfileId, viewClass)
        return mapOf(
            "$prefix.$TARGET_FRAME_FILL" to targetFrameFill,
            "$prefix.$TOLERANCE" to tolerance,
            "$prefix.$SESSION_COUNT" to sessionCount.toDouble(),
            "$prefix.$CONFIDENCE" to confidence,
        )
    }

    fun resolve(
        config: AnalysisConfig,
        policy: PersonalCameraPriorPolicy = PersonalCameraPriorPolicy(),
    ): PersonalCameraPrior? = resolve(
        calibration = config.personalCalibrationProfile,
        profile = config.exerciseProfile.cameraProfile,
        equipmentProfileId = config.equipmentProfile?.profileId,
        viewClass = config.preferredViewClass,
        policy = policy,
    )

    fun resolve(
        calibration: PersonalCalibrationProfile?,
        profile: CameraProfile,
        equipmentProfileId: String?,
        viewClass: ViewClass,
        policy: PersonalCameraPriorPolicy = PersonalCameraPriorPolicy(),
    ): PersonalCameraPrior? {
        calibration ?: return null
        if (viewClass !in profile.allowedViewClasses) return null

        val prefix = prefix(profile, equipmentProfileId, viewClass)
        val target = calibration.cameraSetupPreferences[
            "$prefix.$TARGET_FRAME_FILL"
        ] ?: return null
        val tolerance = calibration.cameraSetupPreferences[
            "$prefix.$TOLERANCE"
        ] ?: return null
        val sessionCountRaw = calibration.cameraSetupPreferences[
            "$prefix.$SESSION_COUNT"
        ] ?: return null
        val confidence = calibration.cameraSetupPreferences[
            "$prefix.$CONFIDENCE"
        ] ?: return null

        if (!target.isFinite() || target !in profile.frameFillRange) return null
        if (
            !tolerance.isFinite() ||
            tolerance < 0.0 ||
            tolerance > policy.maximumFrameFillTolerance
        ) {
            return null
        }
        if (!sessionCountRaw.isFinite()) return null
        val sessionCount = sessionCountRaw.toInt()
        if (
            abs(sessionCountRaw - sessionCount.toDouble()) > 1e-9 ||
            sessionCount < policy.minimumSessions
        ) {
            return null
        }
        if (
            !confidence.isFinite() ||
            confidence < policy.minimumConfidence ||
            confidence > 1.0
        ) {
            return null
        }

        return PersonalCameraPrior(
            cameraProfileId = profile.profileId,
            cameraProfileVersion = profile.profileVersion,
            cameraProfileSemanticHash = profile.semanticHash,
            equipmentProfileId = equipmentProfileId,
            viewClass = viewClass,
            targetFrameFill = target,
            tolerance = tolerance,
            sessionCount = sessionCount,
            confidence = confidence,
        )
    }

    private fun prefix(
        profile: CameraProfile,
        equipmentProfileId: String?,
        viewClass: ViewClass,
    ): String = "camera_prior." + SemanticHash.sha256(
        profile.profileId,
        profile.profileVersion.toString(),
        profile.semanticHash,
        equipmentProfileId.orEmpty(),
        viewClass.name,
    ).take(16)
}
