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
}
