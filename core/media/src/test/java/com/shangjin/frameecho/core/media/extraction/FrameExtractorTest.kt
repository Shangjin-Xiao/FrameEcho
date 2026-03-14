package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FrameExtractorTest {

    private lateinit var context: Context
    private lateinit var frameExtractor: FrameExtractor
    private val videoUri = mockk<Uri>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        // Set sdkInt to 27+ to test getScaledFrameAtTime path
        frameExtractor = FrameExtractor(context, sdkInt = 27)
        mockkConstructor(MediaMetadataRetriever::class)
        mockkStatic(Bitmap::class)
    }

    @Test
    fun `extractThumbnail calls setDataSource once`() = runBlocking {
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } returns mockk()
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        frameExtractor.extractThumbnail(videoUri, 1000L, 100)

        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, videoUri) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `extractThumbnails reuses retriever for multiple frames`() = runBlocking {
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } returns mockk()
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        val timestamps = listOf(1000L, 2000L, 3000L)
        frameExtractor.extractThumbnails(videoUri, timestamps, 100)

        // DataSource should only be set once
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, videoUri) }
        // getScaledFrameAtTime should be called for each timestamp
        verify(exactly = 3) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
        // Released once
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `extractThumbnail falls back on older SDK`() = runBlocking {
        // Mocking SDK < 27
        val legacyExtractor = FrameExtractor(context, sdkInt = 26)

        val mockBitmap = mockk<Bitmap>(relaxed = true)
        val scaledBitmap = mockk<Bitmap>(relaxed = true)

        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns "100"
        every {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any())
        } returns mockBitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } returns scaledBitmap

        legacyExtractor.extractThumbnail(videoUri, 1000L, 100)

        // Should NOT call getScaledFrameAtTime
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
        // Should call getFrameAtTime and then createScaledBitmap
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
        verify(exactly = 1) { Bitmap.createScaledBitmap(mockBitmap, 100, 100, true) }
    }
}
