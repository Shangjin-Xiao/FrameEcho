package com.shangjin.frameecho.app.ui.player

import android.os.Trace
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportResultSnackbarEffectTest {

    private val sampleSuccess = ExportResult.Success(
        outputPath = "content://media/external/images/media/101",
        width = 1920,
        height = 1080,
        fileSizeBytes = 256_000L,
        format = ExportFormat.JPEG
    )

    @Test
    fun shareFailureErrorSnackbar_survivesClearExportResultRecomposition() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs

        try {
            val exportResultFlow = MutableStateFlow<ExportResult?>(sampleSuccess)
            val snackbarHostState = SnackbarHostState()
            var clearCount = 0
            var shareCount = 0

            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(dispatcher + clock)
            val recomposerJob = launch(dispatcher + clock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val composition = Composition(NoOpApplier(), recomposer)

            composition.setContent {
                val exportResult by exportResultFlow.collectAsState()
                val scope = rememberCoroutineScope()
                ExportResultSnackbarEffect(
                    exportResult = exportResult,
                    snackbarHostState = snackbarHostState,
                    snackbarCoroutineScope = scope,
                    exportSuccessMsg = "Frame saved",
                    exportFallbackMsg = "Saved as %2\$s instead of %1\$s",
                    exportAudioDroppedMsg = "Motion photo saved without audio",
                    exportFallbackAudioDroppedMsg = "Saved as %2\$s without audio instead of %1\$s",
                    shareActionLabel = "Share",
                    onClearExportResult = {
                        clearCount++
                        exportResultFlow.value = null
                    },
                    onShareExportedResult = {
                        shareCount++
                        "Exported file is no longer available"
                    }
                )
            }

            Snapshot.sendApplyNotifications()
            clock.sendFrame(16_000_000L)
            testScheduler.runCurrent()

            val initialSnackbar = snackbarHostState.currentSnackbarData
            assertNotNull("Expected initial export success snackbar", initialSnackbar)
            assertEquals("Frame saved", initialSnackbar!!.visuals.message)
            assertEquals("Share", initialSnackbar.visuals.actionLabel)
            assertEquals(SnackbarDuration.Short, initialSnackbar.visuals.duration)

            // User taps "Share" on the export snackbar; clearExportResult() mutates key to null
            // and share fails with an error message.
            initialSnackbar.performAction()
            Snapshot.sendApplyNotifications()
            clock.sendFrame(32_000_000L)
            testScheduler.runCurrent()
            clock.sendFrame(48_000_000L)
            testScheduler.runCurrent()

            assertEquals(1, clearCount)
            assertEquals(1, shareCount)
            assertNull(exportResultFlow.value)

            val errorSnackbar = snackbarHostState.currentSnackbarData
            assertNotNull(
                "Error snackbar must not be cancelled when exportResult recomposes to null",
                errorSnackbar
            )
            assertEquals("Exported file is no longer available", errorSnackbar!!.visuals.message)
            assertEquals(SnackbarDuration.Long, errorSnackbar.visuals.duration)

            errorSnackbar.dismiss()
            testScheduler.runCurrent()
            assertNull(snackbarHostState.currentSnackbarData)

            composition.dispose()
            recomposer.cancel()
            recomposerJob.join()
        } finally {
            unmockkStatic(Trace::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun shareSuccess_clearsExportResultWithoutShowingErrorSnackbar() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs

        try {
            val exportResultFlow = MutableStateFlow<ExportResult?>(sampleSuccess)
            val snackbarHostState = SnackbarHostState()
            var shareCount = 0

            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(dispatcher + clock)
            val recomposerJob = launch(dispatcher + clock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val composition = Composition(NoOpApplier(), recomposer)

            composition.setContent {
                val exportResult by exportResultFlow.collectAsState()
                val scope = rememberCoroutineScope()
                ExportResultSnackbarEffect(
                    exportResult = exportResult,
                    snackbarHostState = snackbarHostState,
                    snackbarCoroutineScope = scope,
                    exportSuccessMsg = "Frame saved",
                    exportFallbackMsg = "Saved as %2\$s instead of %1\$s",
                    exportAudioDroppedMsg = "Motion photo saved without audio",
                    exportFallbackAudioDroppedMsg = "Saved as %2\$s without audio instead of %1\$s",
                    shareActionLabel = "Share",
                    onClearExportResult = { exportResultFlow.value = null },
                    onShareExportedResult = {
                        shareCount++
                        null
                    }
                )
            }

            Snapshot.sendApplyNotifications()
            clock.sendFrame(16_000_000L)
            testScheduler.runCurrent()

            snackbarHostState.currentSnackbarData!!.performAction()
            Snapshot.sendApplyNotifications()
            clock.sendFrame(32_000_000L)
            testScheduler.runCurrent()

            assertEquals(1, shareCount)
            assertNull(exportResultFlow.value)
            assertNull(snackbarHostState.currentSnackbarData)

            composition.dispose()
            recomposer.cancel()
            recomposerJob.join()
        } finally {
            unmockkStatic(Trace::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissExportSnackbar_clearsExportResultWithoutInvokingShare() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs

        try {
            val exportResultFlow = MutableStateFlow<ExportResult?>(sampleSuccess)
            val snackbarHostState = SnackbarHostState()
            var shareCount = 0

            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(dispatcher + clock)
            val recomposerJob = launch(dispatcher + clock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val composition = Composition(NoOpApplier(), recomposer)

            composition.setContent {
                val exportResult by exportResultFlow.collectAsState()
                val scope = rememberCoroutineScope()
                ExportResultSnackbarEffect(
                    exportResult = exportResult,
                    snackbarHostState = snackbarHostState,
                    snackbarCoroutineScope = scope,
                    exportSuccessMsg = "Frame saved",
                    exportFallbackMsg = "Saved as %2\$s instead of %1\$s",
                    exportAudioDroppedMsg = "Motion photo saved without audio",
                    exportFallbackAudioDroppedMsg = "Saved as %2\$s without audio instead of %1\$s",
                    shareActionLabel = "Share",
                    onClearExportResult = { exportResultFlow.value = null },
                    onShareExportedResult = {
                        shareCount++
                        null
                    }
                )
            }

            Snapshot.sendApplyNotifications()
            clock.sendFrame(16_000_000L)
            testScheduler.runCurrent()

            snackbarHostState.currentSnackbarData!!.dismiss()
            Snapshot.sendApplyNotifications()
            clock.sendFrame(32_000_000L)
            testScheduler.runCurrent()

            assertEquals(0, shareCount)
            assertNull(exportResultFlow.value)
            assertNull(snackbarHostState.currentSnackbarData)

            composition.dispose()
            recomposer.cancel()
            recomposerJob.join()
        } finally {
            unmockkStatic(Trace::class)
            Dispatchers.resetMain()
        }
    }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
