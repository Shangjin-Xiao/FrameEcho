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
}
