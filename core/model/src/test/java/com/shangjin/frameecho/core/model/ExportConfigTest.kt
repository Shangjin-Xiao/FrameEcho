package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ExportConfig model.
 */
class ExportConfigTest {

    @Test
    fun `default config has best quality`() {
        val config = ExportConfig()
        assertEquals(100, config.quality)
    }

    @Test
    fun `default config uses JPEG format`() {
        val config = ExportConfig()
        assertEquals(ExportFormat.JPEG, config.format)
    }

    @Test
    fun `default config preserves metadata`() {
        val config = ExportConfig()
        assertTrue(config.preserveMetadata)
    }

    @Test
    fun `default motion duration is 3 seconds total`() {
        val config = ExportConfig()
        assertEquals(3.0f, config.totalMotionDurationS, 0.01f)
    }

    @Test
    fun `motion duration is configurable`() {
        val config = ExportConfig(motionDurationBeforeS = 2.0f, motionDurationAfterS = 1.0f)
        assertEquals(3.0f, config.totalMotionDurationS, 0.01f)
        assertEquals(2.0f, config.motionDurationBeforeS, 0.01f)
        assertEquals(1.0f, config.motionDurationAfterS, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `excessive motion duration throws exception`() {
        ExportConfig(motionDurationAfterS = 100.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `quality below 1 throws exception`() {
        ExportConfig(quality = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `quality above 100 throws exception`() {
        ExportConfig(quality = 101)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative motion duration throws exception`() {
        ExportConfig(motionDurationBeforeS = -1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero max resolution throws exception`() {
        ExportConfig(maxResolution = 0)
    }

    @Test
    fun `null max resolution is valid`() {
        val config = ExportConfig(maxResolution = null)
        assertEquals(null, config.maxResolution)
    }

    @Test
    fun `positive max resolution is valid`() {
        val config = ExportConfig(maxResolution = 1920)
        assertEquals(1920, config.maxResolution)
    }

    @Test
    fun `HDR tone map auto is default`() {
        val config = ExportConfig()
        assertEquals(HdrToneMapStrategy.AUTO, config.hdrToneMap)
    }

    @Test
    fun `default custom file name is null`() {
        val config = ExportConfig()
        assertEquals(null, config.customFileName)
    }

    @Test
    fun `default export directory is pictures`() {
        val config = ExportConfig()
        assertEquals(ExportDirectory.PICTURES_FRAMEECHO, config.exportDirectory)
    }

    @Test
    fun `export directory entries include root directories`() {
        val entries = ExportDirectory.entries
        assertEquals(6, entries.size)
        assertEquals("Pictures", ExportDirectory.PICTURES.relativePath)
        assertEquals("DCIM", ExportDirectory.DCIM.relativePath)
        assertEquals("Movies", ExportDirectory.MOVIES.relativePath)
    }

    @Test
    fun `export directory subfolder variants have correct paths`() {
        assertEquals("Pictures/FrameEcho", ExportDirectory.PICTURES_FRAMEECHO.relativePath)
        assertEquals("DCIM/FrameEcho", ExportDirectory.DCIM_FRAMEECHO.relativePath)
        assertEquals("Movies/FrameEcho", ExportDirectory.MOVIES_FRAMEECHO.relativePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too long custom file name throws exception`() {
        ExportConfig(customFileName = "a".repeat(81))
    }

    @Test
    fun `custom file name at max length is valid`() {
        val config = ExportConfig(customFileName = "a".repeat(80))
        assertEquals("a".repeat(80), config.customFileName)
    }

    @Test
    fun `motion duration at max boundary is valid`() {
        val config = ExportConfig(motionDurationBeforeS = ExportConfig.MAX_MOTION_DURATION_S)
        assertEquals(ExportConfig.MAX_MOTION_DURATION_S, config.motionDurationBeforeS, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `motion duration slightly above max throws exception`() {
        ExportConfig(motionDurationBeforeS = ExportConfig.MAX_MOTION_DURATION_S + 0.01f)
    }

    @Test
    fun `quality at boundary 1 is valid`() {
        val config = ExportConfig(quality = 1)
        assertEquals(1, config.quality)
    }

    @Test
    fun `quality at boundary 100 is valid`() {
        val config = ExportConfig(quality = 100)
        assertEquals(100, config.quality)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative max resolution throws exception`() {
        ExportConfig(maxResolution = -1)
    }

    @Test
    fun `default muteAudio is false`() {
        val config = ExportConfig()
        assertEquals(false, config.muteAudio)
    }

    @Test
    fun `default motionPhoto is false`() {
        val config = ExportConfig()
        assertEquals(false, config.motionPhoto)
    }
}
