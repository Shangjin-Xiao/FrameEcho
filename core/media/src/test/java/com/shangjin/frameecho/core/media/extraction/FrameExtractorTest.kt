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

    @Test
    fun `createPreviewThumbnail downscales landscape and portrait frames to target short side`() = runBlocking {
        val landscapeSource = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 3840
            every { height } returns 2160
            every { config } returns Bitmap.Config.ARGB_8888
        }
        val landscapeThumb = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createScaledBitmap(landscapeSource, 284, 160, true) } returns landscapeThumb

        val resultLandscape = frameExtractor.createPreviewThumbnail(landscapeSource, 160)
        assertSame(landscapeThumb, resultLandscape)
        verify(exactly = 0) { landscapeSource.recycle() }

        val portraitSource = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 1080
            every { height } returns 1920
            every { config } returns Bitmap.Config.ARGB_8888
        }
        val portraitThumb = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createScaledBitmap(portraitSource, 160, 284, true) } returns portraitThumb

        val resultPortrait = frameExtractor.createPreviewThumbnail(portraitSource, 160)
        assertSame(portraitThumb, resultPortrait)
        verify(exactly = 0) { portraitSource.recycle() }
    }

    @Test
    fun `createPreviewThumbnail copies small frame to independent software bitmap when scale returns source`() = runBlocking {
        val smallSource = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 120
            every { height } returns 90
            every { config } returns Bitmap.Config.ARGB_8888
        }
        val distinctCopy = mockk<Bitmap>(relaxed = true)
        // Android's Bitmap.createScaledBitmap returns `source` itself when target dimensions match
        every { Bitmap.createScaledBitmap(smallSource, 120, 90, true) } returns smallSource
        every { smallSource.copy(Bitmap.Config.ARGB_8888, false) } returns distinctCopy

        val result = frameExtractor.createPreviewThumbnail(smallSource, 160)

        assertSame(distinctCopy, result)
        verify(exactly = 1) { smallSource.copy(Bitmap.Config.ARGB_8888, false) }
        verify(exactly = 0) { smallSource.recycle() }
        verify(exactly = 0) { distinctCopy.recycle() }
    }

    @Test
    fun `createPreviewThumbnail converts HARDWARE or F16 bitmap to ARGB_8888 and recycles intermediate full-res copy`() = runBlocking {
        val hardwareSource = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 1920
            every { height } returns 1080
            every { config } returns Bitmap.Config.HARDWARE
        }
        val intermediateSoftCopy = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
        }
        val scaledSoftThumb = mockk<Bitmap>(relaxed = true)
        every { hardwareSource.copy(Bitmap.Config.ARGB_8888, false) } returns intermediateSoftCopy
        every { Bitmap.createScaledBitmap(intermediateSoftCopy, 284, 160, true) } returns scaledSoftThumb

        val result = frameExtractor.createPreviewThumbnail(hardwareSource, 160)

        assertSame(scaledSoftThumb, result)
        verify(exactly = 1) { intermediateSoftCopy.recycle() }
        verify(exactly = 0) { hardwareSource.recycle() }
        verify(exactly = 0) { scaledSoftThumb.recycle() }
    }

    @Test
    fun `createPreviewThumbnail returns null safely for recycled or invalid-dimension bitmap`() = runBlocking {
        val recycled = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns true
            every { width } returns 1920
            every { height } returns 1080
        }
        val zeroSize = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 0
            every { height } returns 1080
        }

        org.junit.Assert.assertNull(frameExtractor.createPreviewThumbnail(recycled, 160))
        org.junit.Assert.assertNull(frameExtractor.createPreviewThumbnail(zeroSize, 160))
    }

    @Test
    fun `extractFrameWithInfo uses Media3 GPU session with EXACT seek for HDR10, HDR10_PLUS, HLG, and DOLBY_VISION`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val hdrTypes = listOf(
            ColorSpaceInfo(
                type = ColorSpaceType.HDR10,
                transferFunction = com.shangjin.frameecho.core.model.TransferFunction.PQ,
                colorGamut = ColorGamut.BT2020,
                bitDepth = 10
            ),
            ColorSpaceInfo(
                type = ColorSpaceType.HDR10_PLUS,
                transferFunction = com.shangjin.frameecho.core.model.TransferFunction.PQ,
                colorGamut = ColorGamut.BT2020,
                bitDepth = 10,
                hasDynamicHdrMetadata = true
            ),
            ColorSpaceInfo(
                type = ColorSpaceType.HLG,
                transferFunction = com.shangjin.frameecho.core.model.TransferFunction.HLG,
                colorGamut = ColorGamut.BT2020,
                bitDepth = 10
            ),
            ColorSpaceInfo(
                type = ColorSpaceType.DOLBY_VISION,
                transferFunction = com.shangjin.frameecho.core.model.TransferFunction.PQ,
                colorGamut = ColorGamut.BT2020,
                bitDepth = 10,
                hasDynamicHdrMetadata = true
            )
        )

        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val expectedMetadata = VideoMetadata(make = "PhoneCam")
        val capturedConfigs = mutableListOf<FrameExtractor.Media3SessionConfig>()

        for ((idx, hdrColorSpace) in hdrTypes.withIndex()) {
            val uri = mockk<Uri> {
                every { this@mockk.toString() } returns "content://videos/hdr_$idx.mp4"
            }
            val gpuBitmap = mockk<Bitmap>(relaxed = true) {
                every { isRecycled } returns false
                every { width } returns 3840
                every { height } returns 2160
                every { config } returns Bitmap.Config.ARGB_8888
            }
            var requestedPosMs = -1L

            every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
            every { ColorSpaceDetector.detect(mediaFormat) } returns hdrColorSpace
            every { MetadataExtractor.extract(context, uri, null, mediaFormat) } returns expectedMetadata

            val extractor = FrameExtractor(context, sdkInt = 34)
            extractor.media3SessionFactory = { cfg ->
                capturedConfigs.add(cfg)
                object : FrameExtractor.Media3FrameSession {
                    override suspend fun extractFrame(positionMs: Long): Bitmap {
                        requestedPosMs = positionMs
                        return gpuBitmap
                    }
                    override fun close() = Unit
                }
            }

            val result = extractor.extractFrameWithInfo(uri, 2_500_000L)
            org.junit.Assert.assertNotNull(result)
            val (bitmap, frame) = result!!
            assertSame(gpuBitmap, bitmap)
            assertEquals(2_500L, requestedPosMs)
            assertEquals(hdrColorSpace, frame.colorSpace)
            assertTrue("GPU-extracted HDR frame must set isToneMapped = true", frame.isToneMapped)
            extractor.release()
        }

        assertEquals(4, capturedConfigs.size)
        for (cfg in capturedConfigs) {
            assertEquals(androidx.media3.exoplayer.SeekParameters.EXACT, cfg.seekParameters)
            org.junit.Assert.assertFalse(cfg.extractHdrFrames)
            org.junit.Assert.assertNull(cfg.targetWidth)
            org.junit.Assert.assertNull(cfg.targetHeight)
        }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) }
    }

    @Test
    fun `extractFrameWithInfo reuses Media3 GPU session for same URI and closes on releaseSession or invalidateCache`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/reusable_hdr.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val hdrInfo = ColorSpaceInfo(type = ColorSpaceType.HDR10, colorGamut = ColorGamut.BT2020)
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns hdrInfo
        every { MetadataExtractor.extract(context, uri, null, any()) } returns VideoMetadata()

        var createdSessions = 0
        var closedSessions = 0
        val extractor = FrameExtractor(context, sdkInt = 33)
        extractor.media3SessionFactory = {
            createdSessions += 1
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap = mockk(relaxed = true) {
                    every { isRecycled } returns false
                    every { width } returns 1920
                    every { height } returns 1080
                }
                override fun close() {
                    closedSessions += 1
                }
            }
        }

        extractor.extractFrameWithInfo(uri, 1_000_000L)
        extractor.extractFrameWithInfo(uri, 3_000_000L)
        assertEquals("Should reuse single Media3 session across captures on same URI", 1, createdSessions)
        assertEquals(0, closedSessions)

        // Idle timeout release closes GPU session while retaining metadata/colorSpace cache
        extractor.releaseSession()
        assertEquals(1, closedSessions)

        // Next capture lazily opens a fresh GPU session without re-querying ColorSpaceDetector
        extractor.extractFrameWithInfo(uri, 4_000_000L)
        assertEquals(2, createdSessions)
        verify(exactly = 1) { ColorSpaceDetector.detect(mediaFormat) }

        extractor.invalidateCache()
        assertEquals(2, closedSessions)
    }

    @Test
    fun `cancelling coroutine during Media3 HDR extraction interrupts immediately and closes session`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/cancel_hdr.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HLG,
            colorGamut = ColorGamut.BT2020
        )

        val enteredExtract = CountDownLatch(1)
        val sessionClosed = CountDownLatch(1)
        val extractor = FrameExtractor(context, sdkInt = 33)
        extractor.media3SessionFactory = {
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap? =
                    kotlinx.coroutines.suspendCancellableCoroutine {
                        enteredExtract.countDown()
                    }

                override fun close() {
                    sessionClosed.countDown()
                }
            }
        }

        val job = launch(Dispatchers.Default) {
            extractor.extractFrameWithInfo(uri, 1_500_000L)
        }
        assertTrue(enteredExtract.await(5, TimeUnit.SECONDS))

        job.cancel()
        job.join()

        assertTrue(
            "Underlying Media3 session must be closed deterministically upon coroutine cancellation",
            sessionClosed.await(5, TimeUnit.SECONDS)
        )
    }

    @Test
    fun `extractFrameWithInfo falls back to MediaMetadataRetriever RGBA_F16 when Media3 GPU throws or returns null`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        mockkConstructor(MediaMetadataRetriever.BitmapParams::class)
        every {
            anyConstructed<MediaMetadataRetriever.BitmapParams>().preferredConfig = any()
        } returns Unit

        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/fallback_hdr.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val hdrInfo = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = com.shangjin.frameecho.core.model.TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )
        val f16Bitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 3840
            every { height } returns 2160
            every { config } returns Bitmap.Config.RGBA_F16
        }

        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns hdrInfo
        every { MetadataExtractor.extract(context, uri, any(), any()) } returns VideoMetadata()
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(
                5_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                any()
            )
        } returns f16Bitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        var sessionClosedOnError = false
        val extractorApi30 = FrameExtractor(context, sdkInt = 30)
        extractorApi30.media3SessionFactory = {
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap? {
                    throw IllegalStateException("GL_EXT_YUV_target unsupported")
                }
                override fun close() {
                    sessionClosedOnError = true
                }
            }
        }

        val result = extractorApi30.extractFrameWithInfo(uri, 5_000_000L)
        org.junit.Assert.assertNotNull(result)
        val (bitmap, frame) = result!!
        assertSame(f16Bitmap, bitmap)
        org.junit.Assert.assertFalse(
            "Fallback frame must set isToneMapped = false so HdrToneMapper processes it",
            frame.isToneMapped
        )
        assertTrue("Failed Media3 session must be closed immediately", sessionClosedOnError)
        verify(exactly = 1) {
            anyConstructed<MediaMetadataRetriever.BitmapParams>().preferredConfig = Bitmap.Config.RGBA_F16
        }
        verify(exactly = 1) {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(
                5_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                any()
            )
        }
    }

    @Test
    fun `SDR extraction bypasses Media3 completely with zero session creation`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/sdr_clip.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val sdrBitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 1920
            every { height } returns 1080
        }
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(type = ColorSpaceType.SDR)
        every { MetadataExtractor.extract(context, uri, any(), mediaFormat) } returns VideoMetadata()
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } returns sdrBitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        var media3Invocations = 0
        val extractor = FrameExtractor(context, sdkInt = 34)
        extractor.media3SessionFactory = {
            media3Invocations += 1
            throw AssertionError("Media3 session must never be created for SDR videos")
        }

        val (bmp, frame) = extractor.extractFrameWithInfo(uri, 1_000_000L)!!
        assertSame(sdrBitmap, bmp)
        org.junit.Assert.assertFalse(frame.colorSpace.isHdr)
        assertEquals(0, media3Invocations)
    }

    @Test
    fun `HDR timeline thumbnails use Media3 GPU downscaling session and close after batch`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/hdr_timeline.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true) {
            every { containsKey(MediaFormat.KEY_WIDTH) } returns true
            every { getInteger(MediaFormat.KEY_WIDTH) } returns 3840
            every { containsKey(MediaFormat.KEY_HEIGHT) } returns true
            every { getInteger(MediaFormat.KEY_HEIGHT) } returns 2160
            every { containsKey(MediaFormat.KEY_ROTATION) } returns true
            every { getInteger(MediaFormat.KEY_ROTATION) } returns 0
        }
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HLG,
            colorGamut = ColorGamut.BT2020
        )

        var thumbConfig: FrameExtractor.Media3SessionConfig? = null
        var thumbClosed = 0
        val requestedMs = mutableListOf<Long>()
        val extractor = FrameExtractor(context, sdkInt = 34)
        extractor.media3SessionFactory = { cfg ->
            thumbConfig = cfg
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap {
                    requestedMs.add(positionMs)
                    return mockk(relaxed = true) { every { isRecycled } returns false }
                }
                override fun close() {
                    thumbClosed += 1
                }
            }
        }

        val emitted = mutableListOf<Pair<Long, Bitmap>>()
        extractor.extractThumbnails(
            videoUri = uri,
            timestampsUs = listOf(0L, 1_000_000L, 2_000_000L),
            targetWidth = 120,
            chunkSize = 2
        ) { chunk ->
            emitted.addAll(chunk)
        }

        assertEquals(3, emitted.size)
        assertEquals(listOf(0L, 1_000L, 2_000L), requestedMs)
        org.junit.Assert.assertNotNull(thumbConfig)
        assertEquals(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC, thumbConfig!!.seekParameters)
        org.junit.Assert.assertFalse(thumbConfig!!.extractHdrFrames)
        assertEquals(120, thumbConfig!!.targetWidth)
        assertEquals(68, thumbConfig!!.targetHeight)
        assertEquals("Thumbnail session must be deterministically closed after batch", 1, thumbClosed)
    }

    @Test
    fun `concurrent releaseSession during in-flight Media3 HDR extraction defers close until borrow finishes`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/concurrent_release_hdr.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val gpuBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 3840
            every { height } returns 2160
        }
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            colorGamut = ColorGamut.BT2020
        )
        every { MetadataExtractor.extract(context, uri, null, any()) } returns VideoMetadata()

        val enteredExtract = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        var closeCount = 0
        val extractor = FrameExtractor(context, sdkInt = 34)
        extractor.media3SessionFactory = {
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap {
                    enteredExtract.countDown()
                    assertTrue(allowFinish.await(5, TimeUnit.SECONDS))
                    assertEquals(
                        "Session must NOT be closed while extractFrame borrow is still in flight",
                        0,
                        synchronized(this@FrameExtractorTest) { closeCount }
                    )
                    return gpuBitmap
                }

                override fun close() {
                    synchronized(this@FrameExtractorTest) {
                        closeCount += 1
                    }
                }
            }
        }

        val deferred = async(Dispatchers.Default) {
            extractor.extractFrameWithInfo(uri, 2_000_000L)
        }
        assertTrue(enteredExtract.await(5, TimeUnit.SECONDS))

        // Main/idle thread calls releaseSession while extraction is in flight
        extractor.releaseSession()
        assertEquals(0, synchronized(this@FrameExtractorTest) { closeCount })

        allowFinish.countDown()
        val result = deferred.await()
        org.junit.Assert.assertNotNull(result)
        assertSame(gpuBitmap, result!!.first)
        assertTrue(result.second.isToneMapped)
        assertEquals(
            "Session must close deterministically once the in-flight borrow completes",
            1,
            synchronized(this@FrameExtractorTest) { closeCount }
        )
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) }
    }

    @Test
    fun `API 28 and 29 HDR fallback uses timestamp-exact OPTION_CLOSEST instead of duration-based frame index estimation`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/vfr_hdr_api29.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val fallbackBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 1920
            every { height } returns 1080
        }
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            colorGamut = ColorGamut.BT2020
        )
        every { MetadataExtractor.extract(context, uri, any(), any()) } returns VideoMetadata()
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any<Uri>()) } returns Unit
        every {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(
                3_333_333L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        } returns fallbackBitmap
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit

        for (sdk in listOf(28, 29)) {
            val extractor = FrameExtractor(context, sdkInt = sdk)
            extractor.media3SessionFactory = {
                object : FrameExtractor.Media3FrameSession {
                    override suspend fun extractFrame(positionMs: Long): Bitmap? = null
                    override fun close() = Unit
                }
            }
            val result = extractor.extractFrameWithInfo(uri, 3_333_333L)
            org.junit.Assert.assertNotNull(result)
            assertSame(fallbackBitmap, result!!.first)
            org.junit.Assert.assertFalse(result.second.isToneMapped)
        }

        verify(exactly = 2) {
            anyConstructed<MediaMetadataRetriever>().getFrameAtTime(
                3_333_333L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getFrameAtIndex(any(), any()) }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getFrameAtIndex(any()) }
    }

    @Test
    fun `API 30 HDR thumbnail fallback requests RGBA_F16 via getScaledFrameAtTime BitmapParams and tone-maps`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector, com.shangjin.frameecho.core.media.colorspace.HdrToneMapper)
        mockkConstructor(MediaMetadataRetriever.BitmapParams::class)
        every {
            anyConstructed<MediaMetadataRetriever.BitmapParams>().preferredConfig = any()
        } returns Unit

        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/hdr_thumb_fallback.mp4"
        }
        val hdrInfo = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = com.shangjin.frameecho.core.model.TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )
        val mediaFormat = mockk<MediaFormat>(relaxed = true) {
            every { containsKey(MediaFormat.KEY_WIDTH) } returns true
            every { getInteger(MediaFormat.KEY_WIDTH) } returns 3840
            every { containsKey(MediaFormat.KEY_HEIGHT) } returns true
            every { getInteger(MediaFormat.KEY_HEIGHT) } returns 2160
        }
        val f16ScaledThumb = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { config } returns Bitmap.Config.RGBA_F16
        }
        val mappedSdrThumb = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { config } returns Bitmap.Config.ARGB_8888
        }

        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns hdrInfo
        stubRetriever(3840, 2160)
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                120,
                68,
                any()
            )
        } returns f16ScaledThumb
        every {
            com.shangjin.frameecho.core.media.colorspace.HdrToneMapper.process(
                bitmap = f16ScaledThumb,
                colorSpaceInfo = hdrInfo,
                isAlreadyToneMapped = false
            )
        } returns mappedSdrThumb

        val extractor = FrameExtractor(context, sdkInt = 30)
        extractor.media3SessionFactory = {
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap? = null
                override fun close() = Unit
            }
        }

        val thumb = extractor.extractThumbnail(uri, 1_000_000L, 120)
        assertSame(mappedSdrThumb, thumb)
        verify(exactly = 1) {
            anyConstructed<MediaMetadataRetriever.BitmapParams>().preferredConfig = Bitmap.Config.RGBA_F16
        }
        verify(exactly = 1) {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                120,
                68,
                any()
            )
        }
        verify(exactly = 1) { f16ScaledThumb.recycle() }
    }

    @Test
    fun `switching video via invalidateCache while media3SessionFactory is creating session closes orphan session without leak`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/mid_create_switch.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        val gpuBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { width } returns 3840
            every { height } returns 2160
        }
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            colorGamut = ColorGamut.BT2020
        )
        every { MetadataExtractor.extract(context, uri, null, any()) } returns VideoMetadata()

        val enteredFactory = CountDownLatch(1)
        val allowFactoryFinish = CountDownLatch(1)
        var closeCount = 0

        val extractor = FrameExtractor(context, sdkInt = 34)
        extractor.media3SessionFactory = {
            enteredFactory.countDown()
            assertTrue(allowFactoryFinish.await(5, TimeUnit.SECONDS))
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap = gpuBitmap
                override fun close() {
                    synchronized(this@FrameExtractorTest) {
                        closeCount += 1
                    }
                }
            }
        }

        val deferred = async(Dispatchers.Default) {
            extractor.extractFrameWithInfo(uri, 1_000_000L)
        }
        assertTrue(enteredFactory.await(5, TimeUnit.SECONDS))

        // Switch video while media3SessionFactory is still building the session
        extractor.invalidateCache()
        assertEquals(0, synchronized(this@FrameExtractorTest) { closeCount })

        allowFactoryFinish.countDown()
        deferred.await()

        assertEquals(
            "Session created across generation boundary must be closed immediately after borrow ends",
            1,
            synchronized(this@FrameExtractorTest) { closeCount }
        )
    }

    @Test
    fun `onCleared style async cancel plus immediate releaseSession never closes session while extractFrame is still running`() = runBlocking {
        mockkObject(MetadataExtractor, ColorSpaceDetector)
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://videos/oncleared_race.mp4"
        }
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        every { MetadataExtractor.getVideoTrackFormat(context, uri) } returns mediaFormat
        every { ColorSpaceDetector.detect(mediaFormat) } returns ColorSpaceInfo(
            type = ColorSpaceType.HLG,
            colorGamut = ColorGamut.BT2020
        )
        every { MetadataExtractor.extract(context, uri, null, any()) } returns VideoMetadata()

        val enteredExtract = CountDownLatch(1)
        val allowCancelledExtractExit = CountDownLatch(1)
        var closeCount = 0

        val extractor = FrameExtractor(context, sdkInt = 34)
        extractor.media3SessionFactory = {
            object : FrameExtractor.Media3FrameSession {
                override suspend fun extractFrame(positionMs: Long): Bitmap? {
                    enteredExtract.countDown()
                    try {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { }
                    } finally {
                        // Simulate in-flight asynchronous teardown/decode work still running after job.cancel()
                        assertTrue(allowCancelledExtractExit.await(5, TimeUnit.SECONDS))
                    }
                    return null
                }

                override fun close() {
                    synchronized(this@FrameExtractorTest) {
                        closeCount += 1
                    }
                }
            }
        }

        val job = launch(Dispatchers.Default) {
            extractor.extractFrameWithInfo(uri, 2_000_000L)
        }
        assertTrue(enteredExtract.await(5, TimeUnit.SECONDS))

        // Mirrors ViewModel.onCleared(): cancel() returns asynchronously, followed immediately by releaseSession()
        job.cancel()
        extractor.releaseSession()

        // While extractFrame has not yet exited, session.close() MUST NOT have raced in-flight work
        assertEquals(
            "releaseSession must defer close() while cancelled capture is still unwinding extractFrame",
            0,
            synchronized(this@FrameExtractorTest) { closeCount }
        )

        allowCancelledExtractExit.countDown()
        job.join()

        assertEquals(
            "Session must close deterministically as soon as the cancelled capture finishes unwinding",
            1,
            synchronized(this@FrameExtractorTest) { closeCount }
        )
    }
}
