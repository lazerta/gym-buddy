plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.gymbuddy.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":domain"))
    api("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.sqlite:sqlite-framework:2.6.1")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
