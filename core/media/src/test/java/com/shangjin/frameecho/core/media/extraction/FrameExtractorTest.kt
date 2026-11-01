package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @After
    fun tearDown() {
        unmockkAll()
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
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100

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
        verify(exactly = 1) { mockBitmap.recycle() }
    }

    @Test
    fun `API 26 thumbnails fit bounds without upscaling`() = runBlocking {
        assertThumbnailBounds(sdkInt = 26)
    }

    @Test
    fun `API 27 thumbnails fit bounds without upscaling`() = runBlocking {
        assertThumbnailBounds(sdkInt = 27)
    }

    private suspend fun assertThumbnailBounds(sdkInt: Int) {
        val extractor = FrameExtractor(context, sdkInt)
        stubRetriever(100, 100)
        // Source width, source height, requested width, expected width, expected height.
        val cases = listOf(
            listOf(8, 8192, 120, 1, 240),
            listOf(16, 16, 120, 16, 16),
            listOf(1920, 1080, 120, 120, 68),
            listOf(1080, 1920, 120, 120, 213),
            listOf(Int.MAX_VALUE, 1, Int.MAX_VALUE, 240, 1),
            listOf(1, Int.MAX_VALUE, 120, 1, 240)
        )
        for ((sourceWidth, sourceHeight, requestedWidth, expectedWidth, expectedHeight) in cases) {
            stubRetriever(sourceWidth, sourceHeight)
            val fullBitmap = mockk<Bitmap>(relaxed = true)
            every { fullBitmap.width } returns sourceWidth
            every { fullBitmap.height } returns sourceHeight
            every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns fullBitmap
            val thumbnail = mockk<Bitmap>()
            every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } answers {
                assertEquals(expectedWidth, secondArg<Int>())
                assertEquals(expectedHeight, thirdArg<Int>())
                thumbnail
            }
            every {
                anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
            } answers {
                assertEquals(expectedWidth, thirdArg<Int>())
                assertEquals(expectedHeight, arg<Int>(3))
                thumbnail
            }

            assertSame(thumbnail, extractor.extractThumbnail(videoUri, 0L, requestedWidth))
            val results = extractor.extractThumbnails(videoUri, listOf(0L), requestedWidth)
            assertEquals(listOf(0L to thumbnail), results)
        }
        verify(exactly = cases.size * 2) {
            if (sdkInt == 26) {
                anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, any(), any())
            }
        }
    }

    @Test
    fun `batch pixel budget limits decoding on both SDK paths`() = runBlocking {
        stubRetriever(240, 240)
        val fullBitmap = mockk<Bitmap>(relaxed = true)
        every { fullBitmap.width } returns 240
        every { fullBitmap.height } returns 240
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns fullBitmap
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } answers { mockk<Bitmap>() }
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers { mockk<Bitmap>() }
        val timestamps = (0L until 1000L).toList()

        for (sdkInt in listOf(26, 27)) {
            val results = FrameExtractor(context, sdkInt).extractThumbnails(videoUri, timestamps, 240)
            assertEquals(78, results.size)
            assertEquals(timestamps.take(78), results.map { it.first })
            assertTrue(results.size * 240L * 240L <= 4_500_000L)
        }
        verify(exactly = 78) { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) }
        verify(exactly = 78) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
    }

    @Test
    fun `batch frame limit bounds tiny thumbnails and preserves timeline capacity`() = runBlocking {
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers { mockk<Bitmap>() }
        for ((width, height) in listOf(1 to 1, 120 to 240)) {
            stubRetriever(width, height)
            val results = frameExtractor.extractThumbnails(videoUri, (0L until 1000L).toList(), 120)
            assertEquals(150, results.size)
        }
        verify(exactly = 300) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
    }

    @Test
    fun `legacy scaling fits actual decoded dimensions and keeps reused bitmap alive`() = runBlocking {
        stubRetriever(1920, 1080)
        val fullBitmap = mockk<Bitmap>(relaxed = true)
        every { fullBitmap.width } returns 16
        every { fullBitmap.height } returns 16
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns fullBitmap
        every { Bitmap.createScaledBitmap(fullBitmap, 16, 16, true) } returns fullBitmap

        val result = FrameExtractor(context, 26).extractThumbnail(videoUri, 0L, 120)

        assertSame(fullBitmap, result)
        verify(exactly = 0) { fullBitmap.recycle() }
    }

    @Test
    fun `invalid media dimensions do not decode frames`() = runBlocking {
        for ((width, height) in listOf(0 to 100, 100 to -1)) {
            stubRetriever(width, height)
            assertEquals(null, frameExtractor.extractThumbnail(videoUri, 0L, 120))
            assertTrue(frameExtractor.extractThumbnails(videoUri, listOf(0L), 120).isEmpty())
        }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
    }

    private fun stubRetriever(width: Int, height: Int) {
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns width.toString()
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns height.toString()
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
    }

    @Test
    fun `releaseBitmap calls recycle when not recycled`() {
        val bitmap = mockk<Bitmap>()
        every { bitmap.isRecycled } returns false
        every { bitmap.recycle() } returns Unit

        frameExtractor.releaseBitmap(bitmap)

        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun `releaseBitmap does not call recycle when already recycled`() {
        val bitmap = mockk<Bitmap>()
        every { bitmap.isRecycled } returns true

        frameExtractor.releaseBitmap(bitmap)

        verify(exactly = 0) { bitmap.recycle() }
    }
}
