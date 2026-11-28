@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.core.media.extraction

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.FrameExtractor as Media3FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import com.shangjin.frameecho.core.media.colorspace.ColorSpaceDetector
import com.shangjin.frameecho.core.media.colorspace.HdrToneMapper
import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.media.utils.getIntegerOrNullSafe
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.VideoMetadata
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * High-performance video frame extraction engine.
 *
 * Routes extraction based on detected video color space:
 * - **SDR sources**: Lightweight [MediaMetadataRetriever] path with zero extra overhead.
 * - **HDR sources** (`HDR10`, `HDR10+`, `HLG`, `DOLBY_VISION`):
 *   - Primary path uses Media3's GPU OpenGL [Media3FrameExtractor] configured with
 *     [SeekParameters.EXACT] and `setExtractHdrFrames(false)` for frame-accurate HDR-to-SDR
 *     `sRGB` tone mapping at original resolution (and GPU [Presentation] downscaling for
 *     timeline thumbnails).
 *   - Fallback path uses [MediaMetadataRetriever] (`OPTION_CLOSEST`, requesting `RGBA_F16`
 *     via `BitmapParams` on API 28+ when supported) paired with [HdrToneMapper]'s perceptual
 *     BT.2408 EETF + `BT.2020 -> BT.709` + dithering pipeline.
 */
