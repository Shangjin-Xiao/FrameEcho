@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper
import com.shangjin.frameecho.core.common.FileUtils

/**
 * Playback controls: seek slider with fine scrubbing, play/pause, skip forward/back,
 * and frame-step buttons.
 *
 * Fine scrubbing improvements for frame-precise capture:
 * - Calculates frame duration from video frame rate for true frame-level granularity
 * - Uses adaptive sensitivity based on video duration
 * - Uses EXACT seek parameters during fine scrubbing for frame-accurate preview
 * - Shows frame number alongside time position in the scrubbing indicator
 * - Provides haptic feedback on frame boundary crossings
 * - Slider thumb stays in sync during fine-scrub mode
 */
@Composable
fun PlaybackControls(
    exoPlayer: ExoPlayer,
    currentPositionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    videoFrameRate: Float,
    isExporting: Boolean,
    seekIntervalMs: Long,
    onUpdatePosition: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    /** Called when slider drag starts */
    onDragStarted: () -> Unit,
    /** Called when slider drag ends */
    onDragEnded: () -> Unit,
    /** Provides the current scrub state to the parent (for thumbnail timeline sync) */
    onScrubStateChanged: (isScrubbing: Boolean, previewPositionMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val fineScrubThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }

    // Slider user-dragging state
    var isSliderDragging by remember { mutableStateOf(false) }
    var isFineScrubbing by remember { mutableStateOf(false) }
    var sliderDragStartFraction by remember { mutableFloatStateOf(0f) }
    var sliderDragStartPositionMs by remember { mutableLongStateOf(0L) }
    var wasPlayingBeforeSliderDrag by remember { mutableStateOf(false) }
    var fineScrubOffsetMs by remember { mutableLongStateOf(0L) }
    var sliderTargetPositionMs by remember { mutableStateOf<Long?>(null) }
    // Local slider fraction for instant visual feedback during drag (bypasses StateFlow)
    var localSliderFraction by remember { mutableFloatStateOf(0f) }

    // Frame duration calculation for frame-precise seeking
    val frameDurationMs = remember(videoFrameRate) {
        if (videoFrameRate > 0f) (1000.0 / videoFrameRate).toLong().coerceAtLeast(1L) else 0L
    }

    // Adaptive fine-scrub sensitivity: inversely proportional to duration.
    // Short videos need very low sensitivity; long videos can be faster.
    // Formula: 500 / durationMs, clamped to [0.02, 0.20]
    val fineScrubSensitivity = remember(durationMs) {
        if (durationMs <= 0) 0.10f
        else (500f / durationMs).coerceIn(0.02f, 0.20f)
    }

    val previewPositionMs = sliderTargetPositionMs ?: currentPositionMs
    // Track last frame boundary for haptic feedback on frame crossings
    var lastFrameBoundaryMs by remember { mutableLongStateOf(0L) }

    Surface(
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Seek slider with real-time preview and fine scrubbing
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startY = down.position.y
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val verticalOffset = startY - change.position.y
                                if (!isFineScrubbing && verticalOffset > fineScrubThresholdPx) {
                                    isFineScrubbing = true
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Anchor from current target so thumb doesn't jump
                                    val anchorMs = sliderTargetPositionMs ?: currentPositionMs
                                    sliderDragStartFraction = if (durationMs > 0) {
                                        anchorMs.toFloat() / durationMs.toFloat()
                                    } else 0f
                                    sliderDragStartPositionMs = anchorMs
                                    fineScrubOffsetMs = 0L
                                    // Initialize frame boundary tracking
                                    lastFrameBoundaryMs = if (frameDurationMs > 0L) {
                                        (anchorMs / frameDurationMs) * frameDurationMs
                                    } else anchorMs
                                    // Switch to EXACT seeking for frame-accurate preview
                                    exoPlayer.setSeekParameters(SeekParameters.EXACT)
                                } else if (isFineScrubbing && verticalOffset < fineScrubThresholdPx / 2) {
                                    isFineScrubbing = false
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    // Re-anchor normal drag from current position
                                    val currentTarget = sliderTargetPositionMs ?: currentPositionMs
                                    localSliderFraction = if (durationMs > 0) {
                                        currentTarget.toFloat() / durationMs.toFloat()
                                    } else 0f
                                    // Revert to CLOSEST_SYNC for normal scrubbing speed
                                    exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                }
                                if (event.changes.none { it.pressed }) {
                                    break
                                }
                            }
                            if (!isSliderDragging && isFineScrubbing) {
                                isFineScrubbing = false
                            }
                        }
                    }
            ) {
                // Compute the slider display fraction: always reflect the actual target position
                val displayFraction = when {
                    isSliderDragging && isFineScrubbing -> {
                        // In fine-scrub mode, compute fraction from target position
                        val target = sliderTargetPositionMs ?: currentPositionMs
                        if (durationMs > 0) target.toFloat() / durationMs.toFloat() else 0f
                    }
                    isSliderDragging -> localSliderFraction
                    durationMs > 0L -> currentPositionMs.toFloat() / durationMs.toFloat()
                    else -> 0f
                }

                Slider(
                    value = displayFraction,
                    onValueChange = { fraction ->
                        val clampedFraction = fraction.coerceIn(0f, 1f)
                        localSliderFraction = clampedFraction
                        if (!isSliderDragging) {
                            wasPlayingBeforeSliderDrag = exoPlayer.isPlaying
                            if (wasPlayingBeforeSliderDrag) {
                                exoPlayer.pause()
                            }
                            // Scrubbing mode: Media3 1.8+ optimization
                            val scrubbingParams = ScrubbingModeParameters.Builder()
                                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO))
                                .setShouldIncreaseCodecOperatingRate(true)
                                .setShouldEnableDynamicScheduling(true)
                                .setAllowSkippingMediaCodecFlush(true)
                                .setUseDecodeOnlyFlag(true)
                                .build()
                            exoPlayer.setScrubbingModeParameters(scrubbingParams)
                            exoPlayer.setScrubbingModeEnabled(true)
                            // Use EXACT seeking for smooth frame-accurate preview.
                            // ScrubbingModeParameters optimizations keep it performant.
                            exoPlayer.setSeekParameters(SeekParameters.EXACT)
                            sliderDragStartFraction = clampedFraction
                            sliderDragStartPositionMs = currentPositionMs
                            fineScrubOffsetMs = 0L
                            sliderTargetPositionMs = null
                            lastFrameBoundaryMs = if (frameDurationMs > 0L) {
                                (currentPositionMs / frameDurationMs) * frameDurationMs
                            } else currentPositionMs
                            onDragStarted()
                        }
                        isSliderDragging = true
                        val newPosition = if (isFineScrubbing) {
                            val deltaFraction = clampedFraction - sliderDragStartFraction
                            val rawDeltaMs = (deltaFraction * durationMs * fineScrubSensitivity).toLong()
                            if (frameDurationMs > 0L) {
                                // Snap to frame boundaries for true frame-level precision
                                val snappedDeltaMs = (rawDeltaMs / frameDurationMs) * frameDurationMs
                                (sliderDragStartPositionMs + snappedDeltaMs).coerceIn(0L, durationMs)
                            } else {
                                (sliderDragStartPositionMs + rawDeltaMs).coerceIn(0L, durationMs)
                            }
                        } else {
                            (clampedFraction * durationMs).toLong()
                        }

                        // Haptic feedback on frame boundary crossing during fine scrub
                        if (isFineScrubbing && frameDurationMs > 0L) {
                            val currentFrameBoundary = (newPosition / frameDurationMs) * frameDurationMs
                            if (currentFrameBoundary != lastFrameBoundaryMs) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastFrameBoundaryMs = currentFrameBoundary
                            }
                        }

                        fineScrubOffsetMs = newPosition - sliderDragStartPositionMs
                        sliderTargetPositionMs = newPosition
                        exoPlayer.seekTo(newPosition)
                        onScrubStateChanged(true, newPosition)
                    },
                    onValueChangeFinished = {
                        val finalPosition = sliderTargetPositionMs ?: currentPositionMs
                        // Restore exact seeking and disable scrubbing mode
                        exoPlayer.setSeekParameters(SeekParameters.EXACT)
                        exoPlayer.setScrubbingModeEnabled(false)
                        onUpdatePosition(finalPosition)
                        exoPlayer.seekTo(finalPosition)
                        isSliderDragging = false
                        isFineScrubbing = false
                        fineScrubOffsetMs = 0L
                        sliderTargetPositionMs = null
                        onScrubStateChanged(false, finalPosition)
                        onDragEnded()
                        if (wasPlayingBeforeSliderDrag && !isExporting) {
                            exoPlayer.play()
                        }
                        wasPlayingBeforeSliderDrag = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = if (isFineScrubbing) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        activeTrackColor = if (isFineScrubbing) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                // Scrubbing indicator with fine-scrub info
                androidx.compose.animation.AnimatedVisibility(
                    visible = isSliderDragging,
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isFineScrubbing) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isFineScrubbing) {
                                    // Show frame number when frame rate is known
                                    if (frameDurationMs > 0L) {
                                        val frameNumber = previewPositionMs / frameDurationMs
                                        stringResource(
                                            R.string.fine_scrubbing_frame_position,
                                            FileUtils.formatDurationWithMillis(previewPositionMs),
                                            frameNumber,
                                            "%+d".format(fineScrubOffsetMs)
                                        )
                                    } else {
                                        stringResource(
                                            R.string.fine_scrubbing_position,
                                            FileUtils.formatDurationWithMillis(previewPositionMs),
                                            "%+d".format(fineScrubOffsetMs)
                                        )
                                    }
                                } else {
                                    stringResource(
                                        R.string.scrubbing_position,
                                        FileUtils.formatDurationWithMillis(previewPositionMs)
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFineScrubbing) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                            Text(
                                text = if (isFineScrubbing) {
                                    stringResource(R.string.swipe_down_exit_fine_scrub)
                                } else {
                                    stringResource(R.string.swipe_up_fine_scrub)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFineScrubbing) {
                                    MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                }
                            )
                        }
                    }
                }
            }

            // Time labels + play controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSliderDragging || isFineScrubbing) {
                        FileUtils.formatDurationWithMillis(previewPositionMs)
                    } else {
                        FileUtils.formatDuration(currentPositionMs)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Play controls: prev frame, replay 5s, play/pause, forward 5s, next frame
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Previous frame
                    if (frameDurationMs > 0L) {
                        val prevFrameDesc = stringResource(R.string.prev_frame)
                        TooltipWrapper(label = prevFrameDesc) {
                            IconButton(
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause()
                                    exoPlayer.setSeekParameters(SeekParameters.EXACT)
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - frameDurationMs)
                                    exoPlayer.seekTo(newPos)
                                    onUpdatePosition(newPos)
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .semantics { contentDescription = prevFrameDesc }
                            ) {
                                Text(
                                    text = "‹‹",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    val replayDesc = stringResource(R.string.replay_5s)
                    TooltipWrapper(label = replayDesc) {
                        IconButton(
                            onClick = {
                                val newPos = maxOf(0L, exoPlayer.currentPosition - seekIntervalMs)
                                onSeekTo(newPos)
                                onUpdatePosition(newPos)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = replayDesc }
                        ) {
                            Text(
                                text = "-${seekIntervalMs / 1000}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TooltipWrapper(
                        label = stringResource(if (isPlaying) R.string.pause else R.string.play)
                    ) {
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.pause else R.string.play
                                ),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    val forwardDesc = stringResource(R.string.forward_5s)
                    TooltipWrapper(label = forwardDesc) {
                        IconButton(
                            onClick = {
                                val newPos = minOf(durationMs, exoPlayer.currentPosition + seekIntervalMs)
                                onSeekTo(newPos)
                                onUpdatePosition(newPos)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = forwardDesc }
                        ) {
                            Text(
                                text = "+${seekIntervalMs / 1000}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Next frame
                    if (frameDurationMs > 0L) {
                        val nextFrameDesc = stringResource(R.string.next_frame)
                        TooltipWrapper(label = nextFrameDesc) {
                            IconButton(
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause()
                                    exoPlayer.setSeekParameters(SeekParameters.EXACT)
                                    val newPos = minOf(durationMs, exoPlayer.currentPosition + frameDurationMs)
                                    exoPlayer.seekTo(newPos)
                                    onUpdatePosition(newPos)
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .semantics { contentDescription = nextFrameDesc }
                            ) {
                                Text(
                                    text = "››",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Text(
                    text = FileUtils.formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
