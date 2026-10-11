package dev.lanbridge

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/** Offline QR recognition shared by CameraX analysis and the Windows QR interoperability test. */
object QrDecoder {
    fun copyLuma(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
        val start = buffer.position()
        require(start.toLong() + (height-1L)*rowStride + (width-1L)*pixelStride < buffer.limit()) { "相机亮度数据不完整" }
        val bytes = ByteArray(Math.multiplyExact(width, height))
        for(y in 0 until height) for(x in 0 until width) bytes[y*width+x] = buffer.get(start+y*rowStride+x*pixelStride)
        return bytes
    }
    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && width.toLong()*height <= luma.size)
        val reader = MultiFormatReader()
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
        } catch(_: ReaderException) { null } finally { reader.reset() }
    }
}
