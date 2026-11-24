package com.fixlocal.android.data.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.fixlocal.android.data.model.ModelOption
import java.io.File
import java.util.Locale

data class DeviceSpecs(
    val manufacturer: String,
    val model: String,
    val totalRamGb: Double,
    val availableRamGb: Double,
    val cpuCores: Int,
    val hasOpenClDriver: Boolean,
    val isGpuSupported: Boolean,
    val gpuDisabledReason: String?,
    val recommendedModel: ModelOption
)

class DeviceCapabilityManager(private val context: Context) {

    fun getDeviceSpecs(): DeviceSpecs {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val cpuCores = Runtime.getRuntime().availableProcessors()

        val openClPaths = listOf(
            "/system/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/lib/libOpenCL.so"
        )
        val hasOpenClDriver = openClPaths.any { File(it).exists() }

        // GPU Acceleration Guardrail:
        // Budget chipsets / devices with < 4GB total RAM suffer memory faults or driver timeouts with GPU delegates.
        val (isGpuSupported, gpuDisabledReason) = when {
            totalRamGb < 4.0 -> {
                false to "Disabled: Device has < 4GB RAM. CPU mode is required for system stability."
            }
            !hasOpenClDriver -> {
                false to "Disabled: OpenCL GPU driver not found on this device chipset."
            }
            else -> {
                true to null
            }
        }

        // Model Recommendation based on hardware capacity:
        val recommendedModel = when {
            totalRamGb < 5.0 -> ModelOption.GEMMA3_270M
            else -> ModelOption.QWEN3_0_6B_Q4
        }

        return DeviceSpecs(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            totalRamGb = totalRamGb,
            availableRamGb = availRamGb,
            cpuCores = cpuCores,
            hasOpenClDriver = hasOpenClDriver,
            isGpuSupported = isGpuSupported,
            gpuDisabledReason = gpuDisabledReason,
            recommendedModel = recommendedModel
        )
    }

    companion object {
        /**
         * Determines if a specific model option is capable of running on this device.
         * Returns a pair of (isCapable, failureReason)
         */
        fun checkModelCapability(option: ModelOption, specs: DeviceSpecs): Pair<Boolean, String?> {
            return when (option) {
                ModelOption.GEMMA3_270M -> {
                    // Ultra-lightweight: runs on any phone with >= 2GB RAM
                    true to null
                }
                ModelOption.QWEN3_0_6B_Q4 -> {
                    if (specs.totalRamGb < 3.5) {
                        false to "Requires at least 4 GB RAM (Device: ${String.format(Locale.US, "%.1f", specs.totalRamGb)} GB)"
                    } else {
                        true to null
                    }
                }
            }
        }
    }

    fun checkModelCapability(option: ModelOption, specs: DeviceSpecs): Pair<Boolean, String?> {
        return Companion.checkModelCapability(option, specs)
    }
}
