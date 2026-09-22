package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.coaching.CueEvent
import com.gymbuddy.domain.evidence.*
import com.gymbuddy.domain.movement.RepClassification
import com.gymbuddy.domain.profile.MovementPrimitive
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoomEvidenceRepositoryTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test fun completedRepBundleRollsBackAtomically() {
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo=RoomEvidenceRepository(db.evidenceDao())
            val ids=openSet(repo)
            val rep=rep(1,"rep-1",1_000_000,ids.config.provenance)
            val duplicate=FormObservation("obs-dup",rep.repId,"bilateral_asymmetry",2,FormObservationState.OK,com.gymbuddy.domain.profile.FormRuleSeverity.MINOR,.9,.0)
            assertThrows(Exception::class.java){repo.persistCompletedRepBundle(ids.setId,rep,listOf(duplicate,duplicate),emptyList(),emptyList())}
            assertNull(db.evidenceDao().rep(rep.repId))
        } finally { db.close() }
    }

    @Test fun closeReopenPreservesDeterministicEvidenceAndCueDelivery() {
        val name="gym-test-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val ids:Ids
        Room.databaseBuilder(context,GymBuddyDatabase::class.java,name).addMigrations(GymBuddyMigrations.MIGRATION_1_2).allowMainThreadQueries().build().use { db ->
            val repo=RoomEvidenceRepository(db.evidenceDao());ids=openSet(repo)
            val rep2=rep(2,"rep-2",2_000_000,ids.config.provenance)
            val rep1=rep(1,"rep-1",1_000_000,ids.config.provenance)
            repo.persistCompletedRepBundle(ids.setId,rep2,listOf(obs(rep2,"obs-2")),emptyList(),emptyList())
            val cue=CueEvent("cue-1","bilateral_asymmetry",rep1.repId,rep1.completedAtUs,"MINOR")
            repo.persistCompletedRepBundle(ids.setId,rep1,listOf(obs(rep1,"obs-1")),listOf(CueEvidenceLink(cue,"obs-1")),emptyList())
            repo.persistCueDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.COMPLETED))
        }
        Room.databaseBuilder(context,GymBuddyDatabase::class.java,name).addMigrations(GymBuddyMigrations.MIGRATION_1_2).allowMainThreadQueries().build().use { db ->
            val loaded=RoomEvidenceRepository(db.evidenceDao()).loadSet(ids.setId)!!
            assertEquals(listOf(1,2),loaded.reps.map{it.ordinal})
            assertEquals(listOf("rep-1","rep-2"),loaded.observations.map{it.repId})
            assertEquals(listOf(CueDeliveryRecord("cue-1",CueDeliveryState.COMPLETED)),loaded.cueDeliveries)
        }
        context.deleteDatabase(name)
    }

    @Test fun terminalCueDeliveryDoesNotRegress() {
        val db=Room.inMemoryDatabaseBuilder(context,GymBuddyDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo=RoomEvidenceRepository(db.evidenceDao())
            val ids=openSet(repo)
            val rep=rep(1,"rep-1",1_000_000,ids.config.provenance)
            val cue=CueEvent("cue-1","bilateral_asymmetry",rep.repId,rep.completedAtUs,"MINOR")
            repo.persistCompletedRepBundle(ids.setId,rep,listOf(obs(rep,"obs-1")),listOf(CueEvidenceLink(cue,"obs-1")),emptyList())
            repo.persistCueDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.STARTED))
            repo.persistCueDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.COMPLETED))
            assertThrows(IllegalArgumentException::class.java){repo.persistCueDelivery(CueDeliveryRecord(cue.cueId,CueDeliveryState.CANCELLED))}
            assertEquals(CueDeliveryState.COMPLETED,repo.loadSet(ids.setId)!!.cueDeliveries.single().state)
        } finally { db.close() }
    }

    @Test fun migration1To2PreservesOldSchemaAndAddsCueDelivery() {
        val name="gym-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        createV1Database(name).close()
        Room.databaseBuilder(context,GymBuddyDatabase::class.java,name).addMigrations(GymBuddyMigrations.MIGRATION_1_2).allowMainThreadQueries().build().use { db ->
            val cursor=db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cue_deliveries'")
            cursor.use { assertTrue(it.moveToFirst()) }
        }
        context.deleteDatabase(name)
    }

    private data class Ids(val setId:String,val config:com.gymbuddy.domain.profile.AnalysisConfig)
    private fun openSet(repo:RoomEvidenceRepository):Ids{
        val bundle=InitialExerciseProfiles.inclineDumbbellPress
        val config=AnalysisConfigResolver.resolve(bundle.definition,bundle.profile,bundle.equipment)
        repo.ensureSession(WorkoutSessionRecord("session",0));repo.ensureExecution(ExerciseExecutionRecord("exec","session",bundle.definition.exerciseId,0));repo.openSet(SetRecord("set","exec",1,0),config)
        return Ids("set",config)
    }
    private fun rep(ordinal:Int,id:String,completed:Long,p:com.gymbuddy.domain.profile.AnalysisProvenance)=RepEvidence(id,ordinal,"cycle",MovementPrimitive.PRESS,completed-500_000,completed,RepClassification.NORMAL,emptyMap(),emptyMap(),p)
    private fun obs(rep:RepEvidence,id:String)=FormObservation(id,rep.repId,"bilateral_asymmetry",2,FormObservationState.OK,com.gymbuddy.domain.profile.FormRuleSeverity.MINOR,.9,.0)

    private fun createV1Database(name:String):SupportSQLiteOpenHelper{
        val factory=FrameworkSQLiteOpenHelperFactory()
        val helper=factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(1){
            override fun onCreate(db:SupportSQLiteDatabase){v1Statements().forEach(db::execSQL)}
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        helper.writableDatabase
        return helper
    }

    private fun v1Statements()=listOf(
        "CREATE TABLE IF NOT EXISTS `workout_sessions` (`sessionId` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))",
        "CREATE TABLE IF NOT EXISTS `exercise_executions` (`executionId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`executionId`), FOREIGN KEY(`sessionId`) REFERENCES `workout_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_exercise_executions_sessionId` ON `exercise_executions` (`sessionId`)",
        "CREATE TABLE IF NOT EXISTS `sets` (`setId` TEXT NOT NULL, `executionId` TEXT NOT NULL, `setOrdinal` INTEGER NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`executionId`) REFERENCES `exercise_executions`(`executionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_sets_executionId` ON `sets` (`executionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sets_executionId_setOrdinal` ON `sets` (`executionId`, `setOrdinal`)",
        "CREATE TABLE IF NOT EXISTS `analysis_contexts` (`setId` TEXT NOT NULL, `exerciseDefinitionId` TEXT NOT NULL, `exerciseDefinitionVersion` INTEGER NOT NULL, `exerciseDefinitionSemanticHash` TEXT NOT NULL, `exerciseProfileId` TEXT NOT NULL, `exerciseProfileVersion` INTEGER NOT NULL, `exerciseProfileSemanticHash` TEXT NOT NULL, `cameraProfileId` TEXT NOT NULL, `cameraProfileVersion` INTEGER NOT NULL, `cameraProfileSemanticHash` TEXT NOT NULL, `signalProfileId` TEXT NOT NULL, `signalProfileVersion` INTEGER NOT NULL, `signalProfileSemanticHash` TEXT NOT NULL, `primitiveProfileId` TEXT NOT NULL, `primitiveProfileVersion` INTEGER NOT NULL, `primitiveProfileSemanticHash` TEXT NOT NULL, `metricProfileId` TEXT NOT NULL, `metricProfileVersion` INTEGER NOT NULL, `metricProfileSemanticHash` TEXT NOT NULL, `formRuleSetId` TEXT NOT NULL, `formRuleSetVersion` INTEGER NOT NULL, `formRuleSetSemanticHash` TEXT NOT NULL, `cuePolicyId` TEXT NOT NULL, `cuePolicyVersion` INTEGER NOT NULL, `cuePolicySemanticHash` TEXT NOT NULL, `equipmentProfileId` TEXT, `equipmentProfileVersion` INTEGER, `equipmentProfileSemanticHash` TEXT, `calibrationProfileId` TEXT, `calibrationProfileVersion` INTEGER, `calibrationProfileSemanticHash` TEXT, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `rep_evidence` (`repId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repOrdinal` INTEGER NOT NULL, `stepId` TEXT NOT NULL, `primitive` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, `completedAtUs` INTEGER NOT NULL, `classification` TEXT NOT NULL, PRIMARY KEY(`repId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_rep_evidence_setId` ON `rep_evidence` (`setId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_rep_evidence_setId_repOrdinal` ON `rep_evidence` (`setId`, `repOrdinal`)",
        "CREATE TABLE IF NOT EXISTS `rep_signal_evidence` (`repId` TEXT NOT NULL, `signalId` TEXT NOT NULL, `unit` TEXT NOT NULL, `minValue` REAL, `maxValue` REAL, `meanValue` REAL, `lastValue` REAL, `confidence` REAL, PRIMARY KEY(`repId`, `signalId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_rep_signal_evidence_repId` ON `rep_signal_evidence` (`repId`)",
        "CREATE TABLE IF NOT EXISTS `rep_metric_evidence` (`repId` TEXT NOT NULL, `metricId` TEXT NOT NULL, `unit` TEXT NOT NULL, `known` INTEGER NOT NULL, `value` REAL, `confidence` REAL, `unknownReason` TEXT, PRIMARY KEY(`repId`, `metricId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_rep_metric_evidence_repId` ON `rep_metric_evidence` (`repId`)",
        "CREATE TABLE IF NOT EXISTS `form_observations` (`observationId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `ruleVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `severity` TEXT NOT NULL, `confidence` REAL, `evidenceValue` REAL, PRIMARY KEY(`observationId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_form_observations_repId` ON `form_observations` (`repId`)",
        "CREATE INDEX IF NOT EXISTS `index_form_observations_setId` ON `form_observations` (`setId`)",
        "CREATE TABLE IF NOT EXISTS `cue_events` (`cueId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repId` TEXT NOT NULL, `observationId` TEXT, `ruleId` TEXT NOT NULL, `emittedAtUs` INTEGER NOT NULL, `severity` TEXT NOT NULL, PRIMARY KEY(`cueId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_cue_events_setId` ON `cue_events` (`setId`)",
        "CREATE INDEX IF NOT EXISTS `index_cue_events_repId` ON `cue_events` (`repId`)",
        "CREATE TABLE IF NOT EXISTS `cue_responses` (`responseId` TEXT NOT NULL, `setId` TEXT NOT NULL, `cueId` TEXT NOT NULL, `repId` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`responseId`), FOREIGN KEY(`cueId`) REFERENCES `cue_events`(`cueId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_cue_responses_cueId` ON `cue_responses` (`cueId`)",
        "CREATE INDEX IF NOT EXISTS `index_cue_responses_repId` ON `cue_responses` (`repId`)",
        "CREATE INDEX IF NOT EXISTS `index_cue_responses_setId` ON `cue_responses` (`setId`)",
        "CREATE TABLE IF NOT EXISTS `tracking_quality_summaries` (`setId` TEXT NOT NULL, `observableFrames` INTEGER NOT NULL, `degradedFrames` INTEGER NOT NULL, `pausedFrames` INTEGER NOT NULL, `unknownFrames` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `set_summaries` (`setId` TEXT NOT NULL, `endedAtUs` INTEGER NOT NULL, `completedReps` INTEGER NOT NULL, `assistedReps` INTEGER NOT NULL, `uncertainReps` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
}
