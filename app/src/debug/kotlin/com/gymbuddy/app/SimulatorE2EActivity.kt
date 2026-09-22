package com.gymbuddy.app

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.data.GymBuddyDatabase
import com.gymbuddy.data.GymBuddyDatabaseFactory
import com.gymbuddy.data.RoomEvidenceRepository
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.frames.FrameAnalysisLoop
import com.gymbuddy.frames.FrameSource
import com.gymbuddy.frames.SimulatorFrameSource
import com.gymbuddy.frames.SimulatorResultSink
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Debug-only Activity used by GitHub emulator E2E. It is absent from release builds. */
class SimulatorE2EActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var poseAnalyzer: MediaPipePoseAnalyzer
    private lateinit var database: GymBuddyDatabase
    private lateinit var repository: RoomEvidenceRepository
    private lateinit var status: TextView
    @Volatile private var source: FrameSource<MPImage>? = null
    @Volatile private var analyzer: ProductionFrameAnalyzer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        poseAnalyzer = MediaPipePoseAnalyzer(this)
        database = GymBuddyDatabaseFactory.create(this, "gym-buddy-e2e.db")
        repository = RoomEvidenceRepository(database.evidenceDao())
        status = TextView(this).apply { text = "Simulator E2E" }
        setContentView(status)
        worker.execute { runSimulator() }
    }

    private fun runSimulator() {
        try {
            val transport = HttpSimulatorTransport(intent.getStringExtra(EXTRA_SIMULATOR_BASE_URL) ?: DEFAULT_SIMULATOR_BASE_URL)
            val session = transport.loadSession()
            val bundle = InitialExerciseProfiles.resolveByExternalId(session.exerciseId)
                ?: error("Unsupported exercise_id: ${session.exerciseId}")
            val runId = UUID.randomUUID().toString()
            val activeAnalyzer = ProductionFrameAnalyzer(poseAnalyzer) { firstFrame -> processor(bundle, session.sessionId, runId, firstFrame) }
            analyzer = activeAnalyzer
            val activeSource = SimulatorFrameSource(transport, JpegMpImageDecoder(), realtimePacing = false)
            source = activeSource
            activeSource.start(FrameAnalysisLoop(
                analyzer = activeAnalyzer,
                resultSink = SimulatorResultSink(session.sessionId, transport, ProductionResultWireEncoder::encode),
            ).consumer())
            activeAnalyzer.finishSet()
            setStatus("Simulator complete: ${session.frames.size} frames")
        } catch (t: Throwable) {
            setStatus("Simulator error: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun processor(bundle: ExerciseBundle, sessionId: String, runId: String, firstFrame: PoseFrame): ProductionPoseFrameProcessor {
        val config = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
        val executionId = "$sessionId:${bundle.definition.exerciseId}:$runId"
        val setId = "$executionId:set:1"
        return ProductionPoseFrameProcessor(
            config = config,
            repository = repository,
            session = WorkoutSessionRecord(sessionId, firstFrame.timestampUs),
            execution = ExerciseExecutionRecord(executionId, sessionId, bundle.definition.exerciseId, firstFrame.timestampUs),
            set = SetRecord(setId, executionId, 1, firstFrame.timestampUs),
            feedback = NoOpCueFeedbackSink,
        )
    }

    private fun setStatus(message: String) { runOnUiThread { status.text = message } }

    override fun onDestroy() {
        source?.stop()
        source = null
        val active = analyzer
        analyzer = null
        if (active != null && !worker.isShutdown) runCatching { worker.execute { active.finishSet() } }
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        poseAnalyzer.close()
        database.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SIMULATOR_BASE_URL = "simulatorBaseUrl"
        const val DEFAULT_SIMULATOR_BASE_URL = "http://10.0.2.2:8788"
    }
}
