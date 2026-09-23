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

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sets` ADD COLUMN `actualLoadValue` REAL")
            db.execSQL("ALTER TABLE `sets` ADD COLUMN `actualLoadUnit` TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workout_flow_states` (
                    `checkpointId` TEXT NOT NULL,
                    `completedSetId` TEXT NOT NULL,
                    `focus` TEXT NOT NULL,
                    `plannedNextLoadValue` REAL,
                    `plannedNextLoadUnit` TEXT,
                    `restStartedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`checkpointId`),
                    FOREIGN KEY(`completedSetId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_flow_states_completedSetId` " +
                    "ON `workout_flow_states` (`completedSetId`)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
