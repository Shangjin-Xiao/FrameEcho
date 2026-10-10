package com.shangjin.frameecho.core.media.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class LogUtilsTest {

    private lateinit var context: Context
    private lateinit var applicationInfo: ApplicationInfo

    @Before
    fun setup() {
        mockkStatic(Log::class)
        context = mockk(relaxed = true)
        applicationInfo = ApplicationInfo()
        every { context.applicationInfo } returns applicationInfo

        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `w logs full exception in debug mode`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val tag = "TestTag"
        val message = "Warning message"
        val exception = RuntimeException("Sensitive details")

        LogUtils.w(context, tag, message, exception)

        verify { Log.w(tag, message, exception) }
        verify(exactly = 0) { Log.w(tag, "$message: RuntimeException") }
    }

    @Test
    fun `w logs sanitized class name in release mode`() {
        applicationInfo.flags = 0
        val tag = "TestTag"
        val message = "Warning message"
        val exception = RuntimeException("Sensitive details")

        LogUtils.w(context, tag, message, exception)

        verify { Log.w(tag, "$message: RuntimeException") }
        verify(exactly = 0) { Log.w(tag, message, exception) }
    }

    @Test
    fun `w logs message only when exception is null`() {
        val tag = "TestTag"
        val message = "Warning message without exception"

        LogUtils.w(context, tag, message, null)

        verify { Log.w(tag, message) }
    }

    @Test
    fun `e logs full exception in debug mode`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val tag = "TestTag"
        val message = "Error message"
        val exception = IllegalArgumentException("Sensitive error info")

        LogUtils.e(context, tag, message, exception)

        verify { Log.e(tag, message, exception) }
        verify(exactly = 0) { Log.e(tag, "$message: IllegalArgumentException") }
    }

    @Test
    fun `e logs sanitized class name in release mode`() {
        applicationInfo.flags = 0
        val tag = "TestTag"
        val message = "Error message"
        val exception = IllegalArgumentException("Sensitive error info")

        LogUtils.e(context, tag, message, exception)

        verify { Log.e(tag, "$message: IllegalArgumentException") }
        verify(exactly = 0) { Log.e(tag, message, exception) }
    }

    @Test
    fun `e logs message only when exception is null`() {
        val tag = "TestTag"
        val message = "Error message without exception"

        LogUtils.e(context, tag, message, null)

        verify { Log.e(tag, message) }
    }

    @Test
    fun `w handles applicationInfo throwing exception by falling back to sanitized log`() {
        every { context.applicationInfo } throws RuntimeException("Context error")
        val tag = "TestTag"
        val message = "Warning message"
        val exception = IllegalStateException("Details")

        LogUtils.w(context, tag, message, exception)

        verify { Log.w(tag, "$message: IllegalStateException") }
        verify(exactly = 0) { Log.w(tag, message, exception) }
    }
}
