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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private companion object {
        const val MAX_THUMBNAIL_DIMENSION = 240
        // Fits the timeline's 150 thumbnails at 120 x 240, including portrait videos.
        const val MAX_THUMBNAIL_BATCH_PIXELS = 4_500_000L
        const val MAX_THUMBNAIL_COUNT = 150
    }

    @Volatile
    private var cachedMetadata: Pair<String, VideoMetadata>? = null

    @Volatile
    private var cachedColorSpace: Pair<String, ColorSpaceInfo>? = null

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
    ): Pair<Bitmap, CapturedFrame>? = useRetriever(
        videoUri,
        null,
        "Failed to extract frame with info"
    ) { retriever ->
        val bitmap = extractFrameInternal(retriever, timestampUs) ?: return@useRetriever null

        val cacheKey = videoUri.toString()

        // Check both caches first (fast path)
        var metadata = cachedMetadata?.takeIf { it.first == cacheKey }?.second
        var colorSpace = cachedColorSpace?.takeIf { it.first == cacheKey }?.second

        if (metadata == null || colorSpace == null) {
            // Need to fetch missing info.
            // We need MediaFormat for both metadata (enrichment) and color space.
            val mediaFormat = MetadataExtractor.getVideoTrackFormat(context, videoUri)

            if (metadata == null) {
                // Pass existing retriever and mediaFormat to avoid creating new ones
                metadata = synchronized(this) {
                    cachedMetadata?.takeIf { it.first == cacheKey }?.second ?: run {
                        val extracted = MetadataExtractor.extract(context, videoUri, retriever, mediaFormat)
                        cachedMetadata = cacheKey to extracted
                        extracted
                    }
                }
            }

            if (colorSpace == null) {
                colorSpace = synchronized(this) {
                    cachedColorSpace?.takeIf { it.first == cacheKey }?.second ?: run {
                        val detected = mediaFormat?.let { ColorSpaceDetector.detect(it) } ?: ColorSpaceInfo()
                        cachedColorSpace = cacheKey to detected
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

            timestampsUs.asSequence().take(maxCount).mapNotNull { ts ->
                val bitmap = extractThumbnailInternal(retriever, ts, width, height)
                if (bitmap != null) ts to bitmap else null
            }.toList()
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
        block: (MediaMetadataRetriever) -> T
    ): T = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            block(retriever)
        } catch (e: SecurityException) {
            // Propagate permission failures so the UI can show a specific
            // message instead of a generic "capture failed".
            LogUtils.e(context, "FrameExtractor", "$errorMessage (permission denied)", e)
            throw e
        } catch (e: Exception) {
            LogUtils.e(context, "FrameExtractor", errorMessage, e)
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
