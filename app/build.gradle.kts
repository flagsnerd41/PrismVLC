plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myvlc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flagsnerd.PrismTume"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "11.0"
        // arm64 only = much smaller APK (fits modern phones)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
