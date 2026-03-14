package com.shangjin.frameecho.core.media.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifUtilsTest {

    @Test
    fun `formatRational correctly formats decimals`() {
        assertEquals("125000/10000", ExifUtils.formatRational(12.5))
        assertEquals("0/10000", ExifUtils.formatRational(0.0))
        assertEquals("3333/10000", ExifUtils.formatRational(0.333333))
        assertEquals("10000/10000", ExifUtils.formatRational(1.0))
        // The implementation uses absolute value
        assertEquals("125000/10000", ExifUtils.formatRational(-12.5))
    }

    @Test
    fun `normalizeExposureTime returns null for null or blank`() {
        assertNull(ExifUtils.normalizeExposureTime(null))
        assertNull(ExifUtils.normalizeExposureTime(""))
        assertNull(ExifUtils.normalizeExposureTime("   "))
    }

    @Test
    fun `normalizeExposureTime validates existing fractional forms`() {
        // Valid fractions
        assertEquals("1/60", ExifUtils.normalizeExposureTime("1/60"))
        assertEquals("1/100", ExifUtils.normalizeExposureTime(" 1/100 "))
        assertEquals("2/3", ExifUtils.normalizeExposureTime("2/3"))

        // Invalid fractions
        assertNull(ExifUtils.normalizeExposureTime("1/"))
        assertNull(ExifUtils.normalizeExposureTime("/60"))
        assertNull(ExifUtils.normalizeExposureTime("1/0"))
        assertNull(ExifUtils.normalizeExposureTime("-1/60"))
        assertNull(ExifUtils.normalizeExposureTime("1/-60"))
        assertNull(ExifUtils.normalizeExposureTime("1/2/3"))
        assertNull(ExifUtils.normalizeExposureTime("a/b"))
    }

    @Test
    fun `normalizeExposureTime converts decimal seconds to fraction`() {
        assertEquals("1/63", ExifUtils.normalizeExposureTime("0.016"))
        assertEquals("1/50", ExifUtils.normalizeExposureTime("0.02"))
        assertEquals("1/4000", ExifUtils.normalizeExposureTime("0.00025"))
        assertEquals("1/2", ExifUtils.normalizeExposureTime("0.5"))
    }

    @Test
    fun `normalizeExposureTime keeps decimals greater than or equal to 1`() {
        assertEquals("1.0", ExifUtils.normalizeExposureTime("1.0"))
        assertEquals("2.5", ExifUtils.normalizeExposureTime("2.5"))
        assertEquals("10.0", ExifUtils.normalizeExposureTime("10.0"))
    }

    @Test
    fun `normalizeExposureTime returns null for non-positive or unparseable decimals`() {
        assertNull(ExifUtils.normalizeExposureTime("0.0"))
        assertNull(ExifUtils.normalizeExposureTime("-0.5"))
        assertNull(ExifUtils.normalizeExposureTime("-1.0"))
        assertNull(ExifUtils.normalizeExposureTime("abc"))
        assertNull(ExifUtils.normalizeExposureTime("NaN"))
        assertNull(ExifUtils.normalizeExposureTime("Infinity"))
        assertNull(ExifUtils.normalizeExposureTime("-Infinity"))
    }
}
