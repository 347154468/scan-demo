plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.demo.scandemo"
    // 35：WeChatQRCode 2.5.0 依赖要求；targetSdk 保持 34 不变，升级只影响编译期 API 可见性
    compileSdk = 35

    defaultConfig {
        applicationId = "com.demo.scandemo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Barcode - 17.3.0 起支持 setZoomSuggestionOptions（自动变焦）
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 让 Task<T>.await() 可用（相册 scanner.process(input).await()）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ZXing —— 二级级联兜底引擎，与 ML Kit 图像处理路径完全不同，能捡到 ML Kit 漏的一部分码
    implementation("com.google.zxing:core:3.5.3")

    // WeChat QRCode —— 三级级联兜底（CNN 定位 + 超分辨率重建），覆盖前两者共同的定位算法天花板。
    // 官方 OpenCV Android AAR 不含 opencv_contrib，这里走第三方预编译发行版 jenly1314/WeChatQRCode，
    // 模型文件打包在库自身 assets 里，WeChatFallback.init() 会自动拷贝初始化
    implementation("com.github.jenly1314.WeChatQRCode:opencv:2.5.0")
    implementation("com.github.jenly1314.WeChatQRCode:opencv-armv64:2.5.0") // 仅 arm64-v8a，覆盖 demo 真机主流架构
    implementation("com.github.jenly1314.WeChatQRCode:wechat-qrcode:2.5.0")

    // 相册路径需要手动读取 EXIF 方向，摆正喂给 ZXing/WeChat 的 Bitmap
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
