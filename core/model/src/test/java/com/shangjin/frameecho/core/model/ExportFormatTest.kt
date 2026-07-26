package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ExportFormat enum.
 */
class ExportFormatTest {

    @Test
    fun `JPEG has correct properties`() {
        assertEquals("jpg", ExportFormat.JPEG.extension)
        assertEquals("image/jpeg", ExportFormat.JPEG.mimeType)
        assertFalse(ExportFormat.JPEG.supportsAlpha)
        assertFalse(ExportFormat.JPEG.supportsHdr)
    }

    @Test
    fun `PNG supports alpha`() {
        assertTrue(ExportFormat.PNG.supportsAlpha)
        assertFalse(ExportFormat.PNG.supportsHdr)
    }

    @Test
    fun `WebP supports alpha but not HDR`() {
        assertTrue(ExportFormat.WEBP.supportsAlpha)
        assertFalse(ExportFormat.WEBP.supportsHdr)
    }

    @Test
    fun `PNG has correct extension and mimeType`() {
        assertEquals("png", ExportFormat.PNG.extension)
        assertEquals("image/png", ExportFormat.PNG.mimeType)
    }

    @Test
    fun `WEBP has correct extension and mimeType`() {
        assertEquals("webp", ExportFormat.WEBP.extension)
        assertEquals("image/webp", ExportFormat.WEBP.mimeType)
    }

    @Test
    fun `all formats have unique extensions`() {
        val extensions = ExportFormat.entries.map { it.extension }
        assertEquals(extensions.size, extensions.distinct().size)
    }

    @Test
    fun `all formats have unique mimeTypes`() {
        val mimeTypes = ExportFormat.entries.map { it.mimeType }
        assertEquals(mimeTypes.size, mimeTypes.distinct().size)
    }
}
