package dev.fusioncam.scanner.core

import android.hardware.camera2.CaptureResult
import android.util.Pair
import org.json.JSONArray
import org.json.JSONObject

/** Per-frame capture metadata, extracted from a [CaptureResult]. */
data class FrameMeta(
    val timestampNs: Long,
    val exposureNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    val aeState: Int?,
    val afState: Int?,
    val lensAperture: Float?,
    val neutralColorPoint: List<Double>?,
    val noiseProfile: List<Pair<Double, Double>>?,
    val dynamicBlackLevel: List<Double>?,
    val colorCorrectionTransform: List<List<Double>>?, // 3x3 CCM
    val focusDistance: Float?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sensor_timestamp_ns", timestampNs)
        put("exposure_ns", exposureNs ?: JSONObject.NULL)
        put("iso", iso ?: JSONObject.NULL)
        put("frame_duration_ns", frameDurationNs ?: JSONObject.NULL)
        put("ae_state", aeState ?: JSONObject.NULL)
        put("af_state", afState ?: JSONObject.NULL)
        put("lens_aperture", lensAperture ?: JSONObject.NULL)
        put("neutral_color_point", rationalList(neutralColorPoint))
        put("noise_profile", noiseProfile?.let { profile ->
            JSONArray().apply { profile.forEach { put(JSONArray(listOf(it.first, it.second))) } }
        } ?: JSONObject.NULL)
        put("dynamic_black_level", dynamicBlackLevel?.let { JSONArray(it) } ?: JSONObject.NULL)
        put("color_correction_transform", colorCorrectionTransform?.let { ccm ->
            JSONArray().apply { ccm.forEach { row -> put(JSONArray(row)) } }
        } ?: JSONObject.NULL)
        put("focus_distance", focusDistance ?: JSONObject.NULL)
    }

    companion object {
        /** Pull everything the future fusion stages will need out of one result. */
        fun from(result: CaptureResult): FrameMeta = FrameMeta(
            timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
            aeState = result.get(CaptureResult.CONTROL_AE_STATE),
            afState = result.get(CaptureResult.CONTROL_AF_STATE),
            lensAperture = result.get(CaptureResult.LENS_APERTURE),
            neutralColorPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                ?.map { it.toDouble() },
            noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.toList(),
            dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                ?.map { it.toDouble() },
            colorCorrectionTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                ?.let { t -> (0..2).map { r -> (0..2).map { c -> t.getElement(c, r).toDouble() } } },
            focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        )

        private fun rationalList(values: List<Double>?) =
            values?.let { JSONArray(it) } ?: JSONObject.NULL
    }
}
