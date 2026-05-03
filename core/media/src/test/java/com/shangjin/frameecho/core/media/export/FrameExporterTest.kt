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

    @Test
    fun `sanitizeFileName should remove control characters`() {
        val exporter = FrameExporter(mockk())
        val input = "file\u0000name\u001Ftest\u007F"
        assertTrue("Control characters should be removed", exporter.sanitizeFileName(input) == "filenametest")
    }

    @Test
    fun `sanitizeFileName should replace illegal filesystem characters`() {
        val exporter = FrameExporter(mockk())
        val input = "a\\b/c:d*e?f\"g<h>i|j"
        val expected = "a_b_c_d_e_f_g_h_i_j"
        assertTrue("Illegal characters should be replaced by underscores", exporter.sanitizeFileName(input) == expected)
    }

    @Test
    fun `sanitizeFileName should collapse multiple dots and prevent path traversal`() {
        val exporter = FrameExporter(mockk())

        assertTrue("Double dots should be replaced and trimmed", exporter.sanitizeFileName("..") == "FrameEcho")
        assertTrue("Triple dots should be replaced and trimmed", exporter.sanitizeFileName("...") == "FrameEcho")
        assertTrue("Path traversal sequence should be sanitized and trimmed", exporter.sanitizeFileName("../../etc/passwd") == "etc_passwd")
        assertTrue("Multiple dots within name should be collapsed", exporter.sanitizeFileName("my...file..name") == "my_file_name")
    }

    @Test
    fun `sanitizeFileName should remove leading and trailing dots, underscores and slashes`() {
        val exporter = FrameExporter(mockk())

        assertTrue("Leading dot should be removed", exporter.sanitizeFileName(".hidden") == "hidden")
        assertTrue("Leading slash should be removed", exporter.sanitizeFileName("/absolute/path") == "absolute_path")
        assertTrue("Leading traversal should be handled and trimmed", exporter.sanitizeFileName("../test") == "test")
        assertTrue("Trailing junk should be trimmed", exporter.sanitizeFileName("test.txt.") == "test.txt")
        assertTrue("Complex junk at boundaries should be trimmed", exporter.sanitizeFileName("._./test_.._") == "test")
    }

    @Test
    fun `sanitizeFileName should handle blank or fully sanitized input`() {
        val exporter = FrameExporter(mockk())

        assertTrue("Blank input should return default", exporter.sanitizeFileName("") == "FrameEcho")
        assertTrue("Whitespace input should return default", exporter.sanitizeFileName("   ") == "FrameEcho")
        assertTrue("Fully sanitized input should return default", exporter.sanitizeFileName("../..") == "FrameEcho")
        assertTrue("Input with only illegal characters should return default", exporter.sanitizeFileName("/\\:*?\"<>|") == "FrameEcho")
    }
}
