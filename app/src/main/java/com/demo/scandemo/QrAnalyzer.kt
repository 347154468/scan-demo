package com.demo.scandemo

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 命中引擎。
 */
enum class ScanEngine { ML_KIT, ZXING }

/**
 * 帧分析器。方案要点：
 *  1) InputImage.fromMediaImage —— ML Kit 原生吃 YUV_420_888，不做 Bitmap 中转
 *  2) 回调绑定后台 Executor，主线程只做 UI
 *  3) enableAllPotentialBarcodes 提升难码召回；过滤空 rawValue
 *  4) setZoomSuggestionOptions —— ML Kit 自动变焦
 *  5) CameraX STRATEGY_KEEP_ONLY_LATEST 背压，不加冗余锁
 *  6) 级联 ZXing 兜底：ML Kit 连续 ZXING_FALLBACK_AFTER 帧无结果时，同一帧交给 ZXing 再扫一次；
 *     二次命中概率不高但成本可控（ZXing 每帧约 5-20ms 且不是每帧都跑），
 *     用于捡 ML Kit 在反光/低对比场景漏的码
 */
class QrAnalyzer(
    private val analyzerExecutor: Executor,
    private val onZoomRequested: (ratio: Float) -> Boolean,
    private val onBarcodes: (engine: ScanEngine, values: List<String>) -> Unit,
    private val onFrameTimings: ((processingMs: Long, arriveIntervalMs: Long) -> Unit)? = null,
    private val onEngineSwitch: ((usingZxingNow: Boolean) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner by lazy {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { requestedRatio ->
                    onZoomRequested(requestedRatio)
                }
                    .setMaxSupportedZoomRatio(MAX_ZOOM_FOR_AUTO)
                    .build()
            )
            .build()
        BarcodeScanning.getClient(opts)
    }

    private val lastArrive = AtomicLong(0L)
    private val consecutiveMisses = AtomicInteger(0)
    private var wasUsingZxing = false

    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val arriveMs = System.currentTimeMillis()
        val prev = lastArrive.getAndSet(arriveMs)
        val interval = if (prev == 0L) 0L else arriveMs - prev

        val rotation = imageProxy.imageInfo.rotationDegrees
        val start = System.currentTimeMillis()
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        scanner.process(input)
            .addOnSuccessListener(analyzerExecutor) { barcodes ->
                val mlKitCost = System.currentTimeMillis() - start
                val valid = barcodes.filter { !it.rawValue.isNullOrEmpty() }

                if (valid.isNotEmpty()) {
                    // ML Kit 命中
                    consecutiveMisses.set(0)
                    switchEngineHintIfNeeded(usingZxingNow = false)
                    onFrameTimings?.invoke(mlKitCost, interval)
                    onBarcodes(ScanEngine.ML_KIT, valid.mapNotNull { it.rawValue })
                    return@addOnSuccessListener
                }

                // ML Kit 未命中 —— 是否触发 ZXing 兜底
                val misses = consecutiveMisses.incrementAndGet()
                if (misses >= ZXING_FALLBACK_AFTER) {
                    val zxingStart = System.currentTimeMillis()
                    val zxingResult = try {
                        ZxingFallback.decode(mediaImage, rotation)
                    } catch (_: Throwable) {
                        null
                    }
                    val zxingCost = System.currentTimeMillis() - zxingStart
                    val totalCost = mlKitCost + zxingCost
                    onFrameTimings?.invoke(totalCost, interval)
                    switchEngineHintIfNeeded(usingZxingNow = true)

                    if (!zxingResult.isNullOrEmpty()) {
                        consecutiveMisses.set(0)
                        onBarcodes(ScanEngine.ZXING, listOf(zxingResult))
                    }
                } else {
                    onFrameTimings?.invoke(mlKitCost, interval)
                    switchEngineHintIfNeeded(usingZxingNow = false)
                }
            }
            .addOnFailureListener(analyzerExecutor) {
                // 静默
            }
            .addOnCompleteListener(analyzerExecutor) {
                imageProxy.close()
            }
    }

    private fun switchEngineHintIfNeeded(usingZxingNow: Boolean) {
        if (usingZxingNow != wasUsingZxing) {
            wasUsingZxing = usingZxingNow
            onEngineSwitch?.invoke(usingZxingNow)
        }
    }

    fun shutdown() {
        scanner.close()
    }

    companion object {
        private const val MAX_ZOOM_FOR_AUTO = 4f
        // ML Kit 连续 N 帧无结果后启用 ZXing 兜底；常态下（能识别的码）永远不会走到 ZXing
        private const val ZXING_FALLBACK_AFTER = 15
    }
}
