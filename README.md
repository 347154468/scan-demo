# 扫码 Demo（Kotlin 原生）

按上一轮分析的核心方案实现的最小可运行 Demo，用来验证识别率和速度的提升点。

## 覆盖的方案（对应上一轮 10 条里的关键项）

| # | 方案 | 位置 |
|---|---|---|
| 1 | `ImageAnalysis` 固定 1280x720，与 Preview 解耦 | `ScanActivity.startCamera()` — `ResolutionSelector` |
| 2 | ML Kit 回调绑定后台 Executor，UI 切主线程 | `QrAnalyzer` — `addOnSuccessListener(analyzerExecutor, ...)` |
| 3 | **不做 YUV→JPEG→Bitmap 中转**，直接 `InputImage.fromMediaImage` | `QrAnalyzer.analyze()` |
| 4 | 相册用 `InputImage.fromFilePath(uri)`，自动 EXIF + 降采样 | `ScanActivity.decodeFromUri()` |
| 5 | 手电筒（`enableTorch`） | `ScanActivity.toggleTorch()` |
| 6 | 变焦（`setZoomRatio`）+ **ML Kit 自动变焦** | `ScanActivity.toggleZoom()` / `QrAnalyzer.setZoomSuggestionOptions` |
| 7 | 点按对焦（`FocusMeteringAction`） | `ScanActivity.previewTouchListener` |
| 9 | `enableAllPotentialBarcodes()` + 过滤空 rawValue | `QrAnalyzer` |
| — | 删掉冗余 `isProcessing` 锁，只靠 CameraX 背压 + `imageProxy.close()` 时机 | `QrAnalyzer` |
| — | 屏幕左上角实时 FPS / ML Kit 单帧耗时 / 当前 Zoom | `ScanActivity.reportFrameTimings()` |

未覆盖：ROI 裁剪（方案 8）、曝光补偿、wechat_qrcode 级联、Google Code Scanner 兜底。核心方案版为了保持代码干净刻意省略了这些。

## 运行

**方式一：Android Studio（推荐）**

1. Android Studio → Open → 选中 `scan-demo` 目录
2. 等 Gradle 同步（首次会下载 CameraX / ML Kit 依赖）
3. 连接一台真机（模拟器没相机不行）
4. Run 'app' → APK 自动装到手机

**方式二：命令行**

需要机器上有：
- JDK 17（当前你系统装的是 JDK 8，需要升级）
- Android SDK（`ANDROID_HOME` 或 `local.properties` 里配 `sdk.dir=...`）

```bash
cd scan-demo
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次跑需要生成 gradle-wrapper.jar，Android Studio 打开时会自动生成；命令行走的话执行：

```bash
gradle wrapper --gradle-version 8.7
```

## 测试怎么看效果

装到手机后：

1. **速度**：左上角显示 `FPS: 24 ML Kit: 18ms` 这类实时数据。原方案在中高端机上 ML Kit 单帧一般 40-80ms（YUV→JPEG→Bitmap 占大头），新方案应该在 15-25ms
2. **难码识别率**：拿一些倾斜、有反光、印刷模糊、屏幕上被压缩过的二维码试。`enableAllPotentialBarcodes` 对这类会明显好
3. **小码/远距**：镜头远离二维码，观察 hint 区域是否出现"自动放大 ×1.5"这类提示 —— 那就是 ML Kit 自动变焦在工作
4. **暗光**：昏暗环境下点"开灯"补光
5. **相册**：点"相册"选一张微信/QQ 里保存下来的压缩过的二维码图，看看能否识别

## 依赖版本

- CameraX 1.3.4
- ML Kit barcode-scanning 17.3.0（自动变焦从 17.2.0 起支持）
- minSdk 24 / targetSdk 34 / compileSdk 34
- Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7 / JDK 17
