package com.gymbuddy.frames

enum class FrameOrigin {
    CAMERA,
    SIMULATOR,
    VIDEO,
}

data class FramePacket<T>(
    val frameId: Long,
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val origin: FrameOrigin,
    val image: T,
) {
    init {
        require(frameId >= 0) { "frameId must be >= 0" }
        require(timestampUs >= 0) { "timestampUs must be >= 0" }
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
    }

    val mediaPipeTimestampMs: Long
        get() = timestampUs / 1_000L
}

fun interface FrameConsumer<T> {
    fun onFrame(frame: FramePacket<T>)
}

interface FrameSource<T> {
    val origin: FrameOrigin
    fun start(consumer: FrameConsumer<T>)
    fun stop()
}

fun interface FrameDecoder<T> {
    fun decode(
        encodedBytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int,
    ): T
}

fun interface FrameAnalyzer<T, R> {
    fun analyze(frame: FramePacket<T>): R
}

fun interface FrameResultSink<R> {
    fun submit(frame: FramePacket<*>, result: R)
}

class FrameAnalysisLoop<T, R>(
    private val analyzer: FrameAnalyzer<T, R>,
    private val resultSink: FrameResultSink<R>,
) {
    fun consumer(): FrameConsumer<T> = FrameConsumer { frame ->
        val result = analyzer.analyze(frame)
        resultSink.submit(frame, result)
    }
}
