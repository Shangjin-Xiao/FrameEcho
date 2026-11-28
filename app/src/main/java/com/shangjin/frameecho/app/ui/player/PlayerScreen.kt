@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.app.ui.player

import android.Manifest
import androidx.media3.common.util.UnstableApi
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shangjin.frameecho.core.media.utils.LogUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.intent.ExportedMediaActionResult
import com.shangjin.frameecho.app.intent.ExportedMediaDispatcher
import com.shangjin.frameecho.app.intent.InboundVideoEvent
import com.shangjin.frameecho.app.ui.components.LEGACY_ONBOARDING_STEP_KEYS
import com.shangjin.frameecho.app.ui.components.OnboardingManager
import com.shangjin.frameecho.app.ui.components.OnboardingOverlay
import com.shangjin.frameecho.app.ui.components.ThumbnailTimeline
import com.shangjin.frameecho.app.ui.components.rememberAllOnboardingSteps
import com.shangjin.frameecho.app.ui.export.ExportSettingsSheet
import com.shangjin.frameecho.app.ui.player.components.EmptyState
import com.shangjin.frameecho.app.ui.player.components.FrameInfoCard
import com.shangjin.frameecho.app.ui.player.components.PendingExternalVideoDialog
import com.shangjin.frameecho.app.ui.player.components.PlaybackControls
import com.shangjin.frameecho.app.ui.player.components.PlayerBottomBar
import com.shangjin.frameecho.app.ui.player.components.PlayerTopBar
import com.shangjin.frameecho.app.ui.player.components.VideoSurface
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    inboundVideoEvent: InboundVideoEvent? = null,
    isResolvingInboundVideo: Boolean = false,
    onInboundVideoEventConsumed: (Long) -> Unit = {},
    onExitExternalSession: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()

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
            val unseenKeys = onboardingManager.getUnseenStepKeys(allKeys, LEGACY_ONBOARDING_STEP_KEYS).toSet()
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

    // Consume external ACTION_VIEW / ACTION_SEND video events after ViewModel initialization
    LaunchedEffect(inboundVideoEvent) {
        val event = inboundVideoEvent ?: return@LaunchedEffect
        viewModel.initialize(context)
        when (event) {
            is InboundVideoEvent.OpenVideo -> viewModel.onExternalVideoReceived(event.uri)
            is InboundVideoEvent.Error -> viewModel.onExternalVideoError(event.error)
        }
        onInboundVideoEventConsumed(event.id)
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
                LogUtils.w(context, "PlayerScreen", "Failed to take persistable URI permission", e)
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

    // Back handler: end external Intent session to return to caller app, or return to in-app home
    BackHandler(enabled = uiState.videoUri != null) {
        if (uiState.isCapturing || uiState.isExporting) return@BackHandler
        val shouldExitExternalSession = uiState.isExternalSession
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        viewModel.clearVideoUri()
        if (shouldExitExternalSession) {
            onExitExternalSession()
        }
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

    // Playback position collected without `by` so 250ms position polling never invalidates
    // this root scope — only leaf composables read `.value` via lambdas.
    val playbackPositionState = viewModel.playbackPositionMs.collectAsStateWithLifecycle()

    // Preview frame drawn over the surface while coarse-scrubbing. Collected without `by`
    // so this composable never reads the value — only VideoSurface does, via a lambda.
    val scrubPreviewState = viewModel.scrubPreview.collectAsStateWithLifecycle()
    // True between issuing a frame-exact seek and the player actually rendering it. The
    // preview is held until then, otherwise retiring it would flash the pre-seek frame.
    var awaitingSettledFrame by remember { mutableStateOf(false) }

    // Safety net for the above: a seek to a position the player is already at renders no
    // new frame, so onRenderedFirstFrame may never come. Retire the preview regardless
    // rather than leaving it stuck over the video. Deliberately long — a slow exact seek
    // on a long-GOP 4K clip must be allowed to finish and report, since expiring early
    // shows the pre-seek frame, which is the very thing the preview exists to hide.
    LaunchedEffect(awaitingSettledFrame) {
        if (awaitingSettledFrame) {
            delay(1_500L)
            awaitingSettledFrame = false
            viewModel.clearScrubPreview()
        }
    }

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

    // Update ExoPlayer when video URI or session changes
    LaunchedEffect(uiState.videoUri, uiState.videoSessionId) {
        uiState.videoUri?.let { uri ->
            exoPlayer.pause()
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            val savedPositionMs = viewModel.playbackPositionMs.value
            exoPlayer.seekTo(savedPositionMs.coerceAtLeast(0L))
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
                    exoPlayer.videoFormat?.takeIf { it.frameRate > 0f }?.let { format ->
                        viewModel.updateVideoFrameRate(format.frameRate)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // While scrubbing, every seek fires a discontinuity. Pushing those into
                // the ViewModel would recompose the whole screen per pointer event, and
                // the position shown during a drag comes from the scrub preview anyway.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !isScrubbing) {
                    viewModel.updatePosition(newPosition.positionMs)
                }
            }

            override fun onRenderedFirstFrame() {
                // Fires after every seek once the new frame is on screen — the cue to
                // hand the surface back and drop the preview overlay.
                if (awaitingSettledFrame) {
                    awaitingSettledFrame = false
                    viewModel.clearScrubPreview()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.setPlaying(isPlaying)
                if (!isPlaying && !isScrubbing) {
                    viewModel.updatePosition(exoPlayer.currentPosition)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isVideoLoading = false
                if (awaitingSettledFrame) {
                    awaitingSettledFrame = false
                    viewModel.clearScrubPreview()
                }
                LogUtils.w(
                    context,
                    "PlayerScreen",
                    "ExoPlayer playback error (code=${error.errorCode})",
                    error
                )
                viewModel.onPlaybackError(error.errorCode, error.cause)
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

    // Localized strings for snackbar & exported media actions
    val exportSuccessMsg = stringResource(R.string.export_success_message)
    val exportFallbackMsg = stringResource(R.string.export_format_fallback_message)
    val exportAudioDroppedMsg = stringResource(R.string.export_audio_dropped_message)
    val exportFallbackAudioDroppedMsg = stringResource(R.string.export_fallback_audio_dropped_message)
    val shareActionLabel = stringResource(R.string.snackbar_action_share)
    val shareFrameChooserTitle = stringResource(R.string.share_captured_frame)
    val viewFrameChooserTitle = stringResource(R.string.view_captured_frame)
    val exportedFileUnavailableMsg = stringResource(R.string.error_exported_file_unavailable)
    val noAppToOpenMediaMsg = stringResource(R.string.error_no_app_to_open_media)

    val resolveDispatchErrorMessage: (ExportedMediaActionResult) -> String? = { dispatchResult ->
        when (dispatchResult) {
            ExportedMediaActionResult.SUCCESS -> null
            ExportedMediaActionResult.FILE_UNAVAILABLE -> exportedFileUnavailableMsg
            ExportedMediaActionResult.NO_APP_AVAILABLE -> noAppToOpenMediaMsg
        }
    }

    // Show export result
    ExportResultSnackbarEffect(
        exportResult = uiState.exportResult,
        snackbarHostState = snackbarHostState,
        snackbarCoroutineScope = scope,
        exportSuccessMsg = exportSuccessMsg,
        exportFallbackMsg = exportFallbackMsg,
        exportAudioDroppedMsg = exportAudioDroppedMsg,
        exportFallbackAudioDroppedMsg = exportFallbackAudioDroppedMsg,
        shareActionLabel = shareActionLabel,
        onClearExportResult = viewModel::clearExportResult,
        onShareExportedResult = { result ->
            val dispatchResult = ExportedMediaDispatcher.Default.share(
                context = context,
                outputPath = result.outputPath,
                mimeType = result.format.mimeType,
                chooserTitle = shareFrameChooserTitle
            )
            resolveDispatchErrorMessage(dispatchResult)
        }
    )

    // Show errors
    val captureErrorMsg = stringResource(R.string.error_capture_failed)
    val exportErrorMsgGeneric = stringResource(R.string.error_export_failed)
    val permissionErrorMsg = stringResource(R.string.error_permission_denied)
    val unsupportedVideoFormatMsg = stringResource(R.string.error_unsupported_video_format)
    val externalVideoAccessFailedMsg = stringResource(R.string.error_external_video_access_failed)
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val message = when (error) {
                is PlayerError.CaptureFailed -> captureErrorMsg
                is PlayerError.PermissionDenied -> permissionErrorMsg
                is PlayerError.UnsupportedVideoFormat -> unsupportedVideoFormatMsg
                is PlayerError.ExternalVideoAccessFailed -> externalVideoAccessFailedMsg
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
                        onCapture = {
                            if (uiState.isCapturing || uiState.isExporting) return@PlayerBottomBar
                            val requiresLegacyWritePermission = shouldRequestLegacyWritePermission(
                                sdkInt = Build.VERSION.SDK_INT,
                                hasCustomExportTreeUri = uiState.customExportTreeUri != null,
                                hasWriteExternalStoragePermission = {
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) == PackageManager.PERMISSION_GRANTED
                                }
                            )
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
                        scrubPreview = { scrubPreviewState.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    )

                    // Playback controls with fine scrubbing
                    PlaybackControls(
                        exoPlayer = exoPlayer,
                        currentPositionMs = { playbackPositionState.value },
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
                        // Warm the preview decoding session up while the finger is still
                        // on its way, so the first preview lands as early as possible.
                        onDragStarted = { viewModel.beginScrubPreview() },
                        onDragEnded = { /* preview retires when the final seek renders */ },
                        onScrubStateChanged = { scrubbing, posMs, isFine ->
                            isScrubbing = scrubbing
                            scrubPreviewPositionMs = posMs
                            // Coarse drag wants speed, so it gets the extractor's preview.
                            // Fine scrub wants the exact frame, which only the real decoder
                            // can give — drop the preview and let it show through.
                            if (scrubbing && !isFine) {
                                // Moving again — cancel the pending hand-back to the surface.
                                awaitingSettledFrame = false
                                viewModel.requestScrubPreview(posMs)
                            } else if (isFine && scrubPreviewState.value != null) {
                                viewModel.clearScrubPreview()
                            }
                        },
                        isScrubPreviewLive = { scrubPreviewState.value != null },
                        onExactSeekIssued = { awaitingSettledFrame = true }
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
                                // Passed as a lambda so the position read happens inside
                                // ThumbnailTimeline: a scrub then recomposes only the strip
                                // instead of this whole Column.
                                currentPositionFraction = {
                                    val durationMs = uiState.durationMs
                                    if (durationMs > 0) {
                                        val posMs =
                                            if (isScrubbing) scrubPreviewPositionMs else playbackPositionState.value
                                        (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                    } else 0f
                                },
                                isScrubbing = isScrubbing,
                                getThumbnail = viewModel::getThumbnail,
                                requestThumbnail = viewModel::requestThumbnail,
                                onThumbnailClick = onThumbnailClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Frame info card (persistent preview + system share & external view)
                    val isBusyExporting = uiState.isCapturing || uiState.isExporting
                    val lastExported = uiState.lastExportSuccess
                    FrameInfoCard(
                        capturedFrame = uiState.capturedFrame,
                        previewBitmap = uiState.capturedPreviewBitmap,
                        isMotionPhoto = lastExported?.isMotionPhoto ?: uiState.isCapturedMotionPhoto,
                        isExporting = isBusyExporting,
                        canOpenOrShare = !isBusyExporting &&
                            lastExported != null &&
                            lastExported.outputPath.isNotBlank(),
                        onShareClick = {
                            val target = uiState.lastExportSuccess
                            if (target != null && !uiState.isCapturing && !uiState.isExporting) {
                                val dispatchResult = ExportedMediaDispatcher.Default.share(
                                    context = context,
                                    outputPath = target.outputPath,
                                    mimeType = target.format.mimeType,
                                    chooserTitle = shareFrameChooserTitle
                                )
                                resolveDispatchErrorMessage(dispatchResult)?.let { errorText ->
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar(
                                            message = errorText,
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                }
                            }
                        },
                        onViewClick = {
                            val target = uiState.lastExportSuccess
                            if (target != null && !uiState.isCapturing && !uiState.isExporting) {
                                val dispatchResult = ExportedMediaDispatcher.Default.view(
                                    context = context,
                                    outputPath = target.outputPath,
                                    mimeType = target.format.mimeType,
                                    chooserTitle = viewFrameChooserTitle
                                )
                                resolveDispatchErrorMessage(dispatchResult)?.let { errorText ->
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar(
                                            message = errorText,
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                }
                            }
                        }
                    )
                } else if (isResolvingInboundVideo || inboundVideoEvent is InboundVideoEvent.OpenVideo) {
                    val loadingLabel = stringResource(R.string.video_loading)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = loadingLabel }
                        )
                    }
                } else {
                    // Empty state
                    EmptyState(
                        onSelectVideo = { videoPickerLauncher.launch("video/*") }
                    )
                }
            }

            if (uiState.pendingExternalVideoUri != null &&
                !uiState.deferPendingExternalSwitchUntilExportEnds
            ) {
                PendingExternalVideoDialog(
                    isExportStillRunning = uiState.isCapturing || uiState.isExporting,
                    onSwitchNow = { viewModel.confirmReplaceWithExternalVideo() },
                    onSwitchAfterExport = { viewModel.switchToExternalVideoAfterExport() },
                    onKeepCurrent = { viewModel.dismissPendingExternalVideo() }
                )
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
                    onClearCustomFolder = { viewModel.setCustomExportTreeUri(null) }
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
                    scope.launch {
                        onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                        onboardingManager.markOnboardingCompleted()
                    }
                },
                onFinish = {
                    showOnboarding = false
                    scope.launch {
                        onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                        onboardingManager.markOnboardingCompleted()
                    }
                }
            )
        }
    }
}

internal fun shouldRequestLegacyWritePermission(
    sdkInt: Int,
    hasCustomExportTreeUri: Boolean,
    hasWriteExternalStoragePermission: () -> Boolean
): Boolean =
    sdkInt <= Build.VERSION_CODES.P &&
        !hasCustomExportTreeUri &&
        !hasWriteExternalStoragePermission()

@Composable
internal fun ExportResultSnackbarEffect(
    exportResult: ExportResult?,
    snackbarHostState: SnackbarHostState,
    snackbarCoroutineScope: CoroutineScope,
    exportSuccessMsg: String,
    exportFallbackMsg: String,
    exportAudioDroppedMsg: String,
    exportFallbackAudioDroppedMsg: String,
    shareActionLabel: String,
    onClearExportResult: () -> Unit,
    onShareExportedResult: (ExportResult.Success) -> String?
) {
    val currentOnClearExportResult by rememberUpdatedState(onClearExportResult)
    val currentOnShareExportedResult by rememberUpdatedState(onShareExportedResult)

    LaunchedEffect(exportResult) {
        when (exportResult) {
            is ExportResult.Success -> {
                val message = when {
                    exportResult.formatFallbackOccurred && exportResult.audioDropped ->
                        exportFallbackAudioDroppedMsg.format(
                            exportResult.requestedFormat!!.name,
                            exportResult.format.name
                        )
                    exportResult.audioDropped -> exportAudioDroppedMsg
                    exportResult.formatFallbackOccurred -> exportFallbackMsg.format(
                        exportResult.requestedFormat!!.name,
                        exportResult.format.name
                    )
                    else -> exportSuccessMsg
                }
                val snackbarResult = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = shareActionLabel,
                    duration = SnackbarDuration.Short
                )
                currentOnClearExportResult()
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    currentOnShareExportedResult(exportResult)?.let { errorText ->
                        snackbarCoroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = errorText,
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                }
            }
            is ExportResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = exportResult.message,
                    duration = SnackbarDuration.Long
                )
                currentOnClearExportResult()
            }
            null -> {}
        }
    }
}
