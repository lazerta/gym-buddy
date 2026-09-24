package com.gymbuddy.app

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.app.runtime.CameraMotionSignalStore
import com.gymbuddy.frames.FrameConsumer
import java.util.concurrent.Executor

class CameraSessionBridge(
    private val context:Context,
    private val lifecycleOwner:LifecycleOwner,
    private val analysisExecutor:Executor,
    private val frameConsumer:FrameConsumer<MPImage>,
    private val cameraMotionSignals:CameraMotionSignalStore,
):AutoCloseable{
    private var previewSurfaceProvider:Preview.SurfaceProvider?=null
    private var source:CameraXFrameSource?=null
    private var requested=false

    fun setPreviewSurfaceProvider(provider:Preview.SurfaceProvider){
        if(previewSurfaceProvider===provider)return
        previewSurfaceProvider=provider
        if(requested){
            stopSource()
            startSource()
        }
    }

    fun start(){
        requested=true
        if(source==null)startSource()
    }

    fun stop(){
        requested=false
        stopSource()
    }

    private fun startSource(){
        if(!requested||source!=null)return
        CameraXFrameSource(
            context=context,
            lifecycleOwner=lifecycleOwner,
            analysisExecutor=analysisExecutor,
            previewSurfaceProvider=previewSurfaceProvider,
            cameraMotionSignals=cameraMotionSignals,
        ).also{
            source=it
            it.start(frameConsumer)
        }
    }

    private fun stopSource(){
        source?.stop()
        source=null
    }

    override fun close(){stop()}
}
