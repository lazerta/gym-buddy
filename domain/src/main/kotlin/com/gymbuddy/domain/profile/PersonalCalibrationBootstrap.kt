package com.gymbuddy.domain.profile

data class BootstrapGeometryRatio(
    val geometryId: String,
    val numerator: Double?,
    val denominator: Double?,
) {
    init {
        require(geometryId.isNotBlank()) { "geometryId must not be blank" }
        numerator?.let {
            require(it.isFinite() && it > 0.0) {
                "numerator must be null or finite and > 0"
            }
        }
        denominator?.let {
            require(it.isFinite() && it > 0.0) {
                "denominator must be null or finite and > 0"
            }
        }
    }

    fun normalizedValue(): Double? =
        if (numerator == null || denominator == null) null else numerator / denominator
}

data class PersonalCalibrationBootstrapEvidence(
    val evidenceReference: String,
    val sourceConfidence: Double,
    val normalizedGeometry: Map<String, Double?> = emptyMap(),
    val explicitRatios: List<BootstrapGeometryRatio> = emptyList(),
) {
    init {
        require(evidenceReference.isNotBlank()) { "evidenceReference must not be blank" }
        require(sourceConfidence.isFinite() && sourceConfidence in 0.0..1.0) {
            "sourceConfidence must be within [0, 1]"
        }
        require(normalizedGeometry.keys.none { it.isBlank() }) {
            "normalizedGeometry keys must not be blank"
        }
        normalizedGeometry.values.filterNotNull().forEach {
            require(it.isFinite() && it > 0.0) {
                "normalizedGeometry values must be null or finite and > 0"
            }
        }
        require(explicitRatios.map { it.geometryId }.distinct().size == explicitRatios.size) {
            "explicitRatios must not contain duplicate geometry ids"
        }
        require(
            normalizedGeometry.keys
                .intersect(explicitRatios.map { it.geometryId }.toSet())
                .isEmpty()
        ) {
            "normalizedGeometry and explicitRatios must not define the same geometry id"
        }
    }
}

object PersonalCalibrationBootstrapper {
    const val MAX_BOOTSTRAP_CONFIDENCE: Double = .35

    fun bootstrap(
        calibrationProfileId: String,
        profileVersion: Int,
        evidence: PersonalCalibrationBootstrapEvidence?,
    ): PersonalCalibrationProfile? {
        evidence ?: return null

        val normalizedBodyGeometry = buildMap<String, Double?> {
            putAll(evidence.normalizedGeometry)
            evidence.explicitRatios.forEach { measurement ->
                put(measurement.geometryId, measurement.normalizedValue())
            }
        }

        return PersonalCalibrationProfile.create(
            calibrationProfileId = calibrationProfileId,
            profileVersion = profileVersion,
            sourceConfidence = evidence.sourceConfidence.coerceAtMost(MAX_BOOTSTRAP_CONFIDENCE),
            normalizedBodyGeometry = normalizedBodyGeometry,
            evidenceReferences = setOf(evidence.evidenceReference),
        )
    }
}
