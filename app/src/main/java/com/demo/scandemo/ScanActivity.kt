package com.demo.scandemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.demo.scandemo.databinding.ActivityScanBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var wechatExecutor: ExecutorService
    private var analyzer: QrAnalyzer? = null

    private var camera: Camera? = null
    private var currentZoom = 1f
    private var torchOn = false

    private val hasResulted = AtomicBoolean(false)

    // FPS / 耗时统计
    private var lastStatsUpdate = 0L
    private var frameCount = 0
    private var costSumMs = 0L

    private val requestCameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { decodeFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        wechatExecutor = Executors.newSingleThreadExecutor()
        // 懒加载初始化 WeChat 引擎；耗时操作丢到后台线程，不卡启动。真正用到时大概率已就绪，
        // 没就绪也没关系——WeChatFallback.decode() 在未就绪时直接返回 null，不影响前两级
        wechatExecutor.execute { WeChatFallback.init(applicationContext) }

        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnZoom.setOnClickListener { toggleZoom() }
        // 诊断入口：长按放大按钮，抓当前预览帧强制走 WeChat 引擎，绕开级联
        // 用于分辨"WeChat 引擎本身没效果" vs "级联条件没触发到 WeChat"
        binding.btnZoom.setOnLongClickListener { forceWeChatDecodeCurrentFrame(); true }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.previewView.setOnTouchListener(previewTouchListener)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPerm.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.shutdown()
        cameraExecutor.shutdown()
        try { cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        wechatExecutor.shutdown()
        try { wechatExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    // 恢复扫码（弹窗关闭后）
    private fun resumeScan() {
        hasResulted.set(false)
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // 关键：ImageAnalysis 与 Preview 分辨率解耦；固定 1280x720，避免高端机选到超高分辨率把 ML Kit 拖慢
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val a = QrAnalyzer(
                analyzerExecutor = cameraExecutor,
                wechatExecutor = wechatExecutor,
                onZoomRequested = { ratio -> applyZoomFromMlKit(ratio) },
                onBarcodes = { engine, values -> onBarcodesDetected(engine, values) },
                onFrameTimings = { cost, _ -> reportFrameTimings(cost) },
                onEngineSwitch = { usingZxingNow, usingWechatNow ->
                    runOnUiThread {
                        binding.tvHint.text = when {
                            usingWechatNow -> "ZXing 也无结果，启用 WeChat 兜底…"
                            usingZxingNow -> "ML Kit 无结果，启用 ZXing 兜底…"
                            else -> getString(R.string.scan_hint)
                        }
                        if (!usingWechatNow) {
                            binding.tvWechatStats.visibility = View.GONE
                        }
                    }
                },
                onWeChatTiming = { ms ->
                    runOnUiThread {
                        binding.tvWechatStats.text = "WeChat: ${ms}ms"
                        binding.tvWechatStats.visibility = View.VISIBLE
                    }
                },
                // Analyzer 只发出"该跑 WeChat 了"信号，实际用 previewView.bitmap 抓图 + 后台 decode
                // 在这里统一做（跟长按放大按钮的诊断路径完全一致，实测能识别）
                onWeChatFallbackTrigger = { done -> runWeChatWithPreviewBitmap(done) }
            )
            analyzer = a

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, a) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
                // 相机绑定后同步当前 zoom 状态
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    currentZoom = state.zoomRatio
                }
            } catch (e: Exception) {
                Log.e(TAG, "bindToLifecycle failed", e)
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ML Kit 自动变焦回调；返回 true 表示"我确实执行了"
    private fun applyZoomFromMlKit(ratio: Float): Boolean {
        val cam = camera ?: return false
        val info = cam.cameraInfo.zoomState.value ?: return false
        val clamped = ratio.coerceIn(info.minZoomRatio, info.maxZoomRatio)
        // 避免抖动：0.05 以内的变化不动
        if (kotlin.math.abs(clamped - currentZoom) < 0.05f) return false
        cam.cameraControl.setZoomRatio(clamped)
        currentZoom = clamped
        Log.d(TAG, "ML Kit 自动变焦 -> $clamped")
        // UI 提示
        runOnUiThread {
            binding.tvHint.text = "自动放大 ×${"%.1f".format(clamped)}"
        }
        return true
    }

    // ---------------- Torch / Zoom / Focus ----------------

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            binding.btnTorch.setText(if (torchOn) R.string.torch_on else R.string.torch_off)
        } else {
            Toast.makeText(this, "本设备不支持闪光灯", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleZoom() {
        val cam = camera ?: return
        val info = cam.cameraInfo.zoomState.value ?: return
        val target = if (currentZoom > 1.1f) 1f
        else (2f).coerceAtMost(info.maxZoomRatio)
        cam.cameraControl.setZoomRatio(target)
        currentZoom = target
        binding.btnZoom.setText(if (target > 1.1f) R.string.zoom_out else R.string.zoom_in)
    }

    private val previewTouchListener = View.OnTouchListener { view, event ->
        if (event.action != MotionEvent.ACTION_UP) return@OnTouchListener false
        val cam = camera ?: return@OnTouchListener true
        val factory = binding.previewView.meteringPointFactory
        val point = factory.createPoint(event.x, event.y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        view.performClick()
        true
    }

    // ---------------- Frame stats ----------------

    private fun reportFrameTimings(costMs: Long) {
        // 在 analyzerExecutor 上被调用
        frameCount++
        costSumMs += costMs
        val now = System.currentTimeMillis()
        if (lastStatsUpdate == 0L) lastStatsUpdate = now
        val elapsed = now - lastStatsUpdate
        if (elapsed >= 500) {
            val fps = frameCount * 1000f / elapsed
            val avgCost = if (frameCount > 0) costSumMs / frameCount else 0
            val text = "FPS: ${"%.1f".format(fps)}  ML Kit: ${avgCost}ms  Zoom: ×${"%.1f".format(currentZoom)}"
            runOnUiThread { binding.tvStats.text = text }
            frameCount = 0
            costSumMs = 0
            lastStatsUpdate = now
        }
    }

    // ---------------- 识别结果 ----------------

    private fun onBarcodesDetected(engine: ScanEngine, values: List<String>) {
        // 幂等：只处理第一次成功；弹窗关闭前不再处理
        if (!hasResulted.compareAndSet(false, true)) return
        runOnUiThread { showResult(engine, values) }
    }

    private fun showResult(engine: ScanEngine, values: List<String>) {
        val engineTag = when (engine) {
            ScanEngine.ML_KIT -> "🟢 ML Kit"
            ScanEngine.ZXING -> "🟡 ZXing 兜底"
            ScanEngine.WECHAT -> "🔵 WeChat 兜底"
        }
        val title = if (values.size == 1)
            "识别成功 · $engineTag"
        else
            "识别到 ${values.size} 个 · $engineTag"
        val msg = values.joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("继续扫") { d, _ -> d.dismiss(); resumeScan() }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { resumeScan() }
            .show()
    }

    // ---------------- WeChat 兜底（共用抓图 + 解码路径） ----------------

    // 级联触发点会调这里：主线程抓 previewView.bitmap（PreviewView 要求主线程访问）
    // -> 丢到 Default 线程池 decode -> 结果回主线程展示 -> 无论成败都调 done() 释放 wechatBusy
    private fun runWeChatWithPreviewBitmap(done: () -> Unit) {
        val bitmap = binding.previewView.bitmap
        if (bitmap == null) {
            Log.w(TAG, "级联触发 WeChat：previewView.bitmap == null，跳过")
            done()
            return
        }
        val ready = WeChatFallback.isReady()
        Log.d(TAG, "级联触发 WeChat：ready=$ready，图片 ${bitmap.width}x${bitmap.height}")
        CoroutineScope(Dispatchers.Main).launch {
            val start = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                try { WeChatFallback.decode(bitmap) } catch (t: Throwable) {
                    Log.e(TAG, "级联 WeChat 异常", t); null
                }
            }
            val cost = System.currentTimeMillis() - start
            binding.tvWechatStats.text = "WeChat: ${cost}ms"
            binding.tvWechatStats.visibility = View.VISIBLE
            if (!result.isNullOrEmpty()) {
                analyzer?.notifyExternalHit()
                onBarcodesDetected(ScanEngine.WECHAT, listOf(result))
            }
            // 无论有没有识别到，都要释放槽位；下一个 WeChat 触发窗口才能进入
            done()
        }
    }

    // 长按放大按钮触发：手动强制走一次 WeChat，绕开级联判定；诊断专用
    private fun forceWeChatDecodeCurrentFrame() {
        val bitmap = binding.previewView.bitmap
        if (bitmap == null) {
            Toast.makeText(this, "预览还没就绪，稍等一下再试", Toast.LENGTH_SHORT).show()
            return
        }
        val ready = WeChatFallback.isReady()
        Log.d(TAG, "手动触发 WeChat：ready=$ready，图片 ${bitmap.width}x${bitmap.height}")
        Toast.makeText(this, "强制 WeChat 中…（ready=$ready）", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val start = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                try { WeChatFallback.decode(bitmap) } catch (t: Throwable) {
                    Log.e(TAG, "手动 WeChat 异常", t); null
                }
            }
            val cost = System.currentTimeMillis() - start
            binding.tvWechatStats.text = "WeChat(手动): ${cost}ms"
            binding.tvWechatStats.visibility = View.VISIBLE
            if (!result.isNullOrEmpty()) {
                hasResulted.set(false)
                onBarcodesDetected(ScanEngine.WECHAT, listOf(result))
            } else {
                Toast.makeText(
                    this@ScanActivity,
                    if (!ready) "WeChat 引擎还没初始化完，再等 1~2 秒重试" else "WeChat 引擎也识别不了这张图",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------- 相册识别 ----------------

    // 三级级联：ML Kit（InputImage.fromFilePath 自动 EXIF 旋转 + 智能降采样）
    // -> 失败则 ZxingFallback.decodeBitmap() -> 失败则 WeChatFallback.decode()
    // 后两级需要一份手动解码 + 手动摆正 EXIF 的 Bitmap（ML Kit 内部管线拿不到中间 Bitmap）
    private fun decodeFromUri(uri: Uri) {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .build()
        val scanner: BarcodeScanner = BarcodeScanning.getClient(opts)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val input = InputImage.fromFilePath(this@ScanActivity, uri)
                val start = System.currentTimeMillis()
                val results = scanner.process(input).await()
                val cost = System.currentTimeMillis() - start
                val valid = results.mapNotNull { it.rawValue }.filter { it.isNotEmpty() }
                Log.d(TAG, "相册识别耗时 ${cost}ms，命中 ${valid.size} 个（ML Kit）")

                if (valid.isNotEmpty()) {
                    hasResulted.set(false) // 让 CAS 能通过
                    onBarcodesDetected(ScanEngine.ML_KIT, valid)
                    return@launch
                }

                // ML Kit 失败 -> 解一份 Bitmap 给 ZXing / WeChat 用
                val bitmap = withContext(Dispatchers.IO) { decodeBitmapWithExif(uri) }
                if (bitmap == null) {
                    Toast.makeText(this@ScanActivity, "照片中未识别到二维码", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val zxingResult = withContext(Dispatchers.Default) {
                    try { ZxingFallback.decodeBitmap(bitmap) } catch (_: Throwable) { null }
                }
                if (!zxingResult.isNullOrEmpty()) {
                    hasResulted.set(false)
                    onBarcodesDetected(ScanEngine.ZXING, listOf(zxingResult))
                    return@launch
                }

                val wechatResult = withContext(Dispatchers.Default) {
                    try { WeChatFallback.decode(bitmap) } catch (_: Throwable) { null }
                }
                if (!wechatResult.isNullOrEmpty()) {
                    hasResulted.set(false)
                    onBarcodesDetected(ScanEngine.WECHAT, listOf(wechatResult))
                } else {
                    Toast.makeText(this@ScanActivity, "照片中未识别到二维码", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "相册解析失败", e)
                Toast.makeText(this@ScanActivity, "相册解析失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                scanner.close()
            }
        }
    }

    // BitmapFactory 解码 + 手动读取 EXIF 方向摆正（InputImage.fromFilePath 内部做了这一步，
    // 但这里是给 ZXing/WeChat 用的独立 Bitmap，要自己补上，否则竖拍照片会被判定为横向）
    private fun decodeBitmapWithExif(uri: Uri): Bitmap? {
        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val rotation = contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (rotation == 0) return original
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    companion object {
        private const val TAG = "ScanDemo"
    }
}
