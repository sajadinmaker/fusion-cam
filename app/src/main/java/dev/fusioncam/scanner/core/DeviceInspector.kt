package dev.fusioncam.scanner.core

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import dev.fusioncam.scanner.model.CameraProfile
import dev.fusioncam.scanner.model.DeviceProfile
import dev.fusioncam.scanner.model.ScanResult

/** Scans the whole device: cameras + RAM + thermal + SoC, and scores each camera. */
object DeviceInspector {

    private const val RAM_BONUS_MAX = 5

    fun scan(context: Context): ScanResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ramGb = ramGb(context)

        val profiles: List<CameraProfile> = buildList {
            for (id in manager.cameraIdList) {
                // Virtual/logical cameras can throw on getCameraCharacteristics; skip those.
                val characteristics = runCatching { manager.getCameraCharacteristics(id) }
                    .getOrNull() ?: continue
                val facts = CameraIntrospector.inspect(characteristics)
                add(CameraIntrospector.toProfile(id, facts).withRamBonus(ramBonus(ramGb), ramGb))
            }
        }

        val device = DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            socName = socName(),
            ramGb = ramGb,
            maxRamSinglePhotoBudgetMb = photoBudgetMb(ramGb),
            thermalStatus = thermalStatus(context),
            indexOfBestCamera = profiles.indices.maxByOrNull { profiles[it].score } ?: -1,
        )
        return ScanResult(device, profiles)
    }

    fun ramBonus(ramGb: Double): Int = when {
        ramGb >= 8.0 -> RAM_BONUS_MAX
        ramGb >= 6.0 -> 4
        ramGb >= 4.0 -> 2
        else -> 0
    }

    /** Bytes a single HDR+ style burst may hold in memory (rough guard). */
    fun photoBudgetMb(ramGb: Double): Int = when {
        ramGb >= 12.0 -> 900
        ramGb >= 8.0 -> 600
        ramGb >= 6.0 -> 400
        else -> 250
    }

    private fun CameraProfile.withRamBonus(bonus: Int, deviceRamGb: Double): CameraProfile =
        if (bonus == 0) this else copy(
            score = score + bonus,
            scoreReasons = scoreReasons +
                "+$bonus RAM bonus (" + "%.1fGB device memory".format(deviceRamGb) + ")"
        )

    private fun ramGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= 31) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE // closest available pre-31
        }

    private fun thermalStatus(context: Context): String =
        if (Build.VERSION.SDK_INT >= 28) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                0 -> "NONE"
                1 -> "LIGHT"
                2 -> "MODERATE"
                3 -> "SEVERE"
                4 -> "CRITICAL"
                5 -> "EMERGENCY"
                6 -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else "N/A (< API 28)"
}
