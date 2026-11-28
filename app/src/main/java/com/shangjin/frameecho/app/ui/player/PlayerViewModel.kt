package com.shangjin.frameecho.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.shangjin.frameecho.app.intent.InboundVideoError
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.extraction.ScrubPreviewExtractor
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Represents user-facing error types. Mapped to localized strings in the UI layer.
 */
sealed class PlayerError {
    data object CaptureFailed : PlayerError()
    data object PermissionDenied : PlayerError()
    data object UnsupportedVideoFormat : PlayerError()
    data object ExternalVideoAccessFailed : PlayerError()
    data class ExportFailed(val detail: String? = null) : PlayerError()
}

/**
 * UI state for the player screen.
 */
data class PlayerUiState(
    val videoUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val rememberQuickSettings: Boolean = true,
    val durationMs: Long = 0L,
    val capturedFrame: CapturedFrame? = null,
    val capturedBitmap: Bitmap? = null,
    /** Downsampled software preview thumbnail for persistent FrameInfoCard; freed by GC. */
    val capturedPreviewBitmap: Bitmap? = null,
    /** Metadata of the most recent successful export in the active video session. */
    val lastExportSuccess: ExportResult.Success? = null,
    /** Whether the current captured frame is being or was exported as a Motion Photo. */
    val isCapturedMotionPhoto: Boolean = false,
    val isCapturing: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: ExportResult? = null,
    val exportConfig: ExportConfig = ExportConfig(),
    val showExportSettings: Boolean = false,
    val error: PlayerError? = null,
    /** Number of thumbnails to display in the timeline */
    val thumbnailCount: Int = 0,
    /** Custom export directory URI from SAF picker */
    val customExportTreeUri: Uri? = null,
    /** Video frame rate (fps) — used for frame-precise seeking in fine scrub mode */
    val videoFrameRate: Float = 0f,
    /** True when the active video was opened directly from an external system Intent */
    val isExternalSession: Boolean = false,
    /** Staged external video URI when an Intent arrives during an ongoing capture/export */
    val pendingExternalVideoUri: Uri? = null,
    /** True when user opted to switch to [pendingExternalVideoUri] automatically after export ends */
    val deferPendingExternalSwitchUntilExportEnds: Boolean = false,
    /** Monotonic session identifier incremented on each fresh video load */
    val videoSessionId: Long = 0L
) {
    /**
     * Adaptive seek interval: ~5% of total duration, rounded to a "nice" value.
     * Clamped to [1s, 30s]. Precomputed once per state instance from [durationMs].
     */
    val seekIntervalMs: Long = PlayerViewModel.seekIntervalForDuration(durationMs)
}

/**
 * ViewModel for the player screen.
 * Manages video playback state, frame capture/export, and thumbnail timeline.
 *
 * Thumbnail strategy: thumbnails span the FULL video duration so they load once
 * and remain stable. Auto-scroll follows the playback position smoothly.
 */
