package com.shangjin.frameecho.app.ui.player

import android.graphics.Bitmap
import android.net.Uri
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

        coEvery {
            frameExtractor.extractThumbnails(any(), any(), any())
        } returns listOf(timestampUs to bitmap)

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
                PlayerViewModel.THUMBNAIL_WIDTH_PX
            )
        }
    }

    @Test
    fun `updateDuration triggers batch thumbnail loading`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10000L)

        coVerify(atLeast = 1) {
            frameExtractor.extractThumbnails(uri, any(), any())
        }
    }

    @Test
    fun `requestThumbnail ignores out of range index`() = runTest {
        val uri = mockk<Uri>()
        viewModel.setVideoUri(uri)
        viewModel.updateDuration(10_000L)

        viewModel.requestThumbnail(-1)
        viewModel.requestThumbnail(PlayerViewModel.thumbnailCountForDuration(10_000L))

        // It might still have been called by updateDuration or updateThumbnailCenter,
        // but not because of these out-of-range calls.
        // We just check it doesn't crash.
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
}
