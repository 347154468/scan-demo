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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 命中引擎。
 */
enum class ScanEngine { ML_KIT, ZXING, WECHAT }

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
 *  7) 三级级联 WeChat 兜底：ZXing 连续 WECHAT_FALLBACK_AFTER 帧也无结果时，
 *     把当前帧 YUV 转成 Bitmap（在 imageProxy.close() 之前完成），丢到独立的
 *     wechatExecutor 上跑 CNN 推理（单帧 30-80ms，明显重于前两级，必须独立线程 + 互斥）。
 *     wechatBusy 保证同一时刻只有一个 WeChat 任务在跑，上一个没完成就直接丢弃新的触发信号，
 *     不排队——这一级本就是低频兜底，不追求"每次触发都跑到"。
 */
class QrAnalyzer(
    private val analyzerExecutor: Executor,
    private val wechatExecutor: Executor,
    private val onZoomRequested: (ratio: Float) -> Boolean,
    private val onBarcodes: (engine: ScanEngine, values: List<String>) -> Unit,
    private val onFrameTimings: ((processingMs: Long, arriveIntervalMs: Long) -> Unit)? = null,
    private val onEngineSwitch: ((usingZxingNow: Boolean, usingWechatNow: Boolean) -> Unit)? = null,
    private val onWeChatTiming: ((ms: Long) -> Unit)? = null,
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
    private val consecutiveZxingMisses = AtomicInteger(0)
    private val wechatBusy = AtomicBoolean(false)
    private var wasUsingZxing = false
    private var wasUsingWechat = false

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
                    consecutiveZxingMisses.set(0)
                    switchEngineHintIfNeeded(usingZxingNow = false, usingWechatNow = false)
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

                    if (!zxingResult.isNullOrEmpty()) {
                        consecutiveMisses.set(0)
                        consecutiveZxingMisses.set(0)
                        switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = false)
                        onBarcodes(ScanEngine.ZXING, listOf(zxingResult))
                        return@addOnSuccessListener
                    }

                    // ZXing 也未命中 —— 是否触发 WeChat 兜底
                    val zxingMisses = consecutiveZxingMisses.incrementAndGet()
                    if (zxingMisses >= WECHAT_FALLBACK_AFTER && wechatBusy.compareAndSet(false, true)) {
                        switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = true)
                        // 必须在 imageProxy.close() 之前完成 YUV -> Bitmap 拷贝
                        val bitmap = try {
                            WeChatFallback.yuvToBitmap(mediaImage, rotation)
                        } catch (_: Throwable) {
                            null
                        }
                        if (bitmap == null) {
                            wechatBusy.set(false)
                        } else {
                            wechatExecutor.execute {
                                try {
                                    val wechatStart = System.currentTimeMillis()
                                    val wechatResult = WeChatFallback.decode(bitmap)
                                    val wechatCost = System.currentTimeMillis() - wechatStart
                                    onWeChatTiming?.invoke(wechatCost)
                                    if (!wechatResult.isNullOrEmpty()) {
                                        consecutiveMisses.set(0)
                                        consecutiveZxingMisses.set(0)
                                        onBarcodes(ScanEngine.WECHAT, listOf(wechatResult))
                                    }
                                } finally {
                                    wechatBusy.set(false)
                                }
                            }
                        }
                    } else {
                        switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = false)
                    }
                } else {
                    onFrameTimings?.invoke(mlKitCost, interval)
                    switchEngineHintIfNeeded(usingZxingNow = false, usingWechatNow = false)
                }
            }
            .addOnFailureListener(analyzerExecutor) {
                // 静默
            }
            .addOnCompleteListener(analyzerExecutor) {
                imageProxy.close()
            }
    }

    private fun switchEngineHintIfNeeded(usingZxingNow: Boolean, usingWechatNow: Boolean) {
        if (usingZxingNow != wasUsingZxing || usingWechatNow != wasUsingWechat) {
            wasUsingZxing = usingZxingNow
            wasUsingWechat = usingWechatNow
            onEngineSwitch?.invoke(usingZxingNow, usingWechatNow)
        }
    }

    fun shutdown() {
        scanner.close()
    }

    companion object {
        private const val MAX_ZOOM_FOR_AUTO = 4f
        // ML Kit 连续 N 帧无结果后启用 ZXing 兜底；常态下（能识别的码）永远不会走到 ZXing
        private const val ZXING_FALLBACK_AFTER = 15
        // ZXing 之后再连续 N 帧无结果才启用 WeChat 兜底（初始值，后续实机可调）
        private const val WECHAT_FALLBACK_AFTER = 10
    }
}