class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * Current playback position in milliseconds.
     *
     * Deliberately kept out of [PlayerUiState] (mirroring [scrubPreview]): 250ms playback
     * polling and frame-step clicks update this flow at high frequency, and folding it into
     * the main state would recompose the whole player screen every time.
     */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    /**
     * Preview frame shown over the video while the seek bar is being dragged.
     *
     * Deliberately kept out of [PlayerUiState]: it changes many times per drag, and folding
     * it into the main state would recompose the whole player screen every time.
     */
    private val _scrubPreview = MutableStateFlow<Bitmap?>(null)
    val scrubPreview: StateFlow<Bitmap?> = _scrubPreview.asStateFlow()

    private var frameExtractor: FrameExtractor? = null
    private var frameExporter: FrameExporter? = null
    private var preferencesStore: PlayerPreferencesStore? = null
    private var appContext: Context? = null

    /** Thumbnail cache: index → Bitmap. Kept outside UiState to avoid serialization issues. */
    private val _thumbnailCache = mutableStateMapOf<Int, Bitmap>()
    private val thumbnailSessionMutex = Mutex()
    private var batchThumbnailJob: Job? = null
    private var activeThumbnailUri: Uri? = null
    private var activeThumbnailDurationMs: Long = 0L
    private var activeThumbnailCount: Int = 0
    private var activeThumbnailAnchorIndex: Int = 0
    private var captureAndSaveJob: Job? = null
    private var hdrCaptureIdleJob: Job? = null
    private var activeBitmapExportRefCount: Int = 0

    // ── Scrub preview ───────────────────────────────────────────────────
    private var scrubPreviewExtractor: ScrubPreviewExtractor? = null
    private var scrubPreviewJob: Job? = null
    private var scrubPreviewIdleJob: Job? = null
    /** Conflated: while one frame is being extracted, only the newest target survives. */
    private var scrubRequests: Channel<Long>? = null
    /**
     * Bumped whenever the preview is retired. An extraction that was already running
     * carries the generation it started under, so its result is discarded instead of
     * popping back over the video after the player has taken the surface back.
     */
    private var scrubPreviewGeneration: Int = 0

    companion object {
        /** Width of each thumbnail in the timeline strip (px) */
        const val THUMBNAIL_WIDTH_PX = 120

        /** Fixed nice seek step candidates (ms): 1s, 2s, 5s, 10s, 15s, 20s, 30s. */
        private val NICE_SEEK_STEPS = longArrayOf(
            1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L
        )

        /**
         * Adaptive seek interval (~5% of total duration) rounded to the nearest step in
         * [NICE_SEEK_STEPS] using primitive math (smaller step wins ties), clamped to [1s, 30s].
         */
        fun seekIntervalForDuration(durationMs: Long): Long {
            if (durationMs <= 0L) return 1_000L
            val raw = (durationMs * 0.05).toLong()
            val steps = NICE_SEEK_STEPS
            var bestStep = steps[0]
            var bestDiff = kotlin.math.abs(bestStep - raw)
            for (i in 1 until steps.size) {
                val step = steps[i]
                val diff = kotlin.math.abs(step - raw)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestStep = step
                }
            }
            return bestStep.coerceIn(1_000L, 30_000L)
        }

        /**
         * Thumbnail count scales with video duration: ~1 thumbnail per second
         * for short videos, tapering off for longer ones using a square-root curve.
         * Clamped to [10, 150].
         */
        fun thumbnailCountForDuration(durationMs: Long): Int {
            if (durationMs <= 0) return 10
            val durationS = durationMs / 1000.0
            // sqrt curve: gives ~10 for 5s, ~28 for 30s, ~55 for 2min, ~77 for 5min, ~110 for 10min
            return (5.0 * kotlin.math.sqrt(durationS)).toInt().coerceIn(10, 150)
        }

        /**
         * How long the preview decoding session is kept open after a drag ends.
         * Keeping it means back-to-back drags stay instant; releasing it eventually
         * gives the decoder and GL context back, which matters on devices with few
         * concurrent codec instances.
         */
        const val SCRUB_PREVIEW_IDLE_RELEASE_MS = 10_000L

        /**
         * How long [FrameExtractor]'s Media3 GPU HDR capture session stays open after an
         * export finishes so consecutive captures reuse the warm decoder, before releasing
         * its hardware codec and GL context back to the system.
         */
        const val HDR_CAPTURE_IDLE_RELEASE_MS = 10_000L
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (frameExtractor == null) {
            frameExtractor = FrameExtractor(context.applicationContext)
            frameExporter = FrameExporter(context.applicationContext)
        }
        if (preferencesStore == null) {
            preferencesStore = PlayerPreferencesStore(context.applicationContext)
            applyPersistedQuickSettings(context.applicationContext)
        }
    }

    /**
     * Inject dependencies for testing.
     */
    fun setDependenciesForTesting(extractor: FrameExtractor, exporter: FrameExporter) {
        this.frameExtractor = extractor
        this.frameExporter = exporter
    }

    fun setVideoUri(uri: Uri) {
        loadVideoInternal(uri = uri, fromExternalIntent = false)
    }

    /**
     * Handle a validated video URI arriving from an external `ACTION_VIEW` or `ACTION_SEND` Intent.
     *
     * If a capture or export operation is currently in progress, stages [uri] in
     * [PlayerUiState.pendingExternalVideoUri] instead of cancelling the ongoing export.
     */
    fun onExternalVideoReceived(uri: Uri) {
        if (isCapturingOrExporting()) {
            _uiState.update {
                it.copy(
                    pendingExternalVideoUri = uri,
                    deferPendingExternalSwitchUntilExportEnds = false,
                    showExportSettings = false
                )
            }
            return
        }
        loadVideoInternal(uri = uri, fromExternalIntent = true)
    }

    /**
     * User confirmed replacing the current video immediately, cancelling any ongoing export.
     */
    fun confirmReplaceWithExternalVideo() {
        val pendingUri = _uiState.value.pendingExternalVideoUri ?: return
        loadVideoInternal(uri = pendingUri, fromExternalIntent = true)
    }

    /**
     * User opted to let the ongoing export finish first and then automatically load the staged
     * external video.
     */
    fun switchToExternalVideoAfterExport() {
        val pendingUri = _uiState.value.pendingExternalVideoUri ?: return
        if (!isCapturingOrExporting()) {
            loadVideoInternal(
                uri = pendingUri,
                fromExternalIntent = true,
                preserveExportResult = true
            )
        } else {
            _uiState.update {
                it.copy(deferPendingExternalSwitchUntilExportEnds = true)
            }
        }
    }

    /**
     * User dismissed the external video replacement prompt, keeping the current video and export.
     */
    fun dismissPendingExternalVideo() {
        _uiState.update {
            it.copy(
                pendingExternalVideoUri = null,
                deferPendingExternalSwitchUntilExportEnds = false
            )
        }
    }

    /**
     * Surface an inbound Intent validation error via the player's snackbar error state.
     */
    fun onExternalVideoError(error: InboundVideoError) {
        val mappedError = when (error) {
            InboundVideoError.UNSUPPORTED_FORMAT -> PlayerError.UnsupportedVideoFormat
            InboundVideoError.ACCESS_FAILED -> PlayerError.ExternalVideoAccessFailed
        }
        _uiState.update { it.copy(error = mappedError) }
    }

    /**
     * Surface a Media3 [PlaybackException] encountered while preparing or decoding the active
     * video so corrupt, truncated, or inaccessible files show a localized message instead of
     * failing silently.
     */
    fun onPlaybackError(errorCode: Int, cause: Throwable? = null) {
        _uiState.update { state ->
            if (state.videoUri == null) return@update state
            val mappedError = when {
                errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                    hasSecurityExceptionCause(cause) -> {
                    if (state.isExternalSession) {
                        PlayerError.ExternalVideoAccessFailed
                    } else {
                        PlayerError.PermissionDenied
                    }
                }
                errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    if (state.isExternalSession) {
                        PlayerError.ExternalVideoAccessFailed
                    } else {
                        PlayerError.UnsupportedVideoFormat
                    }
                }
                else -> PlayerError.UnsupportedVideoFormat
            }
            state.copy(
                isPlaying = false,
                error = mappedError
            )
        }
    }

    private fun hasSecurityExceptionCause(throwable: Throwable?): Boolean {
        return generateSequence(throwable) { it.cause }
            .take(8)
            .any { it is SecurityException }
    }

    private fun loadVideoInternal(
        uri: Uri,
        fromExternalIntent: Boolean,
        preserveExportResult: Boolean = false
    ) {
        // Capture reference BEFORE updating state so we can recycle safely
        // after the StateFlow no longer exposes the old bitmap.
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        cancelHdrCaptureIdleRelease()
        cancelExport()
        cancelHdrCaptureIdleRelease()
        frameExtractor?.invalidateCache()
        clearThumbnailCache()
        // The preview session is bound to a single URI — tear it down before switching.
        releaseScrubPreview()
        _playbackPositionMs.value = 0L

        _uiState.update {
            it.copy(
                videoUri = uri,
                isPlaying = false,
                durationMs = 0L,
                capturedBitmap = null,
                capturedFrame = null,
                capturedPreviewBitmap = null,
                lastExportSuccess = null,
                isCapturedMotionPhoto = false,
                exportResult = if (preserveExportResult) it.exportResult else null,
                showExportSettings = false,
                error = if (preserveExportResult) it.error else null,
                thumbnailCount = 0,
                videoFrameRate = 0f,
                isExternalSession = fromExternalIntent,
                pendingExternalVideoUri = null,
                deferPendingExternalSwitchUntilExportEnds = false,
                videoSessionId = it.videoSessionId + 1L
            )
        }

        // Recycle only when not exporting — the export coroutine may still
        // reference the bitmap on Dispatchers.IO.
        if (canRecycleImmediately && oldBitmap?.isRecycled == false) {
            oldBitmap.recycle()
        }
    }

    /**
     * Clear the video URI and return to home state.
     */
    fun clearVideoUri() {
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        cancelHdrCaptureIdleRelease()
        cancelExport()
        cancelHdrCaptureIdleRelease()
        frameExtractor?.invalidateCache()
        clearThumbnailCache()
        releaseScrubPreview()
        _playbackPositionMs.value = 0L
        _uiState.update {
            PlayerUiState(
                isMuted = it.isMuted,
                rememberQuickSettings = it.rememberQuickSettings,
                exportConfig = it.exportConfig,
                customExportTreeUri = it.customExportTreeUri
            )
        }
        // Recycle after state update so no observer can read a recycled bitmap.
        if (canRecycleImmediately && oldBitmap?.isRecycled == false) {
            oldBitmap.recycle()
        }
    }

    fun updatePosition(positionMs: Long) {
        if (_playbackPositionMs.value != positionMs) {
            _playbackPositionMs.value = positionMs
        }
    }

    fun updateDuration(durationMs: Long) {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val previousState = _uiState.value
        if (safeDurationMs == previousState.durationMs && previousState.thumbnailCount > 0) return
        if (previousState.durationMs > 0L && previousState.durationMs != safeDurationMs) {
            // Duration changed for the current video: invalidate stale slot-to-timestamp entries.
            clearThumbnailCache(recycleBitmaps = false)
        } else {
            batchThumbnailJob?.cancel()
            batchThumbnailJob = null
        }
        _uiState.update {
            it.copy(
                durationMs = safeDurationMs,
                thumbnailCount = thumbnailCountForDuration(safeDurationMs)
            )
        }
        loadThumbnailsForFullVideo()
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun updateVideoFrameRate(frameRate: Float) {
        if (frameRate > 0f && frameRate != _uiState.value.videoFrameRate) {
            _uiState.update { it.copy(videoFrameRate = frameRate) }
        }
    }

    fun setCustomExportTreeUri(uri: Uri?) {
        _uiState.update { it.copy(customExportTreeUri = uri) }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun setMuted(muted: Boolean) {
        _uiState.update { state ->
            state.copy(
                isMuted = muted,
                exportConfig = state.exportConfig.copy(muteAudio = muted)
            )
        }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun setRememberQuickSettings(enabled: Boolean) {
        _uiState.update { it.copy(rememberQuickSettings = enabled) }
        viewModelScope.launch {
            preferencesStore?.setRememberQuickSettings(enabled)
            if (enabled) {
                persistQuickSettingsIfEnabled()
            }
        }
    }

    // ── Thumbnail timeline ──────────────────────────────────────────────

    /**
     * Get a cached thumbnail, or null if not yet loaded.
     */
    fun getThumbnail(index: Int): Bitmap? {
        return _thumbnailCache[index]
    }

    /**
     * Request a thumbnail for the given timeline index.
     * Triggers batch loading if not already loaded.
     */
    fun requestThumbnail(index: Int) {
        if (index !in 0 until _uiState.value.thumbnailCount) return
        if (_thumbnailCache.containsKey(index)) return
        loadThumbnailsForFullVideo(anchorIndex = index)
    }

    /**
     * Load all missing thumbnails spanning the full video duration.
     * Thumbnails are evenly distributed across the entire video, so they
     * load once and remain stable throughout playback.
     *
     * Missing slots are ordered outward from the current viewport/playback anchor
     * and streamed in small chunks inside a single `MediaMetadataRetriever` session,
     * so visible slots appear first while preserving single-session decoder reuse.
     */
    private fun loadThumbnailsForFullVideo(anchorIndex: Int? = null) {
        val state = _uiState.value
        val uri = state.videoUri ?: return
        val durationMs = state.durationMs
        if (durationMs <= 0) return
        val thumbnailCount = state.thumbnailCount
        if (thumbnailCount <= 0) return
        val extractor = frameExtractor ?: return

        val resolvedAnchor = resolveAnchorIndex(
            thumbnailCount = thumbnailCount,
            durationMs = durationMs,
            currentPositionMs = _playbackPositionMs.value,
            preferredIndex = anchorIndex
        )

        val previousJob = batchThumbnailJob
        if (previousJob?.isActive == true) {
            val sameTimeline = uri == activeThumbnailUri &&
                durationMs == activeThumbnailDurationMs &&
                thumbnailCount == activeThumbnailCount
            val withinActiveViewportChunk =
                abs(resolvedAnchor - activeThumbnailAnchorIndex) < FrameExtractor.DEFAULT_THUMBNAIL_CHUNK_SIZE
            if (sameTimeline && withinActiveViewportChunk) {
                return
            }
            previousJob.cancel()
        }

        activeThumbnailUri = uri
        activeThumbnailDurationMs = durationMs
        activeThumbnailCount = thumbnailCount
        activeThumbnailAnchorIndex = resolvedAnchor

        batchThumbnailJob = viewModelScope.launch {
            val callerContext = currentCoroutineContext()
            // Wait for any canceled stream (including an in-flight native decode and
            // retriever.release() on Dispatchers.IO) before opening a replacement decoder session.
            previousJob?.join()
            thumbnailSessionMutex.withLock {
                ensureActive()
                val denominator = (thumbnailCount - 1).coerceAtLeast(1)

                val missingIndices = (0 until thumbnailCount).filter {
                    !_thumbnailCache.containsKey(it)
                }
                if (missingIndices.isEmpty()) return@withLock

                val orderedIndices = missingIndices.sortedBy { index ->
                    abs(index - resolvedAnchor)
                }

                val timestampToIndices = LinkedHashMap<Long, MutableList<Int>>(orderedIndices.size)
                for (index in orderedIndices) {
                    val positionMs = (durationMs * index) / denominator
                    val timestampUs = positionMs * 1000L
                    timestampToIndices.getOrPut(timestampUs) { mutableListOf() }.add(index)
                }

                try {
                    extractor.extractThumbnails(
                        videoUri = uri,
                        timestampsUs = timestampToIndices.keys.toList(),
                        targetWidth = THUMBNAIL_WIDTH_PX,
                        chunkSize = FrameExtractor.DEFAULT_THUMBNAIL_CHUNK_SIZE
                    ) { chunk ->
                        var published = false
                        try {
                            withContext(callerContext) {
                                ensureActive()
                                val batchMap = HashMap<Int, Bitmap>(chunk.size)
                                for ((timestampUs, bitmap) in chunk) {
                                    val indices = timestampToIndices[timestampUs]
                                    if (indices != null) {
                                        for (idx in indices) {
                                            batchMap[idx] = bitmap
                                        }
                                    }
                                }
                                if (batchMap.isNotEmpty()) {
                                    _thumbnailCache.putAll(batchMap)
                                }
                                published = true
                            }
                        } finally {
                            if (!published) {
                                chunk.forEach { (_, bitmap) -> extractor.releaseBitmap(bitmap) }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appContext?.let { ctx ->
                        LogUtils.w(ctx, "PlayerViewModel", "Failed to load thumbnails", e)
                    }
                }
            }
        }
    }

    private fun resolveAnchorIndex(
        thumbnailCount: Int,
        durationMs: Long,
        currentPositionMs: Long,
        preferredIndex: Int?
    ): Int {
        if (thumbnailCount <= 1) return 0
        if (preferredIndex != null) {
            return preferredIndex.coerceIn(0, thumbnailCount - 1)
        }
        if (durationMs <= 0L) return 0
        val fraction = (currentPositionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        return ((thumbnailCount - 1) * fraction).roundToInt().coerceIn(0, thumbnailCount - 1)
    }

    /**
     * Clear thumbnail cache.
     *
     * @param recycleBitmaps If true, recycles all cached bitmaps immediately.
     *   Use true only when no composables can reference them (e.g., onCleared).
     *   Use false during video change to avoid race conditions with composables
     *   that may still reference the bitmaps — GC will handle cleanup.
     */
    private fun clearThumbnailCache(recycleBitmaps: Boolean = false) {
        batchThumbnailJob?.cancel()
        batchThumbnailJob = null
        activeThumbnailUri = null
        activeThumbnailDurationMs = 0L
        activeThumbnailCount = 0
        activeThumbnailAnchorIndex = 0
        if (recycleBitmaps) {
            _thumbnailCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        _thumbnailCache.clear()
    }

    // ── Scrub preview ───────────────────────────────────────────────────

    /**
     * Open the preview session as a drag begins.
     *
     * Called on drag start rather than lazily on the first frame request so the decoding
     * session is warming up while the finger is still on its way.
     */
    fun beginScrubPreview() {
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = null
        // Drop whatever the last drag left behind. A leftover frame would both show the
        // wrong moment and, because the UI treats a live preview as "no need to seek the
        // player", keep the surface frozen until the first fresh frame arrives.
        scrubPreviewGeneration++
        _scrubPreview.value = null
        startScrubPreviewWorker()
    }

    /**
     * Ask for a preview at [positionMs]. Never blocks and never queues up: if an
     * extraction is already running, this replaces whatever target was waiting behind it,
     * so the preview always heads for where the finger is now.
     */
    fun requestScrubPreview(positionMs: Long) {
        startScrubPreviewWorker()
        scrubRequests?.trySend(positionMs)
    }

    /**
     * Drop the preview overlay, revealing the real (now exactly seeked) video surface.
     * The decoding session stays open for [SCRUB_PREVIEW_IDLE_RELEASE_MS] in case the
     * user drags again.
     */
    fun clearScrubPreview() {
        scrubPreviewGeneration++
        _scrubPreview.value = null
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = viewModelScope.launch {
            delay(SCRUB_PREVIEW_IDLE_RELEASE_MS)
            releaseScrubPreview()
        }
    }

    private fun startScrubPreviewWorker() {
        if (scrubPreviewJob?.isActive == true) return
        val uri = _uiState.value.videoUri ?: return
        val context = appContext ?: return

        val requests = Channel<Long>(Channel.CONFLATED)
        scrubRequests = requests
        scrubPreviewJob = viewModelScope.launch {
            val extractor = scrubPreviewExtractor ?: ScrubPreviewExtractor(context).also {
                scrubPreviewExtractor = it
            }
            try {
                extractor.prepare(uri)
                // Awaiting each frame before taking the next request is what paces this:
                // one extraction in flight, newest target wins. Firing requests faster
                // than the decoder can serve them only builds a backlog the user then
                // has to wait out.
                for (positionMs in requests) {
                    val generation = scrubPreviewGeneration
                    val bitmap = extractor.frameAt(positionMs) ?: continue
                    // The preview was retired while this frame was being decoded — the
                    // player owns the surface again, so this result is no longer wanted.
                    if (generation != scrubPreviewGeneration) continue
                    _scrubPreview.value = bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Preview is an optimisation, not a requirement — the UI falls back to
                // seeking the player when no preview arrives.
                appContext?.let { ctx ->
                    LogUtils.w(ctx, "PlayerViewModel", "Scrub preview unavailable", e)
                }
                _scrubPreview.value = null
            } finally {
                // The worker owns the session for its whole lifetime. Without this, a
                // worker that ends on its own — an extraction failure, or the request
                // channel closing — would leave a dead extractor cached in the field for
                // the next drag to pick up and keep failing on, holding its decoder and
                // GL context until the video changed.
                //
                // release() is idempotent, so this and the deterministic release in
                // releaseScrubPreview() can't conflict; whichever runs first wins.
                extractor.release()
                if (scrubPreviewExtractor === extractor) {
                    scrubPreviewExtractor = null
                }
            }
        }
    }

    /**
     * Preview frames are deliberately never recycled.
     *
     * A frame that has been published can still be referenced by a composition, or by a
     * draw pass already in flight when the state flips — recycling it races that draw and
     * crashes. Since API 26 (this app's minSdk) bitmap pixels live on the native heap and
     * are freed when the Bitmap becomes unreachable, so an explicit recycle buys nothing
     * here. They are small by construction: [ScrubPreviewExtractor] downscales to a 480px
     * short side on the GPU, and at most a couple are reachable at a time.
     */
    private fun releaseScrubPreview() {
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = null
        scrubRequests?.close()
        scrubRequests = null
        scrubPreviewJob?.cancel()
        scrubPreviewJob = null
        scrubPreviewExtractor?.release()
        scrubPreviewExtractor = null
        scrubPreviewGeneration++
        _scrubPreview.value = null
    }

    // ── Frame capture ───────────────────────────────────────────────────

    /**
     * One-tap capture and save: captures the current frame and immediately exports it.
     *
     * @param motionPhoto whether to export as a motion photo or static image
     */
    fun captureAndSave(motionPhoto: Boolean) {
        if (captureAndSaveJob?.isActive == true ||
            _uiState.value.isCapturing ||
            _uiState.value.isExporting
        ) return
        val uri = _uiState.value.videoUri ?: return
        val extractor = frameExtractor ?: return
        val exporter = frameExporter ?: return

        cancelHdrCaptureIdleRelease()
        captureAndSaveJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, isExporting = true, error = null, exportResult = null) }

            try {
                val timestampUs = _playbackPositionMs.value * 1000L
                val result = extractor.extractFrameWithInfo(uri, timestampUs)

                if (result != null) {
                    val (bitmap, frame) = result
                    val previewBitmap = try {
                        extractor.createPreviewThumbnail(bitmap)
                    } catch (e: CancellationException) {
                        extractor.releaseBitmap(bitmap)
                        throw e
                    }
                    // Capture old reference BEFORE updating state,
                    // then recycle AFTER so no observer reads a recycled bitmap.
                    // Preview thumbnails are deliberately never recycled (GC frees them safely).
                    val oldBitmap = _uiState.value.capturedBitmap
                    _uiState.update {
                        it.copy(
                            capturedBitmap = bitmap,
                            capturedFrame = frame,
                            capturedPreviewBitmap = previewBitmap,
                            lastExportSuccess = null,
                            isCapturedMotionPhoto = motionPhoto,
                            isCapturing = false
                        )
                    }
                    if (oldBitmap != null && oldBitmap !== bitmap) {
                        extractor.releaseBitmap(oldBitmap)
                    }

                    val customTreeUri = _uiState.value.customExportTreeUri

                    // Export immediately
                    val exportResult = try {
                        beginBitmapExportUsage()
                        if (motionPhoto) {
                            val motionConfig = _uiState.value.exportConfig.copy(motionPhoto = true)
                            exporter.exportMotionPhoto(
                                videoUri = uri,
                                bitmap = bitmap,
                                frame = frame,
                                config = motionConfig,
                                customExportTreeUri = customTreeUri
                            )
                        } else {
                            exporter.exportStaticFrame(
                                bitmap = bitmap,
                                frame = frame,
                                config = _uiState.value.exportConfig,
                                customExportTreeUri = customTreeUri
                            )
                        }
                    } finally {
                        endBitmapExportUsage()
                    }
                    _uiState.update { state ->
                        val successResult = exportResult as? ExportResult.Success
                        state.copy(
                            isExporting = false,
                            exportResult = exportResult,
                            lastExportSuccess = successResult,
                            isCapturedMotionPhoto = successResult?.isMotionPhoto ?: state.isCapturedMotionPhoto
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            isExporting = false,
                            error = PlayerError.CaptureFailed
                        )
                    }
                }
            } catch (e: CancellationException) {
                // User cancelled or scope cancelled — reset state, don't show error
                _uiState.update { it.copy(isCapturing = false, isExporting = false) }
                throw e
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        isExporting = false,
                        error = PlayerError.PermissionDenied
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        isExporting = false,
                        error = PlayerError.ExportFailed(e.message)
                    )
                }
            } finally {
                captureAndSaveJob = null
                val postExportState = _uiState.value
                if (postExportState.deferPendingExternalSwitchUntilExportEnds &&
                    postExportState.pendingExternalVideoUri != null
                ) {
                    loadVideoInternal(
                        uri = postExportState.pendingExternalVideoUri,
                        fromExternalIntent = true,
                        preserveExportResult = true
                    )
                } else if (currentCoroutineContext().isActive && postExportState.videoUri != null) {
                    scheduleHdrCaptureIdleRelease()
                }
            }
        }
    }



    // ── Export ───────────────────────────────────────────────────────────

    fun updateExportConfig(config: ExportConfig) {
        _uiState.update { state ->
            state.copy(exportConfig = config.copy(muteAudio = state.isMuted))
        }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun toggleExportSettings() {
        _uiState.update { it.copy(showExportSettings = !it.showExportSettings) }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Cancel the ongoing capture-and-save operation.
     */
    fun cancelExport() {
        captureAndSaveJob?.cancel()
        captureAndSaveJob = null
    }

    public override fun onCleared() {
        super.onCleared()
        cancelHdrCaptureIdleRelease()
        // Cancel running jobs FIRST — the export coroutine may still hold a
        // reference to capturedBitmap on Dispatchers.IO (e.g. inside
        // Bitmap.compress).  Recycling the bitmap while the IO work is
        // in-flight would cause a use-after-recycle crash.
        captureAndSaveJob?.cancel()
        // Deterministically release any open Media3 GPU HDR capture session
        // (coordinated with any in-flight extraction inside FrameExtractor).
        frameExtractor?.releaseSession()
        // Do NOT recycle capturedBitmap here: the cancelled export coroutine
        // may still be executing non-cancellable blocking IO that references
        // the bitmap.  The Bitmap finalizer will recycle it once the last
        // reference is released by GC.
        // Recycle thumbnail bitmaps — no composables reference them after ViewModel destruction
        clearThumbnailCache(recycleBitmaps = true)
        // Same for the preview session: it owns a decoder and a GL context.
        releaseScrubPreview()
    }

    private fun cancelHdrCaptureIdleRelease() {
        hdrCaptureIdleJob?.cancel()
        hdrCaptureIdleJob = null
    }

    private fun scheduleHdrCaptureIdleRelease() {
        hdrCaptureIdleJob?.cancel()
        hdrCaptureIdleJob = viewModelScope.launch {
            delay(HDR_CAPTURE_IDLE_RELEASE_MS)
            frameExtractor?.releaseSession()
        }
    }

    private fun beginBitmapExportUsage() {
        activeBitmapExportRefCount += 1
    }

    private fun endBitmapExportUsage() {
        if (activeBitmapExportRefCount > 0) {
            activeBitmapExportRefCount -= 1
        }
    }

    private fun isBitmapInUseByExport(): Boolean {
        return activeBitmapExportRefCount > 0
    }

    private fun isAnyExportRunning(): Boolean {
        return _uiState.value.isExporting ||
            captureAndSaveJob?.isActive == true ||
            isBitmapInUseByExport()
    }

    private fun isCapturingOrExporting(): Boolean {
        val state = _uiState.value
        return state.isCapturing || isAnyExportRunning()
    }

    private fun applyPersistedQuickSettings(context: Context) {
        viewModelScope.launch {
            val persisted = preferencesStore?.load() ?: return@launch
            _uiState.update { state ->
                if (!persisted.rememberQuickSettings) {
                    state.copy(
                        isMuted = false,
                        rememberQuickSettings = false,
                        exportConfig = state.exportConfig.copy(muteAudio = false)
                    )
                } else {
                    val savedFormat = persisted.format?.let { name ->
                        try { ExportFormat.valueOf(name) } catch (e: Exception) { null }
                    } ?: state.exportConfig.format

                    val savedDirectory = persisted.exportDirectory?.let { name ->
                        try { ExportDirectory.valueOf(name) } catch (e: Exception) { null }
                    } ?: state.exportConfig.exportDirectory

                    val savedTreeUriStr = persisted.customExportTreeUri
                    val validatedUri = if (!savedTreeUriStr.isNullOrEmpty()) {
                        val parsed = Uri.parse(savedTreeUriStr)
                        val hasPermission = try {
                            context.contentResolver.persistedUriPermissions.any { perm ->
                                perm.uri == parsed && perm.isWritePermission
                            }
                        } catch (e: Exception) {
                            false
                        }
                        if (hasPermission) parsed else null
                    } else null

                    state.copy(
                        isMuted = persisted.isMuted,
                        rememberQuickSettings = true,
                        customExportTreeUri = validatedUri,
                        exportConfig = state.exportConfig.copy(
                            format = savedFormat,
                            quality = persisted.quality,
                            preserveMetadata = persisted.preserveMetadata,
                            motionPhoto = persisted.motionPhoto,
                            customFileName = persisted.customFileName,
                            exportDirectory = savedDirectory,
                            muteAudio = persisted.isMuted
                        )
                    )
                }
            }
        }
    }

    private suspend fun persistQuickSettingsIfEnabled() {
        val state = _uiState.value
        if (!state.rememberQuickSettings) return
        preferencesStore?.saveQuickSettings(
            isMuted = state.isMuted,
            motionPhoto = state.exportConfig.motionPhoto,
            preserveMetadata = state.exportConfig.preserveMetadata,
            format = state.exportConfig.format.name,
            quality = state.exportConfig.quality,
            customFileName = state.exportConfig.customFileName,
            exportDirectory = state.exportConfig.exportDirectory.name,
            customExportTreeUri = state.customExportTreeUri?.toString()
        )
    }
}
