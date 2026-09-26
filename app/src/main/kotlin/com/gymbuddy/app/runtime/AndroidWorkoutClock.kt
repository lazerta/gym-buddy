package com.gymbuddy.app.runtime

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.gymbuddy.app.controller.WorkoutClock

/** elapsedRealtime includes device sleep; boot identity prevents cross-reboot subtraction. */
class AndroidWorkoutClock(context:Context):WorkoutClock {
    private val boot=runCatching{
        Settings.Global.getInt(context.applicationContext.contentResolver,Settings.Global.BOOT_COUNT).toString()
    }.getOrNull()
    override fun nowEpochMs():Long=System.currentTimeMillis()
    override fun nowElapsedMs():Long=SystemClock.elapsedRealtime()
    override fun bootId():String?=boot
}
