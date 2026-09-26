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

        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>(), any()) } returns 0
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
    fun `d logs message when build is debuggable`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val exception = RuntimeException("Debug info")

        LogUtils.d(context, "TestTag", "Debug message", exception)

        verify { Log.d("TestTag", "Debug message", exception) }
    }

    @Test
    fun `d suppresses message when build is not debuggable`() {
        applicationInfo.flags = 0
        val exception = RuntimeException("Debug info")

        LogUtils.d(context, "TestTag", "Debug message", exception)

        verify(exactly = 0) { Log.d(any(), any()) }
        verify(exactly = 0) { Log.d(any(), any(), any()) }
    }

    @Test
    fun `i logs full exception when build is debuggable`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val exception = RuntimeException("Sensitive info")

        LogUtils.i(context, "TestTag", "Info message", exception)

        verify { Log.i("TestTag", "Info message", exception) }
    }

    @Test
    fun `i logs sanitized simple class name when build is not debuggable`() {
        applicationInfo.flags = 0
        val exception = RuntimeException("Sensitive info")

        LogUtils.i(context, "TestTag", "Info message", exception)

        verify { Log.i("TestTag", "Info message: RuntimeException") }
        verify(exactly = 0) { Log.i("TestTag", any<String>(), exception) }
    }

    @Test
    fun `w logs full exception when build is debuggable`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val exception = RuntimeException("Sensitive details: /path/to/file")

        LogUtils.w(context, "TestTag", "Warning occurred", exception)

        verify { Log.w("TestTag", "Warning occurred", exception) }
    }

    @Test
    fun `w logs sanitized simple class name when build is not debuggable`() {
        applicationInfo.flags = 0
        val exception = RuntimeException("Sensitive details: /path/to/file")

        LogUtils.w(context, "TestTag", "Warning occurred", exception)

        verify { Log.w("TestTag", "Warning occurred: RuntimeException") }
        verify(exactly = 0) { Log.w("TestTag", any<String>(), exception) }
    }

    @Test
    fun `w logs simple message without exception when exception is null`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE

        LogUtils.w(context, "TestTag", "Simple warning")

        verify { Log.w("TestTag", "Simple warning") }
    }

    @Test
    fun `e logs full exception when build is debuggable`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val exception = IllegalStateException("Sensitive database error")

        LogUtils.e(context, "TestTag", "Error occurred", exception)

        verify { Log.e("TestTag", "Error occurred", exception) }
    }

    @Test
    fun `e logs sanitized simple class name when build is not debuggable`() {
        applicationInfo.flags = 0
        val exception = IllegalStateException("Sensitive database error")

        LogUtils.e(context, "TestTag", "Error occurred", exception)

        verify { Log.e("TestTag", "Error occurred: IllegalStateException") }
        verify(exactly = 0) { Log.e("TestTag", any<String>(), exception) }
    }

    @Test
    fun `e logs simple message without exception when exception is null`() {
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE

        LogUtils.e(context, "TestTag", "Simple error")

        verify { Log.e("TestTag", "Simple error") }
    }

    @Test
    fun `isDebuggable handles context exception gracefully and defaults to release mode`() {
        every { context.applicationInfo } throws RuntimeException("Context info unavailable")
        val exception = IllegalArgumentException("Sensitive param error")

        LogUtils.e(context, "TestTag", "Failed operation", exception)

        verify { Log.e("TestTag", "Failed operation: IllegalArgumentException") }
        verify(exactly = 0) { Log.e("TestTag", any<String>(), exception) }
    }
}
