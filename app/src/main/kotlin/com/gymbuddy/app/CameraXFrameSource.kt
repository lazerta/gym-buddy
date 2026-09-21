package com.gymbuddy.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.frames.FrameConsumer
import com.gymbuddy.frames.FrameOrigin
import com.gymbuddy.frames.FramePacket
import com.gymbuddy.frames.FrameSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class CameraXFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisExecutor: Executor,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) : FrameSource<MPImage> {

    override val origin: FrameOrigin = FrameOrigin.CAMERA

    @Volatile
    private var running: Boolean = false

    private var provider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val frameIds = AtomicLong(0L)

    override fun start(consumer: FrameConsumer<MPImage>) {
        check(!running) { "CameraXFrameSource is already running" }
        check(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) { "Camera permission is not granted" }

        running = true
        frameIds.set(0L)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (!running) {
                    return@addListener
                }

                val cameraProvider = future.get()
                provider = cameraProvider

                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysisUseCase = analysis
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    analyzeProxy(proxy, consumer)
                }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun stop() {
        running = false
        val useCase = analysisUseCase
        analysisUseCase = null

        if (useCase != null) {
            useCase.clearAnalyzer()
            ContextCompat.getMainExecutor(context).execute {
                provider?.unbind(useCase)
            }
        }
    }

    private fun analyzeProxy(
        proxy: ImageProxy,
        consumer: FrameConsumer<MPImage>,
    ) {
        if (!running) {
            proxy.close()
            return
        }

        try {
            val bitmap = rgbaBitmap(proxy)
            val image = BitmapImageBuilder(bitmap).build()
            val timestampUs = proxy.imageInfo.timestamp / 1_000L

            consumer.onFrame(
                FramePacket(
                    frameId = frameIds.getAndIncrement(),
                    timestampUs = timestampUs,
                    width = image.width,
                    height = image.height,
                    origin = FrameOrigin.CAMERA,
                    image = image,
                )
            )
        } finally {
            proxy.close()
        }
    }

    private fun rgbaBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes.firstOrNull()
            ?: error("RGBA ImageProxy has no plane")

        val width = proxy.width
        val height = proxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride >= 4) {
            "Unexpected RGBA pixel stride: " + pixelStride
        }

        val buffer = plane.buffer.duplicate()
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff
                val a = buffer.get(offset + 3).toInt() and 0xff

                pixels[y * width + x] =
                    (a shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }
        }

        val raw = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
        raw.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height,
        )

        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) {
            return raw
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            raw,
            0,
            0,
            raw.width,
            raw.height,
            matrix,
            true,
        )

        if (rotated !== raw) {
            raw.recycle()
        }
        return rotated
    }
}
