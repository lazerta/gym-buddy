package com.gymbuddy.domain

internal object ProfileFixtures {
    val definition = ExerciseDefinition(
        exerciseId = "incline_dumbbell_press",
        displayName = "Incline Dumbbell Press",
        aliases = setOf("incline db press"),
        movementFamily = MovementFamily.PRESS,
    )

    fun profile(
        profileVersion: Int = 3,
        excursionMin: Double = 0.35,
    ): ExerciseProfile {
        val camera = CameraProfile(
            cameraProfileId = "incline_press_camera",
            version = 2,
            preferredViewClass = ViewClass.SIDE_OBLIQUE,
            allowedViewClasses = setOf(ViewClass.SIDE, ViewClass.SIDE_OBLIQUE),
            allowedLensFacing = setOf(LensFacing.BACK),
            requiredLandmarks = setOf(
                "LEFT_SHOULDER", "RIGHT_SHOULDER",
                "LEFT_ELBOW", "RIGHT_ELBOW",
                "LEFT_WRIST", "RIGHT_WRIST",
            ),
            minRequiredVisibleFraction = 0.85,
            minLandmarkConfidence = 0.70,
            minIdentityMargin = 0.15,
            minFrameFill = 0.25,
            maxFrameFill = 0.85,
        )
        val signals = SignalProfile(
            signalProfileId = "incline_press_signals",
            version = 4,
            signalDefinitions = listOf(
                SignalDefinition(
                    signalId = "press_depth",
                    kind = SignalKind.BODY_LOCAL_DISPLACEMENT,
                    unit = MetricUnit.NORMALIZED_DISPLACEMENT,
                    requiredLandmarks = setOf(
                        "LEFT_SHOULDER", "RIGHT_SHOULDER",
                        "LEFT_WRIST", "RIGHT_WRIST",
                    ),
                    minimumConfidence = 0.70,
                    parameters = mapOf(
                        "smoothing_alpha" to 0.35,
                        "excursion_min" to excursionMin,
                    ),
                ),
                SignalDefinition(
                    signalId = "bilateral_timing",
                    kind = SignalKind.BILATERAL_TIMING_DIFFERENCE,
                    unit = MetricUnit.MILLISECONDS,
                    requiredLandmarks = setOf(
                        "LEFT_ELBOW", "RIGHT_ELBOW",
                        "LEFT_WRIST", "RIGHT_WRIST",
                    ),
                    minimumConfidence = 0.70,
                    parameters = mapOf("smoothing_alpha" to 0.25),
                ),
            ),
        )
        val primitiveSequence = MovementPrimitiveSequence(
            sequenceId = "incline_press_primitive_sequence",
            version = 2,
            primitives = listOf(
                MovementPrimitiveSpec(
                    primitiveId = "press",
                    type = MovementPrimitiveType.PRESS,
                    progressSignalId = "press_depth",
                    parameters = mapOf(
                        "stable_start_max" to 0.15,
                        "minimum_excursion" to excursionMin,
                        "completion_max" to 0.18,
                    ),
                )
            ),
        )
        val metrics = MetricProfile(
            metricProfileId = "incline_press_metrics",
            version = 2,
            metricDefinitions = listOf(
                MetricDefinition(
                    metricId = "rom_proxy",
                    sourceSignalIds = setOf("press_depth"),
                    unit = MetricUnit.NORMALIZED_RANGE,
                ),
                MetricDefinition(
                    metricId = "bilateral_timing_ms",
                    sourceSignalIds = setOf("bilateral_timing"),
                    unit = MetricUnit.MILLISECONDS,
                ),
            ),
        )
        val rules = FormRuleSet(
            formRuleSetId = "incline_press_rules",
            version = 1,
            rules = listOf(
                FormRule(
                    ruleId = "timing_asymmetry",
                    version = 1,
                    sourceSignalIds = setOf("bilateral_timing"),
                    comparator = RuleComparator.GREATER_THAN,
                    threshold = 150.0,
                    minimumConfidence = 0.75,
                    severity = CueSeverity.MINOR,
                    cueKey = "sync_arms",
                )
            ),
        )
        val cuePolicy = CuePolicy(
            cuePolicyId = "default_conservative",
            version = 1,
            persistenceWindowReps = 3,
            requiredHitsInWindow = 2,
            cooldownReps = 2,
        )

        return ExerciseProfile.create(
            exerciseId = definition.exerciseId,
            profileId = "incline_db_press_v1",
            profileVersion = profileVersion,
            lateralityMode = LateralityMode.BILATERAL,
            cameraProfile = camera,
            signalProfile = signals,
            movementPrimitiveSequence = primitiveSequence,
            metricProfile = metrics,
            formRuleSet = rules,
            cuePolicy = cuePolicy,
            supportedEquipmentKinds = setOf(EquipmentKind.DUMBBELL, EquipmentKind.BENCH),
            capabilities = setOf("rep_detection", "bilateral_timing", "rom_proxy"),
        )
    }

    fun equipment(
        profile: ExerciseProfile,
        parameterOverrides: Map<String, Map<String, Double>> = mapOf(
            "press_depth" to mapOf("excursion_min" to 0.40)
        ),
    ): EquipmentProfile = EquipmentProfile.create(
        equipmentProfileId = "incline_bench_1",
        profileVersion = 2,
        equipmentKind = EquipmentKind.BENCH,
        compatibleExerciseProfileIds = setOf(profile.profileId),
        preferredViewClassOverride = ViewClass.SIDE,
        signalParameterOverrides = parameterOverrides,
    )

    fun calibration(
        profile: ExerciseProfile,
        equipment: EquipmentProfile? = null,
        metricId: String = "rom_proxy",
    ): PersonalCalibrationProfile = PersonalCalibrationProfile.create(
        calibrationProfileId = "personal_calibration",
        profileVersion = 5,
        sourceConfidence = 0.82,
        normalizedBodyGeometry = mapOf(
            "upper_arm_to_torso" to 0.61,
            "unknown_absolute_height" to null,
        ),
        cameraSetupPreferences = mapOf(
            profile.profileId to CameraSetupPreference(
                preferredViewClass = ViewClass.SIDE,
                preferredFrameFill = 0.50,
            )
        ),
        exerciseBaselines = listOf(
            ExerciseBaseline(
                exerciseProfileId = profile.profileId,
                compatibleProfileVersion = profile.profileVersion,
                equipmentProfileId = equipment?.equipmentProfileId,
                viewClass = ViewClass.SIDE,
                metricBaselines = mapOf(
                    metricId to MetricBaseline(
                        median = 0.78,
                        medianAbsoluteDeviation = 0.04,
                        sampleCount = 120,
                        sessionCount = 8,
                        confidence = 0.9,
                    )
                ),
            )
        ),
        evidenceReferences = listOf("session-1", "session-2"),
    )
}
