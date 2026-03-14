package com.shangjin.frameecho.core.media.metadata

import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataKeysTest {

    @Test
    fun `ISO keys contain expected values`() {
        val expected = arrayOf("iso", "iso-speed", "iso_speed", "com.android.iso")
        assertTrue(MetadataExtractor.KEYS_ISO.contentEquals(expected))
    }

    @Test
    fun `Exposure time keys contain expected values`() {
        val expected = arrayOf(
            "exposure-time",
            "exposure_time",
            "shutter-speed",
            "shutter_speed",
            "com.android.exposure-time"
        )
        assertTrue(MetadataExtractor.KEYS_EXPOSURE_TIME.contentEquals(expected))
    }

    @Test
    fun `F-number keys contain expected values`() {
        val expected = arrayOf(
            "f-number",
            "f_number",
            "aperture",
            "com.android.aperture"
        )
        assertTrue(MetadataExtractor.KEYS_F_NUMBER.contentEquals(expected))
    }

    @Test
    fun `Focal length keys contain expected values`() {
        val expected = arrayOf(
            "focal-length",
            "focal_length",
            "focal-length-mm",
            "com.android.focal-length"
        )
        assertTrue(MetadataExtractor.KEYS_FOCAL_LENGTH.contentEquals(expected))
    }
}
