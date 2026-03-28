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
     * Extract multiple frames for motion photo (frames around the target timestamp).
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param centerTimestampUs Center timestamp in microseconds
     * @param beforeDurationUs Duration before center in microseconds
     * @param afterDurationUs Duration after center in microseconds
     * @param frameIntervalUs Interval between frames in microseconds (default: 33333 = ~30fps)
     * @return List of (timestamp, bitmap) pairs
     */
    suspend fun extractFrameRange(
        videoUri: Uri,
        centerTimestampUs: Long,
        beforeDurationUs: Long,
        afterDurationUs: Long,
        frameIntervalUs: Long = 33_333L // ~30fps
    ): List<Pair<Long, Bitmap>> {
        require(frameIntervalUs > 0) { "frameIntervalUs must be positive, was $frameIntervalUs" }
        return withContext(Dispatchers.IO) {
        val frames = mutableListOf<Pair<Long, Bitmap>>()
        useRetriever(
            videoUri,
            Unit,
            "Failed to extract frame range"
        ) { retriever ->
            val startUs = maxOf(0L, centerTimestampUs - beforeDurationUs)
            val endUs = centerTimestampUs + afterDurationUs

            var currentUs = startUs
            while (currentUs <= endUs) {
                val bitmap = extractFrameInternal(retriever, currentUs)
                if (bitmap != null) {
                    frames.add(Pair(currentUs, bitmap))
                }
                currentUs += frameIntervalUs
            }
        }
        frames
    }
    }

    /**
     * Extract a downscaled thumbnail frame for timeline preview.
     * Uses OPTION_CLOSEST_SYNC since precision is not critical for thumbnails.
     *
     * Caller is responsible for calling [releaseBitmap] when done.
     *
     * @param videoUri URI of the source video
     * @param timestampUs Timestamp in microseconds
     * @param targetWidth Desired thumbnail width in pixels
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

        if (videoWidth <= 0 || videoHeight <= 0) return@useRetriever null

        val targetHeight = (targetWidth * videoHeight.toFloat() / videoWidth).toInt()
            .coerceAtLeast(1)

        extractThumbnailInternal(retriever, timestampUs, targetWidth, targetHeight)
    }

    /**
     * Extract multiple downscaled thumbnails efficiently by reusing a single retriever.
     *
     * @param videoUri URI of the source video
     * @param timestampsUs List of timestamps in microseconds
     * @param targetWidth Desired thumbnail width in pixels (must be > 0)
     * @return List of (timestamp, bitmap) pairs
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

        val targetHeight = (targetWidth * videoHeight.toFloat() / videoWidth).toInt()
            .coerceAtLeast(1)

        timestampsUs.mapNotNull { ts ->
            val bitmap = extractThumbnailInternal(retriever, ts, targetWidth, targetHeight)
            if (bitmap != null) ts to bitmap else null
        }
    }
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
                val scaled = it.scale(targetWidth, targetHeight, true)
                if (scaled != it) {
                    it.recycle()
                }
                scaled
            }
        }
    }
}
