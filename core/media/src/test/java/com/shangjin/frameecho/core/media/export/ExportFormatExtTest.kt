package com.shangjin.frameecho.core.media.export

import android.graphics.Bitmap
import android.os.Build
import com.shangjin.frameecho.core.model.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFormatExtTest {

    @Test
    fun `JPEG maps to Bitmap CompressFormat JPEG`() {
        assertEquals(Bitmap.CompressFormat.JPEG, ExportFormat.JPEG.toCompressFormat(80))
    }

    @Test
    fun `PNG maps to Bitmap CompressFormat PNG`() {
        assertEquals(Bitmap.CompressFormat.PNG, ExportFormat.PNG.toCompressFormat(100))
    }

    @Test
    fun `HEIF maps to Bitmap CompressFormat JPEG fallback`() {
        assertEquals(Bitmap.CompressFormat.JPEG, ExportFormat.HEIF.toCompressFormat(80))
    }

    @Test
    fun `AVIF maps to Bitmap CompressFormat JPEG fallback`() {
        assertEquals(Bitmap.CompressFormat.JPEG, ExportFormat.AVIF.toCompressFormat(80))
    }

    @Test
    fun `WEBP maps to WEBP on older Android versions`() {
        // SDK < R (30), e.g., 29 (Q)
        @Suppress("DEPRECATION")
        val expected = Bitmap.CompressFormat.WEBP
        assertEquals(expected, ExportFormat.WEBP.toCompressFormat(quality = 100, sdkInt = 29))
        assertEquals(expected, ExportFormat.WEBP.toCompressFormat(quality = 80, sdkInt = 29))
    }

    @Test
    fun `WEBP maps to WEBP_LOSSLESS on Android R+ when quality is 100`() {
        // SDK >= R (30)
        // WEBP_LOSSLESS is available since API 30.
        val expected = Bitmap.CompressFormat.WEBP_LOSSLESS
        assertEquals(expected, ExportFormat.WEBP.toCompressFormat(quality = 100, sdkInt = 30))
    }

    @Test
    fun `WEBP maps to WEBP_LOSSY on Android R+ when quality is less than 100`() {
        // SDK >= R (30)
        val expected = Bitmap.CompressFormat.WEBP_LOSSY
        assertEquals(expected, ExportFormat.WEBP.toCompressFormat(quality = 99, sdkInt = 30))
    }
}
