@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.media3.common.util.UnstableApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shangjin.frameecho.R
import kotlinx.coroutines.delay

/**
 * Video surface with gesture handling (tap-to-play/pause, pinch-to-zoom, pan).
 *
 * Also renders overlays for:
 * - Export progress
 * - Video loading (sustained buffering)
 * - Tap play/pause feedback
 * - Capture flash effect
 * - Zoom level indicator + reset button
 */
@Composable
fun VideoSurface(
    exoPlayer: ExoPlayer,
    isExporting: Boolean,
    showBufferingIndicator: Boolean,
    isSliderDragging: Boolean,
    showCaptureFlash: Boolean,
    onTogglePlayPause: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom and pan state
    var videoScale by remember { mutableFloatStateOf(1f) }
    var videoOffsetX by remember { mutableFloatStateOf(0f) }
    var videoOffsetY by remember { mutableFloatStateOf(0f) }

    // Tap-to-play/pause visual feedback (null = hidden)
    var tapFeedbackIsPlaying by remember { mutableStateOf<Boolean?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(tapFeedbackIsPlaying) {
        if (tapFeedbackIsPlaying != null) {
            delay(600L)
            tapFeedbackIsPlaying = null
        }
    }

    // Reset zoom when video changes (parent re-creates this composable)
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    controllerAutoShow = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setOnClickListener(null)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        var pastTouchSlop = false
                        var isTap = true
                        var hasMultiTouch = false

                        while (true) {
                            val event = awaitPointerEvent()

                            if (event.changes.size > 1) {
                                isTap = false
                                hasMultiTouch = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val newScale = (videoScale * zoomChange).coerceIn(1f, 3f)
                                    val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                                    val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                                    videoScale = newScale
                                    videoOffsetX =
                                        (videoOffsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    videoOffsetY =
                                        (videoOffsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    if (newScale <= 1f) {
                                        videoOffsetX = 0f
                                        videoOffsetY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                val change = event.changes.first()
                                if (!pastTouchSlop) {
                                    val distance =
                                        (change.position - firstDown.position).getDistance()
                                    if (distance > viewConfiguration.touchSlop) {
                                        pastTouchSlop = true
                                        isTap = false
                                    }
                                }
                                if (videoScale > 1f) {
                                    val panChange = change.positionChange()
                                    if (panChange != Offset.Zero) {
                                        val maxOffsetX = (size.width * (videoScale - 1f)) / 2f
                                        val maxOffsetY = (size.height * (videoScale - 1f)) / 2f
                                        videoOffsetX =
                                            (videoOffsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                        videoOffsetY =
                                            (videoOffsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                        change.consume()
                                    }
                                }
                            }
                            if (event.changes.none { it.pressed }) {
                                break
                            }
                        }

                        // Tap gesture handling:
                        // - Not zoomed: single tap toggles play/pause
                        // - Zoomed: double-tap resets zoom to 1x
                        if (isTap && !hasMultiTouch) {
                            if (videoScale > 1f) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTimeMs < 300L) {
                                    videoScale = 1f
                                    videoOffsetX = 0f
                                    videoOffsetY = 0f
                                    lastTapTimeMs = 0L
                                } else {
                                    lastTapTimeMs = now
                                }
                            } else {
                                tapFeedbackIsPlaying = !exoPlayer.isPlaying
                                onTogglePlayPause()
                            }
                        }
                    }
                }
                // ⚡ Bolt: Use lambda-based graphicsLayer to defer state reading to the draw phase.
                // This prevents expensive full recompositions of the VideoSurface during
                // high-frequency gesture events like panning and zooming.
                .graphicsLayer {
                    scaleX = videoScale
                    scaleY = videoScale
                    translationX = videoOffsetX
                    translationY = videoOffsetY
                }
        )

        // Loading/exporting overlay
        if (isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                ElevatedCard(shape = RoundedCornerShape(28.dp)) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.exporting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onCancelExport) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }

        // Video loading indicator (only for sustained buffering, not brief seeks)
        if (showBufferingIndicator && !isExporting && !isSliderDragging) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = stringResource(R.string.video_loading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Tap-to-play/pause visual feedback overlay
        AnimatedVisibility(
            visible = tapFeedbackIsPlaying != null,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (tapFeedbackIsPlaying == true)
                            Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Capture flash overlay (camera shutter effect)
        AnimatedVisibility(
            visible = showCaptureFlash,
            enter = fadeIn(animationSpec = tween(50)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.6f))
            )
        }

        // Zoom level indicator + reset button (visible when zoomed in)
        if (videoScale > 1.05f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "\u00d7${"%.1f".format(videoScale)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                    onClick = {
                        videoScale = 1f
                        videoOffsetX = 0f
                        videoOffsetY = 0f
                    }
                ) {
                    Text(
                        text = stringResource(R.string.zoom_reset),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
