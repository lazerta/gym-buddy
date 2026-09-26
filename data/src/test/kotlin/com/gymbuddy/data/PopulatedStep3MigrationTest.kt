package com.gymbuddy.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gymbuddy.domain.persistence.*
import com.gymbuddy.domain.evidence.AssistanceAssessmentState
import com.gymbuddy.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PopulatedStep3MigrationTest {
    @Test fun genuineVersion7HistorySurvivesMigrationAndReopen() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="populated-v7-${System.nanoTime()}.db"
        val profile=PersonalCalibrationProfile.create("legacy-personal",1,.9)
        val payload=PersonalCalibrationProfileBinaryCodec.encode(profile)
        val helper=FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object:SupportSQLiteOpenHelper.Callback(7){
                    override fun onCreate(db:SupportSQLiteDatabase){
                        val sql=requireNotNull(javaClass.classLoader!!.getResourceAsStream("schema-v7.sql"))
                            .bufferedReader().use{it.readText()}.lineSequence()
                            .filterNot{it.trim().startsWith("--")}.joinToString("\n")
                        sql.split(';').filter{it.isNotBlank()}.forEach{db.execSQL(it)}
                        db.execSQL("INSERT INTO workout_sessions VALUES ('session',100,1000)")
                        db.execSQL("INSERT INTO exercise_executions VALUES ('exec','session','dumbbell_lateral_raise',200,1100)")
                        db.execSQL("INSERT INTO sets VALUES ('set','exec',1,300,25.0,'lb',1200)")
                        // Seed old-schema columns only, including immutable provenance.
                        val columns=mutableListOf<String>();val values=mutableListOf<Any?>()
                        db.query("PRAGMA table_info(analysis_contexts)").use{c->
                            while(c.moveToNext()){
                                val col=c.getString(c.getColumnIndexOrThrow("name"))
                                val required=c.getInt(c.getColumnIndexOrThrow("notnull"))==1
                                columns+=col
                                values+=when{
                                    col=="setId"->"set"
                                    col=="calibrationProfileId"->profile.calibrationProfileId
                                    col=="calibrationProfileVersion"->profile.profileVersion
                                    col=="calibrationProfileSemanticHash"->profile.semanticHash
                                    !required->null
                                    col.endsWith("Version")->1
                                    else->"legacy-$col"
                                }
                            }
                        }
                        db.execSQL("INSERT INTO analysis_contexts (${columns.joinToString()}) VALUES (${columns.joinToString{ "?" }})",values.toTypedArray())
                        db.execSQL("INSERT INTO rep_evidence VALUES ('rep','set',1,'cycle','RAISE',350,900,'NORMAL')")
                        db.execSQL("INSERT INTO rep_metric_evidence VALUES ('rep','left_rom','NORMALIZED',1,0.9,0.95,NULL)")
                        db.execSQL("INSERT INTO form_observations VALUES ('obs','set','rep','bilateral_asymmetry',1,'OK','MINOR',0.95,0.01)")
                        db.execSQL("INSERT INTO set_summaries VALUES ('set',950,1,0,0,2000)")
                        db.execSQL("INSERT INTO workout_flow_states VALUES ('active','set','Legacy focus',30.0,'lb',2000,'REST')")
                        db.execSQL("INSERT INTO personal_calibration_profiles VALUES ('active',?,?,?,?)",
                            arrayOf(profile.calibrationProfileId,profile.profileVersion,profile.semanticHash,payload))
                        db.execSQL("INSERT INTO personal_calibration_profile_history VALUES (?,?,?,?)",
                            arrayOf(profile.calibrationProfileId,profile.profileVersion,profile.semanticHash,payload))
                    }
                    override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("historical fixture must not upgrade itself")
                }).build())
        try {
            helper.writableDatabase.query("PRAGMA table_info(sets)").use{c->
                val columns=buildList{while(c.moveToNext())add(c.getString(1))}
                assertFalse("Historical input cannot already contain v8 columns",columns.contains("plannedLoadValue"))
            }
            helper.close()
            repeat(2){
                val db=Room.databaseBuilder(context,GymBuddyDatabase::class.java,name)
                    .addMigrations(*GymBuddyMigrations.ALL).allowMainThreadQueries().build()
                try {
                    val dao=db.evidenceDao() // Actual Room validates every migrated table.
                    val set=requireNotNull(RoomEvidenceRepository(dao).loadSet("set"))
                    assertEquals(25.0,set.set.actualLoad!!.value,0.0)
                    assertEquals("lb",set.set.actualLoad!!.unit)
                    assertEquals(LoadBasis.UNKNOWN,set.set.actualLoad!!.basis)
                    assertEquals(LoadSource.UNKNOWN,set.set.actualLoad!!.source)
                    assertNull(set.set.plannedLoad)
                    assertNull(dao.execution("exec")!!.plannedExerciseId)
                    assertNull(dao.execution("exec")!!.equipmentContextId)
                    assertEquals(1,set.reps.size)
                    assertTrue(set.reps.single().phaseIntervals.isEmpty())
                    assertEquals(AssistanceAssessmentState.NOT_ASSESSED,set.reps.single().assistanceAssessment)
                    assertEquals(2000L,set.summary!!.endedAtEpochMs)
                    assertEquals(profile.semanticHash,set.analysisProvenance.personalCalibrationProfile!!.semanticHash)
                    val calibration=RoomPersonalCalibrationRepository(dao)
                    assertEquals(profile,calibration.loadActive())
                    assertEquals(profile,calibration.loadVersion(PersonalCalibrationVersionRef.from(profile)))
                    val rest=requireNotNull(RoomWorkoutFlowRepository(dao).loadRestCheckpoint())
                    assertEquals(1,rest.previousReps)
                    assertEquals(30.0,rest.plannedNextLoad!!.value,0.0)
                    assertEquals("Legacy focus",rest.focus)
                    assertTrue(RoomGptAnalysisRepository(dao).listForSet("set").isEmpty())
                    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
                } finally { db.close() }
            }
        } finally { helper.close();context.deleteDatabase(name) }
    }
}
