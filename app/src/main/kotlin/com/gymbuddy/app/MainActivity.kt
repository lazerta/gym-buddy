package com.gymbuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gymbuddy.app.controller.WorkoutController
import com.gymbuddy.app.controller.WorkoutDay
import com.gymbuddy.app.runtime.CameraMotionSignalStore
import com.gymbuddy.app.runtime.DefaultWorkoutRuntime
import com.gymbuddy.app.ui.GymBuddyApp

class MainActivity:ComponentActivity(){
    private lateinit var controller:WorkoutController
    private lateinit var cameraBridge:CameraSessionBridge

    private val cameraPermission=registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){granted->
        if(granted){
            cameraBridge.start()
        }else{
            cameraBridge.stop()
            controller.onCameraPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val initialDay=runCatching{
            WorkoutDay.valueOf(savedInstanceState?.getString(STATE_SELECTED_DAY)?:WorkoutDay.PUSH.name)
        }.getOrDefault(WorkoutDay.PUSH)
        val cameraMotionSignals=CameraMotionSignalStore()
        controller=WorkoutController(
            runtime=DefaultWorkoutRuntime(applicationContext,cameraMotionSignals),
            initialDay=initialDay,
        )
        cameraBridge=CameraSessionBridge(
            context=this,
            lifecycleOwner=this,
            analysisExecutor=controller.analysisExecutor,
            frameConsumer=controller.frameConsumer(),
            cameraMotionSignals=cameraMotionSignals,
        )

        setContent{
            val state by controller.uiState.collectAsStateWithLifecycle()
            GymBuddyApp(
                state=state,
                onSelectDay=controller::selectDay,
                onSelectExercise=controller::selectExercise,
                onPreviewSurfaceAvailable=cameraBridge::setPreviewSurfaceProvider,
                onCameraNeededChanged=::handleCameraNeeded,
                onEndSet={
                    cameraBridge.stop()
                    controller.endSet()
                },
                onNextLoadChange=controller::updateNextLoad,
                onNextSet=controller::nextSet,
                onFinishExercise=controller::finishExercise,
                onAskChatGpt={
                    controller.askChatGpt(::handleChatGptExport)
                },
                onResetCalibration=::handleCalibrationReset,
                onReturnToExercises=controller::returnToSelection,
            )
        }
    }

    override fun onSaveInstanceState(outState:Bundle){
        outState.putString(STATE_SELECTED_DAY,controller.currentDay.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy(){
        cameraBridge.close()
        controller.close()
        super.onDestroy()
    }

    private fun handleCalibrationReset(){
        controller.resetPersonalCalibration{success->
            runOnUiThread{
                Toast.makeText(
                    this,
                    if(success)"Calibration reset. It will rebuild from future workouts."
                    else "Unable to reset calibration.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun handleChatGptExport(result:Result<String>){
        runOnUiThread{
            result.onSuccess(::shareChatGptContext)
                .onFailure{
                    Toast.makeText(
                        this,
                        "Unable to prepare workout context.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    private fun shareChatGptContext(context:String){
        val sendIntent=Intent(Intent.ACTION_SEND).apply{
            type="text/plain"
            putExtra(Intent.EXTRA_SUBJECT,"Gym Buddy workout context")
            putExtra(Intent.EXTRA_TEXT,context)
        }
        startActivity(Intent.createChooser(sendIntent,"Ask ChatGPT"))
    }

    private fun handleCameraNeeded(needed:Boolean){
        if(!needed){
            cameraBridge.stop()
            return
        }
        val granted=ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        )==PackageManager.PERMISSION_GRANTED
        if(granted)cameraBridge.start() else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    companion object { private const val STATE_SELECTED_DAY="selected_day" }
}
