package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class FrameExtractorSecurityTest {

    private lateinit var context: Context
    private lateinit var applicationInfo: ApplicationInfo

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkConstructor(MediaMetadataRetriever::class)
        context = mockk(relaxed = true)
        applicationInfo = ApplicationInfo()
        every { context.applicationInfo } returns applicationInfo

        // Mock Log.w explicitly to prevent test failures when retriever.release() logs warnings
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `extractFrame logs full exception when debuggable`() = runBlocking {
        // Set debug flag
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE

        val frameExtractor = FrameExtractor(context)
        val uri = mockk<Uri>()
        val exception = RuntimeException("Sensitive info: /user/name/video.mp4")

        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } throws exception
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        // Mock Log.e to return 0
        every { Log.e(any(), any(), any()) } returns 0

        frameExtractor.extractFrame(uri, 0L)

        // Verify full exception is logged
        verify { Log.e("FrameExtractor", "Failed to extract frame at 0", exception) }
    }

    @Test
    fun `extractFrame logs sanitized message when not debuggable`() = runBlocking {
        // Clear debug flag
        applicationInfo.flags = 0

        val frameExtractor = FrameExtractor(context)
        val uri = mockk<Uri>()
        val exception = RuntimeException("Sensitive info: /user/name/video.mp4")

        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } throws exception
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        // Mock Log.e
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        frameExtractor.extractFrame(uri, 0L)

        // Verify sanitized log is called
        verify { Log.e("FrameExtractor", "Failed to extract frame at 0: RuntimeException") }

        // Verify full exception is NOT logged
        verify(exactly = 0) { Log.e("FrameExtractor", any(), exception) }
    }
}
