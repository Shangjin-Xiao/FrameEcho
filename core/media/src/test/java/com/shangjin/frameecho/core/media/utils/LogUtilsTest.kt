package com.shangjin.frameecho.core.media.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class LogUtilsTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createContext(isDebuggable: Boolean): Context {
        val context = mockk<Context>()
        val appInfo = ApplicationInfo().apply {
            flags = if (isDebuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
        }
        every { context.applicationInfo } returns appInfo
        return context
    }

    @Test
    fun `w logs warning message without exception`() {
        val context = createContext(isDebuggable = true)
        LogUtils.w(context, "TestTag", "Warning message")

        verify { Log.w("TestTag", "Warning message") }
    }

    @Test
    fun `w logs full exception details in debug build`() {
        val context = createContext(isDebuggable = true)
        val exception = IllegalArgumentException("Sensitive param error")

        LogUtils.w(context, "TestTag", "Warning message", exception)

        verify { Log.w("TestTag", "Warning message", exception) }
    }

    @Test
    fun `w logs sanitized exception class name in release build`() {
        val context = createContext(isDebuggable = false)
        val exception = IllegalArgumentException("Sensitive param error")

        LogUtils.w(context, "TestTag", "Warning message", exception)

        verify { Log.w("TestTag", "Warning message: IllegalArgumentException") }
    }

    @Test
    fun `e logs error message without exception`() {
        val context = createContext(isDebuggable = true)
        LogUtils.e(context, "TestTag", "Error message")

        verify { Log.e("TestTag", "Error message") }
    }

    @Test
    fun `e logs full exception details in debug build`() {
        val context = createContext(isDebuggable = true)
        val exception = IllegalStateException("Internal secret state")

        LogUtils.e(context, "TestTag", "Error message", exception)

        verify { Log.e("TestTag", "Error message", exception) }
    }

    @Test
    fun `e logs sanitized exception class name in release build`() {
        val context = createContext(isDebuggable = false)
        val exception = IllegalStateException("Internal secret state")

        LogUtils.e(context, "TestTag", "Error message", exception)

        verify { Log.e("TestTag", "Error message: IllegalStateException") }
    }

    @Test
    fun `isDebuggable handles context exception and defaults to release behavior`() {
        val context = mockk<Context>()
        every { context.applicationInfo } throws RuntimeException("Package manager died")
        val exception = RuntimeException("Sensitive info")

        LogUtils.e(context, "TestTag", "Error message", exception)

        verify { Log.e("TestTag", "Error message: RuntimeException") }
    }
}
