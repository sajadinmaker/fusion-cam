package dev.fusioncam.scanner.model

import android.util.SizeF

enum class Tier(val label: String, val minScore: Int) {
    A("A · HDR+ full pipeline target", 85),
    B("B · Pipeline supported, needs tuning", 70),
    C("C · Degraded pipeline (fewer frames / YUV merge)", 50),
    D("D · No RAW — YUV fallback merge only", 0);

    companion object {
        fun fromScore(score: Int): Tier = when {
            score >= A.minScore -> A
            score >= B.minScore -> B
            score >= C.minScore -> C
            else -> D
        }
    }
}

/** Everything we can learn about one physical/virtual camera from Camera2. */
data class CameraProfile(
    val cameraId: String,
    val facingName: String,
    val hardwareLevelName: String,
    val hardwareLevel: Int,
    val rawOutputSupported: Boolean,
    val largestRawSize: String?,
    val rawMaxFps: Double,
    val manualSensorCapability: Boolean,
    val burstCaptureCapability: Boolean,
    val rawReprocessingCapability: Boolean,
    val oisSupported: Boolean,
    val maxAnalogIso: Int,
    val activeArrayMegaPixels: Double,
    val pixelArrayMegaPixels: Double,
    val sensorPhysicalAreaMm2: Double,
    val colorFilterArrangement: String,
    val perFrameExposureControl: Boolean,
    val score: Int,
    val scoreReasons: List<String>,
)

/** Device-level facts that bound what our engine can do. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val socName: String,
    val ramGb: Double,
    val maxRamSinglePhotoBudgetMb: Int,
    val thermalStatus: String,
    val indexOfBestCamera: Int, // index into cameras list, -1 when none
)

data class ScanResult(
    val device: DeviceProfile,
    val cameras: List<CameraProfile>,
) {
    val bestCamera: CameraProfile? get() = cameras.getOrNull(device.indexOfBestCamera)
    val bestTier: Tier? get() = bestCamera?.let { Tier.fromScore(it.score) }
}

fun facingName(facing: Int?): String = when (facing) {
    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Back"
    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
    else -> "Unknown"
}

fun megapixels(w: Int, h: Int): Double = w.toDouble() * h.toDouble() / 1_000_000.0

fun sensorAreaMm2(physical: SizeF?): Double =
    physical?.let { it.width.toDouble() * it.height.toDouble() } ?: 0.0
