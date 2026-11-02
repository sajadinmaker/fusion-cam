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
 * The YUV fallback entry ([mergeBurstYuv]) accepts packed [Y, U, V] buffers
 * from [yuvBytes] so cameras without RAW_SENSOR (LEGACY/LIMITED HALs) can
 * still shoot merged bursts.
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
        tauScale: Float,         // noise-floor scaler >= 1 (ISO/100)
    ): IntArray

    /**
     * YUV fallback merge for cameras without RAW_SENSOR. Luma runs the same
     * pyramid align + Wiener merge as the RAW path; chroma comes from the
     * reference frame. A single frame (n == 1) is converted without fusion —
     * the plain-photo path on RAW-less devices.
     */
    external fun mergeBurstYuv(
        frames: Array<ByteArray>,
        refIndex: Int,
        width: Int,
        height: Int,
        tauScale: Float,         // noise-floor scaler >= 1 (ISO/100)
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

    /** YUV_420_888 planes -> tightly packed [Y, U, V] byte buffer. */
    fun yuvBytes(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val y = extractPlane(image.planes[0], w, h)
        val u = extractPlane(image.planes[1], w / 2, h / 2)
        val v = extractPlane(image.planes[2], w / 2, h / 2)
        return y + u + v
    }

    /** Compacts one YUV plane, honoring arbitrary row/pixel strides. */
    private fun extractPlane(plane: Image.Plane, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pxStride = plane.pixelStride
        for (row in 0 until h) {
            if (pxStride == 1) {
                buf.position(row * rowStride)
                buf.get(out, row * w, w)
            } else {
                val base = row * rowStride
                for (col in 0 until w) out[row * w + col] = buf.get(base + col * pxStride)
            }
        }
        return out
    }
}
