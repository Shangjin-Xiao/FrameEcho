@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.app.ui.player

import android.Manifest
import androidx.media3.common.util.UnstableApi
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.LEGACY_ONBOARDING_STEP_KEYS
import com.shangjin.frameecho.app.ui.components.OnboardingManager
import com.shangjin.frameecho.app.ui.components.OnboardingOverlay
import com.shangjin.frameecho.app.ui.components.ThumbnailTimeline
import com.shangjin.frameecho.app.ui.components.rememberAllOnboardingSteps
import com.shangjin.frameecho.app.ui.export.ExportSettingsSheet
import com.shangjin.frameecho.app.ui.player.components.EmptyState
import com.shangjin.frameecho.app.ui.player.components.FrameInfoCard
import com.shangjin.frameecho.app.ui.player.components.PlaybackControls
import com.shangjin.frameecho.app.ui.player.components.PlayerBottomBar
import com.shangjin.frameecho.app.ui.player.components.PlayerTopBar
import com.shangjin.frameecho.app.ui.player.components.VideoSurface
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.delay

/**
 * Main player screen with M3 design — video playback and frame capture controls.
 *
 * Design choices:
 * - PlayerView built-in controller is disabled; custom M3 controls are used instead.
 * - Single progress slider (no duplicate progress bars).
 * - Bottom bar: toggle buttons (mute, motion photo, metadata) + capture FAB.
 * - Dragging slider seeks video in real-time for instant preview.
 * - ExoPlayer (Media3) is the optimal engine — only the UI layer is customized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Onboarding state — version-based, only shows unseen steps
    val onboardingManager = remember { OnboardingManager(context) }
    val allOnboardingSteps = rememberAllOnboardingSteps()
    var unseenSteps by remember { mutableStateOf<List<com.shangjin.frameecho.app.ui.components.OnboardingStep>>(emptyList()) }
    var showOnboarding by remember { mutableStateOf(false) }
    var onboardingStep by remember { mutableIntStateOf(0) }

    // Trigger onboarding when a video is loaded — only show unseen steps
    LaunchedEffect(uiState.videoUri) {
        if (uiState.videoUri != null) {
            val allKeys = allOnboardingSteps.map { it.key }
            val unseenKeys = onboardingManager.getUnseenStepKeys(allKeys, LEGACY_ONBOARDING_STEP_KEYS)
            if (unseenKeys.isNotEmpty()) {
                unseenSteps = allOnboardingSteps.filter { it.key in unseenKeys }
                onboardingStep = 0
                delay(800)
                showOnboarding = true
            }
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setVideoUri(it) }
    }

    // SAF folder picker launcher for custom export directory
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                android.util.Log.w("PlayerScreen", "Failed to take persistable URI permission", e)
            }
            viewModel.setCustomExportTreeUri(uri)
        }
    }

    // ExoPlayer instance — lifecycle-aware via DisposableEffect
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()
            .apply {
                playWhenReady = false
            }
    }

    // Back handler: return to home (clear video) instead of exiting
    BackHandler(enabled = uiState.videoUri != null) {
        if (uiState.isCapturing || uiState.isExporting) return@BackHandler
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        viewModel.clearVideoUri()
    }

    var pendingCaptureAfterPermission by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    // Video loading state (ExoPlayer buffering)
    var isVideoLoading by remember { mutableStateOf(false) }
    var showBufferingIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(isVideoLoading) {
        if (isVideoLoading) {
            delay(500L)
            showBufferingIndicator = true
        } else {
            showBufferingIndicator = false
        }
    }

    // Scrub sync state (shared between PlaybackControls and ThumbnailTimeline)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPreviewPositionMs by remember { mutableLongStateOf(0L) }

    // Capture flash effect
    var showCaptureFlash by remember { mutableStateOf(false) }

    val runCaptureAndSave by rememberUpdatedState(newValue = {
        if (uiState.isCapturing || uiState.isExporting) {
            Unit
        } else {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            exoPlayer.pause()
            viewModel.updatePosition(exoPlayer.currentPosition)
            viewModel.captureAndSave(uiState.exportConfig.motionPhoto)
        }
    })

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted && pendingCaptureAfterPermission) {
            pendingCaptureAfterPermission = false
            runCaptureAndSave()
        } else {
            pendingCaptureAfterPermission = false
        }
    }

    // Camera flash effect on successful export
    LaunchedEffect(uiState.exportResult) {
        if (uiState.exportResult is ExportResult.Success) {
            showCaptureFlash = true
            delay(150)
            showCaptureFlash = false
        }
    }

    // Update ExoPlayer when video URI changes
    LaunchedEffect(uiState.videoUri) {
        uiState.videoUri?.let { uri ->
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            if (uiState.currentPositionMs > 0) {
                exoPlayer.seekTo(uiState.currentPositionMs)
            }
        }
    }

    LaunchedEffect(uiState.isMuted) {
        exoPlayer.volume = if (uiState.isMuted) 0f else 1f
    }

    // Periodically sync player position to ViewModel
    LaunchedEffect(uiState.isPlaying) {
        while (uiState.isPlaying) {
            if (!isScrubbing) {
                viewModel.updatePosition(exoPlayer.currentPosition)
            }
            delay(250L)
        }
    }

    // Listen to player state changes + extract frame rate from video track
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isVideoLoading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    viewModel.updateDuration(exoPlayer.duration)
                    // Extract frame rate from ExoPlayer's selected video track
                    val videoFormat = exoPlayer.videoFormat
                    if (videoFormat != null && videoFormat.frameRate > 0f) {
                        viewModel.updateVideoFrameRate(videoFormat.frameRate)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    viewModel.updatePosition(newPosition.positionMs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.setPlaying(isPlaying)
                if (!isPlaying && !isScrubbing) {
                    viewModel.updatePosition(exoPlayer.currentPosition)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pause ExoPlayer when app goes to background;
    // stop playback fully on ON_DESTROY to release codec resources promptly
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_DESTROY -> {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Localized strings for snackbar messages
    val exportSuccessMsg = stringResource(R.string.export_success_message)
    val exportFallbackMsg = stringResource(R.string.export_format_fallback_message)
    val viewActionLabel = stringResource(R.string.snackbar_action_view)

    // Show export result
    LaunchedEffect(uiState.exportResult) {
        when (val result = uiState.exportResult) {
            is ExportResult.Success -> {
                val message = if (result.formatFallbackOccurred) {
                    exportFallbackMsg.format(
                        result.requestedFormat!!.name,
                        result.format.name
                    )
                } else {
                    exportSuccessMsg
                }
                val snackbarResult = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = viewActionLabel,
                    duration = SnackbarDuration.Short
                )
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    try {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                result.outputPath.toUri(),
                                result.format.mimeType
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(viewIntent)
                    } catch (_: Exception) { }
                }
                viewModel.clearExportResult()
            }
            is ExportResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = result.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.clearExportResult()
            }
            null -> {}
        }
    }

    // Show errors
    val captureErrorMsg = stringResource(R.string.error_capture_failed)
    val exportErrorMsgGeneric = stringResource(R.string.error_export_failed)
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val message = when (error) {
                is PlayerError.CaptureFailed -> captureErrorMsg
                is PlayerError.ExportFailed -> error.detail ?: exportErrorMsgGeneric
            }
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                PlayerTopBar(
                    hasVideo = uiState.videoUri != null,
                    scrollBehavior = scrollBehavior,
                    onSelectVideo = { videoPickerLauncher.launch("video/*") },
                    onOpenSettings = { viewModel.toggleExportSettings() },
                    onNavigateToAbout = onNavigateToAbout
                )
            },
            bottomBar = {
                if (uiState.videoUri != null) {
                    PlayerBottomBar(
                        isMuted = uiState.isMuted,
                        motionPhotoEnabled = uiState.exportConfig.motionPhoto,
                        preserveMetadata = uiState.exportConfig.preserveMetadata,
                        formatExtension = uiState.exportConfig.format.extension,
                        isCapturing = uiState.isCapturing,
                        isExporting = uiState.isExporting,
                        onToggleMute = { viewModel.setMuted(!uiState.isMuted) },
                        onToggleMotionPhoto = {
                            viewModel.updateExportConfig(
                                uiState.exportConfig.copy(
                                    motionPhoto = !uiState.exportConfig.motionPhoto
                                )
                            )
                        },
                        onToggleMetadata = {
                            viewModel.updateExportConfig(
                                uiState.exportConfig.copy(
                                    preserveMetadata = !uiState.exportConfig.preserveMetadata
                                )
                            )
                        },
                        onCycleFormat = { viewModel.cycleFormat() },
                        onCapture = {
                            if (uiState.isCapturing || uiState.isExporting) return@PlayerBottomBar
                            val requiresLegacyWritePermission =
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                    uiState.customExportTreeUri == null &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) != PackageManager.PERMISSION_GRANTED
                            if (requiresLegacyWritePermission) {
                                pendingCaptureAfterPermission = true
                                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                return@PlayerBottomBar
                            }
                            runCaptureAndSave()
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.videoUri != null) {
                    // Video Player
                    VideoSurface(
                        exoPlayer = exoPlayer,
                        isExporting = uiState.isExporting,
                        showBufferingIndicator = showBufferingIndicator,
                        isSliderDragging = isScrubbing,
                        showCaptureFlash = showCaptureFlash,
                        onTogglePlayPause = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onCancelExport = { viewModel.cancelExport() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    )

                    // Playback controls with fine scrubbing
                    PlaybackControls(
                        exoPlayer = exoPlayer,
                        currentPositionMs = uiState.currentPositionMs,
                        durationMs = uiState.durationMs,
                        isPlaying = uiState.isPlaying,
                        videoFrameRate = uiState.videoFrameRate,
                        isExporting = uiState.isExporting,
                        seekIntervalMs = uiState.seekIntervalMs,
                        onUpdatePosition = { viewModel.updatePosition(it) },
                        onTogglePlayPause = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onSeekTo = { exoPlayer.seekTo(it) },
                        onDragStarted = { /* reserved for future use */ },
                        onDragEnded = { /* reserved for future use */ },
                        onScrubStateChanged = { scrubbing, posMs ->
                            isScrubbing = scrubbing
                            scrubPreviewPositionMs = posMs
                        }
                    )

                    // Thumbnail timeline strip
                    if (uiState.thumbnailCount > 0) {
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val onThumbnailClick = remember(exoPlayer, viewModel) {
                                { index: Int ->
                                    val state = viewModel.uiState.value
                                    val denominator =
                                        (state.thumbnailCount - 1).coerceAtLeast(1)
                                    val newPosition = (state.durationMs * index) / denominator
                                    exoPlayer.seekTo(newPosition)
                                    viewModel.updatePosition(newPosition)
                                }
                            }

                            ThumbnailTimeline(
                                thumbnailCount = uiState.thumbnailCount,
                                currentPositionFraction = if (uiState.durationMs > 0) {
                                    val posMs = if (isScrubbing) scrubPreviewPositionMs else uiState.currentPositionMs
                                    (posMs.toFloat() / uiState.durationMs).coerceIn(0f, 1f)
                                } else 0f,
                                getThumbnail = viewModel::getThumbnail,
                                requestThumbnail = viewModel::requestThumbnail,
                                onThumbnailClick = onThumbnailClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Frame info card
                    FrameInfoCard(capturedFrame = uiState.capturedFrame)
                } else {
                    // Empty state
                    EmptyState(
                        onSelectVideo = { videoPickerLauncher.launch("video/*") }
                    )
                }
            }

            // Export settings bottom sheet
            if (uiState.showExportSettings) {
                ExportSettingsSheet(
                    config = uiState.exportConfig,
                    onConfigChange = { viewModel.updateExportConfig(it) },
                    rememberQuickSettings = uiState.rememberQuickSettings,
                    onRememberQuickSettingsChange = { viewModel.setRememberQuickSettings(it) },
                    onDismiss = { viewModel.toggleExportSettings() },
                    customExportTreeUri = uiState.customExportTreeUri,
                    onPickCustomFolder = { folderPickerLauncher.launch(null) },
                    onClearCustomFolder = { viewModel.setCustomExportTreeUri(null) },
                    isHdrContent = uiState.capturedFrame?.colorSpace?.isHdr == true
                )
            }
        }

        // Onboarding overlay — shows only unseen steps one bubble at a time
        if (showOnboarding && uiState.videoUri != null && unseenSteps.isNotEmpty()) {
            OnboardingOverlay(
                steps = unseenSteps,
                currentStep = onboardingStep,
                onNextStep = {
                    if (onboardingStep < unseenSteps.size - 1) {
                        onboardingStep++
                    }
                },
                onSkip = {
                    showOnboarding = false
                    // Mark all steps (both seen and unseen) as seen
                    onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                    onboardingManager.markOnboardingCompleted()
                },
                onFinish = {
                    showOnboarding = false
                    onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                    onboardingManager.markOnboardingCompleted()
                }
            )
        }
    }
}
