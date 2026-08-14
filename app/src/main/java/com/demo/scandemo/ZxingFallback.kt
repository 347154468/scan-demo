package com.demo.scandemo

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * ZXing 兜底扫描器。策略：
 *  - 只有 ML Kit 连续 N 帧无结果时才调用（常态零开销）
 *  - 实时路径直接吃 YUV_420_888 的 Y 平面，不做任何 Bitmap 中转（跟 ML Kit 一样避免历史包袱）
 *  - 相册路径额外提供 decodeBitmap()，走 RGBLuminanceSource（相册场景没有 YUV，只有解码好的 Bitmap）
 *  - 启用 TRY_HARDER + PURE_BARCODE 关闭；旋转 90 度重试一次，覆盖竖屏拍横码
 *
 * 单例（线程安全靠外层"级联时机"保证只有一个线程调）：ZXing 的 reader 不是线程安全的，
 * QrAnalyzer 里 ML Kit 回调本身就是串行的（analyzerExecutor 单线程），所以这里也串行。
 * decodeBitmap() 供相册路径调用，运行在主线程的协程里，同样不会跟实时路径并发。
 */
object ZxingFallback {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }

    /**
     * 从 YUV Image 里扫；扫到就返回 rawValue，扫不到返回 null。
     * @param rotation 图像顺时针旋转角度（ImageProxy.imageInfo.rotationDegrees）
     */
    fun decode(image: Image, rotation: Int): String? = try {
        decodeInternal(image, rotation)
    } catch (t: Throwable) {
        // 稳妥兜底：任何异常都不能扩散到 QrAnalyzer 之外
        android.util.Log.e("ZxingFallback", "decode() 异常，本帧跳过", t)
        null
    }

    private fun decodeInternal(image: Image, rotation: Int): String? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val plane = image.planes[0] // Y plane
        // duplicate + rewind：避免污染原 buffer position，也不受 ML Kit 之前读取的影响
        // （ImageProxy 是同一份 mediaImage；ML Kit 先跑过一次，buffer position 可能已经不在 0）
        val yBuffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val remaining = yBuffer.remaining()

        // 拷贝 Y 数据到紧凑 byte[]（PlanarYUVLuminanceSource 要求 dataWidth * dataHeight 大小）
        val data = ByteArray(width * height)
        if (rowStride == width && pixelStride == 1) {
            if (remaining < width * height) return null
            yBuffer.get(data, 0, width * height)
        } else {
            // 严格边界检查：最后一行读到 (height - 1) * rowStride + rowStride，一旦 buffer 不够就放弃
            val needed = (height - 1) * rowStride + rowStride
            if (remaining < needed) return null
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                yBuffer.position(y * rowStride)
                yBuffer.get(row, 0, rowStride)
                if (pixelStride == 1) {
                    System.arraycopy(row, 0, data, y * width, width)
                } else {
                    for (x in 0 until width) data[y * width + x] = row[x * pixelStride]
                }
            }
        }

        return decodeYuvBytes(data, width, height, rotation)
    }

    /**
     * 从相册解码出来的 Bitmap 里扫；扫到就返回 rawValue，扫不到返回 null。
     * 供相册级联路径用（ML Kit 失败 -> 这里 -> 再失败才轮到 WeChat）。
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(binaryBitmap).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun decodeYuvBytes(y: ByteArray, width: Int, height: Int, rotation: Int): String? {
        // 先按原始朝向扫；扫不到再旋转 90 度扫一次（覆盖横码/竖屏错向）
        tryDecodeOnce(y, width, height)?.let { return it }
        if (rotation == 90 || rotation == 270) {
            val rotated = rotate90(y, width, height)
            tryDecodeOnce(rotated, height, width)?.let { return it }
        }
        return null
    }

    private fun tryDecodeOnce(y: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate90(y: ByteArray, width: Int, height: Int): ByteArray {
        require(y.size >= width * height) { "rotate90: y buffer too small: ${y.size} < ${width * height}" }
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                out[col * height + (height - 1 - row)] = y[row * width + col]
            }
        }
        return out
    }
}
