# WeChat QRCode 三级级联兜底 — 设计文档

日期：2026-08-13
状态：已批准，待写实施计划

## 背景

`scan-demo` 现有二级级联：ML Kit（主引擎）→ ZXing（兜底，`ZxingFallback.kt`）。

实测发现一张真实印刷题库二维码（点阵较粗 + 轻微透视变形 + JPEG 压缩，来源：手机拍照 `20260813-194624.jpg`）无法被 ML Kit 或 ZXing 识别——包括原图、裁剪、旋转、放大、锐化、二值化等各种预处理组合都试过，两者的传统扫描线定位算法在这类码上有共同的天花板。

用 OpenCV 的 `wechat_qrcode` 模块（CNN 定位 + 超分辨率重建）测试同一张图，**原图不裁剪即可直接解出**：
```
https://ns.huatu.com/h5/bookApp.html?channelCode=1005&category=1&subjectId=1&activityId=4129345&type=67
```

这证明 WeChat 引擎与 ML Kit/ZXing 是完全不同的技术路线，能捡回传统定位算法漏掉的一类难码。README 中"未覆盖"清单里本就列了这一项（当时为保持 demo 代码干净故意省略），现在有实测证据支撑，值得补上。

## 目标

在现有 ML Kit → ZXing 二级级联基础上，新增 WeChat QRCode 作为第三级兜底，覆盖实时扫描和相册导入两条路径。

## 非目标

- 不追求 WeChat 引擎的每帧调用（成本过高，见下方阈值设计）
- 不做 ROI 裁剪、曝光补偿、Google Code Scanner 兜底（仍是 README 里其余未覆盖项，不在本次范围内）
- 不引入网络下载模型文件的逻辑（模型已内置在依赖库的 AAR 里）

## 技术选型与前提确认

### SDK 来源（关键澄清）

官方 OpenCV Android AAR（`org.opencv:opencv`，Maven Central，4.9.0 起提供）**不包含 `opencv_contrib` 模块**，而 `wechat_qrcode` 属于 contrib。直接加官方依赖无法使用 `WeChatQRCode` 类。

