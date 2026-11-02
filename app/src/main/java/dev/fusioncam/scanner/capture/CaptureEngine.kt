package dev.fusioncam.scanner.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import dev.fusioncam.scanner.core.FrameMeta

/**
 * Phase 1 capture engine.
 *
 * Runs a repeating (preview + capture) stream — the reader itself is the
 * rolling ZSL buffer. Pressing the latch stops the repeating request, drains
 * up to `maxImages` buffered frames, ranks them by a Laplacian sharpness
 * proxy (Apple's "pick the sharpest frame already taken"), and hands them
 * back with their paired per-frame metadata.
 *
 * Every camera can capture: when the HAL offers RAW_SENSOR the engine streams
 * RAW Bayer ([CaptureFormat.RAW_BAYER]); otherwise it falls back to a
 * YUV_420_888 reader with identical latch semantics ([CaptureFormat.YUV]) so
 * LEGACY/LIMITED devices — i.e. most Android 8+ phones — reach the YUV
 * fusion path instead of dead-ending.
 */
class CaptureEngine(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onFramesLatched(frames: List<LatchedFrame>, characteristics: CameraCharacteristics)
    }

    class LatchedFrame(
        val image: Image,
        val result: TotalCaptureResult?,
        val meta: FrameMeta,
        val sharpness: Double,
    )

    /** Capture path chosen per camera: RAW Bayer for fusion, YUV fallback otherwise. */
    enum class CaptureFormat { RAW_BAYER, YUV }

    var captureFormat: CaptureFormat = CaptureFormat.RAW_BAYER
        private set
    var sensorOrientation: Int = 0
        private set
    var bestCameraId: String = ""

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("fusion-capture").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val fallbackThread = HandlerThread("fusion-io").apply { start() }
    private val ioHandler = Handler(fallbackThread.looper)

    private var characteristics: CameraCharacteristics? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var frameReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    /** Last N results kept to pair with drained images by sensor timestamp. */
    private val recentResults = object : LinkedHashMap<Long, TotalCaptureResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TotalCaptureResult>?): Boolean =
            size > MAX_TRACKED_RESULTS
    }

    private var rawFrameFps: Double = 0.0
    private var lastFpsWindowStartNs = 0L
    private var framesInWindow = 0
    private var latching = false

    fun start(targetCameraId: String, previewHolder: SurfaceHolder) {
        bestCameraId = targetCameraId
        val characteristics = runCatching { cameraManager.getCameraCharacteristics(targetCameraId) }
            .getOrNull()
        if (characteristics == null) {
            listener.onStatus("Camera $targetCameraId not available")
            return
        }
        this.characteristics = characteristics

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // RAW Bayer burst when the HAL offers RAW_SENSOR; otherwise the YUV
        // fallback (works on every Camera2 device, LEGACY included).
        val rawSizes = runCatching { map?.getOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull()
        val rawSize = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
        captureFormat = if (rawSize != null) CaptureFormat.RAW_BAYER else CaptureFormat.YUV

        val previewSize = choosePreviewSize(map, previewHolder.surfaceFrame)
        previewHolder.setFixedSize(previewSize.width, previewSize.height)
        previewSurface = previewHolder.surface

        frameReader = if (rawSize != null) {
            ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, queueDepthFor(rawSize)
            )
        } else {
            val yuvSize = chooseYuvMergeSize(map)
            if (yuvSize == null) {
                listener.onStatus("No RAW_SENSOR or YUV_420_888 output on camera $targetCameraId")
                return
            }
            ImageReader.newInstance(
                yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, queueDepthFor(yuvSize)
            )
        }
        frameReader!!.setOnImageAvailableListener({ reader ->
            if (latching) return@setOnImageAvailableListener // keep frames queued for the latch
            reader.acquireLatestImage()?.apply {
                observeFps()
                close() // during streaming we only count frames, we don't keep them
            }
        }, cameraHandler)

        cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = configureSession(device)
            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int) =
                listener.onStatus("Camera error: $error")
        }, cameraHandler)
    }

    private fun configureSession(device: CameraDevice) {
        camera = device
        val targets = listOfNotNull(previewSurface, frameReader?.surface)
        runCatching {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    startRepeating()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener.onStatus("Session config failed (preview+capture); retrying capture-only")
                    retryReaderOnly(device)
                }
            }, cameraHandler)
            }.onFailure {
                listener.onStatus("Session create failed: ${it.message}")
                // Synchronous failure (e.g. preview Surface abandoned by a
                // relayout) — the capture-only retry was previously reached only
                // through onConfigureFailed and never in this case, leaving
                // the engine permanently sessionless.
                runCatching { retryReaderOnly(device) }
            }
    }

    private fun retryReaderOnly(device: CameraDevice) {
        val rawSurface = frameReader?.surface ?: return
        runCatching {
            device.createCaptureSession(listOf(rawSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    previewSurface = null
                    startRepeating()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener.onStatus("No usable session on this device")
                }
            }, cameraHandler)
        }
    }

    private fun startRepeating() {
        val device = camera ?: return
        val ses = session ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { builder.addTarget(it) }
        frameReader?.surface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        requestBuilder = builder
        runCatching {
            ses.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    stream: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                        recentResults[ts] = result
                    }
                }
            }, cameraHandler)
        }.onFailure { listener.onStatus("Repeating request failed: ${it.message}") }
        val modeName = if (captureFormat == CaptureFormat.RAW_BAYER) "RAW Bayer" else "YUV fallback"
        listener.onStatus(
            "Streaming · $modeName · queue depth ${frameReader?.maxImages ?: ZSL_DEPTH} · ${"%.1f".format(rawFrameFps)} fps (measured)"
        )
    }

    /** Latch: stop, wait for the pipe to settle, drain, rank, hand over. */
    fun latch() {
        if (!latching && session != null) {
            latching = true
            session?.stopRepeating()
            cameraHandler.postDelayed({ drainQueue() }, SETTLE_MS)
        }
    }

    private fun drainQueue() {
        val reader = frameReader
        if (reader == null) {
            listener.onStatus("Drain: reader gone")
            return
        }
        val frames = mutableListOf<LatchedFrame>()
        while (true) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: break
            val result = recentResults[image.timestamp]
            val meta = result?.let { FrameMeta.from(it) } ?: FrameMeta(
                timestampNs = image.timestamp, exposureNs = null, iso = null, frameDurationNs = null,
                aeState = null, afState = null, lensAperture = null, neutralColorPoint = null,
                noiseProfile = null, dynamicBlackLevel = null, colorCorrectionTransform = null,
                focusDistance = null,
            )
            frames += LatchedFrame(image, result, meta, rankSharpness(image))
        }
        if (frames.isEmpty()) {
            listener.onStatus("Drain: nothing buffered")
            return
        }
        frames.sortByDescending { it.sharpness }
        listener.onStatus(
            "Latched ${frames.size} frames · best sharpness " +
                "%.0f".format(frames.first().sharpness) +
                " · exposure ${frames.first().meta.exposureNs}ns · ISO ${frames.first().meta.iso}"
        )
        listener.onFramesLatched(frames, characteristics!!)
    }

    /** Called by the UI once saving finished. */
    fun afterLatch() {
        frameReader?.let { reader ->
            while (runCatching { reader.acquireNextImage() }.getOrNull() != null) {
                // images already consumed by UI; safe to sweep leftovers
            }
        }
        latching = false
        startRepeating()
    }

    fun release() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { frameReader?.close() }
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?, holderSize: android.graphics.Rect): Size {
        val candidates = runCatching { map?.getOutputSizes(SurfaceHolder::class.java) }
            .getOrNull().orEmpty()
            .filter { it.width <= holderSize.width() && it.height <= holderSize.height() }
        return candidates.maxByOrNull { it.width.toLong() * it.height } ?: Size(640, 480)
    }

    /**
     * Largest YUV size that keeps the fallback merge cheap enough on low-end
     * SoCs (≤ ~8 MP); falls back to the smallest size on offer when every
     * stream exceeds the cap.
     */
    private fun chooseYuvMergeSize(map: StreamConfigurationMap?): Size? {
        val sizes = runCatching { map?.getOutputSizes(ImageFormat.YUV_420_888) }
            .getOrNull().orEmpty()
        if (sizes.isEmpty()) return null
        return sizes.filter { it.width.toLong() * it.height <= MAX_YUV_MERGE_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun observeFps() {
        val now = System.nanoTime()
        if (lastFpsWindowStartNs == 0L) lastFpsWindowStartNs = now
        framesInWindow++
        if (now - lastFpsWindowStartNs >= FPS_WINDOW_NS) {
            rawFrameFps = framesInWindow * 1_000_000_000.0 / (now - lastFpsWindowStartNs)
            lastFpsWindowStartNs = now
            framesInWindow = 0
        }
    }

    private fun rankSharpness(image: Image): Double = when (captureFormat) {
        CaptureFormat.RAW_BAYER -> rankSharpnessBayer(image)
        CaptureFormat.YUV -> rankSharpnessY(image)
    }

    /**
     * Bayer-green Laplacian proxy: squared 4-neighbour differences of green
     * pixels at stride. Same-channel sampling is safe because stepping ±2
     * preserves Bayer parity, so each neighbor is green too.
     */
    private fun rankSharpnessBayer(image: Image): Double {
        val cfa = characteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 3
        val plane = image.planes.firstOrNull() ?: return 0.0
        val rowStride = plane.rowStride / 2 // short units
        val w = image.width
        val h = image.height
        val buffer = plane.buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val shorts = buffer.asShortBuffer()

        val gOffsets = when (cfa) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> arrayOf(0 to 1, 1 to 0)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> arrayOf(0 to 0, 1 to 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> arrayOf(0 to 0, 1 to 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> arrayOf(0 to 1, 1 to 0)
            else -> arrayOf(0 to 0, 1 to 1)
        }
        val isGreen: (Int, Int) -> Boolean = { x, y ->
            val parity = (x and 1) to (y and 1)
            parity == gOffsets[0] || parity == gOffsets[1]
        }

        var sum = 0.0
        var count = 0
        var y = 2
        while (y < h - 2) {
            var x = 2
            while (x < w - 2) {
                if (isGreen(x, y)) {
                    val rowBase = y * rowStride
                    val center = shorts[rowBase + x]
                    val left = shorts[rowBase + x - 2]
                    val right = shorts[rowBase + x + 2]
                    val up = shorts[rowBase - rowStride * 2 + x]
                    val down = shorts[rowBase + rowStride * 2 + x]
                    val lap = (center - left).toDouble() + (center - right) +
                        (center - up) + (center - down)
                    sum += lap * lap
                    count++
                }
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return if (count == 0) 0.0 else sum / count
    }

    /**
     * Y-plane Laplacian proxy for the fallback path: every pixel is luma, so
     * no Bayer parity bookkeeping is needed. Same ±2 sampling as the Bayer
     * variant keeps the noise-correlation scale comparable.
     */
    private fun rankSharpnessY(image: Image): Double {
        val plane = image.planes.firstOrNull() ?: return 0.0
        val rowStride = plane.rowStride
        val pxStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val buffer = plane.buffer.duplicate()

        var sum = 0.0
        var count = 0
        var y = 2
        while (y < h - 2) {
            var x = 2
            while (x < w - 2) {
                val base = y * rowStride + x * pxStride
                val center = buffer.get(base).toInt() and 0xFF
                val left = buffer.get(base - 2 * pxStride).toInt() and 0xFF
                val right = buffer.get(base + 2 * pxStride).toInt() and 0xFF
                val up = buffer.get(base - 2 * rowStride).toInt() and 0xFF
                val down = buffer.get(base + 2 * rowStride).toInt() and 0xFF
                val lap = (center - left).toDouble() + (center - right) +
                    (center - up) + (center - down)
                sum += lap * lap
                count++
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return if (count == 0) 0.0 else sum / count
    }

    companion object {
        const val ZSL_DEPTH = 4
        const val MAX_TRACKED_RESULTS = 12
        const val SETTLE_MS = 250L
        const val SAMPLE_STRIDE = 8
        private const val FPS_WINDOW_NS = 2_000_000_000L

        /** Cap the YUV fallback merge so low-end SoCs stay interactive (~8 MP). */
        const val MAX_YUV_MERGE_PIXELS = 8_400_000L

        /** High-res sensors eat hundreds of MB per frame; shrink the queue instead. */
        fun queueDepthFor(rawSize: Size): Int {
            val megaBytesPerFrame = rawSize.width.toLong() * rawSize.height * 2L / (1024 * 1024)
            return when {
                megaBytesPerFrame >= 60 -> 2
                megaBytesPerFrame >= 25 -> 3
                else -> ZSL_DEPTH
            }
        }
    }
}
