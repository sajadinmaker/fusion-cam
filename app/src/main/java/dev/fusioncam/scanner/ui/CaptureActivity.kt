package dev.fusioncam.scanner.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import dev.fusioncam.scanner.capture.CaptureEngine
import dev.fusioncam.scanner.core.toJson
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Phase 1 capture skeleton. Opens the best Tier-A/B camera, streams preview+
 * RAW ZSL, and on latch saves the sharpest frame as DNG plus full metadata.
 */
class CaptureActivity : Activity(), CaptureEngine.Listener {

    companion object {
        const val EXTRA_CAMERA_ID = "cameraId"

        /**
         * Debug test hook: forces the YUV fallback path even when RAW_SENSOR
         * exists, so RAW-less-device plumbing can be exercised on devices that
         * have RAW. Reachable only via adb on exported debug builds; production
         * code paths never set it.
         */
        const val EXTRA_FORCE_YUV = "forceYuv"
        private const val PERMISSION_REQUEST = 42

        private enum class Mode { SAVE_DNG, MERGE_BURST }
    }

    private var engine: CaptureEngine? = null
    private var savedThisLatch = false
    private var pendingMode: Mode = Mode.SAVE_DNG
    private var mergedPreviewView: android.widget.ImageView? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var cameraIdOverride: String? = null
    private var engineForceYuv = false

