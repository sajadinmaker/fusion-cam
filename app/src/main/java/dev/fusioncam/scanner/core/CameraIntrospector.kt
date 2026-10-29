package dev.fusioncam.scanner.core

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.SizeF
import dev.fusioncam.scanner.model.CameraProfile
import dev.fusioncam.scanner.model.facingName
import dev.fusioncam.scanner.model.megapixels
import dev.fusioncam.scanner.model.sensorAreaMm2

/**
 * Camera2-dependent inspection of one camera. Kept separate from scoring so
 * scoring can be reasoned about (and later unit-tested) without a device.
 */
object CameraIntrospector {

    data class RawFacts(
        val facingName: String,
        val hardwareLevel: Int,
        val hardwareLevelName: String,
        val rawOutputSupported: Boolean,
        val largestRawSize: String?,
        val rawMaxFps: Double,
        val manualSensor: Boolean,
        val burstCapture: Boolean,
        val rawReprocessing: Boolean,
        val ois: Boolean,
        val maxAnalogIso: Int,
        val activeArrayMegaPixels: Double,
        val pixelArrayMegaPixels: Double,
        val sensorPhysicalAreaMm2: Double,
        val colorFilterArrangement: String,
        val perFrameExposureControl: Boolean,
    )

    fun inspect(characteristics: CameraCharacteristics): RawFacts {
        val level = intOf(characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        val caps = intArrayValue(characteristics, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: IntArray(0)

        val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val burst = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
        val raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = runCatching {
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
        }.getOrNull()

        var largest: String? = null
        var maxFps = 0.0
        rawSizes?.maxByOrNull { it.width.toLong() * it.height }?.let { largestRaw ->
            val ns = streamMap?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, largestRaw)
                ?: 0L
            largest = "${largestRaw.width}x${largestRaw.height}"
            maxFps = if (ns > 0) 1_000_000_000.0 / ns else 0.0
        }

        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: active?.let { android.util.Size(it.width(), it.height()) }
        val physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val ois = intArrayValue(
            characteristics, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )?.contains(1) ?: false

        val requestKeys: List<CaptureRequest.Key<*>> = characteristics.availableCaptureRequestKeys
        val perFrameExposure = requestKeys.any {
            it == CaptureRequest.SENSOR_SENSITIVITY || it == CaptureRequest.SENSOR_EXPOSURE_TIME
        }

        val activeSize = active?.let { megapixels(it.width(), it.height()) } ?: 0.0
        val pixelSize = pixelArraySize?.let { size -> megapixels(size.width, size.height) } ?: 0.0

        return RawFacts(
            facingName = facingValue(characteristics.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = level,
            hardwareLevelName = hardwareLevelName(level),
            rawOutputSupported = !rawSizes.isNullOrEmpty(),
            largestRawSize = largest,
            rawMaxFps = maxFps,
            manualSensor = manual,
            burstCapture = burst,
            rawReprocessing = raw,
            ois = ois,
            maxAnalogIso = intOf(
                characteristics, CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY
            ) ?: 0,
            activeArrayMegaPixels = activeSize,
            pixelArrayMegaPixels = pixelSize,
            sensorPhysicalAreaMm2 = sensorAreaMm2(physical as? SizeF),
            colorFilterArrangement = colorFilterName(
                intOf(characteristics, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ),
            perFrameExposureControl = perFrameExposure,
        )
    }

    fun toProfile(id: String, facts: RawFacts): CameraProfile {
        val reasons = mutableListOf<String>()
        var score = 0

        // 1) Hardware level, max 30. LEVEL_3 is what Camera2 ZSL RAW bursts need.
        when (facts.hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> {
                score += 30; reasons += "+30 HW level 3: per-frame ZSL RAW burst control OK"
            }
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> {
                score += 25; reasons += "+25 HW level FULL: full manual control, no ZSL-level RAW timing guarantee"
            }
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> {
                score += 12; reasons += "+12 HW level LIMITED: partial manual control"
            }
            else -> reasons += "+0 HW level LEGACY/EXTERNAL: burst RAW capture unreliable"
        }

        // 2) RAW output, max 25.
        if (facts.rawOutputSupported) {
            score += 20
            reasons += "+20 RAW_SENSOR stream (${facts.largestRawSize})"
        } else {
            reasons += "+0 no RAW_SENSOR stream: cannot run frame-host-side fusion"
        }

        // 3) Burst capability, max 10.
        if (facts.burstCapture) {
            score += 8
            reasons += "+8 BURST_CAPTURE capability"
        }
        if (facts.manualSensor) {
            score += 5
            reasons += "+5 MANUAL_SENSOR capability (per-frame exposure/ISO scripting)"
        }

        // 4) RAW burst bandwidth, max 10.
        val rawFps = facts.rawMaxFps
        when {
            rawFps >= 15.0 -> { score += 10; reasons += "+10 RAW max fps %.1f (fast burst)".format(rawFps) }
            rawFps >= 5.0 -> { score += 7; reasons += "+7 RAW max fps %.1f (usable burst)".format(rawFps) }
            rawFps > 0.0 -> { score += 3; reasons += "+3 RAW max fps %.1f (slow burst)".format(rawFps) }
            else -> reasons += "+0 RAW min frame duration unknown"
        }

        // 5) OIS, max 6. Not required for fusion but big for night mode.
        if (facts.ois) { score += 6; reasons += "+6 OIS" } else reasons += "+0 no OIS"

        // 6) Sensor size, max 10. 30 mm² ≈ 1/1.5"; 46 mm² ≈ 1/1.2".
        val area = facts.sensorPhysicalAreaMm2
        when {
            area >= 40.0 -> { score += 10; reasons += "+10 sensor area %.1f mm² (large)".format(area) }
            area >= 30.0 -> { score += 8; reasons += "+8 sensor area %.1f mm² (flagship-2019-class)".format(area) }
            area >= 20.0 -> { score += 5; reasons += "+5 sensor area %.1f mm² (mid)".format(area) }
            area > 0.0 -> reasons += "+0 sensor area %.1f mm² (small)".format(area)
            else -> reasons += "+0 sensor area unknown"
        }

        // 7) RAM, max 5, from device profile; added by caller? Keep here at 0 and
        // let DeviceInspector add its device-level contribution to the best camera.
        // (Caller adjusts final score; we don't double count.)

        // 8) Quad/high-res binning hint, max 4.
        if (facts.pixelArrayMegaPixels >= 48.0) {
            score += 4
            reasons += "+4 high-res sensor (%.1f MP pixel array, binning-friendly)".format(
                facts.pixelArrayMegaPixels
            )
        }

        // 9) Required per-request exposure control: hard gate.
        if (!facts.perFrameExposureControl) {
            reasons += "HARD GATE: no per-request SENSOR_SENSITIVITY/EXPOSURE_TIME — burst bracketing impossible"
            score = minOf(score, 20)
        }

        return CameraProfile(
            cameraId = id,
            facingName = facts.facingName,
            hardwareLevelName = facts.hardwareLevelName,
            hardwareLevel = facts.hardwareLevel,
            rawOutputSupported = facts.rawOutputSupported,
            largestRawSize = facts.largestRawSize,
            rawMaxFps = rawFps,
            manualSensorCapability = facts.manualSensor,
            burstCaptureCapability = facts.burstCapture,
            rawReprocessingCapability = facts.rawReprocessing,
            oisSupported = facts.ois,
            maxAnalogIso = facts.maxAnalogIso,
            activeArrayMegaPixels = facts.activeArrayMegaPixels,
            pixelArrayMegaPixels = facts.pixelArrayMegaPixels,
            sensorPhysicalAreaMm2 = facts.sensorPhysicalAreaMm2,
            colorFilterArrangement = facts.colorFilterArrangement,
            perFrameExposureControl = facts.perFrameExposureControl,
            score = score,
            scoreReasons = reasons,
        )
    }

    private fun hardwareLevelName(level: Int): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "LEGACY"
    }

    private fun colorFilterName(cfa: Int?): String = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        else -> "UNKNOWN"
    }

    private fun facingValue(facing: Int?): String = facingName(facing)

    private fun intOf(characteristics: CameraCharacteristics, key: CameraCharacteristics.Key<Int>): Int? =
        runCatching { characteristics.get(key) }.getOrNull()

    private fun intArrayValue(
        characteristics: CameraCharacteristics,
        key: CameraCharacteristics.Key<IntArray>,
    ): IntArray? = runCatching { characteristics.get(key) }.getOrNull()

    private fun boolArrayValue(
        characteristics: CameraCharacteristics,
        key: CameraCharacteristics.Key<BooleanArray>,
    ): BooleanArray? = runCatching { characteristics.get(key) }.getOrNull()
}
