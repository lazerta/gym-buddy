package com.gymbuddy.app

import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.domain.pose.PoseFrame
import com.gymbuddy.domain.tracking.TrackingObservationContext
import com.gymbuddy.frames.FrameAnalyzer
import com.gymbuddy.frames.FramePacket

class ProductionFrameAnalyzer(
    private val poseAnalyzer:MediaPipePoseAnalyzer,
    private val observationContextProvider:(FramePacket<MPImage>)->TrackingObservationContext={TrackingObservationContext()},
    private val processorFactory:(PoseFrame)->ProductionPoseFrameProcessor,
):FrameAnalyzer<MPImage,ProductionFrameResult>{
    private var processor:ProductionPoseFrameProcessor?=null
    private var lastTimestampUs:Long?=null
    private var finished=false
    private var finishEpochMs:Long?=null

    @Synchronized
    override fun analyze(frame:FramePacket<MPImage>):ProductionFrameResult{
        var delegated=false
        try{
            check(!finished&&finishEpochMs==null){"ProductionFrameAnalyzer has stopped its set"}
            val context=observationContextProvider(frame)
            delegated=true
            val poseFrame=poseAnalyzer.analyze(frame)
            lastTimestampUs=poseFrame.timestampUs
            val active=processor?:processorFactory(poseFrame).also{processor=it}
            return active.process(poseFrame,context)
        }finally{
            if(!delegated)frame.image.close()
        }
    }

    @Synchronized
    fun finishSet(endedAtEpochMs:Long=0L){
        if(finished)return
        val epoch=finishEpochMs?:endedAtEpochMs.also{finishEpochMs=it}
        val timestamp=lastTimestampUs
        if(timestamp!=null)processor?.finishSet(timestamp,epoch)
        finished=true
    }
}
