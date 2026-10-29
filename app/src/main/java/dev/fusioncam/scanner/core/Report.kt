package dev.fusioncam.scanner.core

import dev.fusioncam.scanner.model.CameraProfile
import dev.fusioncam.scanner.model.ScanResult
import dev.fusioncam.scanner.model.Tier
import org.json.JSONArray
import org.json.JSONObject

/** Emit the Phase 0 device-matrix report as JSON for the tuning database. */
fun ScanResult.toJson(): JSONObject {
    val root = JSONObject()
    root.put("tool_version", "phase0-0.1.0")
    root.put("device", JSONObject().apply {
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("sdk", device.sdkInt)
        put("soc", device.socName)
        put("ram_gb", device.ramGb)
        put("photo_burst_budget_mb", device.maxRamSinglePhotoBudgetMb)
        put("thermal_status", device.thermalStatus)
        put("best_camera_id", bestCamera?.cameraId ?: JSONObject.NULL)
        put("best_tier", bestTier?.name ?: JSONObject.NULL)
    })

    val cams = JSONArray()
    cameras.forEach { cams.put(it.toJson()) }
    root.put("cameras", cams)
    return root
}

private fun CameraProfile.toJson(): JSONObject = JSONObject().apply {
    put("camera_id", cameraId)
    put("facing", facingName)
    put("hardware_level", hardwareLevelName)
    put("score", score)
    put("tier", Tier.fromScore(score).name)
    put("raw_output", rawOutputSupported)
    put("largest_raw_size", largestRawSize ?: JSONObject.NULL)
    put("raw_max_fps", rawMaxFps)
    put("manual_sensor", manualSensorCapability)
    put("burst_capture", burstCaptureCapability)
    put("raw_capability", rawReprocessingCapability)
    put("ois", oisSupported)
    put("max_analog_iso", maxAnalogIso)
    put("active_array_mp", activeArrayMegaPixels)
    put("pixel_array_mp", pixelArrayMegaPixels)
    put("sensor_area_mm2", sensorPhysicalAreaMm2)
    put("color_filter", colorFilterArrangement)
    put("per_frame_exposure_control", perFrameExposureControl)
    put("reasons", JSONArray(scoreReasons))
}
