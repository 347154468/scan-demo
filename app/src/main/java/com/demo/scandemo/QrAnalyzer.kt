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
import android.util.Log
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
/**
 * WeChat 兜底触发信号：Analyzer 只负责判断"什么时候该触发"，实际"用什么图去解码"完全外包给外部。
 * 之所以外包：手写的 yuvToBitmap 路径经实测识别不出 previewView 能识别的码，
 * 所以改由 Activity 在主线程抓 previewView.bitmap 后再走 WeChat；抓图/解码/结果回传的完整生命周期
 * 都由外部管理，Analyzer 只在最后收到 done() 回调时释放 wechatBusy 槽位。
 * yuvToBitmap 相关代码保留在 WeChatFallback.kt 里作为诊断和未来回退用。
 */
typealias WeChatFallbackTrigger = (done: () -> Unit) -> Unit

class QrAnalyzer(
    private val analyzerExecutor: Executor,
    private val wechatExecutor: Executor,
    private val onZoomRequested: (ratio: Float) -> Boolean,
    private val onBarcodes: (engine: ScanEngine, values: List<String>) -> Unit,
    private val onFrameTimings: ((processingMs: Long, arriveIntervalMs: Long) -> Unit)? = null,
    private val onEngineSwitch: ((usingZxingNow: Boolean, usingWechatNow: Boolean) -> Unit)? = null,
    private val onWeChatTiming: ((ms: Long) -> Unit)? = null,
    private val onWeChatFallbackTrigger: WeChatFallbackTrigger? = null,
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
                if (misses == ZXING_FALLBACK_AFTER) {
                    Log.d(TAG, "ML Kit 连续 $misses 帧无结果，开始启用 ZXing 兜底")
                }
                if (misses >= ZXING_FALLBACK_AFTER) {
                    val zxingStart = System.currentTimeMillis()
                    val zxingResult = try {
                        ZxingFallback.decode(mediaImage, rotation)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ZXing 解码异常，本帧跳过", t)
                        null
                    }
                    val zxingCost = System.currentTimeMillis() - zxingStart
                    val totalCost = mlKitCost + zxingCost
                    onFrameTimings?.invoke(totalCost, interval)

                    if (!zxingResult.isNullOrEmpty()) {
                        Log.d(TAG, "ZXing 命中：${zxingResult.take(60)}")
                        consecutiveMisses.set(0)
                        consecutiveZxingMisses.set(0)
                        switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = false)
                        onBarcodes(ScanEngine.ZXING, listOf(zxingResult))
                        return@addOnSuccessListener
                    }

                    // ZXing 也未命中 —— 是否触发 WeChat 兜底
                    val zxingMisses = consecutiveZxingMisses.incrementAndGet()
                    if (zxingMisses == WECHAT_FALLBACK_AFTER) {
                        Log.d(TAG, "ZXing 连续 $zxingMisses 帧无结果，开始尝试触发 WeChat 兜底（ready=${WeChatFallback.isReady()}, wechatBusy=${wechatBusy.get()}）")
                    }
                    if (zxingMisses >= WECHAT_FALLBACK_AFTER) {
                        if (!wechatBusy.compareAndSet(false, true)) {
                            // 上一个 WeChat 任务还没跑完，本帧丢弃，正常现象
                            if (zxingMisses == WECHAT_FALLBACK_AFTER) {
                                Log.d(TAG, "WeChat 触发被跳过：上一个任务仍在运行")
                            }
                            switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = false)
                        } else {
                            switchEngineHintIfNeeded(usingZxingNow = true, usingWechatNow = true)
                            // 抓图 + 解码完全外包（Activity 在主线程抓 previewView.bitmap
                            // -> 丢到 wechatExecutor 后台跑 -> 结果通过 onBarcodes 回传
                            // -> 无论成败都会调 done() 释放槽位）
                            val trigger = onWeChatFallbackTrigger
                            if (trigger == null) {
                                Log.w(TAG, "onWeChatFallbackTrigger 未注册，跳过并释放槽位")
                                wechatBusy.set(false)
                            } else {
                                Log.d(TAG, "WeChat 触发信号已发出，等待外部抓图 + 解码")
                                trigger { wechatBusy.set(false) }
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

    /** 外部的 WeChat 兜底命中后调用，清掉 ML Kit / ZXing 的 miss 计数，避免下一帧立刻再次触发。 */
    fun notifyExternalHit() {
        consecutiveMisses.set(0)
        consecutiveZxingMisses.set(0)
    }

    fun shutdown() {
        scanner.close()
    }

    companion object {
        private const val TAG = "QrAnalyzer"
        private const val MAX_ZOOM_FOR_AUTO = 4f
        // ML Kit 连续 N 帧无结果后启用 ZXing 兜底；常态下（能识别的码）永远不会走到 ZXing
        private const val ZXING_FALLBACK_AFTER = 15
        // ZXing 之后再连续 N 帧无结果才启用 WeChat 兜底（初始值，后续实机可调）
        private const val WECHAT_FALLBACK_AFTER = 10
    }
}
