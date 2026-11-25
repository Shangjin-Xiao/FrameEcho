package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.shangjin.frameecho.core.media.colorspace.ColorSpaceDetector
import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import com.shangjin.frameecho.core.model.ColorGamut
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ColorSpaceType
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `progressive extractThumbnails emits 8-frame chunks in single retriever session`() = runBlocking {
        stubRetriever(1920, 1080)
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers { mockk<Bitmap>(relaxed = true) }

        val timestamps = (0L until 20L).map { it * 1_000L }
        val emittedChunks = mutableListOf<List<Pair<Long, Bitmap>>>()

        frameExtractor.extractThumbnails(videoUri, timestamps, 120) { chunk ->
            emittedChunks.add(chunk)
        }

        assertEquals(listOf(8, 8, 4), emittedChunks.map { it.size })
        assertEquals(timestamps, emittedChunks.flatten().map { it.first })
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, videoUri) }
        verify(exactly = 20) { anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any()) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `progressive extractThumbnails preserves pixel budget and 150 frame cap`() = runBlocking {
        stubRetriever(240, 240)
        val fullBitmap = mockk<Bitmap>(relaxed = true)
        every { fullBitmap.width } returns 240
        every { fullBitmap.height } returns 240
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns fullBitmap
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } answers { mockk<Bitmap>(relaxed = true) }
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers { mockk<Bitmap>(relaxed = true) }
        val timestamps = (0L until 1000L).toList()

        for (sdkInt in listOf(26, 27)) {
            val chunks = mutableListOf<List<Pair<Long, Bitmap>>>()
            FrameExtractor(context, sdkInt).extractThumbnails(videoUri, timestamps, 240) { chunk ->
                chunks.add(chunk)
            }
            val flattened = chunks.flatten()
            assertEquals(78, flattened.size)
            assertEquals(List(9) { 8 } + listOf(6), chunks.map { it.size })
            assertEquals(timestamps.take(78), flattened.map { it.first })
            assertTrue(flattened.size * 240L * 240L <= 4_500_000L)
        }

        stubRetriever(120, 240)
        val capChunks = mutableListOf<List<Pair<Long, Bitmap>>>()
        frameExtractor.extractThumbnails(videoUri, timestamps, 120) { chunk ->
            capChunks.add(chunk)
        }
        assertEquals(150, capChunks.flatten().size)
        assertEquals(List(18) { 8 } + listOf(6), capChunks.map { it.size })
    }

    @Test
    fun `progressive extractThumbnails stops on cancellation and recycles only unemitted chunk bitmaps`() = runBlocking {
        stubRetriever(1920, 1080)
        val decodedBitmaps = mutableListOf<Bitmap>()
        val emittedBitmaps = mutableListOf<Bitmap>()
        var decodeCalls = 0
        lateinit var job: Job

        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers {
            decodeCalls += 1
            if (decodeCalls == 11) {
                // Deterministically cancel after 10 completed frames while the 11th decode is in flight
                job.cancel()
            }
            mockk<Bitmap>().also { bmp ->
                every { bmp.isRecycled } returns false
                every { bmp.recycle() } returns Unit
                synchronized(decodedBitmaps) {
                    decodedBitmaps.add(bmp)
                }
            }
        }

        val timestamps = (0L until 30L).map { it * 1_000L }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            frameExtractor.extractThumbnails(videoUri, timestamps, 120, chunkSize = 8) { chunk ->
                emittedBitmaps.addAll(chunk.map { it.second })
            }
        }

        job.start()
        job.join()

        // At most 1 in-flight decode after frame 10 (frame 11), zero subsequent decodes
        assertEquals(11, decodeCalls)
        // First 8-frame chunk was already emitted and must NOT be recycled
        assertEquals(8, emittedBitmaps.size)
        emittedBitmaps.forEach { bmp ->
            verify(exactly = 0) { bmp.recycle() }
        }
        // Frames 9, 10, 11 belong to the uncommitted second chunk and MUST be recycled
        val unemittedBitmaps = decodedBitmaps.drop(8)
        assertEquals(3, unemittedBitmaps.size)
        unemittedBitmaps.forEach { bmp ->
            verify(exactly = 1) { bmp.recycle() }
        }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, videoUri) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `legacy 3-arg extractThumbnails opens video for empty timestamps and propagates SecurityException`() = runBlocking {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every {
            anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>())
        } throws SecurityException("Permission denied")
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        try {
            frameExtractor.extractThumbnails(videoUri, emptyList(), 120)
            fail("Expected SecurityException for empty timestamps on legacy 3-arg overload")
        } catch (_: SecurityException) {
            // Expected
        }

        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, videoUri) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `progressive extractThumbnails propagates decode and callback exceptions instead of reporting partial success`() = runBlocking {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        // 1. Decode exception mid-stream recycles uncommitted pendingChunk bitmaps and rethrows
        stubRetriever(1920, 1080)
        val decodedBitmaps = mutableListOf<Bitmap>()
        val emittedBitmaps = mutableListOf<Bitmap>()
        var decodeCalls = 0
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers {
            decodeCalls += 1
            if (decodeCalls == 11) {
                throw IllegalStateException("Decoder codec error")
            }
            mockk<Bitmap>().also { bmp ->
                every { bmp.isRecycled } returns false
                every { bmp.recycle() } returns Unit
                decodedBitmaps.add(bmp)
            }
        }

        val timestamps = (0L until 20L).map { it * 1_000L }
        try {
            frameExtractor.extractThumbnails(videoUri, timestamps, 120, chunkSize = 8) { chunk ->
                emittedBitmaps.addAll(chunk.map { it.second })
            }
            fail("Expected IllegalStateException from frame decode")
        } catch (e: IllegalStateException) {
            assertEquals("Decoder codec error", e.message)
        }

        assertEquals(8, emittedBitmaps.size)
        emittedBitmaps.forEach { bmp -> verify(exactly = 0) { bmp.recycle() } }
        val uncommitted = decodedBitmaps.drop(8)
        assertEquals(2, uncommitted.size)
        uncommitted.forEach { bmp -> verify(exactly = 1) { bmp.recycle() } }

        // 2. Callback exception from onChunk also propagates instead of returning Unit
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } answers { mockk<Bitmap>(relaxed = true) }

        try {
            frameExtractor.extractThumbnails(videoUri, timestamps, 120, chunkSize = 8) {
                throw IllegalArgumentException("Consumer failed")
            }
            fail("Expected IllegalArgumentException from onChunk")
        } catch (e: IllegalArgumentException) {
            assertEquals("Consumer failed", e.message)
        }

        verify(exactly = 2) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `progressive extractThumbnails rethrows SecurityException and releases retriever`() = runBlocking {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every {
            anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>())
        } throws SecurityException("Permission denied")
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        try {
            frameExtractor.extractThumbnails(videoUri, listOf(0L), 120) { }
            fail("Expected SecurityException")
        } catch (_: SecurityException) {
            // Expected
        }

        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `extractFrameWithInfo reuses cache within session and refreshes metadata and color space after invalidateCache`(): Unit = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val sameUri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://provider/videos/edited_in_place.mp4"
        }
        val decodedBitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 1920
            every { height } returns 1080
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)

        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns decodedBitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        val oldMetadata = VideoMetadata(dateTime = "2025-01-01T10:00:00", make = "OldCam")
        val newMetadata = VideoMetadata(dateTime = null, make = "EditedClip")
        val oldColorSpace = ColorSpaceInfo(type = ColorSpaceType.HDR10, colorGamut = ColorGamut.BT2020)
        val newColorSpace = ColorSpaceInfo(type = ColorSpaceType.SDR, colorGamut = ColorGamut.BT709)

        var metadataCalls = 0
        var colorSpaceCalls = 0
        every { MetadataExtractor.getVideoTrackFormat(context, sameUri) } returns mediaFormat
        every { MetadataExtractor.extract(context, sameUri, any(), mediaFormat) } answers {
            metadataCalls += 1
            if (metadataCalls == 1) oldMetadata else newMetadata
        }
        every { ColorSpaceDetector.detect(mediaFormat) } answers {
            colorSpaceCalls += 1
            if (colorSpaceCalls == 1) oldColorSpace else newColorSpace
        }

        val firstCapture = frameExtractor.extractFrameWithInfo(sameUri, 1_000_000L)!!
        val secondCaptureSameSession = frameExtractor.extractFrameWithInfo(sameUri, 2_000_000L)!!
        assertEquals(oldMetadata, firstCapture.second.metadata)
        assertEquals(oldColorSpace, firstCapture.second.colorSpace)
        assertEquals(oldMetadata, secondCaptureSameSession.second.metadata)
        assertEquals(oldColorSpace, secondCaptureSameSession.second.colorSpace)
        assertEquals(1, metadataCalls)
        assertEquals(1, colorSpaceCalls)

        // Video changed in place and was reopened -> invalidateCache forces fresh extraction for same URI
        frameExtractor.invalidateCache()

        val reopenedCapture = frameExtractor.extractFrameWithInfo(sameUri, 1_000_000L)!!
        assertEquals(newMetadata, reopenedCapture.second.metadata)
        assertEquals(newColorSpace, reopenedCapture.second.colorSpace)
        assertEquals(2, metadataCalls)
        assertEquals(2, colorSpaceCalls)
    }

    @Test
    fun `invalidateCache prevents in-flight extraction from previous session from populating new session cache`(): Unit = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val sameUri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://provider/videos/race_clip.mp4"
        }
        val decodedBitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 1280
            every { height } returns 720
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns decodedBitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        val session1EnteredExtract = CountDownLatch(1)
        val allowSession1ToFinish = CountDownLatch(1)
        val staleMetadata = VideoMetadata(dateTime = "2024-05-01T00:00:00")
        val freshMetadata = VideoMetadata(dateTime = "2026-11-24T12:00:00")
        var extractCount = 0

        every { MetadataExtractor.getVideoTrackFormat(context, sameUri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo()
        every { MetadataExtractor.extract(context, sameUri, any(), mediaFormat) } answers {
            val callIndex = synchronized(this@FrameExtractorTest) {
                extractCount += 1
                extractCount
            }
            if (callIndex == 1) {
                session1EnteredExtract.countDown()
                assertTrue(allowSession1ToFinish.await(5, TimeUnit.SECONDS))
                staleMetadata
            } else {
                freshMetadata
            }
        }

        val session1Deferred = async(Dispatchers.Default) {
            frameExtractor.extractFrameWithInfo(sameUri, 500_000L)
        }
        assertTrue(session1EnteredExtract.await(5, TimeUnit.SECONDS))

        // New video session starts while session 1 extraction is still in flight
        frameExtractor.invalidateCache()
        allowSession1ToFinish.countDown()
        session1Deferred.await()

        // Subsequent capture in the new session must NOT see staleMetadata from session 1
        val session2Result = frameExtractor.extractFrameWithInfo(sameUri, 500_000L)!!
        assertEquals(freshMetadata, session2Result.second.metadata)
        assertEquals(2, extractCount)
    }
}
