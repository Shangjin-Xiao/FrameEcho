package com.shangjin.frameecho.app.ui.player.components

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
