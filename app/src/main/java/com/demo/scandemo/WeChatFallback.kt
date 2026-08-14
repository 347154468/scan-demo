package com.demo.scandemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import org.opencv.OpenCV
import com.king.wechat.qrcode.WeChatQRCodeDetector
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WeChat QRCode 兜底扫描器（三级级联的第三级，ML Kit -> ZXing -> WeChat）。
 *
 * 用的是 OpenCV contrib 里的 wechat_qrcode 模块（CNN 定位 + 超分辨率重建），
 * 官方 OpenCV Android AAR 不含 contrib，这里走第三方预编译发行版
 * jenly1314/WeChatQRCode（模型文件打包在库自身 assets 里，init() 会自动拷贝初始化）。
 *
 * 策略：
 *  - 懒加载初始化：init() 不在 Activity.onCreate 里同步跑，避免启动卡顿；
 *    调用方（ScanActivity）在后台线程里尽早调一次即可，真正用到时大概率已就绪
 *  - init() 用 AtomicBoolean 做"只跑一次"门禁，重复调用是安全的空操作
 *  - decode() 全程 catch(Throwable) 兜底：已知 OpenCV 4.12 Android 上有 native crash
 *    （opencv/opencv#27798），绝不能让异常拖垮独立的 wechatExecutor 线程
 *  - 只接受 Bitmap（WeChatQRCodeDetector 公开 API 不支持直接吃 YUV 字节数组），
 *    yuvToBitmap() 供实时扫描路径把相机 YUV 帧转成 Bitmap 用
 */
object WeChatFallback {

    private const val TAG = "WeChatFallback"

    private val initStarted = AtomicBoolean(false)
    @Volatile
    private var ready = false

    /**
     * 懒加载初始化；首次调用才会真正执行 OpenCV + WeChatQRCodeDetector 的初始化，
     * 重复调用直接返回。建议在后台线程调用（比如 ScanActivity 的 wechatExecutor 上），
     * 不要放在主线程 / Activity.onCreate 里同步跑。
     */
    fun init(context: Context) {
        if (!initStarted.compareAndSet(false, true)) {
            Log.d(TAG, "init() 已发起过，跳过（当前 ready=$ready）")
            return
        }
        val t0 = System.currentTimeMillis()
        try {
            Log.d(TAG, "开始初始化 OpenCV…")
            val loaded = OpenCV.initOpenCV()
            val t1 = System.currentTimeMillis()
            Log.d(TAG, "OpenCV.initOpenCV() 返回 $loaded，耗时 ${t1 - t0}ms")
            if (!loaded) {
                ready = false
                Log.e(TAG, "OpenCV 加载失败，本级兜底将始终不生效")
                return
            }
            Log.d(TAG, "开始初始化 WeChatQRCodeDetector（首次会拷贝模型 + 加载 so，可能 500ms+）…")
            WeChatQRCodeDetector.init(context.applicationContext)
            val t2 = System.currentTimeMillis()
            ready = true
            Log.d(TAG, "WeChatQRCodeDetector.init() 完成，耗时 ${t2 - t1}ms，总初始化耗时 ${t2 - t0}ms，ready=true")
        } catch (t: Throwable) {
            ready = false
            Log.e(TAG, "WeChat QRCode 初始化失败，本级兜底将始终不生效", t)
        }
    }

    /** 供外部诊断/UI 提示；true = 引擎已加载完可用 */
    fun isReady(): Boolean = ready

    /**
     * 用 WeChat 引擎解码；未就绪或推理异常一律返回 null，不影响 ML Kit / ZXing 正常工作。
     */
    fun decode(bitmap: Bitmap): String? {
        if (!ready) {
            Log.w(TAG, "decode() 被调用但引擎未就绪（ready=false），本次跳过；请确认 init() 已完成")
            return null
        }
        return try {
            val t0 = System.currentTimeMillis()
            val results = WeChatQRCodeDetector.detectAndDecode(bitmap)
            val cost = System.currentTimeMillis() - t0
            val hit = results.firstOrNull { it.isNotEmpty() }
            Log.d(TAG, "decode() 完成，耗时 ${cost}ms，命中 ${results.size} 个，返回：${hit?.take(60) ?: "null"}")
            hit
        } catch (t: Throwable) {
            Log.e(TAG, "WeChat QRCode 解码异常", t)
            null
        }
    }

    /**
     * 相机 YUV_420_888 Image -> NV21 字节数组 -> JPEG -> Bitmap。
     * 必须在 imageProxy.close() 之前调用（Image 底层内存会被回收）。
     * @param rotation ImageProxy.imageInfo.rotationDegrees
     */
    fun yuvToBitmap(image: Image, rotation: Int): Bitmap? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val bytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (rotation == 0) bitmap else rotateBitmap(bitmap, rotation)
        } catch (t: Throwable) {
            Log.e(TAG, "YUV -> Bitmap 转换失败", t)
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // YUV_420_888（3 个 plane，Y / U / V 各自独立 stride）打平成 NV21（Y 后面紧跟 VU 交替）
    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var pos = 0
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(row, 0, yRowStride)
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        // VU 交替写入（NV21 要求），用绝对下标读取，不影响 buffer position
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        var pos = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (r in 0 until chromaHeight) {
            for (c in 0 until chromaWidth) {
                val vIndex = r * vRowStride + c * vPixelStride
                val uIndex = r * uRowStride + c * uPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }
}
