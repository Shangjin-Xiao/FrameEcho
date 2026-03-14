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
import org.junit.Assert.assertNotNull
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
    fun `cycleFormat cycles through all ExportFormat entries and wraps around`() {
        // Initial format should be JPEG
        assertEquals(ExportFormat.JPEG, viewModel.uiState.value.exportConfig.format)

        val formats = ExportFormat.entries
        val startIndex = formats.indexOf(ExportFormat.JPEG)

        // Cycle through all formats once
        for (i in 1 until formats.size) {
            viewModel.cycleFormat()
            val expectedFormat = formats[(startIndex + i) % formats.size]
            assertEquals(
                "Failed at cycle $i",
                expectedFormat,
                viewModel.uiState.value.exportConfig.format
            )
        }

        // One more cycle should wrap around to the beginning
        viewModel.cycleFormat()
        assertEquals(
            "Should wrap around to JPEG",
            ExportFormat.JPEG,
            viewModel.uiState.value.exportConfig.format
        )
    }
}
