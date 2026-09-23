import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildFeatures {
        compose = true
    }

    sourceSets.named("main") {
        kotlin.directories.add("../contracts/frame-source/src/main/kotlin")
    }
}

val poseModelFile = layout.projectDirectory.file(
    "src/main/assets/pose_landmarker_lite.task"
).asFile

val fetchPoseModel = tasks.register("fetchPoseModel") {
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
                poseModelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(fetchPoseModel)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.material:material:1.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