class FrameExtractor(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    companion object {
        const val DEFAULT_THUMBNAIL_CHUNK_SIZE = 8
        const val DEFAULT_PREVIEW_SHORT_SIDE_PX = 160
        private const val MAX_PREVIEW_LONG_SIDE_PX = 640
        private const val MAX_THUMBNAIL_DIMENSION = 240
        // Fits the timeline's 150 thumbnails at 120 x 240, including portrait videos.
        private const val MAX_THUMBNAIL_BATCH_PIXELS = 4_500_000L
        private const val MAX_THUMBNAIL_COUNT = 150
    }

    internal data class Media3SessionConfig(
        val videoUri: Uri,
        val mediaId: String,
        val seekParameters: SeekParameters,
        val extractHdrFrames: Boolean,
        val targetWidth: Int? = null,
        val targetHeight: Int? = null
    )

    internal interface Media3FrameSession : AutoCloseable {
        suspend fun extractFrame(positionMs: Long): Bitmap?
        override fun close()
    }

    private data class CacheKey(val uri: String, val generation: Long)

    private data class ResolvedColorSpace(
        val colorSpace: ColorSpaceInfo,
        val mediaFormat: MediaFormat?,
        val fetchedTrackFormat: Boolean
    )

    private val cacheGeneration = AtomicLong(0L)
    private val sessionNonce = AtomicLong(0L)
    private val sessionLock = Any()
    private val hdrCaptureMutex = Mutex()

    @Volatile
    private var cachedMetadata: Pair<CacheKey, VideoMetadata>? = null

    @Volatile
    private var cachedColorSpace: Pair<CacheKey, ColorSpaceInfo>? = null

    private var activeHdrCaptureSession: Media3FrameSession? = null
    private var activeHdrCaptureKey: CacheKey? = null

    internal var media3SessionFactory: (Media3SessionConfig) -> Media3FrameSession =
        ::createDefaultMedia3Session

    /**
     * Invalidates cached video metadata and color space information, advances the cache
     * generation so any in-flight extraction from a previous session cannot repopulate the cache,
     * and deterministically closes any open Media3 HDR decoding session.
     */
    fun invalidateCache() {
        synchronized(this) {
            cacheGeneration.incrementAndGet()
            cachedMetadata = null
            cachedColorSpace = null
        }
        releaseSession()
    }

    /**
     * Deterministically closes any open Media3 HDR frame-extraction session while retaining
     * cached metadata and color-space info for the current video URI.
     *
     * Safe to call from any thread (e.g. idle timeout, video switch, or `ViewModel.onCleared`).
     */
    fun releaseSession() {
        val sessionToClose = synchronized(sessionLock) {
            val current = activeHdrCaptureSession
            activeHdrCaptureSession = null
            activeHdrCaptureKey = null
            current
        }
        sessionToClose?.closeQuietly()
    }

    /**
     * Releases all cached state and underlying hardware decoding / GL resources.
     */
    fun release() {
        invalidateCache()
    }

    /**
     * Recycle the bitmap immediately.
     */
    fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    /**
     * Create a downsampled software (`ARGB_8888`) preview thumbnail for Compose UI display.
     *
     * Always returns an independent [Bitmap] instance distinct from [source] so that recycling
     * the full-resolution `capturedBitmap` can never invalidate an in-flight Compose draw pass.
     * The returned thumbnail is intended to be freed by GC when unreachable (never explicitly recycled).
     */
    suspend fun createPreviewThumbnail(
        source: Bitmap,
        targetShortSidePx: Int = DEFAULT_PREVIEW_SHORT_SIDE_PX
    ): Bitmap? = withContext(Dispatchers.Default) {
        createPreviewThumbnailSync(source, targetShortSidePx)
    }

    internal fun createPreviewThumbnailSync(
        source: Bitmap,
        targetShortSidePx: Int = DEFAULT_PREVIEW_SHORT_SIDE_PX
    ): Bitmap? {
        if (source.isRecycled || targetShortSidePx <= 0) return null
        val srcWidth = source.width
        val srcHeight = source.height
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val (targetWidth, targetHeight) = previewThumbnailDimensions(
            sourceWidth = srcWidth,
            sourceHeight = srcHeight,
            targetShortSidePx = targetShortSidePx
        )

        var softSource: Bitmap? = null
        return try {
            val working = if (source.config != Bitmap.Config.ARGB_8888) {
                val copied = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                if (copied !== source) {
                    softSource = copied
                }
                copied
            } else {
                source
            }

            val scaled = working.scale(targetWidth, targetHeight, true)
            when {
                scaled === source -> {
                    scaled.copy(Bitmap.Config.ARGB_8888, false)?.takeIf { it !== source }
                }
                scaled === softSource -> {
                    softSource = null
                    scaled
                }
                else -> scaled
            }
        } catch (e: Exception) {
            safeLogW("Failed to create preview thumbnail", e)
            null
        } finally {
            val temp = softSource
            if (temp != null && temp !== source && !temp.isRecycled) {
                temp.recycle()
            }
        }
    }

    internal fun previewThumbnailDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        targetShortSidePx: Int = DEFAULT_PREVIEW_SHORT_SIDE_PX
    ): Pair<Int, Int> {
        val shortSide = minOf(sourceWidth, sourceHeight)
        val longSide = maxOf(sourceWidth, sourceHeight)
        val maxLongSidePx = maxOf(targetShortSidePx, MAX_PREVIEW_LONG_SIDE_PX)
        val shortScale = if (shortSide > targetShortSidePx) {
            targetShortSidePx.toDouble() / shortSide.toDouble()
        } else {
            1.0
        }
        val scale = if (longSide * shortScale > maxLongSidePx) {
            minOf(1.0, shortScale, maxLongSidePx.toDouble() / longSide.toDouble())
        } else {
            minOf(1.0, shortScale)
        }
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Extract a single frame at the specified timestamp.
     *
     * For HDR videos, attempts frame-exact GPU OpenGL tone mapping via Media3 first,
     * falling back to [MediaMetadataRetriever] + [HdrToneMapper] if GPU extraction fails.
     * For SDR videos, uses [MediaMetadataRetriever] directly with zero extra overhead.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @return SDR `sRGB` [Bitmap] of the extracted frame, or null on failure
     */
    suspend fun extractFrame(
        videoUri: Uri,
        timestampUs: Long
    ): Bitmap? {
        val startGeneration = cacheGeneration.get()
        val resolved = resolveColorSpace(videoUri, startGeneration)
        val colorSpace = resolved.colorSpace

        if (!colorSpace.isHdr) {
            return useRetriever(
                videoUri,
                null,
                "Failed to extract frame at $timestampUs"
            ) { retriever ->
                extractFrameInternal(retriever, timestampUs)
            }
        }

        val gpuBitmap = extractHdrFrameViaMedia3(videoUri, timestampUs, startGeneration)
        if (gpuBitmap != null) {
            return gpuBitmap
        }

        return useRetriever(
            videoUri,
            null,
            "Failed to extract frame at $timestampUs"
        ) { retriever ->
            val rawBitmap = extractHdrFallbackFrameInternal(retriever, timestampUs)
                ?: return@useRetriever null
            try {
                val mapped = HdrToneMapper.process(
                    bitmap = rawBitmap,
                    colorSpaceInfo = colorSpace,
                    isAlreadyToneMapped = false
                )
                if (mapped !== rawBitmap) {
                    releaseBitmap(rawBitmap)
                }
                mapped
            } catch (t: Throwable) {
                releaseBitmap(rawBitmap)
                throw t
            }
        }
    }

    /**
     * Extract a frame with full metadata and color space information.
     *
     * - **SDR**: Uses [MediaMetadataRetriever] (`OPTION_CLOSEST`) directly.
     * - **HDR**: Uses Media3 GPU OpenGL pipeline (`SeekParameters.EXACT` +
     *   `setExtractHdrFrames(false)`) and marks [CapturedFrame.isToneMapped] = `true` so
     *   `FrameExporter` / [HdrToneMapper] skips double tone mapping. If GPU extraction throws
     *   or returns `null`, falls back to [MediaMetadataRetriever] (`OPTION_CLOSEST`, requesting
     *   `RGBA_F16` on API 28+) with [CapturedFrame.isToneMapped] = `false` so [HdrToneMapper]
     *   applies perceptual software EETF + `BT.2020 -> BT.709` + dithering during export.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @return Pair of extracted [Bitmap] and [CapturedFrame], or null on failure
     */
    suspend fun extractFrameWithInfo(
        videoUri: Uri,
        timestampUs: Long
    ): Pair<Bitmap, CapturedFrame>? {
        val startGeneration = cacheGeneration.get()
        val cacheKey = CacheKey(videoUri.toString(), startGeneration)
        val resolved = resolveColorSpace(videoUri, startGeneration)
        val colorSpace = resolved.colorSpace

        if (colorSpace.isHdr) {
            val gpuBitmap = extractHdrFrameViaMedia3(videoUri, timestampUs, startGeneration)
            if (gpuBitmap != null) {
                try {
                    val metadata = resolveMetadataWithoutRetriever(
                        videoUri = videoUri,
                        cacheKey = cacheKey,
                        startGeneration = startGeneration,
                        preloadedFormat = resolved.mediaFormat,
                        alreadyFetchedFormat = resolved.fetchedTrackFormat
                    )
                    val frame = CapturedFrame(
                        timestampUs = timestampUs,
                        width = gpuBitmap.width,
                        height = gpuBitmap.height,
                        colorSpace = colorSpace,
                        metadata = metadata,
                        isToneMapped = true
                    )
                    return Pair(gpuBitmap, frame)
                } catch (t: Throwable) {
                    releaseBitmap(gpuBitmap)
                    throw t
                }
            }
        }

        return useRetriever(
            videoUri,
            null,
            "Failed to extract frame with info"
        ) { retriever ->
            val bitmap = if (colorSpace.isHdr) {
                extractHdrFallbackFrameInternal(retriever, timestampUs)
            } else {
                extractFrameInternal(retriever, timestampUs)
            } ?: return@useRetriever null

            val metadata = resolveMetadataWithRetriever(
                videoUri = videoUri,
                retriever = retriever,
                cacheKey = cacheKey,
                startGeneration = startGeneration,
                preloadedFormat = resolved.mediaFormat,
                alreadyFetchedFormat = resolved.fetchedTrackFormat
            )

            val frame = CapturedFrame(
                timestampUs = timestampUs,
                width = bitmap.width,
                height = bitmap.height,
                colorSpace = colorSpace,
                metadata = metadata,
                isToneMapped = false
            )

            Pair(bitmap, frame)
        }
    }

    /**
     * Extract a downscaled thumbnail frame for timeline preview.
     * Uses keyframe seeking (`SeekParameters.CLOSEST_SYNC` / `OPTION_CLOSEST_SYNC`) since
     * frame-exact precision is not critical for timeline thumbnails.
     *
     * For HDR sources, uses GPU-side [Presentation] downscaling + OpenGL HDR-to-SDR tone mapping
     * to avoid timeline color shifts, falling back to [MediaMetadataRetriever] + [HdrToneMapper].
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * Thumbnails fit within 240 x 240 pixels without upscaling.
     */
    suspend fun extractThumbnail(
        videoUri: Uri,
        timestampUs: Long,
        targetWidth: Int
    ): Bitmap? {
        if (targetWidth <= 0) return null
        val startGeneration = cacheGeneration.get()
        val resolved = resolveColorSpace(videoUri, startGeneration)
        val colorSpace = resolved.colorSpace

        if (colorSpace.isHdr) {
            val dims = resolveVideoDisplayDimensions(videoUri, resolved.mediaFormat)
            if (dims != null) {
                val (videoWidth, videoHeight) = dims
                if (videoWidth <= 0 || videoHeight <= 0) return null
                val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)
                val gpuThumb = extractHdrThumbnailViaMedia3Single(
                    videoUri = videoUri,
                    timestampUs = timestampUs,
                    width = width,
                    height = height
                )
                if (gpuThumb != null) {
                    return gpuThumb
                }
            }
        }

        return useRetriever(
            videoUri,
            null,
            "Failed to extract thumbnail at $timestampUs"
        ) { retriever ->
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return@useRetriever null
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return@useRetriever null

            if (videoWidth <= 0 || videoHeight <= 0) return@useRetriever null

            val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)

            val rawThumb = extractThumbnailInternal(retriever, timestampUs, width, height)
                ?: return@useRetriever null
            toneMapThumbnailIfHdr(rawThumb, colorSpace)
        }
    }

    /**
     * Extract multiple downscaled thumbnails efficiently by reusing a single session.
     *
     * Thumbnails fit within 240 x 240 pixels without upscaling. Each batch is
     * limited to 150 frames and 4.5 million retained pixels.
     */
    suspend fun extractThumbnails(
        videoUri: Uri,
        timestampsUs: List<Long>,
        targetWidth: Int
    ): List<Pair<Long, Bitmap>> {
        require(targetWidth > 0) { "targetWidth must be positive, was $targetWidth" }
        currentCoroutineContext().ensureActive()
        val startGeneration = cacheGeneration.get()
        val resolved = resolveColorSpace(videoUri, startGeneration)
        val colorSpace = resolved.colorSpace

        if (colorSpace.isHdr && timestampsUs.isNotEmpty()) {
            val dims = resolveVideoDisplayDimensions(videoUri, resolved.mediaFormat)
            if (dims != null) {
                val (videoWidth, videoHeight) = dims
                if (videoWidth <= 0 || videoHeight <= 0) return emptyList()
                val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)
                val maxCount = minOf(
                    MAX_THUMBNAIL_COUNT,
                    (MAX_THUMBNAIL_BATCH_PIXELS / (width.toLong() * height)).toInt()
                )
                if (maxCount <= 0) return emptyList()
                val limit = minOf(timestampsUs.size, maxCount)
                val gpuResults = extractHdrThumbnailsBatchViaMedia3(
                    videoUri = videoUri,
                    timestampsUs = timestampsUs,
                    limit = limit,
                    width = width,
                    height = height
                )
                if (gpuResults != null) {
                    return gpuResults
                }
            }
        }

        return useRetriever(
            videoUri,
            emptyList(),
            "Failed to extract thumbnails"
        ) { retriever ->
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return@useRetriever emptyList()
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return@useRetriever emptyList()

            if (videoWidth <= 0 || videoHeight <= 0) return@useRetriever emptyList()

            val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)
            // Bound the batch before decoding or allocating any thumbnail bitmaps.
            val maxCount = minOf(
                MAX_THUMBNAIL_COUNT,
                (MAX_THUMBNAIL_BATCH_PIXELS / (width.toLong() * height)).toInt()
            )
            if (maxCount <= 0) return@useRetriever emptyList()

            val limit = minOf(timestampsUs.size, maxCount)
            val results = ArrayList<Pair<Long, Bitmap>>(limit)
            try {
                for (i in 0 until limit) {
                    currentCoroutineContext().ensureActive()
                    val ts = timestampsUs[i]
                    val rawThumb = extractThumbnailInternal(retriever, ts, width, height)
                    val bitmap = rawThumb?.let { toneMapThumbnailIfHdr(it, colorSpace) }
                    if (bitmap != null) {
                        results.add(ts to bitmap)
                    }
                    currentCoroutineContext().ensureActive()
                }
                results
            } catch (t: Throwable) {
                results.forEach { (_, bitmap) -> releaseBitmap(bitmap) }
                results.clear()
                throw t
            }
        }
    }

    /**
     * Stream downscaled thumbnails progressively in fixed-size chunks while reusing a single
     * decoding session (Media3 GPU [Presentation] + OpenGL tone-mapping session for HDR videos;
     * [MediaMetadataRetriever] for SDR videos or fallback).
     */
    suspend fun extractThumbnails(
        videoUri: Uri,
        timestampsUs: List<Long>,
        targetWidth: Int,
        chunkSize: Int = DEFAULT_THUMBNAIL_CHUNK_SIZE,
        onChunk: suspend (List<Pair<Long, Bitmap>>) -> Unit
    ) {
        require(targetWidth > 0) { "targetWidth must be positive, was $targetWidth" }
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        currentCoroutineContext().ensureActive()
        if (timestampsUs.isEmpty()) return

        val startGeneration = cacheGeneration.get()
        val resolved = resolveColorSpace(videoUri, startGeneration)
        val colorSpace = resolved.colorSpace

        if (colorSpace.isHdr) {
            val dims = resolveVideoDisplayDimensions(videoUri, resolved.mediaFormat)
            if (dims != null) {
                val (videoWidth, videoHeight) = dims
                if (videoWidth <= 0 || videoHeight <= 0) return
                val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)
                val maxCount = minOf(
                    MAX_THUMBNAIL_COUNT,
                    (MAX_THUMBNAIL_BATCH_PIXELS / (width.toLong() * height)).toInt()
                )
                if (maxCount <= 0) return
                val limit = minOf(timestampsUs.size, maxCount)
                val streamedViaGpu = streamHdrThumbnailsViaMedia3(
                    videoUri = videoUri,
                    timestampsUs = timestampsUs,
                    limit = limit,
                    width = width,
                    height = height,
                    chunkSize = chunkSize,
                    onChunk = onChunk
                )
                if (streamedViaGpu) {
                    return
                }
            }
        }

        useRetriever(
            videoUri = videoUri,
            defaultValue = Unit,
            errorMessage = "Failed to extract thumbnails",
            rethrowExceptions = true
        ) { retriever ->
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return@useRetriever
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return@useRetriever

            if (videoWidth <= 0 || videoHeight <= 0) return@useRetriever

            val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)
            // Bound the batch before decoding or allocating any thumbnail bitmaps.
            val maxCount = minOf(
                MAX_THUMBNAIL_COUNT,
                (MAX_THUMBNAIL_BATCH_PIXELS / (width.toLong() * height)).toInt()
            )
            if (maxCount <= 0) return@useRetriever

            val limit = minOf(timestampsUs.size, maxCount)
            val pendingChunk = ArrayList<Pair<Long, Bitmap>>(minOf(chunkSize, limit))
            try {
                for (i in 0 until limit) {
                    currentCoroutineContext().ensureActive()
                    val ts = timestampsUs[i]
                    val rawThumb = extractThumbnailInternal(retriever, ts, width, height)
                    val bitmap = rawThumb?.let { toneMapThumbnailIfHdr(it, colorSpace) }
                    if (bitmap != null) {
                        pendingChunk.add(ts to bitmap)
                    }
                    currentCoroutineContext().ensureActive()
                    if (pendingChunk.size >= chunkSize) {
                        val chunk = pendingChunk.toList()
                        pendingChunk.clear()
                        onChunk(chunk)
                    }
                }
                if (pendingChunk.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val chunk = pendingChunk.toList()
                    pendingChunk.clear()
                    onChunk(chunk)
                }
            } catch (t: Throwable) {
                pendingChunk.forEach { (_, bitmap) -> releaseBitmap(bitmap) }
                pendingChunk.clear()
                throw t
            }
        }
    }

    // ── Color space & metadata resolution ───────────────────────────────

    private suspend fun resolveColorSpace(
        videoUri: Uri,
        startGeneration: Long
    ): ResolvedColorSpace {
        val cacheKey = CacheKey(videoUri.toString(), startGeneration)
        cachedColorSpace?.takeIf { it.first == cacheKey }?.second?.let { cached ->
            return ResolvedColorSpace(
                colorSpace = cached,
                mediaFormat = null,
                fetchedTrackFormat = false
            )
        }

        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            cachedColorSpace?.takeIf { it.first == cacheKey }?.second?.let { cached ->
                return@withContext ResolvedColorSpace(
                    colorSpace = cached,
                    mediaFormat = null,
                    fetchedTrackFormat = false
                )
            }

            val mediaFormat = safeGetVideoTrackFormat(videoUri)
            if (mediaFormat == null) {
                // Do not cache fallback SDR when track format could not be read (e.g. unmocked
                // MediaExtractor in unit tests or transient I/O before setDataSource).
                return@withContext ResolvedColorSpace(
                    colorSpace = ColorSpaceInfo(),
                    mediaFormat = null,
                    fetchedTrackFormat = true
                )
            }

            val detected = try {
                ColorSpaceDetector.detect(mediaFormat)
            } catch (_: Exception) {
                ColorSpaceInfo()
            }
            val resolved = synchronized(this@FrameExtractor) {
                cachedColorSpace?.takeIf { it.first == cacheKey }?.second ?: run {
                    if (cacheGeneration.get() == startGeneration) {
                        cachedColorSpace = cacheKey to detected
                    }
                    detected
                }
            }
            ResolvedColorSpace(
                colorSpace = resolved,
                mediaFormat = mediaFormat,
                fetchedTrackFormat = true
            )
        }
    }

    private suspend fun resolveMetadataWithoutRetriever(
        videoUri: Uri,
        cacheKey: CacheKey,
        startGeneration: Long,
        preloadedFormat: MediaFormat?,
        alreadyFetchedFormat: Boolean
    ): VideoMetadata = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        cachedMetadata?.takeIf { it.first == cacheKey }?.second?.let { return@withContext it }

        val format = if (alreadyFetchedFormat) {
            preloadedFormat
        } else {
            safeGetVideoTrackFormat(videoUri)
        }
        val extracted = MetadataExtractor.extract(context, videoUri, null, format)
        synchronized(this@FrameExtractor) {
            cachedMetadata?.takeIf { it.first == cacheKey }?.second ?: run {
                if (cacheGeneration.get() == startGeneration) {
                    cachedMetadata = cacheKey to extracted
                }
                extracted
            }
        }
    }

    private fun resolveMetadataWithRetriever(
        videoUri: Uri,
        retriever: MediaMetadataRetriever,
        cacheKey: CacheKey,
        startGeneration: Long,
        preloadedFormat: MediaFormat?,
        alreadyFetchedFormat: Boolean
    ): VideoMetadata {
        cachedMetadata?.takeIf { it.first == cacheKey }?.second?.let { return it }

        val mediaFormat = if (alreadyFetchedFormat) {
            preloadedFormat
        } else {
            safeGetVideoTrackFormat(videoUri)
        }

        if (cachedColorSpace?.takeIf { it.first == cacheKey } == null) {
            val detected = mediaFormat?.let { ColorSpaceDetector.detect(it) } ?: ColorSpaceInfo()
            synchronized(this) {
                if (cachedColorSpace?.first != cacheKey && cacheGeneration.get() == startGeneration) {
                    cachedColorSpace = cacheKey to detected
                }
            }
        }

        // Extract outside synchronized(this) so main-thread invalidateCache() never blocks on I/O
        val extracted = MetadataExtractor.extract(context, videoUri, retriever, mediaFormat)
        return synchronized(this) {
            cachedMetadata?.takeIf { it.first == cacheKey }?.second ?: run {
                if (cacheGeneration.get() == startGeneration) {
                    cachedMetadata = cacheKey to extracted
                }
                extracted
            }
        }
    }

    private fun safeGetVideoTrackFormat(videoUri: Uri): MediaFormat? {
        return try {
            MetadataExtractor.getVideoTrackFormat(context, videoUri)
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveVideoDisplayDimensions(
        videoUri: Uri,
        preloadedFormat: MediaFormat?
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val format = preloadedFormat ?: safeGetVideoTrackFormat(videoUri)
        if (format != null) {
            val rawW = format.getIntegerOrNullSafe(MediaFormat.KEY_WIDTH) ?: 0
            val rawH = format.getIntegerOrNullSafe(MediaFormat.KEY_HEIGHT) ?: 0
            if (rawW > 0 && rawH > 0) {
                val rotation = format.getIntegerOrNullSafe(MediaFormat.KEY_ROTATION) ?: 0
                return@withContext if (rotation == 90 || rotation == 270) {
                    rawH to rawW
                } else {
                    rawW to rawH
                }
            }
        }
        null
    }

    // ── Media3 GPU OpenGL HDR extraction ────────────────────────────────

    private suspend fun extractHdrFrameViaMedia3(
        videoUri: Uri,
        timestampUs: Long,
        generation: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        hdrCaptureMutex.withLock {
            currentCoroutineContext().ensureActive()
            val key = CacheKey(videoUri.toString(), generation)
            val positionMs = timestampUsToPositionMs(timestampUs)
            var session: Media3FrameSession? = null
            try {
                session = getOrCreateHdrCaptureSession(videoUri, key, generation)
                var bitmap = session.extractFrame(positionMs)
                if (bitmap != null && bitmap.isRecycled) {
                    // If caller recycled a previously captured bitmap at the exact same timestamp,
                    // Media3's internal dedupe cache could return that recycled Bitmap. Recreate
                    // the session with a fresh mediaId nonce to force a clean GPU frame decode.
                    evictHdrCaptureSession(session)
                    session = getOrCreateHdrCaptureSession(videoUri, key, generation)
                    bitmap = session.extractFrame(positionMs)
                }
                if (bitmap == null || bitmap.isRecycled) {
                    evictHdrCaptureSession(session)
                    return@withLock null
                }
                if (cacheGeneration.get() != generation) {
                    // Generation changed mid-flight; ensure stale session does not linger.
                    evictHdrCaptureSession(session)
                }
                bitmap
            } catch (e: CancellationException) {
                // Deterministically close the session immediately on cancellation so hardware
                // decoders and EGL contexts are released right away.
                evictHdrCaptureSession(session)
                throw e
            } catch (e: SecurityException) {
                evictHdrCaptureSession(session)
                throw e
            } catch (e: Exception) {
                evictHdrCaptureSession(session)
                safeLogW("Media3 GPU HDR extraction failed, falling back to MediaMetadataRetriever", e)
                null
            }
        }
    }

    private fun getOrCreateHdrCaptureSession(
        videoUri: Uri,
        key: CacheKey,
        generation: Long
    ): Media3FrameSession {
        var staleToClose: Media3FrameSession? = null
        synchronized(sessionLock) {
            val existing = activeHdrCaptureSession
            if (existing != null && activeHdrCaptureKey == key) {
                return existing
            }
            staleToClose = existing
            activeHdrCaptureSession = null
            activeHdrCaptureKey = null
        }
        staleToClose?.closeQuietly()

        val created = media3SessionFactory(
            Media3SessionConfig(
                videoUri = videoUri,
                mediaId = "hdr-capture-${sessionNonce.incrementAndGet()}:$videoUri",
                seekParameters = SeekParameters.EXACT,
                extractHdrFrames = false
            )
        )

        var orphanToClose: Media3FrameSession? = null
        synchronized(sessionLock) {
            if (cacheGeneration.get() == generation) {
                orphanToClose = activeHdrCaptureSession
                activeHdrCaptureSession = created
                activeHdrCaptureKey = key
            }
        }
        orphanToClose?.takeIf { it !== created }?.closeQuietly()
        return created
    }

    private fun evictHdrCaptureSession(session: Media3FrameSession?) {
        if (session == null) return
        synchronized(sessionLock) {
            if (activeHdrCaptureSession === session) {
                activeHdrCaptureSession = null
                activeHdrCaptureKey = null
            }
        }
        session.closeQuietly()
    }

    private suspend fun extractHdrThumbnailViaMedia3Single(
        videoUri: Uri,
        timestampUs: Long,
        width: Int,
        height: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var session: Media3FrameSession? = null
        try {
            session = media3SessionFactory(
                Media3SessionConfig(
                    videoUri = videoUri,
                    mediaId = "hdr-thumb-${sessionNonce.incrementAndGet()}:$videoUri:${width}x$height",
                    seekParameters = SeekParameters.CLOSEST_SYNC,
                    extractHdrFrames = false,
                    targetWidth = width,
                    targetHeight = height
                )
            )
            val bitmap = session.extractFrame(timestampUsToPositionMs(timestampUs))
            bitmap?.takeIf { !it.isRecycled }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            safeLogW("Media3 GPU HDR thumbnail extraction failed, falling back", e)
            null
        } finally {
            session?.closeQuietly()
        }
    }

    private suspend fun extractHdrThumbnailsBatchViaMedia3(
        videoUri: Uri,
        timestampsUs: List<Long>,
        limit: Int,
        width: Int,
        height: Int
    ): List<Pair<Long, Bitmap>>? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var session: Media3FrameSession? = null
        val results = ArrayList<Pair<Long, Bitmap>>(limit)
        try {
            session = media3SessionFactory(
                Media3SessionConfig(
                    videoUri = videoUri,
                    mediaId = "hdr-thumbs-${sessionNonce.incrementAndGet()}:$videoUri:${width}x$height",
                    seekParameters = SeekParameters.CLOSEST_SYNC,
                    extractHdrFrames = false,
                    targetWidth = width,
                    targetHeight = height
                )
            )
            for (i in 0 until limit) {
                currentCoroutineContext().ensureActive()
                val ts = timestampsUs[i]
                val bitmap = session.extractFrame(timestampUsToPositionMs(ts))
                if (bitmap != null && !bitmap.isRecycled) {
                    results.add(ts to bitmap)
                } else if (results.isEmpty() && i == 0) {
                    // First frame returned null; fall back to MediaMetadataRetriever for the batch.
                    return@withContext null
                }
                currentCoroutineContext().ensureActive()
            }
            results
        } catch (e: CancellationException) {
            results.forEach { (_, bmp) -> releaseBitmap(bmp) }
            results.clear()
            throw e
        } catch (e: SecurityException) {
            results.forEach { (_, bmp) -> releaseBitmap(bmp) }
            results.clear()
            throw e
        } catch (e: Exception) {
            results.forEach { (_, bmp) -> releaseBitmap(bmp) }
            results.clear()
            safeLogW("Media3 GPU HDR batch thumbnail extraction failed, falling back", e)
            null
        } finally {
            session?.closeQuietly()
        }
    }

    private suspend fun streamHdrThumbnailsViaMedia3(
        videoUri: Uri,
        timestampsUs: List<Long>,
        limit: Int,
        width: Int,
        height: Int,
        chunkSize: Int,
        onChunk: suspend (List<Pair<Long, Bitmap>>) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var session: Media3FrameSession? = null
        var emittedAnyChunk = false
        val pendingChunk = ArrayList<Pair<Long, Bitmap>>(minOf(chunkSize, limit))
        try {
            session = media3SessionFactory(
                Media3SessionConfig(
                    videoUri = videoUri,
                    mediaId = "hdr-stream-thumbs-${sessionNonce.incrementAndGet()}:$videoUri:${width}x$height",
                    seekParameters = SeekParameters.CLOSEST_SYNC,
                    extractHdrFrames = false,
                    targetWidth = width,
                    targetHeight = height
                )
            )
            for (i in 0 until limit) {
                currentCoroutineContext().ensureActive()
                val ts = timestampsUs[i]
                val bitmap = session.extractFrame(timestampUsToPositionMs(ts))
                if (bitmap != null && !bitmap.isRecycled) {
                    pendingChunk.add(ts to bitmap)
                } else if (!emittedAnyChunk && pendingChunk.isEmpty() && i == 0) {
                    return@withContext false
                }
                currentCoroutineContext().ensureActive()
                if (pendingChunk.size >= chunkSize) {
                    val chunk = pendingChunk.toList()
                    pendingChunk.clear()
                    emittedAnyChunk = true
                    invokeChunkPropagatingConsumerErrors(chunk, onChunk)
                }
            }
            if (pendingChunk.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val chunk = pendingChunk.toList()
                pendingChunk.clear()
                emittedAnyChunk = true
                invokeChunkPropagatingConsumerErrors(chunk, onChunk)
            }
            true
        } catch (e: ConsumerCallbackException) {
            pendingChunk.forEach { (_, bmp) -> releaseBitmap(bmp) }
            pendingChunk.clear()
            throw e.cause
        } catch (e: CancellationException) {
            pendingChunk.forEach { (_, bmp) -> releaseBitmap(bmp) }
            pendingChunk.clear()
            throw e
        } catch (e: SecurityException) {
            pendingChunk.forEach { (_, bmp) -> releaseBitmap(bmp) }
            pendingChunk.clear()
            throw e
        } catch (e: Exception) {
            pendingChunk.forEach { (_, bmp) -> releaseBitmap(bmp) }
            pendingChunk.clear()
            if (emittedAnyChunk) {
                throw e
            }
            safeLogW("Media3 GPU HDR progressive thumbnail stream failed, falling back", e)
            false
        } finally {
            session?.closeQuietly()
        }
    }

    private class ConsumerCallbackException(override val cause: Throwable) : RuntimeException(cause)

    private suspend fun invokeChunkPropagatingConsumerErrors(
        chunk: List<Pair<Long, Bitmap>>,
        onChunk: suspend (List<Pair<Long, Bitmap>>) -> Unit
    ) {
        try {
            onChunk(chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw ConsumerCallbackException(t)
        }
    }

    @SuppressLint("NewApi") // Runtime-guarded by sdkInt >= UPSIDE_DOWN_CAKE (API 34)
    private fun createDefaultMedia3Session(config: Media3SessionConfig): Media3FrameSession {
        val mediaItem = MediaItem.Builder()
            .setUri(config.videoUri)
            .setMediaId(config.mediaId)
            .build()
        val builder = Media3FrameExtractor.Builder(context, mediaItem)
            .setSeekParameters(config.seekParameters)
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            applyExtractHdrFramesApi34(builder, config.extractHdrFrames)
        }
        if (config.targetWidth != null && config.targetHeight != null) {
            builder.setEffects(
                listOf<Effect>(
                    Presentation.createForWidthAndHeight(
                        config.targetWidth,
                        config.targetHeight,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            )
        }
        val extractor = builder.build()
        return object : Media3FrameSession {
            override suspend fun extractFrame(positionMs: Long): Bitmap? {
                return extractor.getFrame(positionMs).awaitMedia3FrameBitmap()
            }

            override fun close() {
                extractor.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyExtractHdrFramesApi34(
        builder: Media3FrameExtractor.Builder,
        extractHdrFrames: Boolean
    ) {
        builder.setExtractHdrFrames(extractHdrFrames)
    }

    private fun Media3FrameSession.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
            // Session is being discarded either way.
        }
    }

    private fun timestampUsToPositionMs(timestampUs: Long): Long {
        val clampedUs = timestampUs.coerceAtLeast(0L)
        return (clampedUs + 500L) / 1_000L
    }

    private fun toneMapThumbnailIfHdr(
        rawThumb: Bitmap,
        colorSpace: ColorSpaceInfo
    ): Bitmap {
        if (!colorSpace.isHdr) return rawThumb
        return try {
            val mapped = HdrToneMapper.process(
                bitmap = rawThumb,
                colorSpaceInfo = colorSpace,
                isAlreadyToneMapped = false
            )
            if (mapped !== rawThumb) {
                releaseBitmap(rawThumb)
            }
            mapped
        } catch (t: Throwable) {
            releaseBitmap(rawThumb)
            throw t
        }
    }

    private fun thumbnailDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        maxWidth: Int,
        maxHeight: Int = MAX_THUMBNAIL_DIMENSION
    ): Pair<Int, Int> {
        val scale = minOf(
            1.0,
            maxWidth.coerceAtMost(MAX_THUMBNAIL_DIMENSION).toDouble() / sourceWidth,
            maxHeight.toDouble() / sourceHeight
        )
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Helper to manage MediaMetadataRetriever lifecycle and error handling.
     */
    private suspend fun <T> useRetriever(
        videoUri: Uri,
        defaultValue: T,
        errorMessage: String,
        rethrowExceptions: Boolean = false,
        block: suspend (MediaMetadataRetriever) -> T
    ): T = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            block(retriever)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // Propagate permission failures so the UI can show a specific
            // message instead of a generic "capture failed".
            LogUtils.e(context, "FrameExtractor", "$errorMessage (permission denied)", e)
            throw e
        } catch (e: Exception) {
            LogUtils.e(context, "FrameExtractor", errorMessage, e)
            if (rethrowExceptions) throw e
            defaultValue
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                safeLogW("Failed to release retriever", e)
            }
        }
    }

    /**
     * Internal SDR frame extraction — always uses OPTION_CLOSEST for maximum precision.
     */
    private fun extractFrameInternal(
        retriever: MediaMetadataRetriever,
        timestampUs: Long
    ): Bitmap? {
        return retriever.getFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST
        )
    }

    /**
     * Internal HDR fallback frame extraction via [MediaMetadataRetriever].
     *
     * Always preserves frame-exact seeking (`OPTION_CLOSEST`). On API 30+ (and API 28–29 via
     * `BitmapParams` when supported), requests [Bitmap.Config.RGBA_F16] to retain >8-bit HDR
     * highlight precision before [HdrToneMapper] executes perceptual EETF roll-off, falling back
     * to standard `getFrameAtTime(timestampUs, OPTION_CLOSEST)` if `RGBA_F16` is unsupported.
     */
    @SuppressLint("NewApi") // Runtime-guarded by sdkInt checks
    private fun extractHdrFallbackFrameInternal(
        retriever: MediaMetadataRetriever,
        timestampUs: Long
    ): Bitmap? {
        if (sdkInt >= Build.VERSION_CODES.R) {
            val f16Bitmap = extractHdrFrameApi30(retriever, timestampUs)
            if (f16Bitmap != null) {
                return f16Bitmap
            }
        } else if (sdkInt >= Build.VERSION_CODES.P) {
            val f16Bitmap = extractHdrFrameApi28(retriever, timestampUs)
            if (f16Bitmap != null) {
                return f16Bitmap
            }
        }
        return extractFrameInternal(retriever, timestampUs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun extractHdrFrameApi30(
        retriever: MediaMetadataRetriever,
        timestampUs: Long
    ): Bitmap? {
        return try {
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.RGBA_F16
            }
            retriever.getFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                params
            )
        } catch (e: Exception) {
            safeLogW("API 30 RGBA_F16 getFrameAtTime unsupported, falling back to 8-bit OPTION_CLOSEST", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun extractHdrFrameApi28(
        retriever: MediaMetadataRetriever,
        timestampUs: Long
    ): Bitmap? {
        return try {
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toIntOrNull() ?: return null
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return null
            if (frameCount <= 0 || durationMs <= 0L) return null

            val durationUs = durationMs * 1_000L
            val clampedUs = timestampUs.coerceIn(0L, durationUs)
            val frameIndex = ((clampedUs.toDouble() / durationUs.toDouble()) * (frameCount - 1))
                .roundToInt()
                .coerceIn(0, frameCount - 1)
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.RGBA_F16
            }
            retriever.getFrameAtIndex(frameIndex, params)
        } catch (e: Exception) {
            safeLogW("API 28 RGBA_F16 getFrameAtIndex unsupported, falling back to OPTION_CLOSEST", e)
            null
        }
    }

    /**
     * Internal thumbnail extraction with API compatibility check.
     * Uses getScaledFrameAtTime on API 27+ (guarded by [sdkInt] runtime check),
     * falls back to manual scaling on older devices.
     */
    @SuppressLint("NewApi") // Runtime-guarded by sdkInt >= 27
    private fun extractThumbnailInternal(
        retriever: MediaMetadataRetriever,
        timestampUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return if (sdkInt >= 27) {
            retriever.getScaledFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight
            )
        } else {
            val fullBitmap = retriever.getFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            fullBitmap?.let {
                // The decoded frame can differ from metadata (for example, rotation).
                val (width, height) = thumbnailDimensions(it.width, it.height, targetWidth, targetHeight)
                var scaled: Bitmap? = null
                try {
                    scaled = it.scale(width, height, true)
                    scaled
                } finally {
                    if (scaled !== it) {
                        it.recycle()
                    }
                }
            }
        }
    }

    private fun safeLogW(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                LogUtils.w(context, "FrameExtractor", message, throwable)
            } else {
                LogUtils.w(context, "FrameExtractor", message)
            }
        } catch (_: RuntimeException) {
            // android.util.Log may be unmocked in local JVM unit tests.
        }
    }
}

private suspend fun ListenableFuture<Media3FrameExtractor.Frame>.awaitMedia3FrameBitmap(): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get()?.bitmap)
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: InterruptedException) {
                    continuation.resumeWithException(e)
                } catch (e: java.util.concurrent.CancellationException) {
                    continuation.cancel(e)
                }
            },
            Executor { it.run() }
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
