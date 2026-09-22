package com.gymbuddy.app

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.frames.FrameAnalyzer
import com.gymbuddy.frames.FramePacket

class ProductionFrameAnalyzer(
    private val poseAnalyzer: MediaPipePoseAnalyzer,
    private val processorFactory: (PoseFrame) -> ProductionPoseFrameProcessor,
) : FrameAnalyzer<MPImage, ProductionFrameResult> {
    private var processor: ProductionPoseFrameProcessor? = null
    private var lastTimestampUs: Long? = null
    private var finished = false

    @Synchronized
    override fun analyze(frame: FramePacket<MPImage>): ProductionFrameResult {
        check(!finished) { "ProductionFrameAnalyzer has already finished its set" }
        val poseFrame = poseAnalyzer.analyze(frame)
        lastTimestampUs = poseFrame.timestampUs
        val active = processor ?: processorFactory(poseFrame).also { processor = it }
        return active.process(poseFrame)
    }

    @Synchronized
    fun finishSet() {
        if (finished) return
        finished = true
        val timestamp = lastTimestampUs ?: return
        processor?.finishSet(timestamp)
    }
}
