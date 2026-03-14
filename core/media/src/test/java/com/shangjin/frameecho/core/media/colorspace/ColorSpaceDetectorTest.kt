package com.shangjin.frameecho.core.media.colorspace

import android.media.MediaFormat
import com.shangjin.frameecho.core.model.ColorSpaceType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSpaceDetectorTest {

    @Test
    fun `detect SDR format`() {
        val format = mockk<MediaFormat>()
        every { format.containsKey(any()) } returns false
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/avc"

        val info = ColorSpaceDetector.detect(format)
        assertEquals(ColorSpaceType.SDR, info.type)
        assertEquals(8, info.bitDepth)
    }

    @Test
    fun `detect Dolby Vision format`() {
        val format = mockk<MediaFormat>()
        every { format.containsKey(any()) } returns false
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/dolby-vision"

        // Mock transfer function to ensure 10-bit depth is detected
        every { format.containsKey("color-transfer") } returns true
        every { format.getInteger("color-transfer") } returns 6 // ST2084

        val info = ColorSpaceDetector.detect(format)
        assertEquals(ColorSpaceType.DOLBY_VISION, info.type)
        assertEquals(10, info.bitDepth)
    }

    @Test
    fun `detect HDR10 format`() {
        val format = mockk<MediaFormat>()
        every { format.containsKey(any()) } returns false
        every { format.containsKey("color-transfer") } returns true
        every { format.getInteger("color-transfer") } returns 6 // ST2084
        every { format.containsKey("color-standard") } returns true
        every { format.getInteger("color-standard") } returns 6 // BT2020
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/hevc"
        every { format.containsKey("hdr-static-info") } returns true

        val info = ColorSpaceDetector.detect(format)
        assertEquals(ColorSpaceType.HDR10, info.type)
        assertEquals(10, info.bitDepth)
    }

    @Test
    fun `detect HDR10 Plus format`() {
        val format = mockk<MediaFormat>()
        every { format.containsKey(any()) } returns false
        every { format.containsKey("color-transfer") } returns true
        every { format.getInteger("color-transfer") } returns 6 // ST2084
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/hevc"
        every { format.containsKey("hdr10-plus-info") } returns true

        val info = ColorSpaceDetector.detect(format)
        assertEquals(ColorSpaceType.HDR10_PLUS, info.type)
        assertEquals(10, info.bitDepth)
    }

    @Test
    fun `detect HLG format`() {
        val format = mockk<MediaFormat>()
        every { format.containsKey(any()) } returns false
        every { format.containsKey("color-transfer") } returns true
        every { format.getInteger("color-transfer") } returns 7 // HLG
        every { format.getString(MediaFormat.KEY_MIME) } returns "video/hevc"

        val info = ColorSpaceDetector.detect(format)
        assertEquals(ColorSpaceType.HLG, info.type)
        assertEquals(10, info.bitDepth)
    }
}
