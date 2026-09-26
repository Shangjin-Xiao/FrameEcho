package com.shangjin.frameecho.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [VideoMetadata] model.
 */
class VideoMetadataTest {

    @Test
    fun `default values are set correctly`() {
        val metadata = VideoMetadata()

        assertNull(metadata.dateTime)
        assertNull(metadata.latitude)
        assertNull(metadata.longitude)
        assertNull(metadata.altitude)
        assertNull(metadata.make)
        assertNull(metadata.model)
        assertNull(metadata.iso)
        assertNull(metadata.exposureTime)
        assertNull(metadata.fNumber)
        assertNull(metadata.focalLength)
        assertEquals(0, metadata.rotation)
        assertEquals(0, metadata.videoWidth)
        assertEquals(0, metadata.videoHeight)
        assertEquals(0f, metadata.frameRate, 0.0f)
        assertEquals(0L, metadata.bitrate)
        assertNull(metadata.codec)
        assertEquals(0L, metadata.durationMs)
        assertNull(metadata.sourceFileName)
    }

    @Test
    fun `custom values are set correctly`() {
        val metadata = VideoMetadata(
            dateTime = "2023-10-25T14:30:00Z",
            latitude = 37.7749,
            longitude = -122.4194,
            altitude = 15.0,
            make = "Google",
            model = "Pixel 7",
            iso = 100,
            exposureTime = "1/1000",
            fNumber = 1.85,
            focalLength = 6.81,
            rotation = 90,
            videoWidth = 3840,
            videoHeight = 2160,
            frameRate = 60.0f,
            bitrate = 50000000L,
            codec = "hevc",
            durationMs = 120000L,
            sourceFileName = "sample_video.mp4"
        )

        assertEquals("2023-10-25T14:30:00Z", metadata.dateTime)
        assertEquals(37.7749, metadata.latitude!!, 0.0001)
        assertEquals(-122.4194, metadata.longitude!!, 0.0001)
        assertEquals(15.0, metadata.altitude!!, 0.0001)
        assertEquals("Google", metadata.make)
        assertEquals("Pixel 7", metadata.model)
        assertEquals(100, metadata.iso)
        assertEquals("1/1000", metadata.exposureTime)
        assertEquals(1.85, metadata.fNumber!!, 0.0001)
        assertEquals(6.81, metadata.focalLength!!, 0.0001)
        assertEquals(90, metadata.rotation)
        assertEquals(3840, metadata.videoWidth)
        assertEquals(2160, metadata.videoHeight)
        assertEquals(60.0f, metadata.frameRate, 0.001f)
        assertEquals(50000000L, metadata.bitrate)
        assertEquals("hevc", metadata.codec)
        assertEquals(120000L, metadata.durationMs)
        assertEquals("sample_video.mp4", metadata.sourceFileName)
    }

    @Test
    fun `copy and equality work as expected`() {
        val metadata1 = VideoMetadata(
            dateTime = "2023-10-25T14:30:00Z",
            videoWidth = 1920,
            videoHeight = 1080
        )
        val metadata2 = metadata1.copy()

        assertEquals(metadata1, metadata2)

        val metadata3 = metadata1.copy(rotation = 180)
        assertEquals(180, metadata3.rotation)
        assertEquals(metadata1.videoWidth, metadata3.videoWidth)
    }
}
