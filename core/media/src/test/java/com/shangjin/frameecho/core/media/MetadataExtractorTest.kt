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
}
