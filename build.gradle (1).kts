plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.poseguard.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poseguard.monitor"
        minSdk = 26           // 悬浮窗 TYPE_APPLICATION_OVERLAY 需要 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 让 assets 不被压缩（模型文件）
    androidResources { noCompress += listOf("param", "bin") }

    // NCNN 的 .so 从 jniLibs 打包
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // NCNN（腾讯官方 aar，含 arm64-v8a .so）
    implementation("com.tencent.ncnn:ncnn-android-vulkan:20240102")
}
