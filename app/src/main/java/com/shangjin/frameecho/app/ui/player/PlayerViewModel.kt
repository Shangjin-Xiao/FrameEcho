package com.shangjin.frameecho.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents user-facing error types. Mapped to localized strings in the UI layer.
 */
sealed class PlayerError {
    data object CaptureFailed : PlayerError()
    data object PermissionDenied : PlayerError()
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
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val capturedFrame: CapturedFrame? = null,
    val capturedBitmap: Bitmap? = null,
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
    val videoFrameRate: Float = 0f
) {
    /**
     * Adaptive seek interval: ~5% of total duration, rounded to a "nice" value.
     * Clamped to [1s, 30s].
     */
    val seekIntervalMs: Long
        get() {
            if (durationMs <= 0) return 1_000L
            val raw = (durationMs * 0.05).toLong()
            // Round to nearest nice step: 1, 2, 5, 10, 15, 20, 30
            val nice = longArrayOf(1000, 2000, 5000, 10_000, 15_000, 20_000, 30_000)
            return nice.minByOrNull { kotlin.math.abs(it - raw) }!!
                .coerceIn(1_000L, 30_000L)
        }

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

    private var frameExtractor: FrameExtractor? = null
    private var frameExporter: FrameExporter? = null
    private var preferencesStore: PlayerPreferencesStore? = null
    private var appContext: Context? = null

    /** Thumbnail cache: index → Bitmap. Kept outside UiState to avoid serialization issues. */
    private val _thumbnailCache = mutableStateMapOf<Int, Bitmap>()
    private var batchThumbnailJob: Job? = null
    private var captureAndSaveJob: Job? = null
    private var activeBitmapExportRefCount: Int = 0

    companion object {
        /** Width of each thumbnail in the timeline strip (px) */
        const val THUMBNAIL_WIDTH_PX = 120
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
        /** Thumbnails per batch for progressive loading */
        const val THUMBNAIL_BATCH_SIZE = 5
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
        // Capture reference BEFORE updating state so we can recycle safely
        // after the StateFlow no longer exposes the old bitmap.
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        cancelExport()
        clearThumbnailCache()

        _uiState.update {
            it.copy(
                videoUri = uri,
                capturedBitmap = null,
                capturedFrame = null,
                error = null,
                thumbnailCount = 0,
                videoFrameRate = 0f
            )
        }

        // Recycle only when not exporting — the export coroutine may still
        // reference the bitmap on Dispatchers.IO.
        if (canRecycleImmediately) {
            oldBitmap?.recycle()
        }
    }

    /**
     * Clear the video URI and return to home state.
     */
    fun clearVideoUri() {
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        cancelExport()
        clearThumbnailCache()
        _uiState.update {
            PlayerUiState(
                isMuted = it.isMuted,
                rememberQuickSettings = it.rememberQuickSettings,
                exportConfig = it.exportConfig,
                customExportTreeUri = it.customExportTreeUri
            )
        }
        // Recycle after state update so no observer can read a recycled bitmap.
        if (canRecycleImmediately) {
            oldBitmap?.recycle()
        }
    }

    fun updatePosition(positionMs: Long) {
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun updateDuration(durationMs: Long) {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        if (safeDurationMs == _uiState.value.durationMs && _uiState.value.thumbnailCount > 0) return
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
        loadThumbnailsForFullVideo()
    }

    /**
     * Load all missing thumbnails spanning the full video duration.
     * Thumbnails are evenly distributed across the entire video, so they
     * load once and remain stable throughout playback.
     *
     * Uses progressive batch loading (5 at a time) so thumbnails appear
     * incrementally as they're extracted, instead of all-or-nothing.
     */
    private fun loadThumbnailsForFullVideo() {
        if (batchThumbnailJob?.isActive == true) return

        val uri = _uiState.value.videoUri ?: return
        val durationMs = _uiState.value.durationMs
        if (durationMs <= 0) return
        val extractor = frameExtractor ?: return

        batchThumbnailJob = viewModelScope.launch {
            val denominator = (_uiState.value.thumbnailCount - 1).coerceAtLeast(1)

            val missingIndices = (0 until _uiState.value.thumbnailCount).filter {
                !_thumbnailCache.containsKey(it)
            }
            if (missingIndices.isEmpty()) return@launch

            // Load in small batches for progressive appearance
            for (batch in missingIndices.chunked(THUMBNAIL_BATCH_SIZE)) {
                ensureActive()

                val indexToTimestamp = batch.associateWith { index ->
                    val positionMs = (durationMs * index) / denominator
                    positionMs * 1000L
                }

                try {
                    val results = extractor.extractThumbnails(
                        uri,
                        indexToTimestamp.values.toList(),
                        THUMBNAIL_WIDTH_PX
                    )

                    val timestampToBitmap = results.toMap()
                    indexToTimestamp.forEach { (index, timestampUs) ->
                        timestampToBitmap[timestampUs]?.let { bitmap ->
                            _thumbnailCache[index] = bitmap
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appContext?.let { ctx ->
                        LogUtils.w(ctx, "PlayerViewModel", "Failed to load thumbnail batch", e)
                    }
                }
            }
        }
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
        if (recycleBitmaps) {
            _thumbnailCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        _thumbnailCache.clear()
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

        captureAndSaveJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, isExporting = true, error = null, exportResult = null) }

            try {
                val timestampUs = _uiState.value.currentPositionMs * 1000L
                val result = extractor.extractFrameWithInfo(uri, timestampUs)

                if (result != null) {
                    val (bitmap, frame) = result
                    // Capture old reference BEFORE updating state,
                    // then recycle AFTER so no observer reads a recycled bitmap.
                    val oldBitmap = _uiState.value.capturedBitmap
                    _uiState.update {
                        it.copy(
                            capturedBitmap = bitmap,
                            capturedFrame = frame,
                            isCapturing = false
                        )
                    }
                    oldBitmap?.let { extractor.releaseBitmap(it) }

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
                    _uiState.update {
                        it.copy(isExporting = false, exportResult = exportResult)
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

    override fun onCleared() {
        super.onCleared()
        // Cancel running jobs FIRST — the export coroutine may still hold a
        // reference to capturedBitmap on Dispatchers.IO (e.g. inside
        // Bitmap.compress).  Recycling the bitmap while the IO work is
        // in-flight would cause a use-after-recycle crash.
        captureAndSaveJob?.cancel()
        // Do NOT recycle capturedBitmap here: the cancelled export coroutine
        // may still be executing non-cancellable blocking IO that references
        // the bitmap.  The Bitmap finalizer will recycle it once the last
        // reference is released by GC.
        // Recycle thumbnail bitmaps — no composables reference them after ViewModel destruction
        clearThumbnailCache(recycleBitmaps = true)
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
                        runCatching { ExportFormat.valueOf(name) }.getOrNull()
                    } ?: state.exportConfig.format

                    val savedDirectory = persisted.exportDirectory?.let { name ->
                        runCatching { ExportDirectory.valueOf(name) }.getOrNull()
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
