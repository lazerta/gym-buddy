package com.gymbuddy.frames

data class SimulatorFrameDescriptor(
    val frameId: Long,
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

data class SimulatorSession(
    val schemaVersion: Int,
    val sessionId: String,
    val exerciseId: String,
    val fps: Double,
    val width: Int,
    val height: Int,
    val frames: List<SimulatorFrameDescriptor>,
)

data class SimulatorResultEnvelope(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val frameId: Long,
    val timestampUs: Long,
    val analysisJson: String,
)

interface SimulatorTransport {
    fun loadSession(): SimulatorSession
    fun loadFrame(frame: SimulatorFrameDescriptor): ByteArray
    fun submitResult(result: SimulatorResultEnvelope)
}

class SimulatorFrameSource<T>(
    private val transport: SimulatorTransport,
    private val decoder: FrameDecoder<T>,
    private val realtimePacing: Boolean = false,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) : FrameSource<T> {

    @Volatile
    private var running: Boolean = false

    override val origin: FrameOrigin = FrameOrigin.SIMULATOR

    override fun start(consumer: FrameConsumer<T>) {
        check(!running) { "SimulatorFrameSource is already running" }
        running = true

        val session = transport.loadSession()
        validateSession(session)

        var previousTimestampUs: Long? = null

        try {
            for (descriptor in session.frames) {
                if (!running) break

                if (realtimePacing && previousTimestampUs != null) {
                    val deltaUs = descriptor.timestampUs - previousTimestampUs
                    if (deltaUs > 0) {
                        sleepMillis(deltaUs / 1_000L)
                    }
                }

                val bytes = transport.loadFrame(descriptor)
                val image = decoder.decode(
                    encodedBytes = bytes,
                    mimeType = descriptor.mimeType,
                    width = descriptor.width,
                    height = descriptor.height,
                )

                consumer.onFrame(
                    FramePacket(
                        frameId = descriptor.frameId,
                        timestampUs = descriptor.timestampUs,
                        width = descriptor.width,
                        height = descriptor.height,
                        origin = FrameOrigin.SIMULATOR,
                        image = image,
                    )
                )

                previousTimestampUs = descriptor.timestampUs
            }
        } finally {
            running = false
        }
    }

    override fun stop() {
        running = false
    }

    private fun validateSession(session: SimulatorSession) {
        require(session.schemaVersion == 1) {
            "Unsupported simulator frame schema: ${session.schemaVersion}"
        }
        require(session.sessionId.isNotBlank()) { "sessionId is required" }
        require(session.exerciseId.isNotBlank()) { "exerciseId is required" }
        require(session.fps > 0.0) { "fps must be positive" }

        var previousUs = -1L
        session.frames.forEachIndexed { index, frame ->
            require(frame.frameId == index.toLong()) {
                "frame ids must be dense and ordered"
            }
            require(frame.timestampUs > previousUs) {
                "timestamps must be strictly increasing"
            }
            require(frame.width == session.width && frame.height == session.height) {
                "frame dimensions must match session dimensions"
            }
            previousUs = frame.timestampUs
        }
    }
}

class SimulatorResultSink<R>(
    private val sessionId: String,
    private val transport: SimulatorTransport,
    private val encoder: (R) -> String,
) : FrameResultSink<R> {

    override fun submit(frame: FramePacket<*>, result: R) {
        require(frame.origin == FrameOrigin.SIMULATOR) {
            "SimulatorResultSink only accepts simulator frames"
        }

        transport.submitResult(
            SimulatorResultEnvelope(
                sessionId = sessionId,
                frameId = frame.frameId,
                timestampUs = frame.timestampUs,
                analysisJson = encoder(result),
            )
        )
    }
}
