package com.shangjin.frameecho.core.media

import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for MetadataExtractor.
 */
class MetadataExtractorTest {

    @Test
    fun `parseLocation with valid positive coordinates`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+34.0522-118.2437/")
        assertEquals(34.0522, lat!!, 0.0001)
        assertEquals(-118.2437, lon!!, 0.0001)
    }

    @Test
    fun `parseLocation with valid negative coordinates`() {
        val (lat, lon) = MetadataExtractor.parseLocation("-33.8688+151.2093/")
        assertEquals(-33.8688, lat!!, 0.0001)
        assertEquals(151.2093, lon!!, 0.0001)
    }

    @Test
    fun `parseLocation with null returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation(null)
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with empty string returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with blank string returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("   ")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with invalid format returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("invalid")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation without trailing slash`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+40.7128-74.0060")
        assertEquals(40.7128, lat!!, 0.0001)
        assertEquals(-74.0060, lon!!, 0.0001)
    }

    @Test
    fun `parseLocation with single coordinate returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+34.0522")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with non-numeric location returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("invalid-location")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with non-numeric coordinates returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+abc-def")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with multiple trailing slashes works correctly`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+34.0522-118.2437//")
        assertEquals(34.0522, lat!!, 0.0001)
        assertEquals(-118.2437, lon!!, 0.0001)
    }

    @Test
    fun `parseLocation with garbage data mixed with signs returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+-/")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with double signs returns nulls`() {
        val (lat, lon) = MetadataExtractor.parseLocation("++34.0522--118.2437")
        assertNull(lat)
        assertNull(lon)
    }

    @Test
    fun `parseLocation with altitude suffix parses lat lon correctly`() {
        val (lat, lon) = MetadataExtractor.parseLocation("+34.0522-118.2437+89.0/")
        assertEquals(34.0522, lat!!, 0.0001)
        assertEquals(-118.2437, lon!!, 0.0001)
    }

    @Test
    fun `getStringFromKeys returns first non-blank string value`() {
        val format = io.mockk.mockk<android.media.MediaFormat>(relaxed = true)
        io.mockk.every { format.getString("key1") } throws NullPointerException("Missing key")
        io.mockk.every { format.getString("key2") } returns ""
        io.mockk.every { format.getString("key3") } returns "1/100"

        val result = MetadataExtractor.getStringFromKeys(format, "key1", "key2", "key3")
        assertEquals("1/100", result)
    }

    @Test
    fun `getIntFromKeys returns first valid integer`() {
        val format = io.mockk.mockk<android.media.MediaFormat>(relaxed = true)
        io.mockk.every { format.getInteger("key1") } throws ClassCastException("Wrong type")
        io.mockk.every { format.getInteger("key2") } returns 400

        val result = MetadataExtractor.getIntFromKeys(format, "key1", "key2")
        assertEquals(400, result)
    }

    @Test
    fun `getFloatFromKeys falls back to string parsing if getFloat throws`() {
        val format = io.mockk.mockk<android.media.MediaFormat>(relaxed = true)
        io.mockk.every { format.getFloat("key1") } throws ClassCastException("Wrong type")
        io.mockk.every { format.getString("key1") } returns "2.8"

        val result = MetadataExtractor.getFloatFromKeys(format, "key1")
        assertEquals(2.8f, result!!, 0.001f)
    }

    @Test
    fun `benchmark getStringFromKeys execution`() {
        val format = io.mockk.mockk<android.media.MediaFormat>(relaxed = true)
        io.mockk.every { format.getString("invalid1") } throws NullPointerException()
        io.mockk.every { format.getString("invalid2") } throws NullPointerException()
        io.mockk.every { format.getString("validKey") } returns "1/500"

        // Warmup
        repeat(100) {
            MetadataExtractor.getStringFromKeys(format, "invalid1", "invalid2", "validKey")
        }

        val startTime = System.nanoTime()
        repeat(1_000) {
            MetadataExtractor.getStringFromKeys(format, "invalid1", "invalid2", "validKey")
        }
        val durationNs = System.nanoTime() - startTime
        println("getStringFromKeys 1k iterations took: ${durationNs / 1_000_000.0} ms")
    }
}
