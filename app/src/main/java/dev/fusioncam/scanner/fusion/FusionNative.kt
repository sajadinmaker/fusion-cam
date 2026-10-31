package dev.fusioncam.scanner.fusion

import android.media.Image
import java.nio.ByteBuffer

/**
 * JNI surface for the Phase 2 HDR+ style burst merge.
 *
 * Inputs: one compact RAW16 buffer per frame (little-endian Bayer, 2 bytes per
 * pixel, rows tightly packed), the reference index, geometry, Bayer layout and
 * level constants from the Camera2 characteristics.
 *
 * Output: sRGB-888 pixels (0xAARRGGBB), one per input pixel.
 */
object FusionNative {
    init {
        System.loadLibrary("fusion")
    }

    external fun mergeBurst(
        frames: Array<ByteArray>,
        refIndex: Int,
        width: Int,
        height: Int,
        cfa: Int,
        blackLevels: IntArray,   // 4 entries: R, G-even, G-odd, B (RAW units)
        whiteLevel: Int,
        wbGains: FloatArray,     // 3 entries: R, G, B (1/1/1 = neutral)
    ): IntArray

    /** Image plane 0 (RAW16) -> tightly packed little-endian byte buffer. */
    fun raw16Bytes(image: Image): ByteArray {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val out = ByteArray(width * height * 2)
        val bb: ByteBuffer = plane.buffer
        if (rowStride == width * 2 && bb.remaining() >= out.size) {
            bb.position(0)
            bb.get(out)
        } else {
            val rowBytes = width * 2
            for (y in 0 until height) {
                bb.position(y * rowStride)
                bb.get(out, y * rowBytes, rowBytes)
            }
        }
        return out
    }
}
