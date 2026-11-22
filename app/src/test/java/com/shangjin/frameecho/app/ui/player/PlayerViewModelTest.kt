package com.shangjin.frameecho.app.ui.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.model.CapturedFrame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: PlayerViewModel
    private lateinit var frameExtractor: FrameExtractor
    private lateinit var frameExporter: FrameExporter

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        frameExtractor = mockk(relaxed = true)
        frameExporter = mockk(relaxed = true)
        viewModel = PlayerViewModel()
        viewModel.setDependenciesForTesting(frameExtractor, frameExporter)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `requestThumbnail populates cache using batch extraction`() = runTest {
        // Arrange
        val uri = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val timestampUs = 0L

        coEvery {
            frameExtractor.extractThumbnails(any(), any(), any(), any(), any())
        } coAnswers {
            val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
            onChunk(listOf(timestampUs to bitmap))
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10000L) // Trigger loadThumbnailsForWindow

        // Act
        viewModel.requestThumbnail(0)

        // Assert
        val cachedBitmap = viewModel.getThumbnail(0)
        assertNotNull(cachedBitmap)
        // Note: multiple thumbnails might be loaded in batch, index 0 corresponds to 0us

        coVerify {
            frameExtractor.extractThumbnails(
                uri,
                any(), // list containing 0L
                PlayerViewModel.THUMBNAIL_WIDTH_PX,
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateDuration triggers batch thumbnail loading`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10000L)

        coVerify(atLeast = 1) {
            frameExtractor.extractThumbnails(uri, any(), any(), any(), any())
        }
    }

    @Test
    fun `requestThumbnail ignores out of range index`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10_000L)

        viewModel.requestThumbnail(-1)
        viewModel.requestThumbnail(PlayerViewModel.thumbnailCountForDuration(10_000L))

        // Only the initial updateDuration call should have triggered extraction
        coVerify(exactly = 1) {
            frameExtractor.extractThumbnails(uri, any(), any(), any(), any())
        }
    }

    @Test
    fun `captureAndSave ignores concurrent calls`() = runTest {
        val uri = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)

        coEvery { frameExtractor.extractFrameWithInfo(any(), any()) } coAnswers {
            delay(100)
            Pair(bitmap, frame)
        }

        viewModel.setVideoUri(uri)

        // Launch first capture
        val job1 = launch { viewModel.captureAndSave(false) }

        // Launch second capture immediately - should be ignored by the guard
        val job2 = launch { viewModel.captureAndSave(false) }

        advanceTimeBy(200)
        job1.join()
        job2.join()

        coVerify(exactly = 1) {
            frameExtractor.extractFrameWithInfo(any(), any())
        }
    }

    @Test
    fun `non-zero playback position orders requested timestamps outward from anchor`() = runTest {
        val uri = mockk<Uri>()
        val durationMs = 100_000L
        val currentPosMs = 50_000L
        val capturedTimestamps = slot<List<Long>>()

        coEvery {
            frameExtractor.extractThumbnails(any(), capture(capturedTimestamps), any(), any(), any())
        } returns Unit

        viewModel.setVideoUri(uri)
        viewModel.updatePosition(currentPosMs)
        viewModel.updateDuration(durationMs)

        val totalCount = PlayerViewModel.thumbnailCountForDuration(durationMs)
        val denominator = (totalCount - 1).coerceAtLeast(1)
        val expectedAnchor = ((totalCount - 1) * (currentPosMs.toDouble() / durationMs)).roundToInt()

        val timestampToIndex = (0 until totalCount).associateBy { idx ->
            ((durationMs * idx) / denominator) * 1000L
        }
        val requestedIndices = capturedTimestamps.captured.map { timestampToIndex.getValue(it) }
        assertEquals(totalCount, requestedIndices.size)
        assertEquals(expectedAnchor, requestedIndices.first())

        val distances = requestedIndices.map { abs(it - expectedAnchor) }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `requestThumbnail orders missing indices outward from requested slot`() = runTest {
        val uri = mockk<Uri>()
        val durationMs = 100_000L
        val capturedCalls = mutableListOf<List<Long>>()

        coEvery {
            frameExtractor.extractThumbnails(any(), any(), any(), any(), any())
        } coAnswers {
            capturedCalls.add(secondArg())
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(durationMs) // First call returns without populating cache

        val targetSlot = 32
        viewModel.requestThumbnail(targetSlot)

        assertEquals(2, capturedCalls.size)
        val totalCount = PlayerViewModel.thumbnailCountForDuration(durationMs)
        val denominator = (totalCount - 1).coerceAtLeast(1)
        val timestampToIndex = (0 until totalCount).associateBy { idx ->
            ((durationMs * idx) / denominator) * 1000L
        }
        val requestedIndices = capturedCalls.last().map { timestampToIndex.getValue(it) }
        assertEquals(targetSlot, requestedIndices.first())
        val distances = requestedIndices.map { abs(it - targetSlot) }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `first chunk thumbnails become visible before remaining offscreen chunks complete`() = runTest {
        val uri = mockk<Uri>()
        val durationMs = 900_000L // 15 min -> 150 thumbnails
        val allowRemainingChunks = CompletableDeferred<Unit>()

        coEvery {
            frameExtractor.extractThumbnails(any(), any(), any(), any(), any())
        } coAnswers {
            val timestamps = secondArg<List<Long>>()
            val chunkSize = arg<Int>(3)
            val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)

            val firstBatch = timestamps.take(chunkSize).map { ts -> ts to mockk<Bitmap>(relaxed = true) }
            onChunk(firstBatch)

            allowRemainingChunks.await()

            timestamps.drop(chunkSize).chunked(chunkSize).forEach { chunkTs ->
                onChunk(chunkTs.map { ts -> ts to mockk<Bitmap>(relaxed = true) })
            }
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(durationMs)

        // First 8 slots are immediately visible while remaining 142 slots are still in flight
        for (i in 0 until 8) {
            assertNotNull("Expected slot $i to be populated after first chunk", viewModel.getThumbnail(i))
        }
        for (i in 8 until 150) {
            assertNull("Expected offscreen slot $i to still be pending", viewModel.getThumbnail(i))
        }

        allowRemainingChunks.complete(Unit)

        for (i in 0 until 150) {
            assertNotNull("Expected slot $i to be populated after full stream", viewModel.getThumbnail(i))
        }
    }

    @Test
    fun `setVideoUri and clearVideoUri immediately cancel active streaming job without recycling published bitmaps`() = runTest {
        for (cancelWithNewVideo in listOf(true, false)) {
            val localViewModel = PlayerViewModel()
            val localExtractor = mockk<FrameExtractor>(relaxed = true)
            localViewModel.setDependenciesForTesting(localExtractor, frameExporter)

            val uri1 = mockk<Uri>()
            val uri2 = mockk<Uri>()
            val publishedBitmaps = List(8) { mockk<Bitmap>(relaxed = true) }
            val hangGate = CompletableDeferred<Unit>()
            var wasCancelled = false

            coEvery {
                localExtractor.extractThumbnails(uri1, any(), any(), any(), any())
            } coAnswers {
                val timestamps = secondArg<List<Long>>()
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
                onChunk(timestamps.take(8).zip(publishedBitmaps))
                try {
                    hangGate.await()
                } catch (e: CancellationException) {
                    wasCancelled = true
                    throw e
                }
            }

            localViewModel.setVideoUri(uri1)
            localViewModel.updateDuration(900_000L)
            assertNotNull(localViewModel.getThumbnail(0))

            if (cancelWithNewVideo) {
                localViewModel.setVideoUri(uri2)
            } else {
                localViewModel.clearVideoUri()
            }

            assertTrue("Expected streaming job to be cancelled immediately", wasCancelled)
            assertNull(localViewModel.getThumbnail(0))
            // Published bitmaps must not be eagerly recycled on video switch/clear (recycleBitmaps = false)
            publishedBitmaps.forEach { bmp ->
                verify(exactly = 0) { bmp.recycle() }
            }
        }
    }

    @Test
    fun `progressive 8-frame chunking cuts first viewport latency over 90 percent and reduces state map writes from 150 to 19`() {
        val standardDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val durationMs = 900_000L // 15 min -> 150 thumbnails
            val perFrameDecodeDelayMs = 20L
            val totalFrames = PlayerViewModel.thumbnailCountForDuration(durationMs)
            assertEquals(150, totalFrames)

            // 1. Baseline simulation: monolithic 150-frame decode + per-key SnapshotStateMap writes
            val baselineMap: SnapshotStateMap<Int, Bitmap> = mutableStateMapOf()
            var baselineMapWrites = 0
            val baselineObserver = Snapshot.registerGlobalWriteObserver { obj ->
                if (obj === baselineMap) {
                    baselineMapWrites += 1
                }
            }
            val baselineStartMs = currentTime
            var baselineFirstViewportReadyMs = -1L
            try {
                val baselineJob = launch {
                    val decoded = mutableListOf<Pair<Int, Bitmap>>()
                    for (i in 0 until totalFrames) {
                        delay(perFrameDecodeDelayMs)
                        decoded.add(i to mockk(relaxed = true))
                    }
                    for ((idx, bmp) in decoded) {
                        baselineMap[idx] = bmp
                    }
                }
                while (baselineFirstViewportReadyMs < 0L) {
                    advanceTimeBy(perFrameDecodeDelayMs)
                    runCurrent()
                    if ((0 until 8).all { baselineMap[it] != null }) {
                        baselineFirstViewportReadyMs = currentTime - baselineStartMs
                    }
                }
                baselineJob.join()
            } finally {
                baselineObserver.dispose()
            }

            // 2. Optimized progressive mode: 8-frame chunks + putAll batch state commits
            val benchViewModel = PlayerViewModel()
            val benchExtractor = mockk<FrameExtractor>(relaxed = true)
            benchViewModel.setDependenciesForTesting(benchExtractor, frameExporter)
            val uri = mockk<Uri>()

            coEvery {
                benchExtractor.extractThumbnails(any(), any(), any(), any(), any())
            } coAnswers {
                val timestamps = secondArg<List<Long>>()
                val chunkSize = arg<Int>(3)
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
                val pending = ArrayList<Pair<Long, Bitmap>>(chunkSize)
                for (ts in timestamps) {
                    delay(perFrameDecodeDelayMs)
                    pending.add(ts to mockk(relaxed = true))
                    if (pending.size >= chunkSize) {
                        val emitted = pending.toList()
                        pending.clear()
                        onChunk(emitted)
                    }
                }
                if (pending.isNotEmpty()) {
                    onChunk(pending.toList())
                }
            }

            var progressiveMapWrites = 0
            val progressiveObserver = Snapshot.registerGlobalWriteObserver { obj ->
                if (obj is SnapshotStateMap<*, *>) {
                    progressiveMapWrites += 1
                }
            }
            val progressiveStartMs = currentTime
            var progressiveFirstViewportReadyMs = -1L
            try {
                benchViewModel.setVideoUri(uri)
                benchViewModel.updateDuration(durationMs)
                runCurrent()

                while (progressiveFirstViewportReadyMs < 0L) {
                    advanceTimeBy(perFrameDecodeDelayMs)
                    runCurrent()
                    if ((0 until 8).all { benchViewModel.getThumbnail(it) != null }) {
                        progressiveFirstViewportReadyMs = currentTime - progressiveStartMs
                    }
                }
                advanceUntilIdle()
            } finally {
                progressiveObserver.dispose()
            }

            for (i in 0 until totalFrames) {
                assertNotNull(benchViewModel.getThumbnail(i))
            }

            // Baseline waits all 150 * 20ms = 3000ms; progressive waits 8 * 20ms = 160ms (94.67% faster)
            assertEquals(3_000L, baselineFirstViewportReadyMs)
            assertEquals(160L, progressiveFirstViewportReadyMs)
            val latencyReduction =
                (baselineFirstViewportReadyMs - progressiveFirstViewportReadyMs).toDouble() /
                    baselineFirstViewportReadyMs.toDouble()
            assertTrue(
                "Expected > 90% latency reduction, got ${latencyReduction * 100}%",
                latencyReduction > 0.90
            )

            // Main-thread SnapshotStateMap writes drop from 150 per-item writes to 19 chunked putAll commits
            assertEquals(150, baselineMapWrites)
            assertEquals(19, progressiveMapWrites)
            assertTrue(progressiveMapWrites <= 19)
        }
    }

    @Test
    fun `unpublished chunk bitmaps in flight on background thread are released via releaseBitmap when cancelled`() {
        val standardDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val localViewModel = PlayerViewModel()
            val localExtractor = mockk<FrameExtractor>(relaxed = true)
            localViewModel.setDependenciesForTesting(localExtractor, frameExporter)

            val uri = mockk<Uri>()
            val unpublishedBitmaps = List(8) { mockk<Bitmap>(relaxed = true) }
            val chunkReadyOnIo = CountDownLatch(1)
            val proceedToPublish = CountDownLatch(1)
            val ioFinished = CountDownLatch(1)

            coEvery {
                localExtractor.extractThumbnails(uri, any(), any(), any(), any())
            } coAnswers {
                val timestamps = secondArg<List<Long>>()
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        chunkReadyOnIo.countDown()
                        proceedToPublish.await(5, TimeUnit.SECONDS)
                        onChunk(timestamps.take(8).zip(unpublishedBitmaps))
                    } finally {
                        ioFinished.countDown()
                    }
                }
            }

            localViewModel.setVideoUri(uri)
            localViewModel.updateDuration(900_000L)
            runCurrent()

            assertTrue(chunkReadyOnIo.await(5, TimeUnit.SECONDS))
            // Cancel on Main before the IO thread delivers the chunk to Main
            localViewModel.clearVideoUri()
            proceedToPublish.countDown()
            assertTrue(ioFinished.await(5, TimeUnit.SECONDS))
            runCurrent()

            assertNull(localViewModel.getThumbnail(0))
            unpublishedBitmaps.forEach { bmp ->
                verify(exactly = 1) { localExtractor.releaseBitmap(bmp) }
            }
        }
    }
}
