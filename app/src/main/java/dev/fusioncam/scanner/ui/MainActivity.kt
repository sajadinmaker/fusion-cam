package dev.fusioncam.scanner.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import dev.fusioncam.scanner.core.DeviceInspector
import dev.fusioncam.scanner.core.toJson
import dev.fusioncam.scanner.model.ScanResult
import dev.fusioncam.scanner.model.Tier
import java.io.File
import java.util.concurrent.Executors

/**
 * Phase 0 capability scanner UI. Programmatic views only — the tool is meant
 * to run on every phone in the target device matrix with zero extra deps.
 */
class MainActivity : Activity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusLine: android.widget.TextView
    private lateinit var container: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.ScrollView(this).apply {
            setFillViewport(true)
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(column)

        val title = android.widget.TextView(this).apply {
            text = "Fusion Cam · Phase 0\nDevice capability matrix"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        column.addView(title)

        val scanButton = android.widget.Button(this).apply {
            text = "Scan this device"
            setOnClickListener { startScan() }
        }
        column.addView(scanButton, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(16) })

        statusLine = android.widget.TextView(this).apply { text = "Idle." }
        column.addView(statusLine)

        container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        column.addView(container, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(12) })

        setContentView(root)
    }

    private fun startScan() {
        statusLine.text = "Scanning…"
        container.removeAllViews()
        ioExecutor.execute {
            val result = try {
                DeviceInspector.scan(this)
            } catch (t: Throwable) {
                runOnUiThread { statusLine.text = "Scan failed: ${t.message}" }
                return@execute
            }
            runOnUiThread { render(result) }
        }
    }

    private fun render(result: ScanResult) {
        val best = result.bestCamera
        val bestTier = result.bestTier
        statusLine.text = buildString {
            appendLine("${result.device.manufacturer} ${result.device.model} · Android ${result.device.sdkInt} · ${result.device.socName}")
            appendLine("RAM %.1f GB · burst budget ~%d MB/photo · thermal %s".format(
                result.device.ramGb, result.device.maxRamSinglePhotoBudgetMb, result.device.thermalStatus))
            if (best != null && bestTier != null) {
                appendLine("Best camera: #${best.cameraId} (${best.facingName}) → tier ${bestTier.name} · score ${best.score}")
            } else {
                appendLine("No usable cameras found.")
            }
        }

        result.cameras.forEach { cam ->
            val tier = Tier.fromScore(cam.score)
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.parseColor(tierBackgroundHex(tier)))
            }
            val titleLine = android.widget.TextView(this).apply {
                text = "Camera #${cam.cameraId} · ${cam.facingName} · score ${cam.score} · ${tier.label}"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(titleLine)

            val stats = android.widget.TextView(this).apply {
                textSize = 13f
                text = buildString {
                    appendLine("HW level: ${cam.hardwareLevelName}  · RAW: ${cam.rawOutputSupported} (${cam.largestRawSize ?: "n/a"}, %.1f fps)".format(cam.rawMaxFps))
                    appendLine("Manual sensor: ${cam.manualSensorCapability} · Burst: ${cam.burstCaptureCapability} · OIS: ${cam.oisSupported}")
                    appendLine("Per-frame exposure scripting: ${cam.perFrameExposureControl} · Max analog ISO: ${cam.maxAnalogIso}")
                    appendLine("Sensor: %.1f MP active / %.1f MP array · %.1f mm² · CFA ${cam.colorFilterArrangement}".format(
                        cam.activeArrayMegaPixels, cam.pixelArrayMegaPixels, cam.sensorPhysicalAreaMm2))
                }
            }
            card.addView(stats)

            val reasons = android.widget.TextView(this).apply {
                textSize = 12f
                movementMethod = ScrollingMovementMethod()
                text = cam.scoreReasons.joinToString("\n")
            }
            card.addView(reasons)

            val params = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            container.addView(card, params)
        }

        // Report file location for the tuning database.
        try {
            val dir = File(getExternalFilesDir(null), "reports").apply { mkdirs() }
            val file = File(dir, "capabilities-${System.currentTimeMillis()}.json")
            file.writeText(result.toJson().toString(2))
            val banner = android.widget.TextView(this).apply {
                text = "\nFull JSON report saved to:\n${file.absolutePath}"
                textSize = 12f
                setTextColor(Color.rgb(20, 70, 120))
            }
            container.addView(banner)
        } catch (t: Throwable) {
            // Storage unavailable; on-screen report is still authoritative.
        }
    }

    private fun tierBackgroundHex(tier: Tier): String = when (tier) {
        Tier.A -> "#E3F4E6"
        Tier.B -> "#E8F1F8"
        Tier.C -> "#FBF3DF"
        Tier.D -> "#F8E2E2"
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
