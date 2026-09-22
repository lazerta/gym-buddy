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
import com.gymbuddy.frames.SimulatorFrameSource
import com.gymbuddy.frames.SimulatorResultSink
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

    @Volatile
    private var currentSource: FrameSource<MPImage>? = null

    @Volatile
    private var currentAnalyzer: ProductionFrameAnalyzer? = null

    @Volatile
    private var latestCueText: String? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else setStatus("Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        poseAnalyzer = MediaPipePoseAnalyzer(this)
        database = GymBuddyDatabaseFactory.create(this)
        repository = RoomEvidenceRepository(database.evidenceDao())
        ttsFeedback = TextToSpeechCueSink(this)

        status = TextView(this).apply {
            text = "Ready"
            textSize = 16f
        }

        val cameraButton = Button(this).apply {
            text = "Use Camera"
            setOnClickListener {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        val simulatorButton = Button(this).apply {
            text = "Use Simulator"
            setOnClickListener { startSimulator() }
        }

        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopCurrentSource()
                setStatus("Stopped")
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 48, 32, 32)
                addView(status)
                addView(cameraButton)
                addView(simulatorButton)
                addView(stopButton)
            }
        )

        if (intent.getBooleanExtra(EXTRA_AUTO_START_SIMULATOR, false)) startSimulator()
    }

    override fun onDestroy() {
        stopCurrentSource()
        worker.shutdownNow()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        ttsFeedback.close()
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

        val source = CameraXFrameSource(
            context = this,
            lifecycleOwner = this,
            analysisExecutor = worker,
        )
        currentSource = source

        source.start(
            FrameAnalysisLoop(
                analyzer = analyzer,
                resultSink = FrameResultSink<ProductionFrameResult> { _, result ->
                    renderStatus("Camera", result)
                },
            ).consumer()
        )
        setStatus("Camera running: ${bundle.definition.displayName}")
    }

    private fun startSimulator() {
        stopCurrentSource()
        latestCueText = null
        setStatus("Connecting to simulator...")

        worker.execute {
            try {
                val baseUrl = intent.getStringExtra(EXTRA_SIMULATOR_BASE_URL)
                    ?: DEFAULT_SIMULATOR_BASE_URL
                val transport = HttpSimulatorTransport(baseUrl)
                val session = transport.loadSession()
                val bundle = resolveExercise(session.exerciseId)
                val config = resolveConfig(bundle)
                val runId = UUID.randomUUID().toString()
                val analyzer = productionAnalyzer(
                    config = config,
                    bundle = bundle,
                    sessionId = session.sessionId,
                    executionSuffix = runId,
                )
                currentAnalyzer = analyzer

                val source = SimulatorFrameSource(
                    transport = transport,
                    decoder = JpegMpImageDecoder(),
                    realtimePacing = false,
                )
                currentSource = source

                val sink = SimulatorResultSink(
                    sessionId = session.sessionId,
                    transport = transport,
                    encoder = ProductionResultWireEncoder::encode,
                )

                source.start(
                    FrameAnalysisLoop(
                        analyzer = analyzer,
                        resultSink = sink,
                    ).consumer()
                )
                analyzer.finishSet()
                setStatus("Simulator complete: ${session.frames.size} frames")
            } catch (t: Throwable) {
                setStatus("Simulator error: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun productionAnalyzer(
        config: AnalysisConfig,
        bundle: ExerciseBundle,
        sessionId: String,
        executionSuffix: String = UUID.randomUUID().toString(),
    ): ProductionFrameAnalyzer {
        val visual = CallbackVisualFeedbackSink(
            onShow = { text -> latestCueText = text },
            onClear = { latestCueText = null },
        )
        val feedback = CompositeCueFeedbackSink(visual, ttsFeedback)

        return ProductionFrameAnalyzer(poseAnalyzer) { firstFrame: PoseFrame ->
            val executionId = "$sessionId:${bundle.definition.exerciseId}:$executionSuffix"
            val setId = "$executionId:set:1"
            ProductionPoseFrameProcessor(
                config = config,
                repository = repository,
                session = WorkoutSessionRecord(sessionId, firstFrame.timestampUs),
                execution = ExerciseExecutionRecord(
                    executionId = executionId,
                    sessionId = sessionId,
                    exerciseId = bundle.definition.exerciseId,
                    startedAtUs = firstFrame.timestampUs,
                ),
                set = SetRecord(
                    setId = setId,
                    executionId = executionId,
                    setOrdinal = 1,
                    startedAtUs = firstFrame.timestampUs,
                ),
                feedback = feedback,
            )
        }
    }

    private fun resolveConfig(bundle: ExerciseBundle): AnalysisConfig =
        AnalysisConfigResolver.resolve(
            exerciseDefinition = bundle.definition,
            exerciseProfile = bundle.profile,
            equipmentProfile = bundle.equipment,
        )

    private fun resolveExercise(externalId: String?): ExerciseBundle {
        if (externalId.isNullOrBlank()) return InitialExerciseProfiles.inclineDumbbellPress
        return InitialExerciseProfiles.resolveByExternalId(externalId)
            ?: error("Unsupported exercise_id: $externalId")
    }

    private fun renderStatus(prefix: String, result: ProductionFrameResult) {
        val cue = latestCueText?.let { " | Cue: $it" }.orEmpty()
        setStatus(
            "$prefix: ${result.pipeline.tracking.state.name.lowercase()}" +
                " | reps=${result.repCount}$cue"
        )
    }

    private fun stopCurrentSource() {
        currentSource?.stop()
        currentSource = null
        currentAnalyzer?.finishSet()
        currentAnalyzer = null
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    companion object {
        const val EXTRA_SIMULATOR_BASE_URL = "simulatorBaseUrl"
        const val EXTRA_AUTO_START_SIMULATOR = "autoStartSimulator"
        const val EXTRA_EXERCISE_ID = "exerciseId"
        const val DEFAULT_SIMULATOR_BASE_URL = "http://10.0.2.2:8788"
    }
}
