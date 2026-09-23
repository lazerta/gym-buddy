package com.gymbuddy.app.ui

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gymbuddy.app.controller.CameraReadinessUi
import com.gymbuddy.app.controller.WorkoutUiState

@Composable
fun CameraSetupScreen(
    state:WorkoutUiState.CameraSetup,
    onPreviewSurfaceAvailable:(Preview.SurfaceProvider)->Unit,
){
    Box(Modifier.fillMaxSize()){
        AndroidView(
            modifier=Modifier.fillMaxSize(),
            factory={context->
                PreviewView(context).apply{
                    scaleType=PreviewView.ScaleType.FILL_CENTER
                    implementationMode=PreviewView.ImplementationMode.PERFORMANCE
                    onPreviewSurfaceAvailable(surfaceProvider)
                }
            },
            update={view->onPreviewSurfaceAvailable(view.surfaceProvider)},
        )
        Column(
            modifier=Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha=.62f))
                .padding(16.dp),
        ){
            Text(
                "${state.exerciseName} · Set ${state.setNumber}",
                color=Color.White,
                style=MaterialTheme.typography.h6,
            )
        }
        Column(
            modifier=Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha=.72f))
                .padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(8.dp),
        ){
            Text(
                state.instruction,
                color=Color.White,
                fontSize=if(state.readiness==CameraReadinessUi.READY)42.sp else 24.sp,
            )
        }
    }
}