    private lateinit var status: android.widget.TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var shutterButton: android.widget.Button
    private lateinit var mergeButton: android.widget.Button
    private var engineStarted = false
    private var lastStartedSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraIdOverride = intent.getStringExtra(EXTRA_CAMERA_ID)
        engineForceYuv = intent.getBooleanExtra(EXTRA_FORCE_YUV, false)

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        status = android.widget.TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
        }
        column.addView(status, rowParams(top = dp(12)))

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    maybeStartEngine(holder)
                    lastStartedSurface = holder.surface
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {
                    // Layout passes may recreate the native Surface after
                    // surfaceCreated; a session holding the old (abandoned)
                    // object dies. Restart against the fresh surface — start
                    // is idempotent for identical surfaces.
                    if (holder.surface?.isValid != true) return
                    if (holder.surface != lastStartedSurface) {
                        engine?.release()
                        engine = null
                        engineStarted = false
                        maybeStartEngine(holder)
                        lastStartedSurface = holder.surface
                    }
                }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    engine?.release()
                    engineStarted = false
                }
            })
        }
        // TODO: pick aspect from supported preview sizes rather than hardcoding 3:4.
        surfaceView.layoutParams = rowParams(top = dp(12)).apply {
            width = dp(280)
            height = dp(373)
        }
        column.addView(surfaceView)

        shutterButton = android.widget.Button(this).apply {
            text = "Phase 1: save sharpest RAW frame"
        }
        column.addView(shutterButton, rowParams(top = dp(12)))
        shutterButton.setOnClickListener {
            pendingMode = Mode.SAVE_DNG
            engine?.latch()
        }

        mergeButton = android.widget.Button(this).apply {
            text = "Phase 2: HDR+ merge burst"
        }
        column.addView(mergeButton, rowParams(top = dp(8)))
        mergeButton.setOnClickListener {
            pendingMode = Mode.MERGE_BURST
            engine?.latch()
        }

        val mergedPreview = android.widget.ImageView(this).apply { adjustViewBounds = true }
        column.addView(mergedPreview, rowParams(top = dp(12)))
        mergedPreviewView = mergedPreview

        setContentView(android.widget.ScrollView(this).apply {
            setFillViewport(true)
            addView(column)
        })

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST)
        } else {
            maybeStartEngine(null)
        }
    }

    private fun maybeStartEngine(holder: android.view.SurfaceHolder?) {
        if (engineStarted) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val engineSurface: SurfaceHolder = holder ?: surfaceView.holder
        engineStarted = true
        engine = engine ?: CaptureEngine(applicationContext, this)
        engine?.start(resolveCameraId(), engineSurface, forceYuv = engineForceYuv)
        // captureFormat is decided synchronously inside start(); keep the
        // buttons honest on cameras without RAW_SENSOR.
        val raw = engine?.captureFormat == CaptureEngine.CaptureFormat.RAW_BAYER
        shutterButton.text =
            if (raw) "Phase 1: save sharpest RAW frame" else "Save photo (YUV — no RAW_SENSOR)"
        mergeButton.text =
            if (raw) "Phase 2: HDR+ merge burst" else "Phase 2: HDR+ merge burst (YUV)"
    }

    private fun resolveCameraId(): String {
        cameraIdOverride?.let { return it }
        val manager = getSystemService(android.content.Context.CAMERA_SERVICE)
            as android.hardware.camera2.CameraManager
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        } ?: "0"
    }

    // --- CaptureEngine.Listener -------------------------------------------------

    override fun onStatus(text: String) {
        runOnUiThread { status.append("\n$text") }
    }

    override fun onFramesLatched(frames: List<CaptureEngine.LatchedFrame>, characteristics: CameraCharacteristics) {
        val best = frames.firstOrNull() ?: return
        val run = File(getExternalFilesDir(null), "phase1/${System.currentTimeMillis()}")
        if (savedThisLatch) return
        savedThisLatch = true
        val mode = pendingMode
        ioExecutor.execute {
            try {
                run.mkdirs()
                val yuvMode = engine?.captureFormat == CaptureEngine.CaptureFormat.YUV
                when (mode) {
                    Mode.SAVE_DNG ->
                        if (yuvMode) saveYuvFrame(frames, run)
                        else saveDng(frames, characteristics, run, best)
                    Mode.MERGE_BURST ->
                        if (yuvMode) mergeYuvOnIo(frames, run, best)
                        else mergeBurstOnIo(frames, characteristics, run, best)
                }
            } catch (t: Throwable) {
                onStatus("$mode failed: ${t.message}\n${android.util.Log.getStackTraceString(t)}")
            } finally {
                frames.forEach { runCatching { it.image.close() } }
                runOnUiThread {
                    engine?.afterLatch()
                    savedThisLatch = false
                }
            }
        }
    }

    private fun saveDng(
        frames: List<CaptureEngine.LatchedFrame>,
        characteristics: CameraCharacteristics,
        run: File,
        best: CaptureEngine.LatchedFrame,
    ) {
        val result = best.result
        if (result == null) {
            onStatus("No CaptureResult paired with best frame; DNG skipped")
        } else {
            FileOutputStream(File(run, "best.dng")).use { out ->
                android.hardware.camera2.DngCreator(characteristics, result)
                    .writeImage(out, best.image)
            }
        }
        writeFramesJson(frames, run)
        onStatus("Saved → $run")
    }

    private fun mergeBurstOnIo(
        frames: List<CaptureEngine.LatchedFrame>,
        characteristics: CameraCharacteristics,
        run: File,
        best: CaptureEngine.LatchedFrame,
    ) {
        writeFramesJson(frames, run)
        val first = frames.firstOrNull() ?: return
        val w = first.image.width
        val h = first.image.height
        val planeBytes = frames.map { dev.fusioncam.scanner.fusion.FusionNative.raw16Bytes(it.image) }
        val refIndex = frames.indexOf(best).let { if (it < 0) 0 else it }

        val blackLevelPattern = characteristics
            .get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevels = IntArray(4).also { blackLevelPattern?.copyTo(it, 0) }
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 4095
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: 0

        // White balance gains from the reference frame's CaptureResult
        // (RggbChannelVector -> R, mean-of-greens, B).
        val wbGains = best.result
            ?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?.let { rgbv -> floatArrayOf(rgbv.red, (rgbv.greenEven + rgbv.greenOdd) / 2f, rgbv.blue) }
            ?: floatArrayOf(1f, 1f, 1f)

        // Noise floor scales with the reference frame's ISO (variance grows
        // with analog gain); floored at 1x so the model never under-merges at
        // base ISO.
        val iso = best.result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val tauScale = maxOf(1f, iso / 100f)

        onStatus("Merging ${planeBytes.size} frames (${w}x$h, cfa=$cfa, " +
            "black=[${blackLevels.joinToString()}], white=$whiteLevel, " +
            "wb=[${wbGains.joinToString { "%.2f".format(it) }}], iso=$iso)…")
        val t0 = System.currentTimeMillis()
        val pixels = dev.fusioncam.scanner.fusion.FusionNative
            .mergeBurst(planeBytes.toTypedArray(), refIndex, w, h, cfa,
                        blackLevels, whiteLevel, wbGains, tauScale)
        val ms = System.currentTimeMillis() - t0

        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val saved = finishAndSave(bitmap, run, "merged.jpg")
        runOnUiThread { mergedPreviewView?.setImageBitmap(thumbOf(saved)) }
        onStatus("Merged in ${ms}ms · saved → ${File(run, "merged.jpg").absolutePath}")
    }

    /**
     * YUV fallback merge for cameras without RAW_SENSOR (LEGACY/LIMITED
     * HALs): the native luma align+merge handles the fusion; here we compact
     * the planes, pick the sharpest frame as reference and save.
     */
    private fun mergeYuvOnIo(
        frames: List<CaptureEngine.LatchedFrame>,
        run: File,
        best: CaptureEngine.LatchedFrame,
    ) {
        writeFramesJson(frames, run)
        val first = frames.firstOrNull() ?: return
        val w = first.image.width
        val h = first.image.height
        val planeBytes = frames.map { dev.fusioncam.scanner.fusion.FusionNative.yuvBytes(it.image) }
        val refIndex = frames.indexOf(best).let { if (it < 0) 0 else it }

        val iso = best.result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val tauScale = maxOf(1f, iso / 100f)

        onStatus("Merging ${planeBytes.size} YUV frames (${w}x$h, iso=$iso)…")
        val t0 = System.currentTimeMillis()
        val pixels = dev.fusioncam.scanner.fusion.FusionNative
            .mergeBurstYuv(planeBytes.toTypedArray(), refIndex, w, h, tauScale)
        val ms = System.currentTimeMillis() - t0

        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val saved = finishAndSave(bitmap, run, "merged.jpg")
        runOnUiThread { mergedPreviewView?.setImageBitmap(thumbOf(saved)) }
        onStatus("Merged in ${ms}ms · saved → ${File(run, "merged.jpg").absolutePath}")
    }

    /** Single-frame photo on cameras without RAW_SENSOR (YUV → JPEG). */
    private fun saveYuvFrame(frames: List<CaptureEngine.LatchedFrame>, run: File) {
        writeFramesJson(frames, run)
        val best = frames.first()
        val w = best.image.width
        val h = best.image.height
        val pixels = dev.fusioncam.scanner.fusion.FusionNative.mergeBurstYuv(
            arrayOf(dev.fusioncam.scanner.fusion.FusionNative.yuvBytes(best.image)),
            0, w, h, 1f,
        )
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        finishAndSave(bitmap, run, "photo.jpg")
        onStatus("Saved → ${File(run, "photo.jpg").absolutePath}")
    }

    /** Rotate upright for the sensor orientation, stamp the watermark, write the JPEG. */
    private fun finishAndSave(
        bitmap: android.graphics.Bitmap,
        run: File,
        name: String,
    ): android.graphics.Bitmap {
        val upright = rotateBitmap(bitmap, engine?.sensorOrientation ?: 0)
        stampWatermark(upright)
        FileOutputStream(File(run, name)).use { out ->
            upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }
        return upright
    }

    private fun thumbOf(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val displayW = dp(280)
        val displayH = maxOf(displayW * bitmap.height / bitmap.width, 1)
        return android.graphics.Bitmap.createScaledBitmap(bitmap, displayW, displayH, true)
    }

    private fun rotateBitmap(bitmap: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /**
     * Stamps every merged photo with the app watermark before it leaves the
     * app for sharing: "fusioncam(sajadinmaker)". Scales with image width and
     * carries a drop shadow so it stays legible on any scene.
     */
    private fun stampWatermark(bitmap: android.graphics.Bitmap) {
        val wm = "fusioncam(sajadinmaker)"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xC8FFFFFF.toInt() // white at ~78% opacity
            textSize = bitmap.width / 26f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setShadowLayer(bitmap.width / 180f, 0f, bitmap.width / 400f, 0xB3000000.toInt())
        }
        val pad = bitmap.width / 40f
        val canvas = android.graphics.Canvas(bitmap)
        val baseline = bitmap.height - pad - paint.fontMetrics.descent
        canvas.drawText(wm, pad, baseline, paint)
    }

    private fun writeFramesJson(frames: List<CaptureEngine.LatchedFrame>, run: File) {
        val best = frames.firstOrNull()
        JSONObject().apply {
            put("camera_id", engine?.bestCameraId ?: JSONObject.NULL)
            put("frames", org.json.JSONArray().apply {
                frames.forEach { f ->
                    put(JSONObject().apply {
                        put("meta", f.meta.toJson())
                        put("sharpness", f.sharpness)
                        put("is_reference", f === best)
                    })
                }
            })
        }.also { meta -> File(run, "frames.json").writeText(meta.toString(2)) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            maybeStartEngine(null)
        } else {
            status.append("\nCamera permission denied — cannot capture.")
        }
    }

    override fun onDestroy() {
        engine?.release()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun rowParams(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(left, top, right, bottom)
        }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