现实可行路径：使用第三方预编译发行版 [`jenly1314/WeChatQRCode`](https://github.com/jenly1314/WeChatQRCode)（GitHub 861 star 的开源库），已将 OpenCV + contrib 编译好并发布到 Maven Central，模型文件（`detect.prototxt`/`detect.caffemodel`/`sr.prototxt`/`sr.caffemodel`，共 4 个）打包在库自身的 `assets/models/` 目录，`init(Context)` 会自动完成拷贝和初始化，无需 demo 自己管理模型资源。

依赖坐标（2.5.0，要求 `compileSdk >= 35`）：
```kotlin
implementation("com.github.jenly1314.WeChatQRCode:opencv:2.5.0")
implementation("com.github.jenly1314.WeChatQRCode:opencv-armv64:2.5.0") // 仅 arm64-v8a，覆盖 demo 真机主流架构
implementation("com.github.jenly1314.WeChatQRCode:wechat-qrcode:2.5.0")
```

### API 约束

`WeChatQRCodeDetector.detectAndDecode()` 公开 API **只接受 `Bitmap`/`Mat`，不支持直接输入 YUV 字节数组**。这意味着：
- 实时扫描路径：需要在触发 WeChat 前，把 `ImageProxy` 的 YUV 数据转换为 NV21 字节数组 → `YuvImage.compressToJpeg()` → `BitmapFactory.decodeByteArray()` 得到 Bitmap，再喂给 WeChat。
- 相册路径：现有 `InputImage.fromFilePath()` 走的是 ML Kit 内部管线，拿不到中间 Bitmap，需要另外用 `BitmapFactory.decodeStream(uri)` 解码一次，并手动处理 EXIF 旋转（`ExifInterface`）。

### 已知风险

- OpenCV 4.12 Android 上有已知 native crash issue（[opencv/opencv#27798](https://github.com/opencv/opencv/issues/27798)，`legacy_backend.hpp` 断言失败），需在 `WeChatFallback.decode()` 内部 `catch (Throwable)` 兜底，防止拖垮 `wechatExecutor` 线程。
- WeChat 引擎推理成本明显高于前两级（单帧约 30-80ms，CNN 同步 native 调用），必须独立线程运行，且做互斥防堆积。

## 架构设计

### 触发规则

三级递进，默认零成本（能被 ML Kit 解出的码，ZXing/WeChat 全程不跑）：

```
ML Kit 处理每帧
  ├─ 命中 → 上报 ScanEngine.ML_KIT，重置所有计数
  └─ 未命中 → consecutiveMisses++（沿用现有字段名，不重命名）
       ├─ < ZXING_FALLBACK_AFTER (15帧) → 结束本帧
       └─ >= 15帧 → 同步在 analyzerExecutor 跑 ZXing（现状不变）
            ├─ 命中 → 上报 ScanEngine.ZXING，重置计数
            └─ 未命中 → consecutiveZxingMisses++
                 ├─ < WECHAT_FALLBACK_AFTER (10帧) → 结束本帧
                 └─ >= 10帧 且 wechatBusy == false →
                      标记 wechatBusy = true
                      在 analyzerExecutor 上把当前帧 YUV 拷贝为 NV21 字节数组
                        （必须在 imageProxy.close() 之前完成拷贝）
                      post 到独立的 wechatExecutor：
                        NV21 → Bitmap → WeChatFallback.decode(bitmap)
                        ├─ 命中 → 上报 ScanEngine.WECHAT，重置计数
                        └─ 未命中 → 无动作
                      完成后 wechatBusy = false
```

**并发控制**：WeChat 单帧推理耗时（30-80ms）可能长于相机帧间隔。若上一个 WeChat 任务尚未完成，新的触发信号直接丢弃（不排队），用一个 `AtomicBoolean wechatBusy` 互斥即可。这一级本就是低频兜底，不追求"每次触发都跑到"。

相册导入路径不复用上述计数器机制（一次性判定，没有"帧"的概念），改为简单的一次性递进 `try`：

```
decodeCascade(bitmap: Bitmap): Pair<ScanEngine, String>?
  ML Kit(InputImage.fromFilePath 得到的结果，如已命中直接返回)
  → 失败则 ZxingFallback.decodeBitmap(bitmap)
  → 失败则 WeChatFallback.decode(bitmap)
  → 三者都失败 → null（提示"照片中未识别到二维码"，文案不变）
```

## 组件设计

### 新增：`WeChatFallback.kt`

与现有 `ZxingFallback.kt` 同级、同风格的 `object` 单例：

```kotlin
object WeChatFallback {
    fun init(context: Context) { ... }       // 懒加载初始化 WeChatQRCodeDetector，内部 catch 全部异常
    fun decode(bitmap: Bitmap): String? { ... }  // catch(Throwable) 兜底，异常/未初始化一律返回 null
    fun yuvToBitmap(image: Image, rotation: Int): Bitmap { ... } // NV21 -> JPEG -> Bitmap，供实时路径用
}
```

### `QrAnalyzer.kt` 改动

- `ScanEngine` 枚举新增 `WECHAT`
- 新增字段：`consecutiveZxingMisses: AtomicInteger`、`wechatBusy: AtomicBoolean`
- 新增构造参数：`wechatExecutor: Executor`（由 `ScanActivity` 传入，与 `analyzerExecutor` 分离）
- ZXing 未命中分支里累加 `consecutiveZxingMisses`，达到阈值且 `wechatBusy` 为 false 时触发 WeChat 异步任务
- WeChat 命中/未命中的结果通过既有 `onBarcodes` / `onFrameTimings` 风格的回调传回，但耗时单独用一个新回调 `onWeChatTiming: ((ms: Long) -> Unit)?` 上报，**不并入 `reportFrameTimings` 的平均值**（避免低频、高耗时的 WeChat 调用把 FPS/平均耗时统计拉得忽高忽低，失去"性能对比"这条测试线索原本的意义）

### `ZxingFallback.kt` 改动

新增一个重载，供相册路径使用（现有 `decode(image: Image, rotation: Int)` 只吃相机的 YUV `Image`，不能直接喂 Bitmap）：
```kotlin
fun decodeBitmap(bitmap: Bitmap): String? { ... }
```

### `ScanActivity.kt` 改动

- 新增 `wechatExecutor = Executors.newSingleThreadExecutor()`，`onDestroy()` 一并 shutdown
- `startCamera()` 中把 `wechatExecutor` 传给 `QrAnalyzer`
- `onEngineSwitch` 的 UI 提示文案增加一档："ZXing 也无结果，启用 WeChat 兜底…"
- `showResult()` 的 `engineTag` when 分支新增：`ScanEngine.WECHAT -> "🔵 WeChat 兜底"`
- 左上角统计栏（`tvStats` 或新增一个 `tvWechatStats`）在 WeChat 触发时单独展示一行耗时，如"WeChat: 52ms"，不触发时不显示
- `decodeFromUri()` 重写：
  - ML Kit 路径不变（`InputImage.fromFilePath`）
  - 新增 `decodeBitmapWithExif(uri): Bitmap`（`BitmapFactory.decodeStream` + `ExifInterface` 手动旋转）
  - ML Kit 失败后，用该 Bitmap 依次 try `ZxingFallback.decodeBitmap()` → `WeChatFallback.decode()`

### `build.gradle.kts` 改动

- `compileSdk` 从 34 升至 35（`targetSdk` 保持 34 不变，升级只影响编译期 API 可见性，不影响运行时行为）
- 新增三个 WeChatQRCode 依赖坐标（见上方"技术选型"）

## 阈值参数

```kotlin
private const val ZXING_FALLBACK_AFTER = 15   // 已有
private const val WECHAT_FALLBACK_AFTER = 10  // 新增，ZXing 之后再等待的帧数，初始值，后续实机可调
```

## 错误处理

延续现有代码"静默吞并"风格：
- `WeChatFallback.init()` 失败（模型拷贝失败、库加载失败等）：内部 catch，标记未就绪；此后 `decode()` 直接返回 `null`，不影响 ML Kit/ZXing 正常工作
- `WeChatFallback.decode()` 推理异常（含已知的 native crash 风险）：`catch (Throwable)` 兜底，绝不让异常穿透到 `wechatExecutor` 线程导致线程死亡
- 相册级联：三级都失败才 Toast 提示"照片中未识别到二维码"（文案不变）

## 测试方式

更新 README 测试章节，新增：

1. 用触发本次设计的实拍图（微信/QQ 图库场景常见的粗印刷点阵码）验证：
   - 实时对屏幕/纸面扫描，能否最终弹出"🔵 WeChat 兜底"结果
   - 相册导入同一张图，能否被三级级联救回来
2. 观察 UI 提示文案是否按 ML Kit → ZXing → WeChat 顺序正确切换
3. 观察左上角新增的 WeChat 单次耗时是否落在预期区间（30-80ms）
4. 确认能被 ML Kit 正常识别的码，WeChat 全程不触发（左上角不出现 WeChat 耗时行），验证"默认零成本"

## 已知代价（供实施后验收对照）

- APK 体积增加（OpenCV + contrib so 库 + 内置模型文件），预估 +30~60MB
- 引入 native C++ 依赖，耦合度高于纯 Kotlin/ML Kit 方案
- `compileSdk` 强制升级到 35
- WeChat 推理路径引入 YUV→Bitmap 转换开销（约 5-15ms），计入 WeChat 这一级的总耗时预算内
