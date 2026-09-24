package com.gymbuddy.app.runtime

import com.gymbuddy.domain.persistence.PersonalCalibrationRepository
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profile.PersonalCalibrationProfile
import com.gymbuddy.domain.profiles.ExerciseBundle

internal class RuntimeAnalysisConfigResolver(
    private val loadCalibration:()->PersonalCalibrationProfile?,
){
    constructor(repository:PersonalCalibrationRepository):this(repository::loadActive)

    fun resolve(bundle:ExerciseBundle):AnalysisConfig = AnalysisConfigResolver.resolve(
        bundle.definition,
        bundle.profile,
        bundle.equipment,
        loadCalibration(),
    )
}
