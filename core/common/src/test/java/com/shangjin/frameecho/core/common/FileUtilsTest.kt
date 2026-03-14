package com.shangjin.frameecho.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Tests for [FileUtils].
 */
class FileUtilsTest {

    private var defaultLocale: Locale? = null

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        defaultLocale?.let { Locale.setDefault(it) }
    }

    // formatFileSize Tests

    @Test
    fun formatFileSize_negativeBytes_returnsNegativeB() {
        assertEquals("-1 B", FileUtils.formatFileSize(-1))
    }

    @Test
    fun formatFileSize_zeroBytes_returnsZeroB() {
        assertEquals("0 B", FileUtils.formatFileSize(0))
    }

    @Test
    fun formatFileSize_belowOneKB_returnsBytes() {
        assertEquals("1023 B", FileUtils.formatFileSize(1023))
    }

    @Test
    fun formatFileSize_exactOneKB_returnsOneKB() {
        assertEquals("1.0 KB", FileUtils.formatFileSize(1024))
    }

    @Test
    fun formatFileSize_oneAndHalfKB_returnsCorrectKB() {
        assertEquals("1.5 KB", FileUtils.formatFileSize(1536))
    }

    @Test
    fun formatFileSize_exactOneMB_returnsOneMB() {
        assertEquals("1.0 MB", FileUtils.formatFileSize(1048576))
    }

    @Test
    fun formatFileSize_justBelowOneMB_roundsToKB() {
        // 1048575 / 1024.0 = 1023.999... -> rounds to 1024.0
        assertEquals("1024.0 KB", FileUtils.formatFileSize(1048575))
    }

    @Test
    fun formatFileSize_exactOneGB_returnsOneGB() {
        assertEquals("1.0 GB", FileUtils.formatFileSize(1073741824))
    }

    @Test
    fun formatFileSize_justBelowOneGB_roundsToMB() {
        // 1073741823 / 1048576.0 = 1023.999... -> rounds to 1024.0
        assertEquals("1024.0 MB", FileUtils.formatFileSize(1073741823))
    }

    @Test
    fun formatFileSize_largeGB_returnsCorrectGB() {
        // 100 GB
        assertEquals("100.0 GB", FileUtils.formatFileSize(107374182400))
    }

    @Test
    fun formatFileSize_terabytes_returnsGB() {
        // 1 TB = 1024 GB. The current implementation caps at GB.
        assertEquals("1024.0 GB", FileUtils.formatFileSize(1099511627776))
    }

    // formatDuration Tests

    @Test
    fun formatDuration_negative_clampsToZero() {
        assertEquals("0:00", FileUtils.formatDuration(-1))
        assertEquals("0:00", FileUtils.formatDuration(-1000))
    }

    @Test
    fun formatDuration_zero_returnsZeroTime() {
        assertEquals("0:00", FileUtils.formatDuration(0))
    }

    @Test
    fun formatDuration_lessThanOneSecond_returnsZeroTime() {
        assertEquals("0:00", FileUtils.formatDuration(999))
    }

    @Test
    fun formatDuration_exactOneSecond_returnsOneSecond() {
        assertEquals("0:01", FileUtils.formatDuration(1000))
    }

    @Test
    fun formatDuration_singleDigitSeconds_padsCorrectly() {
        assertEquals("0:05", FileUtils.formatDuration(5000))
    }

    @Test
    fun formatDuration_fiftyNineSeconds_returnsCorrectTime() {
        assertEquals("0:59", FileUtils.formatDuration(59000))
    }

    @Test
    fun formatDuration_oneMinute_returnsCorrectTime() {
        assertEquals("1:00", FileUtils.formatDuration(60000))
    }

    @Test
    fun formatDuration_oneMinuteOneSecond_returnsCorrectTime() {
        assertEquals("1:01", FileUtils.formatDuration(61000))
    }

    @Test
    fun formatDuration_fiftyNineMinutesFiftyNineSeconds_returnsCorrectTime() {
        assertEquals("59:59", FileUtils.formatDuration(3599000))
    }

    @Test
    fun formatDuration_oneHour_returnsCorrectTime() {
        assertEquals("1:00:00", FileUtils.formatDuration(3600000))
    }

    @Test
    fun formatDuration_oneHourOneMinuteOneSecond_returnsCorrectTime() {
        assertEquals("1:01:01", FileUtils.formatDuration(3661000))
    }

    @Test
    fun formatDuration_oneHourFiveMinutesFiveSeconds_padsCorrectly() {
        // 1 * 3600 + 5 * 60 + 5 = 3600 + 300 + 5 = 3905 sec -> 3905000 ms
        assertEquals("1:05:05", FileUtils.formatDuration(3905000))
    }

    @Test
    fun formatDuration_largeDuration_returnsCorrectTime() {
        // 100 hours
        val duration = 100L * 3600 * 1000
        assertEquals("100:00:00", FileUtils.formatDuration(duration))
    }

    // formatDurationWithMillis Tests

    @Test
    fun formatDurationWithMillis_zero_returnsZeroWithMillis() {
        assertEquals("0:00.000", FileUtils.formatDurationWithMillis(0))
    }

    @Test
    fun formatDurationWithMillis_secondsAndMillis_returnsExpectedFormat() {
        assertEquals("0:01.234", FileUtils.formatDurationWithMillis(1234))
    }

    @Test
    fun formatDurationWithMillis_hourRange_returnsExpectedFormat() {
        assertEquals("1:01:01.234", FileUtils.formatDurationWithMillis(3_661_234))
    }

    @Test
    fun formatDurationWithMillis_negative_clampsToZero() {
        assertEquals("0:00.000", FileUtils.formatDurationWithMillis(-1))
    }
}
