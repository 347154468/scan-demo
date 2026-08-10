package com.demo.scandemo

import android.Manifest
import android.content.pm.PackageManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
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

        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnZoom.setOnClickListener { toggleZoom() }
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
                onZoomRequested = { ratio -> applyZoomFromMlKit(ratio) },
                onBarcodes = { list -> onBarcodesDetected(list) },
                onFrameTimings = { cost, _ -> reportFrameTimings(cost) }
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

    private fun onBarcodesDetected(barcodes: List<Barcode>) {
        // 幂等：只处理第一次成功；弹窗关闭前不再处理
        if (!hasResulted.compareAndSet(false, true)) return

        val values = barcodes.mapNotNull { it.rawValue }
        runOnUiThread { showResult(values) }
    }

    private fun showResult(values: List<String>) {
        val title = if (values.size == 1) "识别成功" else "识别到 ${values.size} 个二维码"
        val msg = values.joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("继续扫") { d, _ -> d.dismiss(); resumeScan() }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { resumeScan() }
            .show()
    }

    // ---------------- 相册识别 ----------------

    // 关键：InputImage.fromFilePath 自动做 EXIF 旋转 + 智能降采样，不用自己搭 Glide 管线
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
                Log.d(TAG, "相册识别耗时 ${cost}ms，命中 ${valid.size} 个")
                if (valid.isEmpty()) {
                    Toast.makeText(this@ScanActivity, "照片中未识别到二维码", Toast.LENGTH_SHORT).show()
                } else {
                    hasResulted.set(false) // 让 showResult 里的 CAS 能通过
                    onBarcodesDetected(results.filter { !it.rawValue.isNullOrEmpty() })
                }
            } catch (e: Exception) {
                Log.e(TAG, "相册解析失败", e)
                Toast.makeText(this@ScanActivity, "相册解析失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                scanner.close()
            }
        }
    }

    companion object {
        private const val TAG = "ScanDemo"
    }
}
