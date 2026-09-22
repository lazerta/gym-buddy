package com.gymbuddy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.data.GymBuddyDatabase
import com.gymbuddy.data.GymBuddyDatabaseFactory
import com.gymbuddy.data.RoomEvidenceRepository
import com.gymbuddy.domain.persistence.ExerciseExecutionRecord
import com.gymbuddy.domain.persistence.SetRecord
import com.gymbuddy.domain.persistence.WorkoutSessionRecord
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.profile.AnalysisConfig
import com.gymbuddy.domain.profile.AnalysisConfigResolver
import com.gymbuddy.domain.profiles.ExerciseBundle
import com.gymbuddy.domain.profiles.InitialExerciseProfiles
import com.gymbuddy.frames.FrameAnalysisLoop
import com.gymbuddy.frames.FrameResultSink
import com.gymbuddy.frames.FrameSource
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var poseAnalyzer: MediaPipePoseAnalyzer
    private lateinit var database: GymBuddyDatabase
    private lateinit var repository: RoomEvidenceRepository
    private lateinit var ttsFeedback: TextToSpeechCueSink
    private lateinit var status: TextView

    @Volatile private var currentSource: FrameSource<MPImage>? = null
    @Volatile private var currentAnalyzer: ProductionFrameAnalyzer? = null
    @Volatile private var latestCueText: String? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else setStatus("Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        poseAnalyzer = MediaPipePoseAnalyzer(this)
        database = GymBuddyDatabaseFactory.create(this)
        repository = RoomEvidenceRepository(database.evidenceDao())
        ttsFeedback = TextToSpeechCueSink(this) { delivery ->
            if (!worker.isShutdown) {
                runCatching { worker.execute { repository.persistCueDelivery(delivery) } }
            }
        }

        status = TextView(this).apply { text = "Ready"; textSize = 16f }
        val cameraButton = Button(this).apply {
            text = "Use Camera"
            setOnClickListener {
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopCurrentSource(); setStatus("Stopped") }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(status)
            addView(cameraButton)
            addView(stopButton)
        })
    }

    override fun onDestroy() {
        stopCurrentSource()
        ttsFeedback.close()
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        poseAnalyzer.close()
        database.close()
        super.onDestroy()
    }

    private fun startCamera() {
        stopCurrentSource()
        latestCueText = null
        val bundle = resolveExercise(intent.getStringExtra(EXTRA_EXERCISE_ID))
        val config = resolveConfig(bundle)
        val sessionId = "camera-${UUID.randomUUID()}"
        val analyzer = productionAnalyzer(config, bundle, sessionId)
        currentAnalyzer = analyzer
        val source = CameraXFrameSource(context = this, lifecycleOwner = this, analysisExecutor = worker)
        currentSource = source
        source.start(FrameAnalysisLoop(analyzer = analyzer, resultSink = FrameResultSink<ProductionFrameResult> { _, result -> renderStatus("Camera", result) }).consumer())
        setStatus("Camera running: ${bundle.definition.displayName}")
    }

    private fun productionAnalyzer(config: AnalysisConfig, bundle: ExerciseBundle, sessionId: String, executionSuffix: String = UUID.randomUUID().toString()): ProductionFrameAnalyzer {
        val visual = CallbackVisualFeedbackSink(onShow = { text -> latestCueText = text }, onClear = { latestCueText = null })
        val feedback = CompositeCueFeedbackSink(visual, ttsFeedback)
        return ProductionFrameAnalyzer(poseAnalyzer) { firstFrame: PoseFrame ->
            val executionId = "$sessionId:${bundle.definition.exerciseId}:$executionSuffix"
            val setId = "$executionId:set:1"
            ProductionPoseFrameProcessor(
                config = config,
                repository = repository,
                session = WorkoutSessionRecord(sessionId, firstFrame.timestampUs),
                execution = ExerciseExecutionRecord(executionId, sessionId, bundle.definition.exerciseId, firstFrame.timestampUs),
                set = SetRecord(setId, executionId, 1, firstFrame.timestampUs),
                feedback = feedback,
            )
        }
    }

    private fun resolveConfig(bundle: ExerciseBundle): AnalysisConfig = AnalysisConfigResolver.resolve(bundle.definition, bundle.profile, bundle.equipment)
    private fun resolveExercise(externalId: String?): ExerciseBundle = if (externalId.isNullOrBlank()) InitialExerciseProfiles.inclineDumbbellPress else InitialExerciseProfiles.resolveByExternalId(externalId) ?: error("Unsupported exercise_id: $externalId")

    private fun renderStatus(prefix: String, result: ProductionFrameResult) {
        val cue = latestCueText?.let { " | Cue: $it" }.orEmpty()
        setStatus("$prefix: ${result.pipeline.lifecycleState.name.lowercase()} | ${result.pipeline.tracking.state.name.lowercase()} | reps=${result.repCount}$cue")
    }

    private fun stopCurrentSource() {
        currentSource?.stop()
        currentSource = null
        val analyzer = currentAnalyzer
        currentAnalyzer = null
        if (analyzer != null && !worker.isShutdown) {
            runCatching { worker.execute { analyzer.finishSet() } }
        }
    }

    private fun setStatus(message: String) { runOnUiThread { status.text = message } }

    companion object {
        const val EXTRA_EXERCISE_ID = "exerciseId"
    }
}
