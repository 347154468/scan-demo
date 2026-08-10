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
import java.util.concurrent.atomic.AtomicLong

/**
 * 帧分析器。方案要点：
 *  1) InputImage.fromMediaImage —— ML Kit 原生吃 YUV_420_888，不做 Bitmap 中转，去掉 YUV→JPEG→Bitmap 数十毫秒的历史包袱
 *  2) 回调绑定后台 Executor，主线程只做 UI，避免丢帧
 *  3) enableAllPotentialBarcodes 提升难码召回；用 filterEmptyRawValue 过滤空 rawValue，避免一扫就弹空结果
 *  4) setZoomSuggestionOptions —— ML Kit 自动变焦：检测到小码时建议放大比例，我们回调里执行 setZoomRatio，比手动按钮体验好一个量级
 *  5) 不再使用 isProcessing 手动锁 —— CameraX STRATEGY_KEEP_ONLY_LATEST 已经在 imageProxy.close() 前不会给下一帧
 *
 * @param analyzerExecutor 后台单线程 Executor，ML Kit 回调在其上运行
 * @param onZoomRequested  ML Kit 请求变焦时的回调，返回 true 表示确实执行了 setZoomRatio；执行必须在相机线程
 * @param onBarcodes       识别结果回调（在 analyzerExecutor 上）；barcodes 已过滤掉空 rawValue
 * @param onFrameTimings   每帧 ML Kit 处理耗时（ms）的回调，用于 UI 展示；可为空
 */
class QrAnalyzer(
    private val analyzerExecutor: Executor,
    private val onZoomRequested: (ratio: Float) -> Boolean,
    private val onBarcodes: (List<Barcode>) -> Unit,
    private val onFrameTimings: ((processingMs: Long, arriveIntervalMs: Long) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner by lazy {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { requestedRatio ->
                    // ML Kit 建议放大到 requestedRatio；执行 setZoomRatio 后返回 true
                    onZoomRequested(requestedRatio)
                }
                    .setMaxSupportedZoomRatio(MAX_ZOOM_FOR_AUTO)
                    .build()
            )
            .build()
        BarcodeScanning.getClient(opts)
    }

    private val lastArrive = AtomicLong(0L)

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

        val start = System.currentTimeMillis()
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(input)
            .addOnSuccessListener(analyzerExecutor) { barcodes ->
                val cost = System.currentTimeMillis() - start
                onFrameTimings?.invoke(cost, interval)
                // 关键：过滤空 rawValue —— enableAllPotentialBarcodes 会返回未解码候选，rawValue 为 null
                val valid = barcodes.filter { !it.rawValue.isNullOrEmpty() }
                if (valid.isNotEmpty()) {
                    onBarcodes(valid)
                }
            }
            .addOnFailureListener(analyzerExecutor) {
                // 静默：多数是本帧无码，下一帧继续
            }
            .addOnCompleteListener(analyzerExecutor) {
                // 必须在 complete 里 close，否则任何异常都会卡死 pipeline
                imageProxy.close()
            }
    }

    fun shutdown() {
        scanner.close()
    }

    companion object {
        // 自动变焦允许 ML Kit 建议的最大倍数；超过该倍数放大意义不大且噪点严重
        private const val MAX_ZOOM_FOR_AUTO = 4f
    }
}
