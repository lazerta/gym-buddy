package com.gymbuddy.domain.persistence

import com.gymbuddy.domain.profile.PersonalCalibrationProfile

interface PersonalCalibrationRepository {
    fun loadActive():PersonalCalibrationProfile?
    fun saveActive(profile:PersonalCalibrationProfile)
    fun clearActive()
}
