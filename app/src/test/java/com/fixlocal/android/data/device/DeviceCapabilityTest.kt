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
                totalRamGb < 4.0 -> ModelOption.SMOLLM2_135M
                totalRamGb < 6.0 -> ModelOption.SMOLLM2_360M
                else -> ModelOption.QWEN3_0_6B
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

        // SmolLM2 135M should be capable
        val (cap135, reason135) = DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_135M, specs)
        assertTrue(cap135)
        assertNull(reason135)

        // SmolLM2 360M should be disabled
        val (cap360, reason360) = DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_360M, specs)
        assertFalse(cap360)
        assertNotNull(reason360)

        // Qwen3 0.6B should be disabled
        val (capQwen, reasonQwen) = DeviceCapabilityManager.checkModelCapability(ModelOption.QWEN3_0_6B, specs)
        assertFalse(capQwen)
        assertNotNull(reasonQwen)

        assertEquals(ModelOption.SMOLLM2_135M, specs.recommendedModel)
        assertFalse(specs.isGpuSupported)
    }

    @Test
    fun testMidRangeDeviceCapabilities() {
        // Mid-range device with 4.0 GB RAM
        val specs = createDummySpecs(4.0)

        // SmolLM2 135M should be capable
        val (cap135, _) = DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_135M, specs)
        assertTrue(cap135)

        // SmolLM2 360M should be capable
        val (cap360, _) = DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_360M, specs)
        assertTrue(cap360)

        // Qwen3 0.6B should be disabled (needs 6GB)
        val (capQwen, reasonQwen) = DeviceCapabilityManager.checkModelCapability(ModelOption.QWEN3_0_6B, specs)
        assertFalse(capQwen)
        assertNotNull(reasonQwen)

        assertEquals(ModelOption.SMOLLM2_360M, specs.recommendedModel)
        assertTrue(specs.isGpuSupported)
    }

    @Test
    fun testHighEndDeviceCapabilities() {
        // High-end / 8GB RAM device (like Galaxy A17 8GB model)
        val specs = createDummySpecs(7.4)

        // All models capable
        assertTrue(DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_135M, specs).first)
        assertTrue(DeviceCapabilityManager.checkModelCapability(ModelOption.SMOLLM2_360M, specs).first)
        assertTrue(DeviceCapabilityManager.checkModelCapability(ModelOption.QWEN3_0_6B, specs).first)

        assertEquals(ModelOption.QWEN3_0_6B, specs.recommendedModel)
        assertTrue(specs.isGpuSupported)
    }
}
