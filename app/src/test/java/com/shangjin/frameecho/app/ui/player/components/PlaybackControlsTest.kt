package com.shangjin.frameecho.app.ui.player.components

import android.net.Uri
import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import com.shangjin.frameecho.app.ui.player.PlayerViewModel
import com.shangjin.frameecho.core.common.FileUtils
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class PlaybackControlsTest {

    @Test
    fun testCalculateScrubPosition_normalScrubbing() {
        val position = calculateScrubPosition(
            isFineScrubbing = false,
            clampedFraction = 0.5f,
            sliderDragStartFraction = 0.0f,
            durationMs = 10000L,
            fineScrubSensitivity = 0.1f,
            frameDurationMs = 33L,
            sliderDragStartPositionMs = 0L
        )
        assertEquals(5000L, position)
    }

    @Test
    fun testCalculateScrubPosition_fineScrubbingWithFrames() {
        val position = calculateScrubPosition(
            isFineScrubbing = true,
            clampedFraction = 0.6f,
            sliderDragStartFraction = 0.5f,
            durationMs = 10000L,
            fineScrubSensitivity = 0.1f,
            frameDurationMs = 33L,
            sliderDragStartPositionMs = 5000L
        )
        // rawDeltaMs = 0.1 * 10000 * 0.1 = 100ms.
        // snappedDeltaMs = (100 / 33) * 33 = 3 * 33 = 99ms.
        // position = 5000 + 99 = 5099ms.
        assertEquals(5099L, position)
    }

    @Test
    fun testCalculateScrubPosition_fineScrubbingWithoutFrames() {
        val position = calculateScrubPosition(
            isFineScrubbing = true,
            clampedFraction = 0.6f,
            sliderDragStartFraction = 0.5f,
            durationMs = 10000L,
            fineScrubSensitivity = 0.1f,
            frameDurationMs = 0L,
            sliderDragStartPositionMs = 5000L
        )
        // rawDeltaMs = 0.1 * 10000 * 0.1 = 100ms.
        // position = 5000 + 100 = 5100ms.
        assertEquals(5100L, position)
    }

    @Test
    fun testCalculateSliderDisplayFraction_lazyPositionReadModes() {
        var providerInvocations = 0
        val positionProvider = {
            providerInvocations++
            2_500L
        }

        // Normal playback reads position provider
        val playbackFraction = calculateSliderDisplayFraction(
            currentPositionMs = positionProvider,
            durationMs = 10_000L,
            isSliderDragging = false,
            isFineScrubbing = false,
            sliderTargetPositionMs = null,
            localSliderFraction = 0.8f
        )
        assertEquals(0.25f, playbackFraction, 1e-5f)
        assertEquals(1, providerInvocations)

        // Coarse dragging uses localSliderFraction without invoking position provider
        val coarseFraction = calculateSliderDisplayFraction(
            currentPositionMs = positionProvider,
            durationMs = 10_000L,
            isSliderDragging = true,
            isFineScrubbing = false,
            sliderTargetPositionMs = 7_000L,
            localSliderFraction = 0.72f
        )
        assertEquals(0.72f, coarseFraction, 1e-5f)
        assertEquals(1, providerInvocations)

        // Fine scrubbing uses sliderTargetPositionMs when present without invoking position provider
        val fineFraction = calculateSliderDisplayFraction(
            currentPositionMs = positionProvider,
            durationMs = 10_000L,
            isSliderDragging = true,
            isFineScrubbing = true,
            sliderTargetPositionMs = 5_000L,
            localSliderFraction = 0.72f
        )
        assertEquals(0.5f, fineFraction, 1e-5f)
        assertEquals(1, providerInvocations)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testComposeRecompositionIsolationDuring250msPositionPolling() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs
        try {
            val viewModel = PlayerViewModel()
            viewModel.setVideoUri(mockk<Uri>(relaxed = true))
            viewModel.updateDuration(60_000L)
            viewModel.updateVideoFrameRate(30f)
            viewModel.setPlaying(true)

            var rootCompositions = 0
            var topBarCompositions = 0
            var bottomBarCompositions = 0
            var videoSurfaceCompositions = 0
            var playbackControlsRootCompositions = 0
            var timelineWrapperCompositions = 0
            var frameInfoCardCompositions = 0
            val buttonCompositions = IntArray(5)
            var durationFormatInvocations = 0

            var sliderLeafCompositions = 0
            var currentTimeLeafCompositions = 0
            var timelineStripCompositions = 0
            var latestSliderFraction = -1f
            var latestFormattedPosition = ""
            var latestSelectedThumbnail = -1

            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(dispatcher + clock)
            val recomposerJob = launch(dispatcher + clock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val composition = Composition(UnitApplier(), recomposer)

            composition.setContent {
                rootCompositions++
                val uiState by viewModel.uiState.collectAsState()
                val playbackPositionState = viewModel.playbackPositionMs.collectAsState()

                IsolatedTopBarScope(hasVideo = uiState.videoUri != null) {
                    topBarCompositions++
                }
                IsolatedBottomBarScope(
                    isMuted = uiState.isMuted,
                    isExporting = uiState.isExporting
                ) {
                    bottomBarCompositions++
                }
                IsolatedVideoSurfaceScope(isExporting = uiState.isExporting) {
                    videoSurfaceCompositions++
                }
                IsolatedPlaybackControlsScope(
                    currentPositionMs = { playbackPositionState.value },
                    durationMs = uiState.durationMs,
                    isPlaying = uiState.isPlaying,
                    seekIntervalMs = uiState.seekIntervalMs,
                    onRootComposed = { playbackControlsRootCompositions++ },
                    onDurationFormatted = { durationFormatInvocations++ },
                    onButtonComposed = { idx -> buttonCompositions[idx]++ },
                    onSliderComposed = { fraction ->
                        sliderLeafCompositions++
                        latestSliderFraction = fraction
                    },
                    onCurrentTimeComposed = { formatted ->
                        currentTimeLeafCompositions++
                        latestFormattedPosition = formatted
                    }
                )
                IsolatedTimelineWrapperScope(
                    thumbnailCount = uiState.thumbnailCount,
                    currentPositionFraction = {
                        val dur = uiState.durationMs
                        if (dur > 0L) {
                            (playbackPositionState.value.toFloat() / dur).coerceIn(0f, 1f)
                        } else 0f
                    },
                    onWrapperComposed = { timelineWrapperCompositions++ },
                    onSelectedThumbnailComposed = { selected ->
                        timelineStripCompositions++
                        latestSelectedThumbnail = selected
                    }
                )
                IsolatedFrameInfoScope(hasCapturedFrame = uiState.capturedFrame != null) {
                    frameInfoCardCompositions++
                }
            }

            Snapshot.sendApplyNotifications()
            clock.sendFrame(0L)

            // Baseline initial composition: every scope ran exactly once
            assertEquals(1, rootCompositions)
            assertEquals(1, topBarCompositions)
            assertEquals(1, bottomBarCompositions)
            assertEquals(1, videoSurfaceCompositions)
            assertEquals(1, playbackControlsRootCompositions)
            assertEquals(1, timelineWrapperCompositions)
            assertEquals(1, frameInfoCardCompositions)
            assertEquals(1, durationFormatInvocations)
            for (i in buttonCompositions.indices) {
                assertEquals("Button $i initial composition", 1, buttonCompositions[i])
            }
            assertEquals(1, sliderLeafCompositions)
            assertEquals(1, currentTimeLeafCompositions)
            assertEquals(1, timelineStripCompositions)

            // Simulate 16 ticks of 250ms playback polling (250ms .. 4_000ms)
            for (tick in 1..16) {
                viewModel.updatePosition(tick * 250L)
                Snapshot.sendApplyNotifications()
                clock.sendFrame(tick * 250_000_000L)
            }

            // Non-position scopes must have ZERO recompositions during 250ms polling
            assertEquals(0, rootCompositions - 1)
            assertEquals(0, topBarCompositions - 1)
            assertEquals(0, bottomBarCompositions - 1)
            assertEquals(0, videoSurfaceCompositions - 1)
            assertEquals(0, playbackControlsRootCompositions - 1)
            assertEquals(0, timelineWrapperCompositions - 1)
            assertEquals(0, frameInfoCardCompositions - 1)
            assertEquals(0, durationFormatInvocations - 1)
            for (i in buttonCompositions.indices) {
                assertEquals("Button $i recompositions during polling", 0, buttonCompositions[i] - 1)
            }

            // Leaf scopes updated accurately with playback position
            assertEquals(1 + 16, sliderLeafCompositions)
            assertEquals(1 + 16, currentTimeLeafCompositions)
            assertEquals(4_000f / 60_000f, latestSliderFraction, 1e-5f)
            assertEquals("0:04", latestFormattedPosition)
            // ThumbnailTimeline derivedStateOf only recomposed when crossing thumbnail boundaries
            assertTrue(
                "Timeline strip should recompose only on thumbnail boundary crossings, got $timelineStripCompositions",
                timelineStripCompositions in 2..5
            )
            val expectedThumb = ((uiStateThumbnailCount(60_000L) - 1) * (4_000f / 60_000f)).roundToInt()
            assertEquals(expectedThumb, latestSelectedThumbnail)

            composition.dispose()
            recomposer.cancel()
            recomposerJob.join()
        } finally {
            unmockkStatic(Trace::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testCompiledPlayerScreenAndPlaybackControlsBytecodeScopeIsolation() {
        val playbackControlsClass = "com.shangjin.frameecho.app.ui.player.components.PlaybackControlsKt"
        val playbackControlsMain = javapClasses(listOf(playbackControlsClass))

        val mainControlsMethod = extractMethodSection(
            playbackControlsMain,
            "public static final void PlaybackControls("
        )
        assertFalse(
            "PlaybackControls root body must not invoke currentPositionMs Function0 during composition",
            mainControlsMethod.contains("Function0.invoke")
        )

        val sliderLeafMethod = extractMethodSection(
            playbackControlsMain,
            "private static final void PlaybackSeekSlider("
        )
        assertTrue(
            "PlaybackSeekSlider leaf must invoke calculateSliderDisplayFraction",
            sliderLeafMethod.contains("calculateSliderDisplayFraction")
        )

        val currentTimeLeafMethod = extractMethodSection(
            playbackControlsMain,
            "private static final void CurrentPositionText("
        )
        assertTrue(
            "CurrentPositionText leaf must invoke currentPositionMs Function0",
            currentTimeLeafMethod.contains("Function0.invoke")
        )

        // Audit every inner class of PlaybackControlsKt (Surface content + all 5 control buttons):
        // Only the AnimatedVisibility scrubbing tooltip (taking AnimatedVisibilityScope) may read
        // currentPositionMs() during composition (and only while dragging).
        val innerControlsClasses = listInnerClassNames(playbackControlsClass)
        assertTrue("Expected inner classes for PlaybackControlsKt", innerControlsClasses.isNotEmpty())
        val innerControlsDisassemblies = splitByClass(javapClasses(innerControlsClasses))
        for ((classHeader, disassembly) in innerControlsDisassemblies) {
            for (line in disassembly.lines()) {
                if (line.contains("invoke(") &&
                    line.contains("androidx.compose.runtime.Composer") &&
                    !line.contains("AnimatedVisibilityScope")
                ) {
                    val section = extractMethodSection(disassembly, line.trim())
                    assertFalse(
                        "$classHeader composable scope must not invoke currentPositionMs Function0",
                        section.contains("Function0.invoke")
                    )
                }
            }
        }

        val playerScreenClass = "com.shangjin.frameecho.app.ui.player.PlayerScreenKt"
        val playerScreenMain = javapClasses(listOf(playerScreenClass))
        val screenMethod = extractMethodSection(
            playerScreenMain,
            "public static final void PlayerScreen("
        )
        assertTrue(
            "PlayerScreen must collect playbackPositionMs StateFlow",
            screenMethod.contains("getPlaybackPositionMs")
        )
        assertFalse(
            "PlayerScreen root must not unbox playbackPositionState.value via Long.longValue",
            screenMethod.contains("Long.longValue")
        )

        // Audit every inner composable scope in PlayerScreenKt (TopBar, BottomBar, Scaffold Column,
        // ThumbnailTimeline outer Surface, etc.): none may unbox playbackPositionState.value directly.
        val innerScreenClasses = listInnerClassNames(playerScreenClass)
        assertTrue("Expected inner classes for PlayerScreenKt", innerScreenClasses.isNotEmpty())
        val innerScreenDisassemblies = splitByClass(javapClasses(innerScreenClasses))
        for ((classHeader, disassembly) in innerScreenDisassemblies) {
            for (line in disassembly.lines()) {
                if (line.contains("invoke(") && line.contains("androidx.compose.runtime.Composer")) {
                    val section = extractMethodSection(disassembly, line.trim())
                    assertFalse(
                        "$classHeader composable scope must not read playbackPositionState.value directly",
                        section.contains("Long.longValue")
                    )
                }
            }
        }
    }

    private fun uiStateThumbnailCount(durationMs: Long): Int =
        PlayerViewModel.thumbnailCountForDuration(durationMs)

    private fun listInnerClassNames(outerClassName: String): List<String> {
        val outerResourcePath = outerClassName.replace('.', '/') + ".class"
        val classUrl = PlaybackControlsTest::class.java.classLoader!!.getResource(outerResourcePath)
        assertNotNull("Compiled class not found: $outerResourcePath", classUrl)
        val prefix = outerClassName.replace('.', '/') + "$"
        return if (classUrl!!.protocol == "jar") {
            val jarConn = classUrl.openConnection() as java.net.JarURLConnection
            jarConn.jarFile.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(".class") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toList()
        } else {
            val outerFile = File(classUrl.toURI())
            val simplePrefix = outerFile.name.removeSuffix(".class") + "$"
            val pkg = outerClassName.substringBeforeLast('.')
            outerFile.parentFile
                ?.listFiles { file -> file.name.startsWith(simplePrefix) && file.name.endsWith(".class") }
                .orEmpty()
                .map { "$pkg.${it.name.removeSuffix(".class")}" }
        }
    }

    private fun javapClasses(classNames: List<String>): String {
        val firstResource = classNames.first().replace('.', '/') + ".class"
        val classUrl = PlaybackControlsTest::class.java.classLoader!!.getResource(firstResource)
        assertNotNull("Compiled class not found: $firstResource", classUrl)
        val javapBin = File(System.getProperty("java.home"), "bin/javap").let {
            if (it.exists()) it.absolutePath else "javap"
        }
        val command = if (classUrl!!.protocol == "jar") {
            val jarConn = classUrl.openConnection() as java.net.JarURLConnection
            val jarFile = File(jarConn.jarFileURL.toURI())
            listOf(javapBin, "-cp", jarFile.absolutePath, "-c", "-p") + classNames
        } else {
            val files = classNames.map { name ->
                val url = PlaybackControlsTest::class.java.classLoader!!.getResource(
                    name.replace('.', '/') + ".class"
                )!!
                File(url.toURI()).absolutePath
            }
            listOf(javapBin, "-c", "-p") + files
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("javap failed: $output", 0, process.waitFor())
        return output
    }

    private fun splitByClass(batchDisassembly: String): List<Pair<String, String>> {
        val chunks = batchDisassembly.split("Compiled from ").drop(1)
        return chunks.map { chunk ->
            val lines = chunk.lines()
            val header = lines.getOrNull(1)?.trim() ?: lines.first()
            header to chunk
        }
    }

    private fun extractMethodSection(disassembly: String, signaturePrefix: String): String {
        val lines = disassembly.lines()
        val startIdx = lines.indexOfFirst { it.contains(signaturePrefix) }
        assertTrue("Method '$signaturePrefix' not found", startIdx >= 0)
        val endIdx = (startIdx + 1 until lines.size).firstOrNull { idx ->
            val line = lines[idx]
            line == "}" || (line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(";"))
        } ?: lines.size
        return lines.subList(startIdx, endIdx).joinToString("\n")
    }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}

@Composable
private fun IsolatedTopBarScope(hasVideo: Boolean, onComposed: () -> Unit) {
    onComposed()
    if (hasVideo) { /* no-op */ }
}

@Composable
private fun IsolatedBottomBarScope(isMuted: Boolean, isExporting: Boolean, onComposed: () -> Unit) {
    onComposed()
    if (isMuted || isExporting) { /* no-op */ }
}

@Composable
private fun IsolatedVideoSurfaceScope(isExporting: Boolean, onComposed: () -> Unit) {
    onComposed()
    if (isExporting) { /* no-op */ }
}

@Composable
private fun IsolatedFrameInfoScope(hasCapturedFrame: Boolean, onComposed: () -> Unit) {
    onComposed()
    if (hasCapturedFrame) { /* no-op */ }
}

@Composable
private fun IsolatedPlaybackControlsScope(
    currentPositionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    seekIntervalMs: Long,
    onRootComposed: () -> Unit,
    onDurationFormatted: () -> Unit,
    onButtonComposed: (Int) -> Unit,
    onSliderComposed: (Float) -> Unit,
    onCurrentTimeComposed: (String) -> Unit
) {
    onRootComposed()
    val formattedDuration = remember(durationMs) {
        onDurationFormatted()
        FileUtils.formatDuration(durationMs)
    }
    if (formattedDuration.isEmpty()) return

    IsolatedSliderLeaf(
        currentPositionMs = currentPositionMs,
        durationMs = durationMs,
        onComposed = onSliderComposed
    )
    IsolatedCurrentTimeLeaf(
        currentPositionMs = currentPositionMs,
        onComposed = onCurrentTimeComposed
    )
    for (buttonIndex in 0 until 5) {
        IsolatedControlButtonLeaf(
            buttonIndex = buttonIndex,
            isPlaying = isPlaying,
            seekIntervalMs = seekIntervalMs,
            onComposed = onButtonComposed
        )
    }
}

@Composable
private fun IsolatedSliderLeaf(
    currentPositionMs: () -> Long,
    durationMs: Long,
    onComposed: (Float) -> Unit
) {
    val fraction = calculateSliderDisplayFraction(
        currentPositionMs = currentPositionMs,
        durationMs = durationMs,
        isSliderDragging = false,
        isFineScrubbing = false,
        sliderTargetPositionMs = null,
        localSliderFraction = 0f
    )
    onComposed(fraction)
}

@Composable
private fun IsolatedCurrentTimeLeaf(
    currentPositionMs: () -> Long,
    onComposed: (String) -> Unit
) {
    val formatted = FileUtils.formatDuration(currentPositionMs())
    onComposed(formatted)
}

@Composable
private fun IsolatedControlButtonLeaf(
    buttonIndex: Int,
    isPlaying: Boolean,
    seekIntervalMs: Long,
    onComposed: (Int) -> Unit
) {
    onComposed(buttonIndex)
    if (isPlaying && seekIntervalMs < 0L) return
}

@Composable
private fun IsolatedTimelineWrapperScope(
    thumbnailCount: Int,
    currentPositionFraction: () -> Float,
    onWrapperComposed: () -> Unit,
    onSelectedThumbnailComposed: (Int) -> Unit
) {
    onWrapperComposed()
    IsolatedTimelineStripScope(
        thumbnailCount = thumbnailCount,
        currentPositionFraction = currentPositionFraction,
        onSelectedThumbnailComposed = onSelectedThumbnailComposed
    )
}

@Composable
private fun IsolatedTimelineStripScope(
    thumbnailCount: Int,
    currentPositionFraction: () -> Float,
    onSelectedThumbnailComposed: (Int) -> Unit
) {
    val fractionProvider by rememberUpdatedState(currentPositionFraction)
    val selectedIndex by remember(thumbnailCount) {
        derivedStateOf {
            if (thumbnailCount > 0) {
                ((thumbnailCount - 1) * fractionProvider())
                    .roundToInt()
                    .coerceIn(0, thumbnailCount - 1)
            } else 0
        }
    }
    onSelectedThumbnailComposed(selectedIndex)
}
