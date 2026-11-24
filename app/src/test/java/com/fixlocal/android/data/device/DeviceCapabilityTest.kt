package com.fixlocal.android.data.device

import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.model.ModelOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {

    private fun createDummySpecs(totalRamGb: Double): DeviceSpecs {
        return DeviceSpecs(
            manufacturer = "Samsung",
            model = "Galaxy A17",
            totalRamGb = totalRamGb,
            availableRamGb = totalRamGb * 0.5,
            cpuCores = 8,
            hasOpenClDriver = true,
            isGpuSupported = totalRamGb >= 4.0,
            gpuDisabledReason = if (totalRamGb < 4.0) "Disabled: Device has < 4GB RAM." else null,
            recommendedModel = when {
                totalRamGb < 5.0 -> ModelOption.GEMMA3_270M
                else -> ModelOption.QWEN3_0_6B_Q4
            }
        )
    }

    @Test
    fun testFixToneFromName() {
        assertEquals(FixTone.NATURAL, FixTone.fromName("NATURAL"))
        assertEquals(FixTone.CASUAL, FixTone.fromName("casual"))
        assertEquals(FixTone.PROFESSIONAL, FixTone.fromName("Professional"))
        assertEquals(FixTone.CUSTOM, FixTone.fromName("custom"))
        assertEquals(FixTone.NATURAL, FixTone.fromName("UNKNOWN_TONE"))
        assertEquals(FixTone.NATURAL, FixTone.fromName(null))
    }

    @Test
    fun testLowRamDeviceCapabilities() {
        // Budget device with 2.5 GB RAM
        val specs = createDummySpecs(2.5)

        // Gemma 3 270M should be capable
        val (capGemma, reasonGemma) = DeviceCapabilityManager.checkModelCapability(ModelOption.GEMMA3_270M, specs)
        assertTrue(capGemma)
        assertNull(reasonGemma)

        // Qwen3 0.6B Q4 should be disabled (needs 3.5GB)
        val (capQwen, reasonQwen) = DeviceCapabilityManager.checkModelCapability(ModelOption.QWEN3_0_6B_Q4, specs)
        assertFalse(capQwen)
        assertNotNull(reasonQwen)

        assertEquals(ModelOption.GEMMA3_270M, specs.recommendedModel)
        assertFalse(specs.isGpuSupported)
    }

    @Test
    fun testHighEndDeviceCapabilities() {
        // High-end / 8GB RAM device
        val specs = createDummySpecs(7.4)

        // Both models capable
        assertTrue(DeviceCapabilityManager.checkModelCapability(ModelOption.GEMMA3_270M, specs).first)
        assertTrue(DeviceCapabilityManager.checkModelCapability(ModelOption.QWEN3_0_6B_Q4, specs).first)

        assertEquals(ModelOption.QWEN3_0_6B_Q4, specs.recommendedModel)
        assertTrue(specs.isGpuSupported)
    }
}
