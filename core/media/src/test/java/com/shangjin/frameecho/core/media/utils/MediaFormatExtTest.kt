package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatExtTest {

    @Test
    fun `getIntegerSafe returns value when key exists`() {
        val format = mockk<MediaFormat>()
        every { format.getInteger("key") } returns 42

        val result = format.getIntegerSafe("key", 0)
        assertEquals(42, result)
    }

    @Test
    fun `getIntegerSafe returns default when exception occurs`() {
        val format = mockk<MediaFormat>()
        every { format.getInteger("key") } throws RuntimeException("Stub!")

        val result = format.getIntegerSafe("key", 100)
        assertEquals(100, result)
    }

    @Test
    fun `getIntegerSafe returns default when getInteger throws ClassCastException`() {
        val format = mockk<MediaFormat>()
        every { format.getInteger("key") } throws ClassCastException("Stub!")

        val result = format.getIntegerSafe("key", 100)
        assertEquals(100, result)
    }

    @Test
    fun `getIntegerSafe returns default when getInteger throws NullPointerException`() {
        val format = mockk<MediaFormat>()
        every { format.getInteger("key") } throws NullPointerException("Stub!")

        val result = format.getIntegerSafe("key", 100)
        assertEquals(100, result)
    }
}
