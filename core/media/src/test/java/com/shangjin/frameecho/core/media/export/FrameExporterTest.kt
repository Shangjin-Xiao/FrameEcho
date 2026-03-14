package com.shangjin.frameecho.core.media.export

import android.content.Context
import android.util.Xml
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrameExporterTest {

    @Before
    fun setup() {
        mockkStatic(Xml::class)
        every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
    }

    @After
    fun tearDown() {
        unmockkStatic(Xml::class)
    }

    @Test
    fun `injectMotionPhotoXmp should throw exception when jpeg bytes are invalid`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        val invalidJpegBytes = byteArrayOf(0x00, 0x01, 0x02)

        assertThrows(IllegalArgumentException::class.java) {
            exporter.injectMotionPhotoXmp(invalidJpegBytes, 1000L, 1000L)
        }
    }

    @Test
    fun `injectMotionPhotoXmp should work with valid jpeg bytes`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        val validJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 0, 0, 0)

        val result = exporter.injectMotionPhotoXmp(validJpegBytes, 1000L, 1000L)

        // Result should start with SOI (FF D8) and then APP1 marker (FF E1)
        assertTrue(result.size > 4)
        assertTrue((result[0].toInt() and 0xFF) == 0xFF)
        assertTrue((result[1].toInt() and 0xFF) == 0xD8)
        assertTrue((result[2].toInt() and 0xFF) == 0xFF)
        assertTrue((result[3].toInt() and 0xFF) == 0xE1)

        // Extract XMP packet to verify content
        // Skip SOI (2) + APP1 Marker (2) + Length (2) + Namespace (29 bytes)
        val headerLen = 29
        val offset = 2 + 2 + 2 + headerLen
        if (result.size > offset) {
            val xmpString = String(result, offset, result.size - offset - 2, Charsets.UTF_8) // -2 for rest of JPEG dummy data
            // Verify key elements are present
            assertTrue(xmpString.contains("x:xmpmeta"))
            assertTrue(xmpString.contains("rdf:RDF"))
            assertTrue(xmpString.contains("GCamera:MotionPhoto=\"1\""))
            assertTrue(xmpString.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1000\""))
            assertTrue(xmpString.contains("Item:Mime=\"video/mp4\""))
            assertTrue(xmpString.contains("Item:Length=\"1000\""))
            // Verify Padding attribute per Google Motion Photo spec
            assertTrue("Primary item must have Padding=\"0\"",
                xmpString.contains("Item:Semantic=\"Primary\" Item:Padding=\"0\""))
            assertTrue("MotionPhoto item must have Padding=\"0\"",
                xmpString.contains("Item:Semantic=\"MotionPhoto\" Item:Length=\"1000\" Item:Padding=\"0\""))
        }
    }
}
