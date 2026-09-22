package com.gymbuddy.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object GymBuddyMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cue_deliveries` (
                    `cueId` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`cueId`),
                    FOREIGN KEY(`cueId`) REFERENCES `cue_events`(`cueId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }
}
