package com.shangjin.frameecho.core.media.metadata

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataWriterTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `sanitizes make and model attributes before writing to EXIF`() {
        // Arrange
        val maliciousMake = "Sony\nCamera" // Has newline
        val longModel = "b".repeat(200)   // Exceeds 100 chars
        val metadata = VideoMetadata(
            make = maliciousMake,
            model = longModel
        )

        val exif = mockk<ExifInterface>(relaxed = true)

        // Act
        MetadataWriter.writeExifData(exif, metadata)

        // Assert
        val expectedMake = "SonyCamera"
        val expectedModel = "b".repeat(100)

        verify { exif.setAttribute(ExifInterface.TAG_MAKE, expectedMake) }
        verify { exif.setAttribute(ExifInterface.TAG_MODEL, expectedModel) }
    }

    @Test
    fun `sanitizes metadata in user comment`() {
        // Arrange
        val maliciousFileName = "malicious\nfile.mp4"
        // Create a very long string (200 chars)
        val longCodec = "a".repeat(200)

        val metadata = VideoMetadata(
            sourceFileName = maliciousFileName,
            codec = longCodec,
            frameRate = 30.0f
        )

        // Act
        val comment = MetadataWriter.buildUserComment(metadata)

        // Log output for debugging
        println("Generated Comment: $comment")

        // Assert
        // 1. Check for absence of newlines
        assertFalse("Comment should not contain newlines", comment.contains("\n"))

        // 2. Check filename sanitization (newline removed)
        // "malicious\nfile.mp4" -> "maliciousfile.mp4"
        assertTrue("Comment should contain sanitized filename", comment.contains("maliciousfile.mp4"))

        // 3. Check codec truncation (max 100 chars)
        // The codec part should look like "; Codec: aaaaa..." (100 'a's)
        val expectedCodecPart = "a".repeat(100)
        assertTrue("Comment should contain truncated codec", comment.contains(expectedCodecPart))
        assertFalse("Comment should not contain full length codec", comment.contains("a".repeat(101)))
    }
}
