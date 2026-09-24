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

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `personal_calibration_profiles` (
                    `slotId` TEXT NOT NULL,
                    `calibrationProfileId` TEXT NOT NULL,
                    `profileVersion` INTEGER NOT NULL,
                    `semanticHash` TEXT NOT NULL,
                    `payload` TEXT NOT NULL,
                    PRIMARY KEY(`slotId`)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `workout_flow_states` " +
                    "ADD COLUMN `state` TEXT NOT NULL DEFAULT 'REST'"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `interrupted_sets` (
                    `setId` TEXT NOT NULL,
                    `recoveredAtEpochMs` INTEGER NOT NULL,
                    `committedReps` INTEGER NOT NULL,
                    PRIMARY KEY(`setId`),
                    FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `exercise_executions` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sets` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `set_summaries` ADD COLUMN `endedAtEpochMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeObservableFrames` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeDegradedFrames` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activePausedFrames` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeUnknownFrames` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `interruptionEpisodes` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `cameraDisturbanceEpisodes` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `observedViewClass` TEXT")
            db.execSQL("ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeFrameFillMean` REAL")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `personal_calibration_profile_history` (
                    `calibrationProfileId` TEXT NOT NULL,
                    `profileVersion` INTEGER NOT NULL,
                    `semanticHash` TEXT NOT NULL,
                    `payload` TEXT NOT NULL,
                    PRIMARY KEY(`calibrationProfileId`, `profileVersion`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `personal_calibration_profile_history`
                    (`calibrationProfileId`, `profileVersion`, `semanticHash`, `payload`)
                SELECT `calibrationProfileId`, `profileVersion`, `semanticHash`, `payload`
                FROM `personal_calibration_profiles`
                """.trimIndent()
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}
