package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ColorSpaceInfo model.
 */
class ColorSpaceInfoTest {

    @Test
    fun `SDR color space is not HDR`() {
        val sdr = ColorSpaceInfo(type = ColorSpaceType.SDR)
        assertFalse(sdr.isHdr)
    }

    @Test
    fun `HDR10 color space is HDR`() {
        val hdr10 = ColorSpaceInfo(type = ColorSpaceType.HDR10)
        assertTrue(hdr10.isHdr)
    }

    @Test
    fun `HDR10 Plus color space is HDR`() {
        val hdr10Plus = ColorSpaceInfo(type = ColorSpaceType.HDR10_PLUS)
        assertTrue(hdr10Plus.isHdr)
    }

    @Test
    fun `HLG color space is HDR`() {
        val hlg = ColorSpaceInfo(type = ColorSpaceType.HLG)
        assertTrue(hlg.isHdr)
    }

    @Test
    fun `Dolby Vision color space is HDR`() {
        val dv = ColorSpaceInfo(type = ColorSpaceType.DOLBY_VISION)
        assertTrue(dv.isHdr)
    }

    @Test
    fun `default color space is SDR with sRGB`() {
        val default = ColorSpaceInfo()
        assertEquals(ColorSpaceType.SDR, default.type)
        assertEquals(TransferFunction.SRGB, default.transferFunction)
        assertEquals(ColorGamut.BT709, default.colorGamut)
        assertEquals(8, default.bitDepth)
        assertFalse(default.hasStaticHdrMetadata)
        assertFalse(default.hasDynamicHdrMetadata)
    }
}
