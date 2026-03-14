package com.shangjin.frameecho.core.media.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class DateTimeUtilsTest {

    private var originalTimeZone: TimeZone? = null

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        originalTimeZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun normalizeToIso_withVariousFormats() {
        val testCases = mapOf(
            "20231027T103000.500Z" to "2023-10-27T10:30:00Z",
            "2023-10-27T10:30:00Z" to "2023-10-27T10:30:00Z",
            "2023-10-27T10:30:00+0000" to "2023-10-27T10:30:00Z",
            "2023-10-27T20:30:00+0800" to "2023-10-27T20:30:00+08:00",
            "2023-10-27T10:30:00.500+0000" to "2023-10-27T10:30:00Z",
            "20231027T103000" to "2023-10-27T10:30:00",
            "2023 10 27" to "2023-10-27T00:00:00",
            "2023-10-27T10:30:00" to "2023-10-27T10:30:00"
        )

        for ((input, expected) in testCases) {
            assertEquals("Failed for input: $input", expected, DateTimeUtils.normalizeToIso(input))
        }
    }

    @Test
    fun normalizeToIso_withInvalidInput() {
        assertEquals("invalid", DateTimeUtils.normalizeToIso("invalid"))
        assertEquals("", DateTimeUtils.normalizeToIso(""))
    }

    @Test
    fun convertToExif_withIsoDate() {
        assertEquals("2023:10:27 18:30:00", DateTimeUtils.convertToExif("2023-10-27T10:30:00Z"))
        assertEquals("2023:10:27 18:30:00", DateTimeUtils.convertToExif("20231027T103000.500Z"))
        assertEquals("2023:10:27 20:30:00", DateTimeUtils.convertToExif("2023-10-27T20:30:00+0800"))
    }

    @Test
    fun convertToExif_withInvalidInput() {
        assertNull(DateTimeUtils.convertToExif("invalid"))
        assertNull(DateTimeUtils.convertToExif(""))
    }

    @Test
    fun parseToMillis_withVariousFormats() {
        val expectedUtc = 1698402600000L // 2023-10-27T10:30:00Z
        val expectedLocal = LocalDateTime.of(2023, 10, 27, 10, 30)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()
        val expectedOffset = LocalDateTime.of(2023, 10, 27, 20, 30)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        assertEquals(expectedUtc, DateTimeUtils.parseToMillis("2023-10-27T10:30:00Z"))
        assertEquals(expectedLocal, DateTimeUtils.parseToMillis("20231027T103000"))
        assertEquals(expectedLocal, DateTimeUtils.parseToMillis("2023-10-27T10:30:00"))
        assertEquals(expectedOffset, DateTimeUtils.parseToMillis("2023-10-27T20:30:00+0800"))
        assertEquals(expectedUtc + 500, DateTimeUtils.parseToMillis("20231027T103000.500Z"))
    }

    @Test
    fun parseToMillis_withInvalidInput() {
        assertNull(DateTimeUtils.parseToMillis("invalid"))
        assertNull(DateTimeUtils.parseToMillis(""))
    }
}
