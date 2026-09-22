package com.gymbuddy.data

import android.content.Context
import androidx.room.Room

object GymBuddyDatabaseFactory {
    fun create(
        context: Context,
        databaseName: String = "gym-buddy.db",
    ): GymBuddyDatabase = Room.databaseBuilder(
        context.applicationContext,
        GymBuddyDatabase::class.java,
        databaseName,
    ).build()
}
