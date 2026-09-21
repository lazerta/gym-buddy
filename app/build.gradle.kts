import java.net.URI

plugins {
    id("com.android.application")
}

android {
    namespace = "com.gymbuddy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gymbuddy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        kotlin.directories += file("../contracts/frame-source/src/main/kotlin")
    }
}

val poseModelFile = layout.projectDirectory.file(
    "src/main/assets/pose_landmarker_lite.task"
).asFile

val fetchPoseModel by tasks.registering {
    outputs.file(poseModelFile)

    doLast {
        if (!poseModelFile.exists() || poseModelFile.length() < 1024L) {
            poseModelFile.parentFile.mkdirs()
            val url = URI(
                "https://storage.googleapis.com/mediapipe-models/" +
                    "pose_landmarker/pose_landmarker_lite/float16/1/" +
                    "pose_landmarker_lite.task"
            ).toURL()

            url.openStream().use { input ->
                poseModelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(fetchPoseModel)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")

    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
