package dev.fusioncam.scanner.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.SurfaceHolder
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
        private const val PERMISSION_REQUEST = 42
    }

    private var engine: CaptureEngine? = null
    private var savedThisLatch = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var cameraIdOverride: String? = null

    private lateinit var status: android.widget.TextView
    private lateinit var surfaceView: SurfaceView
    private var engineStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraIdOverride = intent.getStringExtra(EXTRA_CAMERA_ID)

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
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {
                    // Engine needs the surface only once; start is idempotent.
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

        val shutter = android.widget.Button(this).apply {
            text = "Latch sharpest RAW frame"
        }
        column.addView(shutter, rowParams(top = dp(12)))
        shutter.setOnClickListener {
            savedThisLatch = false
            engine?.latch()
        }

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
        engine?.start(resolveCameraId(), engineSurface)
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
        ioExecutor.execute {
            try {
                run.mkdirs()
                val result = best.result
                if (result == null) {
                    onStatus("No CaptureResult paired with best frame; DNG skipped")
                } else {
                    FileOutputStream(File(run, "best.dng")).use { out ->
                        android.hardware.camera2.DngCreator(characteristics, result)
                            .writeImage(out, best.image)
                    }
                }
                JSONObject().apply {
                    put("camera_id", engine?.bestCameraId ?: JSONObject.NULL)
                    put("characteristics_hint", JSONObject.NULL)
                    put("frames", org.json.JSONArray().apply {
                        frames.forEach { f ->
                            put(JSONObject().apply {
                                put("meta", f.meta.toJson())
                                put("sharpness", f.sharpness)
                                put("is_best", f === best)
                            })
                        }
                    })
                }.also { meta ->
                    File(run, "frames.json").writeText(meta.toString(2))
                }
                onStatus("Saved → $run")
            } catch (t: Throwable) {
                onStatus("Save failed: ${t.message}")
            } finally {
                frames.forEach { runCatching { it.image.close() } }
                runOnUiThread { engine?.afterLatch() }
            }
        }
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
