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
import com.gymbuddy.frames.FrameAnalysisLoop
import com.gymbuddy.frames.FrameResultSink
import com.gymbuddy.frames.FrameSource
import com.gymbuddy.frames.SimulatorFrameSource
import com.gymbuddy.frames.SimulatorResultSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor()

    private lateinit var poseAnalyzer:
        MediaPipePoseAnalyzer

    private lateinit var status: TextView

    @Volatile
    private var currentSource:
        FrameSource<MPImage>? = null

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                setStatus("Camera permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        poseAnalyzer = MediaPipePoseAnalyzer(this)

        status = TextView(this).apply {
            text = "Ready"
            textSize = 16f
        }

        val cameraButton = Button(this).apply {
            text = "Use Camera"
            setOnClickListener {
                val granted =
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    startCamera()
                } else {
                    cameraPermission.launch(
                        Manifest.permission.CAMERA
                    )
                }
            }
        }

        val simulatorButton = Button(this).apply {
            text = "Use Simulator"
            setOnClickListener {
                startSimulator()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopCurrentSource()
                setStatus("Stopped")
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(status)
            addView(cameraButton)
            addView(simulatorButton)
            addView(stopButton)
        }

        setContentView(layout)

        if (intent.getBooleanExtra(EXTRA_AUTO_START_SIMULATOR, false)) {
            startSimulator()
        }
    }

    override fun onDestroy() {
        stopCurrentSource()
        poseAnalyzer.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startCamera() {
        stopCurrentSource()

        val source = CameraXFrameSource(
            context = this,
            lifecycleOwner = this,
            analysisExecutor = worker,
        )
        currentSource = source

        val sink =
            FrameResultSink<PoseAnalysis> { _, result ->
                setStatus(
                    "Camera: poses=" +
                        result.normalizedPoses.size
                )
            }

        source.start(
            FrameAnalysisLoop(
                analyzer = poseAnalyzer,
                resultSink = sink,
            ).consumer()
        )

        setStatus("Camera running")
    }

    private fun startSimulator() {
        stopCurrentSource()
        setStatus("Connecting to simulator...")

        worker.execute {
            try {
                val baseUrl =
                    intent.getStringExtra(
                        EXTRA_SIMULATOR_BASE_URL
                    ) ?: DEFAULT_SIMULATOR_BASE_URL

                val transport =
                    HttpSimulatorTransport(baseUrl)

                val session =
                    transport.loadSession()

                val source =
                    SimulatorFrameSource(
                        transport = transport,
                        decoder = JpegMpImageDecoder(),
                        realtimePacing = false,
                    )

                currentSource = source

                val sink =
                    SimulatorResultSink(
                        sessionId = session.sessionId,
                        transport = transport,
                        encoder = PoseAnalysis::toWireMap,
                    )

                source.start(
                    FrameAnalysisLoop(
                        analyzer = poseAnalyzer,
                        resultSink = sink,
                    ).consumer()
                )

                setStatus(
                    "Simulator complete: " +
                        session.frames.size +
                        " frames"
                )
            } catch (t: Throwable) {
                setStatus(
                    "Simulator error: " +
                        (
                            t.message ?:
                                t::class.java.simpleName
                            )
                )
            }
        }
    }

    private fun stopCurrentSource() {
        currentSource?.stop()
        currentSource = null
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            status.text = message
        }
    }

    companion object {
        const val EXTRA_SIMULATOR_BASE_URL =
            "simulatorBaseUrl"

        const val EXTRA_AUTO_START_SIMULATOR =
            "autoStartSimulator"

        const val DEFAULT_SIMULATOR_BASE_URL =
            "http://10.0.2.2:8788"
    }
}
