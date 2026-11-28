package com.shangjin.frameecho.app.ui.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.media3.common.PlaybackException
import com.shangjin.frameecho.app.intent.InboundVideoError
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        val requestedTimestampsPerCall = mutableListOf<List<Long>>()

        coEvery {
            frameExtractor.extractThumbnails(any(), any(), any(), any(), any())
        } coAnswers {
            val timestamps = secondArg<List<Long>>()
            requestedTimestampsPerCall.add(timestamps)
            // Keep the initial updateDuration-triggered extraction from populating slot 0,
            // so requestThumbnail(0) genuinely exercises the on-demand request path.
            if (requestedTimestampsPerCall.size >= 2) {
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
                onChunk(listOf(timestampUs to bitmap))
            }
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10000L)
        assertNull(viewModel.getThumbnail(0))

        // Act
        viewModel.requestThumbnail(0)

        // Assert
        val cachedBitmap = viewModel.getThumbnail(0)
        assertNotNull(cachedBitmap)
        assertEquals(2, requestedTimestampsPerCall.size)
        assertEquals(0L, requestedTimestampsPerCall.last().first())

        coVerify(exactly = 2) {
            frameExtractor.extractThumbnails(
                uri,
                any(),
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
    fun `updatePosition updates playbackPositionMs without changing uiState reference and deduplicates`() = runTest {
        val initialUiState = viewModel.uiState.value
        val emissions = mutableListOf<Long>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.playbackPositionMs.collect { emissions.add(it) }
        }

        assertEquals(0L, viewModel.playbackPositionMs.value)

        viewModel.updatePosition(250L)
        viewModel.updatePosition(250L)
        viewModel.updatePosition(500L)
        viewModel.updatePosition(500L)
        viewModel.updatePosition(750L)

        assertEquals(750L, viewModel.playbackPositionMs.value)
        assertSame(initialUiState, viewModel.uiState.value)
        assertEquals(listOf(0L, 250L, 500L, 750L), emissions)

        collectJob.cancel()
    }

    @Test
    fun `clearVideoUri resets playbackPositionMs to zero and preserves quick settings`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        viewModel.setMuted(true)
        viewModel.updatePosition(14_250L)

        assertEquals(14_250L, viewModel.playbackPositionMs.value)

        viewModel.clearVideoUri()

        assertEquals(0L, viewModel.playbackPositionMs.value)
        assertNull(viewModel.uiState.value.videoUri)
        assertTrue(viewModel.uiState.value.isMuted)
    }

    @Test
    fun `captureAndSave extracts frame using exact playbackPositionMs in microseconds`() = runTest {
        val uri = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)

        coEvery { frameExtractor.extractFrameWithInfo(uri, 8_765_000L) } returns Pair(bitmap, frame)

        viewModel.setVideoUri(uri)
        viewModel.updatePosition(8_765L)
        viewModel.captureAndSave(motionPhoto = false)

        coVerify(exactly = 1) {
            frameExtractor.extractFrameWithInfo(uri, 8_765_000L)
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
    fun `seekIntervalMs matches legacy rounding across all ranges and tie boundaries`() {
        fun legacySeekIntervalMs(durationMs: Long): Long {
            if (durationMs <= 0) return 1_000L
            val raw = (durationMs * 0.05).toLong()
            val nice = longArrayOf(1000, 2000, 5000, 10_000, 15_000, 20_000, 30_000)
            return nice.minByOrNull { kotlin.math.abs(it - raw) }!!
                .coerceIn(1_000L, 30_000L)
        }

        val testDurations = listOf(
            Long.MIN_VALUE,
            -5_000L,
            -1L,
            0L,
            1L,
            5_000L,
            20_000L,
            29_999L,
            30_000L,   // raw = 1_500L -> tie between 1_000 and 2_000 -> 1_000L
            30_040L,   // raw = 1_502L -> 2_000L
            60_000L,   // raw = 3_000L -> 2_000L
            70_000L,   // raw = 3_500L -> tie between 2_000 and 5_000 -> 2_000L
            70_040L,   // raw = 3_502L -> 5_000L
            100_000L,  // raw = 5_000L -> 5_000L
            150_000L,  // raw = 7_500L -> tie between 5_000 and 10_000 -> 5_000L
            150_040L,  // raw = 7_502L -> 10_000L
            250_000L,  // raw = 12_500L -> tie between 10_000 and 15_000 -> 10_000L
            250_040L,  // raw = 12_502L -> 15_000L
            350_000L,  // raw = 17_500L -> tie between 15_000 and 20_000 -> 15_000L
            350_040L,  // raw = 17_502L -> 20_000L
            500_000L,  // raw = 25_000L -> tie between 20_000 and 30_000 -> 20_000L
            500_040L,  // raw = 25_002L -> 30_000L
            600_000L,
            3_600_000L,
            Long.MAX_VALUE
        )

        for (durationMs in testDurations) {
            val expected = legacySeekIntervalMs(durationMs)
            assertEquals(
                "Mismatch for durationMs=$durationMs",
                expected,
                PlayerViewModel.seekIntervalForDuration(durationMs)
            )
            assertEquals(
                "State property mismatch for durationMs=$durationMs",
                expected,
                PlayerUiState(durationMs = durationMs).seekIntervalMs
            )
            assertEquals(
                "Copied state property mismatch for durationMs=$durationMs",
                expected,
                PlayerUiState().copy(durationMs = durationMs).seekIntervalMs
            )
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
    fun `seekIntervalMs bytecode contains no dynamic long array allocation and no Long boxing`() {
        val uiStateDisassembly = javapMethod(
            "com.shangjin.frameecho.app.ui.player.PlayerUiState",
            "getSeekIntervalMs"
        )
        assertFalse(
            "PlayerUiState.getSeekIntervalMs must not allocate long[]",
            uiStateDisassembly.contains("newarray")
        )
        assertFalse(
            "PlayerUiState.getSeekIntervalMs must not box Long",
            uiStateDisassembly.contains("Long.valueOf")
        )

        val companionDisassembly = javapMethod(
            "com.shangjin.frameecho.app.ui.player.PlayerViewModel\$Companion",
            "seekIntervalForDuration"
        )
        assertFalse(
            "seekIntervalForDuration must not allocate long[]",
            companionDisassembly.contains("newarray")
        )
        assertFalse(
            "seekIntervalForDuration must not box Long",
            companionDisassembly.contains("Long.valueOf")
        )
    }

    private fun javapMethod(className: String, methodName: String): String {
        val resourcePath = className.replace('.', '/') + ".class"
        val classUrl = PlayerViewModel::class.java.classLoader!!.getResource(resourcePath)
        assertNotNull("Compiled class not found: $resourcePath", classUrl)
        val javapBin = java.io.File(System.getProperty("java.home"), "bin/javap").let {
            if (it.exists()) it.absolutePath else "javap"
        }
        val command = if (classUrl!!.protocol == "jar") {
            val jarConn = classUrl.openConnection() as java.net.JarURLConnection
            val jarFile = java.io.File(jarConn.jarFileURL.toURI())
            listOf(javapBin, "-cp", jarFile.absolutePath, "-c", "-p", className)
        } else {
            listOf(javapBin, "-c", "-p", java.io.File(classUrl.toURI()).absolutePath)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val fullOutput = process.inputStream.bufferedReader().readText()
        assertEquals("javap failed: $fullOutput", 0, process.waitFor())
        // Extract target method section
        val lines = fullOutput.lines()
        val startIdx = lines.indexOfFirst { it.contains(" $methodName(") }
        assertTrue("Method $methodName not found in $className", startIdx >= 0)
        val endIdx = (startIdx + 1 until lines.size).firstOrNull { idx ->
            val line = lines[idx]
            line == "}" || (line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(";"))
        } ?: lines.size
        return lines.subList(startIdx, endIdx).joinToString("\n")
    }

    @Test
    fun `progressive 8-frame chunking cuts simulated virtual-time first viewport cache latency over 90 percent and reduces state map writes from 150 to 19`() {
        val standardDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val durationMs = 900_000L // 15 min -> 150 thumbnails
            val perFrameDecodeDelayMs = 20L
            val totalFrames = PlayerViewModel.thumbnailCountForDuration(durationMs)
            val maxSimulatedWaitMs = totalFrames * perFrameDecodeDelayMs * 2
            assertEquals(150, totalFrames)

            // Note: Measures deterministic virtual-time cache readiness under a simulated fixed
            // 20ms per-frame decode delay (not physical device codec/GPU rendering time) and
            // exact Compose SnapshotStateMap write counts.

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
                while (baselineFirstViewportReadyMs < 0L &&
                    currentTime - baselineStartMs < maxSimulatedWaitMs
                ) {
                    advanceTimeBy(perFrameDecodeDelayMs)
                    runCurrent()
                    if ((0 until 8).all { baselineMap[it] != null }) {
                        baselineFirstViewportReadyMs = currentTime - baselineStartMs
                    }
                }
                assertTrue(
                    "Baseline viewport slots 0..7 were not populated within ${maxSimulatedWaitMs}ms",
                    baselineFirstViewportReadyMs >= 0L
                )
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

                while (progressiveFirstViewportReadyMs < 0L &&
                    currentTime - progressiveStartMs < maxSimulatedWaitMs
                ) {
                    advanceTimeBy(perFrameDecodeDelayMs)
                    runCurrent()
                    if ((0 until 8).all { benchViewModel.getThumbnail(it) != null }) {
                        progressiveFirstViewportReadyMs = currentTime - progressiveStartMs
                    }
                }
                assertTrue(
                    "Progressive viewport slots 0..7 were not populated within ${maxSimulatedWaitMs}ms",
                    progressiveFirstViewportReadyMs >= 0L
                )
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
        val workerDispatcher = StandardTestDispatcher(standardDispatcher.scheduler)
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val localViewModel = PlayerViewModel()
            val localExtractor = mockk<FrameExtractor>(relaxed = true)
            localViewModel.setDependenciesForTesting(localExtractor, frameExporter)

            val uri = mockk<Uri>()
            val unpublishedBitmaps = List(8) { mockk<Bitmap>(relaxed = true) }
            val chunkReadyOnWorker = CompletableDeferred<Unit>()
            val proceedToPublish = CompletableDeferred<Unit>()

            coEvery {
                localExtractor.extractThumbnails(uri, any(), any(), any(), any())
            } coAnswers {
                val timestamps = secondArg<List<Long>>()
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
                withContext(workerDispatcher) {
                    chunkReadyOnWorker.complete(Unit)
                    withContext(NonCancellable) {
                        proceedToPublish.await()
                    }
                    onChunk(timestamps.take(8).zip(unpublishedBitmaps))
                }
            }

            localViewModel.setVideoUri(uri)
            localViewModel.updateDuration(900_000L)
            runCurrent()

            chunkReadyOnWorker.await()
            // Cancel on Main before the worker delivers the chunk back to Main
            localViewModel.clearVideoUri()
            proceedToPublish.complete(Unit)
            advanceUntilIdle()

            assertNull(localViewModel.getThumbnail(0))
            unpublishedBitmaps.forEach { bmp ->
                verify(exactly = 1) { localExtractor.releaseBitmap(bmp) }
            }
        }
    }

    @Test
    fun `requestThumbnail at distant slot during active stream restarts anchored at new viewport while preserving cached chunks`() = runTest {
        val uri = mockk<Uri>()
        val durationMs = 900_000L // 150 slots
        val totalCount = PlayerViewModel.thumbnailCountForDuration(durationMs)
        val denominator = (totalCount - 1).coerceAtLeast(1)
        val timestampToIndex = (0 until totalCount).associateBy { idx ->
            ((durationMs * idx) / denominator) * 1000L
        }

        val allowFirstChunk = CompletableDeferred<Unit>()
        val hangFirstStream = CompletableDeferred<Unit>()
        var firstStreamCancelled = false
        val capturedTimestampLists = mutableListOf<List<Long>>()

        coEvery {
            frameExtractor.extractThumbnails(uri, any(), any(), any(), any())
        } coAnswers {
            val timestamps = secondArg<List<Long>>()
            val chunkSize = arg<Int>(3)
            val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
            capturedTimestampLists.add(timestamps)

            if (capturedTimestampLists.size == 1) {
                try {
                    allowFirstChunk.await()
                    // Emit first chunk (slots 0..7) and stay active
                    onChunk(timestamps.take(chunkSize).map { ts -> ts to mockk(relaxed = true) })
                    hangFirstStream.await()
                } catch (e: CancellationException) {
                    firstStreamCancelled = true
                    throw e
                }
            } else {
                // Emit first chunk around the new distant anchor
                onChunk(timestamps.take(chunkSize).map { ts -> ts to mockk(relaxed = true) })
            }
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(durationMs)

        // Nearby UN-CACHED visible slot requests within the initial chunk window must reach
        // loadThumbnailsForFullVideo's viewport guard without cancelling or restarting stream 1
        assertNull(viewModel.getThumbnail(7))
        viewModel.requestThumbnail(7)
        assertFalse(firstStreamCancelled)
        assertEquals(1, capturedTimestampLists.size)

        // Now let the first chunk (slots 0..7) populate the cache while stream 1 stays active
        allowFirstChunk.complete(Unit)
        for (i in 0 until 8) {
            assertNotNull(viewModel.getThumbnail(i))
        }

        // User scrolls timeline to distant slot 120 while stream 1 is still active
        val distantAnchor = 120
        viewModel.requestThumbnail(distantAnchor)

        assertTrue("Expected initial stream to be cancelled on distant viewport jump", firstStreamCancelled)
        assertEquals(2, capturedTimestampLists.size)

        // Previously cached slots 0..7 are preserved and NOT re-requested
        for (i in 0 until 8) {
            assertNotNull(viewModel.getThumbnail(i))
        }
        val restartedIndices = capturedTimestampLists[1].map { timestampToIndex.getValue(it) }
        assertEquals(142, restartedIndices.size)
        assertTrue((0 until 8).none { it in restartedIndices })
        assertEquals(distantAnchor, restartedIndices.first())
        val distances = restartedIndices.map { abs(it - distantAnchor) }
        assertEquals(distances.sorted(), distances)
        assertNotNull(viewModel.getThumbnail(distantAnchor))
    }

    @Test
    fun `distant viewport jump waits for in-flight canceled stream teardown before starting replacement session`() {
        val standardDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val localViewModel = PlayerViewModel()
            val localExtractor = mockk<FrameExtractor>(relaxed = true)
            localViewModel.setDependenciesForTesting(localExtractor, frameExporter)

            val uri = mockk<Uri>()
            val durationMs = 900_000L
            val inFlightNativeDecodeEntered = CompletableDeferred<Unit>()
            val allowInFlightDecodeToFinish = CompletableDeferred<Unit>()
            var activeDecoderSessions = 0
            var maxConcurrentDecoderSessions = 0
            val startedSessionFirstTimestamps = mutableListOf<Long>()

            coEvery {
                localExtractor.extractThumbnails(uri, any(), any(), any(), any())
            } coAnswers {
                val timestamps = secondArg<List<Long>>()
                val chunkSize = arg<Int>(3)
                val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)

                activeDecoderSessions += 1
                maxConcurrentDecoderSessions = maxOf(maxConcurrentDecoderSessions, activeDecoderSessions)
                startedSessionFirstTimestamps.add(timestamps.first())
                try {
                    if (startedSessionFirstTimestamps.size == 1) {
                        onChunk(timestamps.take(chunkSize).map { ts -> ts to mockk(relaxed = true) })
                        inFlightNativeDecodeEntered.complete(Unit)
                        // Simulate non-interruptible native frame decode + retriever.release()
                        withContext(NonCancellable) {
                            allowInFlightDecodeToFinish.await()
                        }
                        currentCoroutineContext().ensureActive()
                    } else {
                        onChunk(timestamps.take(chunkSize).map { ts -> ts to mockk(relaxed = true) })
                    }
                } finally {
                    activeDecoderSessions -= 1
                }
            }

            localViewModel.setVideoUri(uri)
            localViewModel.updateDuration(durationMs)
            runCurrent()
            inFlightNativeDecodeEntered.await()

            val distantAnchor = 120
            localViewModel.requestThumbnail(distantAnchor)
            runCurrent()

            // Replacement stream must wait until the canceled stream exits extractThumbnails
            assertEquals(1, startedSessionFirstTimestamps.size)
            assertEquals(1, activeDecoderSessions)
            assertNull(localViewModel.getThumbnail(distantAnchor))

            allowInFlightDecodeToFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, startedSessionFirstTimestamps.size)
            assertEquals(0, activeDecoderSessions)
            assertEquals(1, maxConcurrentDecoderSessions)
            assertNotNull(localViewModel.getThumbnail(distantAnchor))
        }
    }

    @Test
    fun `updateDuration during active stream cancels stale extraction and restarts with updated timeline size`() = runTest {
        val uri = mockk<Uri>()
        val hangInitialStream = CompletableDeferred<Unit>()
        var initialStreamCancelled = false
        val requestedCounts = mutableListOf<Int>()

        coEvery {
            frameExtractor.extractThumbnails(uri, any(), any(), any(), any())
        } coAnswers {
            val timestamps = secondArg<List<Long>>()
            val onChunk = arg<suspend (List<Pair<Long, Bitmap>>) -> Unit>(4)
            requestedCounts.add(timestamps.size)
            if (requestedCounts.size == 1) {
                onChunk(timestamps.take(4).map { ts -> ts to mockk(relaxed = true) })
                try {
                    hangInitialStream.await()
                } catch (e: CancellationException) {
                    initialStreamCancelled = true
                    throw e
                }
            } else {
                onChunk(timestamps.map { ts -> ts to mockk(relaxed = true) })
            }
        }

        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10_000L) // 15 thumbnails
        assertEquals(listOf(PlayerViewModel.thumbnailCountForDuration(10_000L)), requestedCounts)

        // Duration updates to 120_000L (54 thumbnails) while initial stream is still active
        val updatedDurationMs = 120_000L
        val expectedNewCount = PlayerViewModel.thumbnailCountForDuration(updatedDurationMs)
        viewModel.updateDuration(updatedDurationMs)

        assertTrue("Expected stale duration stream to be cancelled", initialStreamCancelled)
        assertEquals(2, requestedCounts.size)
        assertEquals(expectedNewCount, requestedCounts.last())
        for (i in 0 until expectedNewCount) {
            assertNotNull("Expected slot $i populated for updated duration", viewModel.getThumbnail(i))
        }
    }

    @Test
    fun `onExternalVideoReceived marks session as external and resets to in-app when setVideoUri is called`() = runTest {
        val initialInAppUri = mockk<Uri>()
        val externalUri = mockk<Uri>()
        val nextInAppUri = mockk<Uri>()

        viewModel.setVideoUri(initialInAppUri)
        viewModel.updatePosition(12_000L)
        viewModel.updateDuration(60_000L)
        assertFalse(viewModel.uiState.value.isExternalSession)

        // Warm switch via external Intent resets position/duration and marks external session
        viewModel.onExternalVideoReceived(externalUri)
        assertSame(externalUri, viewModel.uiState.value.videoUri)
        assertTrue(viewModel.uiState.value.isExternalSession)
        assertEquals(0L, viewModel.playbackPositionMs.value)
        assertEquals(0L, viewModel.uiState.value.durationMs)
        assertNull(viewModel.uiState.value.pendingExternalVideoUri)

        // Switching via top-bar local picker restores standard in-app back behavior
        viewModel.setVideoUri(nextInAppUri)
        assertSame(nextInAppUri, viewModel.uiState.value.videoUri)
        assertFalse(viewModel.uiState.value.isExternalSession)
    }

    @Test
    fun `onExternalVideoReceived during ongoing export stages URI without cancelling export and supports keep or immediate replace`() = runTest {
        val originalUri = mockk<Uri>()
        val stagedExternalUri1 = mockk<Uri>()
        val stagedExternalUri2 = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)
        val exportGate = CompletableDeferred<ExportResult>()
        var exportCancelled = false

        coEvery { frameExtractor.extractFrameWithInfo(originalUri, any()) } returns Pair(bitmap, frame)
        coEvery { frameExporter.exportMotionPhoto(originalUri, bitmap, frame, any(), any()) } coAnswers {
            try {
                exportGate.await()
            } catch (e: CancellationException) {
                exportCancelled = true
                throw e
            }
        }

        viewModel.setVideoUri(originalUri)
        viewModel.captureAndSave(motionPhoto = true)
        assertTrue(viewModel.uiState.value.isExporting)

        // External video arrives while export is active -> staged, NOT cancelled
        viewModel.onExternalVideoReceived(stagedExternalUri1)
        assertFalse(exportCancelled)
        assertTrue(viewModel.uiState.value.isExporting)
        assertSame(originalUri, viewModel.uiState.value.videoUri)
        assertSame(stagedExternalUri1, viewModel.uiState.value.pendingExternalVideoUri)

        // User chooses "Keep Current" -> clears staged URI and keeps export running
        viewModel.dismissPendingExternalVideo()
        assertNull(viewModel.uiState.value.pendingExternalVideoUri)
        assertFalse(exportCancelled)
        assertTrue(viewModel.uiState.value.isExporting)
        assertSame(originalUri, viewModel.uiState.value.videoUri)

        // Another external video arrives and user confirms "Switch Now" -> cancels export and switches
        viewModel.onExternalVideoReceived(stagedExternalUri2)
        assertSame(stagedExternalUri2, viewModel.uiState.value.pendingExternalVideoUri)

        viewModel.confirmReplaceWithExternalVideo()
        assertTrue("Expected ongoing export to be cancelled only upon explicit confirmation", exportCancelled)
        assertSame(stagedExternalUri2, viewModel.uiState.value.videoUri)
        assertTrue(viewModel.uiState.value.isExternalSession)
        assertNull(viewModel.uiState.value.pendingExternalVideoUri)
        assertFalse(viewModel.uiState.value.isExporting)
    }

    @Test
    fun `switchToExternalVideoAfterExport waits for active export completion before loading staged external video`() = runTest {
        val originalUri = mockk<Uri>()
        val stagedExternalUri = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)
        val finishExport = CompletableDeferred<ExportResult>()
        val successResult = ExportResult.Success(
            outputPath = "content://media/external/images/media/99",
            format = ExportFormat.JPEG,
            width = 1920,
            height = 1080,
            fileSizeBytes = 1024L
        )

        coEvery { frameExtractor.extractFrameWithInfo(originalUri, any()) } returns Pair(bitmap, frame)
        coEvery { frameExporter.exportStaticFrame(bitmap, frame, any(), any()) } coAnswers {
            finishExport.await()
        }

        viewModel.setVideoUri(originalUri)
        viewModel.captureAndSave(motionPhoto = false)
        assertTrue(viewModel.uiState.value.isExporting)

        viewModel.onExternalVideoReceived(stagedExternalUri)
        viewModel.switchToExternalVideoAfterExport()

        // Dialog is dismissed while export continues on original video
        assertTrue(viewModel.uiState.value.deferPendingExternalSwitchUntilExportEnds)
        assertSame(originalUri, viewModel.uiState.value.videoUri)
        assertTrue(viewModel.uiState.value.isExporting)

        // Once export completes, ViewModel preserves exportResult and automatically loads staged video
        finishExport.complete(successResult)
        assertFalse(viewModel.uiState.value.isExporting)
        assertSame(stagedExternalUri, viewModel.uiState.value.videoUri)
        assertTrue(viewModel.uiState.value.isExternalSession)
        assertNull(viewModel.uiState.value.pendingExternalVideoUri)
        assertFalse(viewModel.uiState.value.deferPendingExternalSwitchUntilExportEnds)
        assertEquals(successResult, viewModel.uiState.value.exportResult)
    }

    @Test
    fun `onExternalVideoError surfaces localized error types without clobbering existing video`() = runTest {
        val existingUri = mockk<Uri>()
        viewModel.setVideoUri(existingUri)

        viewModel.onExternalVideoError(InboundVideoError.UNSUPPORTED_FORMAT)
        assertEquals(PlayerError.UnsupportedVideoFormat, viewModel.uiState.value.error)
        assertSame(existingUri, viewModel.uiState.value.videoUri)

        viewModel.clearError()
        viewModel.onExternalVideoError(InboundVideoError.ACCESS_FAILED)
        assertEquals(PlayerError.ExternalVideoAccessFailed, viewModel.uiState.value.error)
        assertSame(existingUri, viewModel.uiState.value.videoUri)
    }

    @Test
    fun `video session transitions and clearVideoUri invalidate FrameExtractor metadata cache`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        verify(exactly = 1) { frameExtractor.invalidateCache() }

        // Re-importing the exact same URI (e.g. edited in place externally) invalidates cache again
        viewModel.onExternalVideoReceived(uri)
        verify(exactly = 2) { frameExtractor.invalidateCache() }

        viewModel.clearVideoUri()
        verify(exactly = 3) { frameExtractor.invalidateCache() }
    }

    @Test
    fun `switchToExternalVideoAfterExport preserves capture and export failures when switching to staged video`() = runTest {
        val originalUri = mockk<Uri>()
        val stagedUri1 = mockk<Uri>()
        val stagedUri2 = mockk<Uri>()
        val stagedUri3 = mockk<Uri>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)

        // 1. Capture fails (returns null) after "After Export" was selected
        val captureGate = CompletableDeferred<Pair<Bitmap, CapturedFrame>?>()
        coEvery { frameExtractor.extractFrameWithInfo(originalUri, any()) } coAnswers {
            captureGate.await()
        }
        viewModel.setVideoUri(originalUri)
        viewModel.captureAndSave(motionPhoto = false)
        viewModel.onExternalVideoReceived(stagedUri1)
        viewModel.switchToExternalVideoAfterExport()

        captureGate.complete(null)
        assertSame(stagedUri1, viewModel.uiState.value.videoUri)
        assertEquals(PlayerError.CaptureFailed, viewModel.uiState.value.error)

        // 2. Export throws SecurityException after "After Export" was selected
        val permissionGate = CompletableDeferred<ExportResult>()
        coEvery { frameExtractor.extractFrameWithInfo(stagedUri1, any()) } returns Pair(bitmap, frame)
        coEvery { frameExporter.exportStaticFrame(bitmap, frame, any(), any()) } coAnswers {
            permissionGate.await()
        }
        viewModel.captureAndSave(motionPhoto = false)
        viewModel.onExternalVideoReceived(stagedUri2)
        viewModel.switchToExternalVideoAfterExport()

        permissionGate.completeExceptionally(SecurityException("URI grant expired"))
        assertSame(stagedUri2, viewModel.uiState.value.videoUri)
        assertEquals(PlayerError.PermissionDenied, viewModel.uiState.value.error)

        // 3. Export throws generic Exception after "After Export" was selected
        val exportFailGate = CompletableDeferred<ExportResult>()
        coEvery { frameExtractor.extractFrameWithInfo(stagedUri2, any()) } returns Pair(bitmap, frame)
        coEvery { frameExporter.exportStaticFrame(bitmap, frame, any(), any()) } coAnswers {
            exportFailGate.await()
        }
        viewModel.captureAndSave(motionPhoto = false)
        viewModel.onExternalVideoReceived(stagedUri3)
        viewModel.switchToExternalVideoAfterExport()

        exportFailGate.completeExceptionally(IllegalStateException("Storage full"))
        assertSame(stagedUri3, viewModel.uiState.value.videoUri)
        assertEquals(PlayerError.ExportFailed("Storage full"), viewModel.uiState.value.error)
    }

    @Test
    fun `onPlaybackError maps corrupt container, unsupported codec, and access errors to localized PlayerError`() = runTest {
        val externalUri = mockk<Uri>()
        val inAppUri = mockk<Uri>()

        // No-op when no active video URI is loaded
        viewModel.onPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertNull(viewModel.uiState.value.error)

        // External video session: corrupt container / decoder failure -> UnsupportedVideoFormat
        viewModel.onExternalVideoReceived(externalUri)
        viewModel.setPlaying(true)
        viewModel.onPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertFalse(viewModel.uiState.value.isPlaying)
        assertEquals(PlayerError.UnsupportedVideoFormat, viewModel.uiState.value.error)

        viewModel.clearError()
        viewModel.onPlaybackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        assertEquals(PlayerError.UnsupportedVideoFormat, viewModel.uiState.value.error)

        // External video session: permission / file missing -> ExternalVideoAccessFailed
        viewModel.clearError()
        viewModel.onPlaybackError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertEquals(PlayerError.ExternalVideoAccessFailed, viewModel.uiState.value.error)

        viewModel.clearError()
        viewModel.onPlaybackError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(PlayerError.ExternalVideoAccessFailed, viewModel.uiState.value.error)

        // In-app session: SecurityException cause -> PermissionDenied
        viewModel.setVideoUri(inAppUri)
        viewModel.onPlaybackError(
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            RuntimeException("Wrapper", SecurityException("Revoked"))
        )
        assertEquals(PlayerError.PermissionDenied, viewModel.uiState.value.error)
    }

    @Test
    fun `clearExportResult consumes transient snackbar event while retaining lastExportSuccess and preview thumbnail`() = runTest {
        val uri = mockk<Uri>()
        val fullBitmap = mockk<Bitmap>(relaxed = true)
        val previewBitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)
        val exportedSuccess = ExportResult.Success(
            outputPath = "content://media/external/images/media/512",
            width = 3840,
            height = 2160,
            fileSizeBytes = 2_048_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = true
        )

        coEvery { frameExtractor.extractFrameWithInfo(uri, any()) } returns Pair(fullBitmap, frame)
        coEvery { frameExtractor.createPreviewThumbnail(fullBitmap, any()) } returns previewBitmap
        coEvery { frameExporter.exportMotionPhoto(uri, fullBitmap, frame, any(), any()) } returns exportedSuccess

        viewModel.setVideoUri(uri)
        viewModel.captureAndSave(motionPhoto = true)

        assertEquals(exportedSuccess, viewModel.uiState.value.exportResult)
        assertEquals(exportedSuccess, viewModel.uiState.value.lastExportSuccess)
        assertSame(previewBitmap, viewModel.uiState.value.capturedPreviewBitmap)
        assertSame(frame, viewModel.uiState.value.capturedFrame)
        assertTrue(viewModel.uiState.value.isCapturedMotionPhoto)

        // Simulate 4s Snackbar dismissal consuming transient exportResult
        viewModel.clearExportResult()

        assertNull(viewModel.uiState.value.exportResult)
        assertEquals(exportedSuccess, viewModel.uiState.value.lastExportSuccess)
        assertSame(previewBitmap, viewModel.uiState.value.capturedPreviewBitmap)
        assertSame(frame, viewModel.uiState.value.capturedFrame)
        assertTrue(viewModel.uiState.value.isCapturedMotionPhoto)
        verify(exactly = 0) { previewBitmap.recycle() }
    }

    @Test
    fun `in-progress second capture clears stale lastExportSuccess until new export finishes and never recycles preview bitmaps`() = runTest {
        val uri = mockk<Uri>()
        val bitmap1 = mockk<Bitmap>(relaxed = true)
        val preview1 = mockk<Bitmap>(relaxed = true)
        val frame1 = mockk<CapturedFrame>(relaxed = true)
        val success1 = ExportResult.Success(
            outputPath = "content://media/external/images/media/101",
            width = 1920,
            height = 1080,
            fileSizeBytes = 500_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = false
        )

        val bitmap2 = mockk<Bitmap>(relaxed = true)
        val preview2 = mockk<Bitmap>(relaxed = true)
        val frame2 = mockk<CapturedFrame>(relaxed = true)
        val exportGate2 = CompletableDeferred<ExportResult>()
        val success2 = ExportResult.Success(
            outputPath = "content://media/external/images/media/102",
            width = 1920,
            height = 1080,
            fileSizeBytes = 650_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = false
        )

        // Frame 1 capture & export
        coEvery { frameExtractor.extractFrameWithInfo(uri, 1_000_000L) } returns Pair(bitmap1, frame1)
        coEvery { frameExtractor.createPreviewThumbnail(bitmap1, any()) } returns preview1
        coEvery { frameExporter.exportStaticFrame(bitmap1, frame1, any(), any()) } returns success1

        viewModel.setVideoUri(uri)
        viewModel.updatePosition(1_000L)
        viewModel.captureAndSave(motionPhoto = false)
        viewModel.clearExportResult()
        assertEquals(success1, viewModel.uiState.value.lastExportSuccess)
        assertSame(preview1, viewModel.uiState.value.capturedPreviewBitmap)

        // Frame 2 capture starts and suspends during export
        coEvery { frameExtractor.extractFrameWithInfo(uri, 5_000_000L) } returns Pair(bitmap2, frame2)
        coEvery { frameExtractor.createPreviewThumbnail(bitmap2, any()) } returns preview2
        coEvery { frameExporter.exportStaticFrame(bitmap2, frame2, any(), any()) } coAnswers {
            exportGate2.await()
        }

        viewModel.updatePosition(5_000L)
        viewModel.captureAndSave(motionPhoto = false)

        // While Frame 2 is exporting: preview2 is shown, isExporting is true, and stale success1 is cleared
        assertTrue(viewModel.uiState.value.isExporting)
        assertSame(preview2, viewModel.uiState.value.capturedPreviewBitmap)
        assertSame(frame2, viewModel.uiState.value.capturedFrame)
        assertNull(viewModel.uiState.value.lastExportSuccess)
        // Old full-res bitmap1 is released, while old UI preview1 is left to GC (never explicitly recycled)
        verify(exactly = 1) { frameExtractor.releaseBitmap(bitmap1) }
        verify(exactly = 0) { preview1.recycle() }
        verify(exactly = 0) { frameExtractor.releaseBitmap(preview1) }

        // Complete Frame 2 export -> lastExportSuccess activates immediately with new URI
        exportGate2.complete(success2)
        assertFalse(viewModel.uiState.value.isExporting)
        assertEquals(success2, viewModel.uiState.value.lastExportSuccess)
        assertSame(preview2, viewModel.uiState.value.capturedPreviewBitmap)
    }

    @Test
    fun `video switch and clearVideoUri reset lastExportSuccess and capturedPreviewBitmap without recycling preview`() = runTest {
        val uri1 = mockk<Uri>()
        val uri2 = mockk<Uri>()
        val externalUri = mockk<Uri>()
        val fullBitmap = mockk<Bitmap>(relaxed = true)
        val previewBitmap = mockk<Bitmap>(relaxed = true)
        val frame = mockk<CapturedFrame>(relaxed = true)
        val exportedSuccess = ExportResult.Success(
            outputPath = "content://media/external/images/media/777",
            width = 1920,
            height = 1080,
            fileSizeBytes = 800_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = true
        )

        coEvery { frameExtractor.extractFrameWithInfo(any(), any()) } returns Pair(fullBitmap, frame)
        coEvery { frameExtractor.createPreviewThumbnail(fullBitmap, any()) } returns previewBitmap
        coEvery { frameExporter.exportMotionPhoto(any(), fullBitmap, frame, any(), any()) } returns exportedSuccess

        // 1. Switch via setVideoUri clears persistent export state & preview
        viewModel.setVideoUri(uri1)
        viewModel.captureAndSave(motionPhoto = true)
        viewModel.clearExportResult()
        assertNotNull(viewModel.uiState.value.lastExportSuccess)
        assertSame(previewBitmap, viewModel.uiState.value.capturedPreviewBitmap)

        viewModel.setVideoUri(uri2)
        assertNull(viewModel.uiState.value.lastExportSuccess)
        assertNull(viewModel.uiState.value.capturedPreviewBitmap)
        assertNull(viewModel.uiState.value.capturedFrame)
        assertFalse(viewModel.uiState.value.isCapturedMotionPhoto)
        verify(exactly = 0) { previewBitmap.recycle() }

        // 2. Switch via external video Intent clears persistent export state & preview
        val fullBitmap2 = mockk<Bitmap>(relaxed = true)
        val previewBitmap2 = mockk<Bitmap>(relaxed = true)
        coEvery { frameExtractor.extractFrameWithInfo(uri2, any()) } returns Pair(fullBitmap2, frame)
        coEvery { frameExtractor.createPreviewThumbnail(fullBitmap2, any()) } returns previewBitmap2
        coEvery { frameExporter.exportStaticFrame(fullBitmap2, frame, any(), any()) } returns exportedSuccess.copy(isMotionPhoto = false)

        viewModel.captureAndSave(motionPhoto = false)
        assertNotNull(viewModel.uiState.value.lastExportSuccess)
        assertSame(previewBitmap2, viewModel.uiState.value.capturedPreviewBitmap)

        viewModel.onExternalVideoReceived(externalUri)
        assertNull(viewModel.uiState.value.lastExportSuccess)
        assertNull(viewModel.uiState.value.capturedPreviewBitmap)
        verify(exactly = 0) { previewBitmap2.recycle() }

        // 3. Return home via clearVideoUri clears persistent export state & preview
        val fullBitmap3 = mockk<Bitmap>(relaxed = true)
        val previewBitmap3 = mockk<Bitmap>(relaxed = true)
        coEvery { frameExtractor.extractFrameWithInfo(externalUri, any()) } returns Pair(fullBitmap3, frame)
        coEvery { frameExtractor.createPreviewThumbnail(fullBitmap3, any()) } returns previewBitmap3
        coEvery { frameExporter.exportStaticFrame(fullBitmap3, frame, any(), any()) } returns exportedSuccess

        viewModel.captureAndSave(motionPhoto = false)
        assertNotNull(viewModel.uiState.value.lastExportSuccess)
        assertSame(previewBitmap3, viewModel.uiState.value.capturedPreviewBitmap)

        viewModel.clearVideoUri()
        assertNull(viewModel.uiState.value.lastExportSuccess)
        assertNull(viewModel.uiState.value.capturedPreviewBitmap)
        assertNull(viewModel.uiState.value.capturedFrame)
        verify(exactly = 0) { previewBitmap3.recycle() }
    }

    @Test
    fun `captureAndSave releases FrameExtractor GPU session after idle timeout and resets timer on new capture`() {
        val standardDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(standardDispatcher)
        runTest(standardDispatcher) {
            val localViewModel = PlayerViewModel()
            val localExtractor = mockk<FrameExtractor>(relaxed = true)
            val localExporter = mockk<FrameExporter>(relaxed = true)
            localViewModel.setDependenciesForTesting(localExtractor, localExporter)

            val uri = mockk<Uri>()
            val bmp1 = mockk<Bitmap>(relaxed = true)
            val bmp2 = mockk<Bitmap>(relaxed = true)
            val frame = mockk<CapturedFrame>(relaxed = true)
            val success = ExportResult.Success(
                outputPath = "content://media/external/images/media/55",
                width = 3840,
                height = 2160,
                fileSizeBytes = 1024L,
                format = ExportFormat.JPEG
            )

            coEvery { localExtractor.extractFrameWithInfo(uri, 1_000_000L) } returns Pair(bmp1, frame)
            coEvery { localExtractor.extractFrameWithInfo(uri, 2_000_000L) } returns Pair(bmp2, frame)
            coEvery { localExtractor.createPreviewThumbnail(any(), any()) } returns mockk(relaxed = true)
            coEvery { localExporter.exportStaticFrame(any(), frame, any(), any()) } returns success

            localViewModel.setVideoUri(uri)
            localViewModel.updatePosition(1_000L)
            localViewModel.captureAndSave(motionPhoto = false)
            runCurrent()

            // Before 10s idle timeout, GPU session stays warm for back-to-back captures
            advanceTimeBy(PlayerViewModel.HDR_CAPTURE_IDLE_RELEASE_MS - 2_000L)
            runCurrent()
            verify(exactly = 0) { localExtractor.releaseSession() }

            // Second capture at 8s resets the 10s idle timer
            localViewModel.updatePosition(2_000L)
            localViewModel.captureAndSave(motionPhoto = false)
            runCurrent()

            advanceTimeBy(5_000L)
            runCurrent()
            verify(exactly = 0) { localExtractor.releaseSession() }

            // Completing full 10s idle window after second capture deterministically releases session
            advanceTimeBy(PlayerViewModel.HDR_CAPTURE_IDLE_RELEASE_MS - 5_000L)
            runCurrent()
            verify(exactly = 1) { localExtractor.releaseSession() }
        }
    }

    @Test
    fun `onCleared deterministically releases FrameExtractor GPU session`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)

        viewModel.onCleared()

        verify(atLeast = 1) { frameExtractor.releaseSession() }
    }
}
