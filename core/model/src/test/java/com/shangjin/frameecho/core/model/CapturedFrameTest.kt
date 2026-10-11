package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for CapturedFrame model.
 */
class CapturedFrameTest {

    @Test
    fun `default values for format and quality are correct`() {
        val frame = CapturedFrame(
            timestampUs = 1_000_000L,
            width = 1920,
            height = 1080,
            colorSpace = ColorSpaceInfo(),
            metadata = VideoMetadata()
        )

        assertEquals(1_000_000L, frame.timestampUs)
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
        assertEquals(ColorSpaceInfo(), frame.colorSpace)
        assertEquals(VideoMetadata(), frame.metadata)
        assertEquals(ExportFormat.JPEG, frame.format)
        assertEquals(100, frame.quality)
    }

    @Test
    fun `custom format and quality are preserved`() {
        val frame = CapturedFrame(
            timestampUs = 2_000_000L,
            width = 3840,
            height = 2160,
            colorSpace = ColorSpaceInfo(type = ColorSpaceType.HDR10),
            metadata = VideoMetadata(make = "Google", model = "Pixel"),
            format = ExportFormat.JPEG,
            quality = 90
        )

        assertEquals(2_000_000L, frame.timestampUs)
        assertEquals(3840, frame.width)
        assertEquals(2160, frame.height)
        assertEquals(ColorSpaceType.HDR10, frame.colorSpace.type)
        assertEquals("Google", frame.metadata.make)
        assertEquals(ExportFormat.JPEG, frame.format)
        assertEquals(90, frame.quality)
    }

    @Test
    fun `copy creates equal copy or modified instance`() {
        val frame = CapturedFrame(
            timestampUs = 1_000_000L,
            width = 1920,
            height = 1080,
            colorSpace = ColorSpaceInfo(),
            metadata = VideoMetadata()
        )

        val identicalCopy = frame.copy()
        assertEquals(frame, identicalCopy)
        assertEquals(frame.hashCode(), identicalCopy.hashCode())

        val modifiedCopy = frame.copy(quality = 80)
        assertEquals(80, modifiedCopy.quality)
        assertNotEquals(frame, modifiedCopy)
    }
}
