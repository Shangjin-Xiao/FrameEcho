package com.shangjin.frameecho.core.media.extraction

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.shangjin.frameecho.core.media.colorspace.ColorSpaceDetector
import androidx.core.graphics.scale
import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * High-performance video frame extraction engine.
 *
 * Uses MediaMetadataRetriever for precise frame extraction with:
 * - Exact frame seeking (always OPTION_CLOSEST — precision is non-negotiable)
 * - HDR frame capture support
 * - Proper color space handling
 * - Original resolution preservation
 */
class FrameExtractor(
    private val context: Context,
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT
) {
    companion object {
        const val DEFAULT_THUMBNAIL_CHUNK_SIZE = 8
        private const val MAX_THUMBNAIL_DIMENSION = 240
        // Fits the timeline's 150 thumbnails at 120 x 240, including portrait videos.
        private const val MAX_THUMBNAIL_BATCH_PIXELS = 4_500_000L
        private const val MAX_THUMBNAIL_COUNT = 150
    }

    private data class CacheKey(val uri: String, val generation: Long)

    private val cacheGeneration = AtomicLong(0L)

    @Volatile
    private var cachedMetadata: Pair<CacheKey, VideoMetadata>? = null

    @Volatile
    private var cachedColorSpace: Pair<CacheKey, ColorSpaceInfo>? = null

    /**
     * Invalidates cached video metadata and color space information and advances the cache
     * generation so any in-flight extraction from a previous session cannot repopulate the cache.
     */
    fun invalidateCache() {
        synchronized(this) {
            cacheGeneration.incrementAndGet()
            cachedMetadata = null
            cachedColorSpace = null
        }
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
     * Extract a single frame at the specified timestamp.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @return Bitmap of the extracted frame, or null on failure
     */
    suspend fun extractFrame(
        videoUri: Uri,
        timestampUs: Long
    ): Bitmap? = useRetriever(
        videoUri,
        null,
        "Failed to extract frame at $timestampUs"
    ) { retriever ->
        extractFrameInternal(retriever, timestampUs)
    }

    /**
     * Extract a frame with full metadata and color space information.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @return CapturedFrame with bitmap data, metadata, and color space info
     */
    suspend fun extractFrameWithInfo(
        videoUri: Uri,
        timestampUs: Long
    ): Pair<Bitmap, CapturedFrame>? {
        val startGeneration = cacheGeneration.get()
        val cacheKey = CacheKey(videoUri.toString(), startGeneration)
        return useRetriever(
            videoUri,
            null,
            "Failed to extract frame with info"
        ) { retriever ->
            val bitmap = extractFrameInternal(retriever, timestampUs) ?: return@useRetriever null

            // Check both caches first (fast path)
            var metadata = cachedMetadata?.takeIf { it.first == cacheKey }?.second
            var colorSpace = cachedColorSpace?.takeIf { it.first == cacheKey }?.second

            if (metadata == null || colorSpace == null) {
                // Need to fetch missing info.
                // We need MediaFormat for both metadata (enrichment) and color space.
                val mediaFormat = MetadataExtractor.getVideoTrackFormat(context, videoUri)

                if (metadata == null) {
                    // Extract outside synchronized(this) so main-thread invalidateCache() never blocks on I/O
                    val extracted = MetadataExtractor.extract(context, videoUri, retriever, mediaFormat)
                    metadata = synchronized(this) {
                        cachedMetadata?.takeIf { it.first == cacheKey }?.second ?: run {
                            if (cacheGeneration.get() == startGeneration) {
                                cachedMetadata = cacheKey to extracted
                            }
                            extracted
                        }
                    }
                }

                if (colorSpace == null) {
                    val detected = mediaFormat?.let { ColorSpaceDetector.detect(it) } ?: ColorSpaceInfo()
                    colorSpace = synchronized(this) {
                        cachedColorSpace?.takeIf { it.first == cacheKey }?.second ?: run {
                            if (cacheGeneration.get() == startGeneration) {
                                cachedColorSpace = cacheKey to detected
                            }
                            detected
                        }
                    }
                }
            }

            val frame = CapturedFrame(
                timestampUs = timestampUs,
                width = bitmap.width,
                height = bitmap.height,
                colorSpace = colorSpace!!,
                metadata = metadata!!
            )

            Pair(bitmap, frame)
        }
    }

    /**
     * Extract a downscaled thumbnail frame for timeline preview.
     * Uses OPTION_CLOSEST_SYNC since precision is not critical for thumbnails.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * Thumbnails fit within 240 x 240 pixels without upscaling.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @param targetWidth Maximum thumbnail width in pixels
     * @return Downscaled bitmap suitable for LazyRow display, or null on failure
     */
    suspend fun extractThumbnail(
        videoUri: Uri,
        timestampUs: Long,
        targetWidth: Int
    ): Bitmap? = useRetriever(
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

        if (videoWidth <= 0 || videoHeight <= 0 || targetWidth <= 0) return@useRetriever null

        val (width, height) = thumbnailDimensions(videoWidth, videoHeight, targetWidth)

        extractThumbnailInternal(retriever, timestampUs, width, height)
    }

    /**
     * Extract multiple downscaled thumbnails efficiently by reusing a single retriever.
     *
     * Thumbnails fit within 240 x 240 pixels without upscaling. Each batch is
     * limited to 150 frames and 4.5 million retained pixels.
     *
     * @param videoUri URI of the source video
     * @param timestampsUs List of timestamps in microseconds
     * @param targetWidth Maximum thumbnail width in pixels (must be > 0)
     * @return List of (timestamp, bitmap) pairs, possibly truncated to fit the budget
     */
    suspend fun extractThumbnails(
        videoUri: Uri,
        timestampsUs: List<Long>,
        targetWidth: Int
    ): List<Pair<Long, Bitmap>> {
        require(targetWidth > 0) { "targetWidth must be positive, was $targetWidth" }
        currentCoroutineContext().ensureActive()
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
                    val bitmap = extractThumbnailInternal(retriever, ts, width, height)
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
     * [MediaMetadataRetriever] session.
     *
     * Thumbnails fit within 240 x 240 pixels without upscaling. Total decoded frames per session
     * remain bounded by 150 frames and 4.5 million retained pixels. Returns immediately without
     * opening a decoder session when [timestampsUs] is empty.
     *
     * Checks coroutine cancellation before and after each frame decode and recycles any bitmaps in
     * an uncommitted chunk if cancelled or failing before [onChunk] is invoked. Once [onChunk] is
     * invoked for a batch, ownership of all [Bitmap] instances in that chunk transfers to the
     * callback: if [onChunk] suspends and is cancelled or throws before publishing/retaining them,
     * [onChunk] is responsible for recycling those bitmaps via [releaseBitmap]. Any exception
     * thrown while opening the video, decoding frames, or executing [onChunk] is propagated to the
     * caller after releasing the underlying [MediaMetadataRetriever].
     *
     * @param videoUri URI of the source video
     * @param timestampsUs Ordered list of timestamps in microseconds
     * @param targetWidth Maximum thumbnail width in pixels (must be > 0)
     * @param chunkSize Number of decoded frames per emitted batch (must be > 0, defaults to 8)
     * @param onChunk Suspend callback invoked as soon as each chunk (or final remainder) is ready;
     *   takes ownership of the emitted [Bitmap] instances upon invocation
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
                    val bitmap = extractThumbnailInternal(retriever, ts, width, height)
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
                LogUtils.w(context, "FrameExtractor", "Failed to release retriever", e)
            }
        }
    }

    /**
     * Internal frame extraction — always uses OPTION_CLOSEST for maximum precision.
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
}
