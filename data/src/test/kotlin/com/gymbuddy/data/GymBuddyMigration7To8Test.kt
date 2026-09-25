package com.gymbuddy.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GymBuddyMigration7To8Test {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    @Test fun migrationAddsStep3ColumnsAndTables(){
        val name="step3-migration-${System.nanoTime()}.db"
        val factory=FrameworkSQLiteOpenHelperFactory()
        val v7=factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(7){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE workout_sessions(sessionId TEXT NOT NULL PRIMARY KEY, startedAtUs INTEGER NOT NULL, startedAtEpochMs INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE exercise_executions(executionId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, exerciseId TEXT NOT NULL, startedAtUs INTEGER NOT NULL, startedAtEpochMs INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE sets(setId TEXT NOT NULL PRIMARY KEY, executionId TEXT NOT NULL, setOrdinal INTEGER NOT NULL, startedAtUs INTEGER NOT NULL, actualLoadValue REAL, actualLoadUnit TEXT, startedAtEpochMs INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE rep_evidence(repId TEXT NOT NULL PRIMARY KEY, setId TEXT NOT NULL, repOrdinal INTEGER NOT NULL, stepId TEXT NOT NULL, primitive TEXT NOT NULL, startedAtUs INTEGER NOT NULL, completedAtUs INTEGER NOT NULL, classification TEXT NOT NULL)")
                db.execSQL("CREATE TABLE workout_flow_states(checkpointId TEXT NOT NULL PRIMARY KEY, completedSetId TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'REST', focus TEXT NOT NULL, plannedNextLoadValue REAL, plannedNextLoadUnit TEXT, restStartedAtEpochMs INTEGER NOT NULL)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        v7.writableDatabase.close();v7.close()

        val v8=factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(8){
            override fun onCreate(db:SupportSQLiteDatabase)=Unit
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){GymBuddyMigrations.MIGRATION_7_8.migrate(db)}
        }).build())
        val db=v8.writableDatabase
        assertTrue(columns(db,"exercise_executions").containsAll(listOf("plannedExerciseId","equipmentContextId")))
        assertTrue(columns(db,"sets").containsAll(listOf("actualLoadBasis","actualLoadSource","plannedLoadValue","plannedLoadUnit","plannedLoadBasis","plannedLoadSource")))
        assertTrue(columns(db,"rep_evidence").containsAll(listOf("assistanceAssessment","assistanceScore")))
        listOf("rep_phase_evidence","invalid_attempt_evidence","exercise_preferences","equipment_contexts","workout_exercise_completions","workout_product_state","gpt_analyses").forEach{
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",arrayOf(it)).use{c->assertTrue(it,c.moveToFirst())}
        }
        v8.close();context.deleteDatabase(name)
    }

    private fun columns(db:SupportSQLiteDatabase,table:String):Set<String>{
        val result=linkedSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use{c->
            val index=c.getColumnIndexOrThrow("name")
            while(c.moveToNext())result+=c.getString(index)
        }
        return result
    }
}
