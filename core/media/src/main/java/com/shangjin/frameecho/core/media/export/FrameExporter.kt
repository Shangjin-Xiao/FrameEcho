package com.shangjin.frameecho.core.media.export

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.shangjin.frameecho.core.media.utils.getIntegerOrNullSafe
import com.shangjin.frameecho.core.media.utils.getIntegerSafe
import com.shangjin.frameecho.core.media.utils.getStringOrNullSafe
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import androidx.core.graphics.scale
import com.shangjin.frameecho.core.media.colorspace.HdrToneMapper
import com.shangjin.frameecho.core.media.metadata.MetadataWriter
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.media.utils.DateTimeUtils
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Exports captured frames as images with full metadata and color space handling.
 *
 * Supports:
 * - Static image export (JPEG, PNG, WebP)
 * - Motion photo export (Google Motion Photo format)
 * - HDR tone mapping for SDR formats
 * - Full EXIF metadata preservation
 * - MediaStore integration for gallery visibility
 */
class FrameExporter internal constructor(
    private val context: Context,
    private val sdkInt: Int
) {
    constructor(context: Context) : this(context, Build.VERSION.SDK_INT)

    private data class StaticSaveOutcome(
        val uri: Uri,
        val fileSizeBytes: Long,
        val metadataPreserved: Boolean
    )

    companion object {
        private const val XMP_NAMESPACE_URI = "http://ns.adobe.com/xap/1.0/\u0000"
        private const val DEFAULT_CUSTOM_FILENAME = "FrameEcho"

        // AAC transcoding for audio codecs MediaMuxer cannot embed in MP4
        // (LPCM from Sony/Canon cameras, AC-3/E-AC-3, MP3, Opus, FLAC, ...).
        private const val AAC_BIT_RATE_PER_CHANNEL = 96_000
        internal const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
        /** Max consecutive rounds with no codec progress before giving up (~6 s). */
        internal const val MAX_CODEC_IDLE_ROUNDS = 600
        /** Bounded frame-aligned carry buffer (16 KB) bridging audio sources to AAC encoder. */
        internal const val PCM_CARRY_BUFFER_BYTES = 16 * 1024
        /**
         * Fallback direct read buffer (256 KB) for raw LPCM when neither per-sample size
         * (Android 8 / API 26-27) nor track KEY_MAX_INPUT_SIZE is available.
         */
        internal const val FALLBACK_LPCM_READ_BUFFER_BYTES = 256 * 1024
        internal const val MAX_PCM_BUFFER_BYTES = 15 * 1024 * 1024
        internal const val DEFAULT_SAMPLE_BUFFER_BYTES = 1024 * 1024
        internal const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024

        // Motion photo remux bounds to prevent sparse-keyframe lookback or
        // sample/chunk amplification from writing unbounded data (CWE-770).
        internal const val MAX_KEYFRAME_LOOKBACK_US = 10_000_000L
        internal const val MAX_AUDIO_SEEK_LOOKBACK_US = 2_000_000L
        internal const val MAX_MUXED_CLIP_BYTES = 128L * 1024 * 1024
        internal const val MAX_MUXED_SAMPLES = 6_000
        internal const val MAX_AUDIO_SOURCE_SAMPLES = 6_000

        private val CSD_KEYS = arrayOf(
            "csd-0",
            "csd-1",
            "csd-2",
            MediaFormat.KEY_HDR_STATIC_INFO
        )
        private val OPTIONAL_INT_KEYS = arrayOf(
            MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_MAX_INPUT_SIZE,
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_TRANSFER,
            MediaFormat.KEY_COLOR_RANGE
        )
        private val VALID_ROTATIONS = intArrayOf(0, 90, 180, 270)
    }

    internal var extractorFactory: () -> MediaExtractor = { MediaExtractor() }
    internal var muxerFactory: (String) -> MediaMuxer = { path ->
        MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }
    internal var bufferInfoFactory: () -> MediaCodec.BufferInfo = { MediaCodec.BufferInfo() }
    internal var decoderFactory: (mime: String, format: MediaFormat) -> AudioCodecAdapter? =
        ::createAudioDecoderAdapter
    internal var aacEncoderFactory: (sampleRate: Int, channelCount: Int) -> AudioCodecAdapter? =
        ::createAacEncoderAdapter

    /**
     * Ensure bitmap is in a software-accessible config (not HARDWARE).
     * Hardware bitmaps cannot be compressed or have their pixels read directly.
     */
    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Failed to convert hardware bitmap to software bitmap")
        }
        return bitmap
    }

    /**
     * Export a single static frame.
     */
    suspend fun exportStaticFrame(
        bitmap: Bitmap,
        frame: CapturedFrame,
        config: ExportConfig,
        customExportTreeUri: Uri? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        var softBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var outputUri: Uri? = null
        val coroutineContext = currentCoroutineContext()
        val checkCancellation: () -> Unit = { coroutineContext.ensureActive() }
        try {
            checkCancellation()
            // Ensure we have a software bitmap (HARDWARE bitmaps can't be compressed)
            softBitmap = ensureSoftwareBitmap(bitmap)

            checkCancellation()
            // Apply HDR tone mapping if needed (passes straight through when already GPU tone-mapped)
            processedBitmap = HdrToneMapper.process(
                bitmap = softBitmap,
                colorSpaceInfo = frame.colorSpace,
                isAlreadyToneMapped = frame.isToneMapped
            )

            checkCancellation()
            // Scale if needed
            finalBitmap = scaleBitmap(processedBitmap, config.maxResolution)

            val resultWidth = finalBitmap.width
            val resultHeight = finalBitmap.height

            // Generate output file
            val fileName = generateFileName(frame, config)

            checkCancellation()
            // Prebuild standard EXIF APP1 segment from a 28-byte minimal JPEG stub before opening
            // the destination stream so JPEG static frames are written once with zero rewrite IPC.
            val prebuiltApp1Segment = if (config.preserveMetadata && config.format == ExportFormat.JPEG) {
                buildJpegExifApp1Segment(frame.metadata, resultWidth, resultHeight)
            } else {
                null
            }

            checkCancellation()
            // Save to MediaStore or custom directory (scoped storage compatible)
            val dateTakenMs = frame.metadata.dateTime?.let { DateTimeUtils.parseToMillis(it) }
            val saveOutcome = if (customExportTreeUri != null) {
                saveStaticToCustomDirectory(
                    bitmap = finalBitmap,
                    fileName = fileName,
                    config = config,
                    treeUri = customExportTreeUri,
                    metadata = frame.metadata,
                    prebuiltApp1Segment = prebuiltApp1Segment,
                    checkCancellation = checkCancellation
                )
            } else {
                saveStaticToMediaStore(
                    bitmap = finalBitmap,
                    fileName = fileName,
                    config = config,
                    dateTakenMs = dateTakenMs,
                    metadata = frame.metadata,
                    prebuiltApp1Segment = prebuiltApp1Segment,
                    checkCancellation = checkCancellation
                )
            }
            outputUri = saveOutcome.uri

            ExportResult.Success(
                outputPath = saveOutcome.uri.toString(),
                width = resultWidth,
                height = resultHeight,
                fileSizeBytes = saveOutcome.fileSizeBytes,
                format = config.format,
                isMotionPhoto = false,
                metadataPreserved = saveOutcome.metadataPreserved
            )
        } catch (e: CancellationException) {
            outputUri?.let { cleanupFailedOutput(it) }
            throw e
        } catch (e: OutOfMemoryError) {
            ExportResult.Error("Image too large to process. Try reducing resolution or using JPEG format.", e)
        } catch (e: SecurityException) {
            ExportResult.Error("Storage permission denied. Please grant storage access.", e)
        } catch (e: Exception) {
            ExportResult.Error("Export failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (finalBitmap !== processedBitmap && finalBitmap !== bitmap) {
                finalBitmap?.recycle()
            }
            if (processedBitmap !== bitmap && processedBitmap !== softBitmap) {
                processedBitmap?.recycle()
            }
            if (softBitmap !== bitmap) {
                softBitmap?.recycle()
            }
        }
    }

    /**
     * Export a motion photo (static image + embedded video clip).
     *
     * Follows the Google Motion Photo specification:
     * - Primary JPEG image with XMP metadata containing GCamera namespace
     * - Embedded MP4 video clip appended directly after the JPEG EOF marker
     * - XMP namespace: http://ns.google.com/photos/1.0/camera/
     */
    suspend fun exportMotionPhoto(
        videoUri: Uri,
        bitmap: Bitmap,
        frame: CapturedFrame,
        config: ExportConfig,
        customExportTreeUri: Uri? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        var softBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var outputUri: Uri? = null
        try {
            val effectiveConfig = config
            softBitmap = ensureSoftwareBitmap(bitmap)

            processedBitmap = HdrToneMapper.process(
                bitmap = softBitmap,
                colorSpaceInfo = frame.colorSpace,
                isAlreadyToneMapped = frame.isToneMapped
            )

            finalBitmap = scaleBitmap(processedBitmap, config.maxResolution)

            val resultWidth = finalBitmap.width
            val resultHeight = finalBitmap.height

            val fileName = generateFileName(frame, effectiveConfig, isMotion = true)

            // Motion photo container requires JPEG primary image
            val jpegBytes = compressBitmapToBytes(finalBitmap, Bitmap.CompressFormat.JPEG, config.quality)

            // Step 1: Write EXIF metadata to JPEG bytes FIRST (before XMP injection).
            // ExifInterface.saveAttributes() rewrites the entire JPEG — doing this on
            // a plain JPEG avoids any risk of corrupting the XMP we inject later.
            val (exifJpegBytes, metadataPreserved) = if (config.preserveMetadata) {
                writeExifToJpegBytes(jpegBytes, frame.metadata)
            } else {
                Pair(jpegBytes, false)
            }

            val beforeDurationUs = (config.motionDurationBeforeS * 1_000_000).toLong()
            val afterDurationUs = (config.motionDurationAfterS * 1_000_000).toLong()

            // Step 2: Extract the video clip around the selected frame into a temp MP4.
            // The clip always starts from the nearest keyframe to ensure valid playback.
            val clipResult = extractVideoClip(
                videoUri = videoUri,
                centerTimestampUs = frame.timestampUs,
                beforeDurationUs = beforeDurationUs,
                afterDurationUs = afterDurationUs,
                muteAudio = config.muteAudio,
                videoDurationUs = frame.metadata.durationMs * 1000L,
                rotation = frame.metadata.rotation
            )
            val videoClipFile = clipResult.file

            try {
                // Validate video clip
                if (!videoClipFile.exists() ||
                    videoClipFile.length() == 0L ||
                    clipResult.videoSamplesWritten == 0
                ) {
                    return@withContext ExportResult.Error(
                        "Failed to extract video clip. The video format may not be supported for motion photo export."
                    )
                }

                // Compute presentation timestamp = offset of the captured frame
                // within the clip, measured from the actual keyframe start.
                // Reject clips starting after the captured moment rather than
                // clamping a negative offset to zero (which would misalign still and video).
                val capturedTimestampUs = maxOf(0L, frame.timestampUs)
                if (clipResult.actualStartUs > capturedTimestampUs) {
                    return@withContext ExportResult.Error(
                        "Failed to extract video clip containing the captured frame."
                    )
                }
                val presentationTimestampUs = capturedTimestampUs - clipResult.actualStartUs

                // Step 3: Inject XMP metadata into the EXIF-enriched JPEG.
                // This is done AFTER ExifInterface so the XMP is never disturbed.
                val xmpJpegBytes = injectMotionPhotoXmp(
                    jpegBytes = exifJpegBytes,
                    videoLength = videoClipFile.length(),
                    presentationTimestampUs = presentationTimestampUs
                )

                // Step 4: Write combined JPEG+video to storage
                val dateTakenMs = frame.metadata.dateTime?.let { DateTimeUtils.parseToMillis(it) }
                val savedUri = if (customExportTreeUri != null) {
                    saveMotionPhotoToCustomDirectory(xmpJpegBytes, videoClipFile, fileName, customExportTreeUri)
                } else {
                    saveMotionPhotoToMediaStore(xmpJpegBytes, videoClipFile, fileName, effectiveConfig, dateTakenMs)
                }
                outputUri = savedUri

                ExportResult.Success(
                    outputPath = savedUri.toString(),
                    width = resultWidth,
                    height = resultHeight,
                    fileSizeBytes = getFileSize(savedUri),
                    format = effectiveConfig.format,
                    isMotionPhoto = true,
                    metadataPreserved = metadataPreserved,
                    requestedFormat = null,
                    audioDropped = !config.muteAudio && clipResult.hasAudioTrack && !clipResult.audioIncluded
                )
            } finally {
                videoClipFile.delete()
            }
        } catch (e: CancellationException) {
            outputUri?.let { cleanupFailedOutput(it) }
            throw e
        } catch (e: OutOfMemoryError) {
            ExportResult.Error("Image too large to process. Try reducing resolution or using JPEG format.", e)
        } catch (e: SecurityException) {
            ExportResult.Error("Storage permission denied. Please grant storage access.", e)
        } catch (e: Exception) {
            ExportResult.Error("Motion photo export failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (finalBitmap !== processedBitmap && finalBitmap !== bitmap) {
                finalBitmap?.recycle()
            }
            if (processedBitmap !== bitmap && processedBitmap !== softBitmap) {
                processedBitmap?.recycle()
            }
            if (softBitmap !== bitmap) {
                softBitmap?.recycle()
            }
        }
    }

    /**
     * Compress a bitmap to a JPEG/PNG/WebP byte array.
     */
    private fun compressBitmapToBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): ByteArray {
        // Pre-allocate based on estimated compressed size to avoid repeated array copies.
        // JPEG ~1 byte/pixel at high quality; PNG ~2 bytes/pixel; fallback to ~1 byte/pixel.
        // Use Long arithmetic to avoid Int overflow on very large bitmaps.
        val estimatedSize = when (format) {
            Bitmap.CompressFormat.PNG -> (bitmap.width.toLong() * bitmap.height * 2).coerceIn(8192L, Int.MAX_VALUE.toLong()).toInt()
            else -> (bitmap.width.toLong() * bitmap.height).coerceIn(8192L, Int.MAX_VALUE.toLong()).toInt()
        }
        val baos = ByteArrayOutputStream(estimatedSize.coerceAtLeast(8192))
        if (!bitmap.compress(format, quality, baos)) {
            throw java.io.IOException("Failed to compress bitmap to byte array")
        }
        return baos.toByteArray()
    }

    /**
     * Result of video clip extraction.
     *
     * @param file The MP4 file containing the trimmed clip
     * @param actualStartUs The actual start timestamp of the clip (at a keyframe).
     *        This may be earlier than the requested start to ensure the clip
     *        begins with a sync sample, which is required for valid MP4 playback.
     * @param audioIncluded Whether any audio samples were written into the clip.
     */
    internal data class VideoClipResult(
        val file: java.io.File,
        val actualStartUs: Long,
        val videoSamplesWritten: Int,
        val audioIncluded: Boolean,
        val hasAudioTrack: Boolean = false
    )

    /** One encoded AAC packet ready for [MediaMuxer.writeSampleData]. */
    internal class EncodedAudioPacket(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /** AAC-encoded audio held in memory until the muxer has started. */
    internal class EncodedAudio(
        val format: MediaFormat,
        val packets: List<EncodedAudioPacket>
    )

    /**
     * Extract a video clip around [centerTimestampUs] using MediaExtractor + MediaMuxer.
     *
     * The clip always starts at the nearest keyframe before the requested start time
     * to ensure the resulting MP4 is playable. Skipping the keyframe and starting at
     * a P/B-frame produces an unplayable file.
     */
    internal suspend fun extractVideoClip(
        videoUri: Uri,
        centerTimestampUs: Long,
        beforeDurationUs: Long,
        afterDurationUs: Long,
        muteAudio: Boolean = false,
        videoDurationUs: Long = -1L,
        rotation: Int = 0
    ): VideoClipResult {
        // Defense-in-depth: Clamp durations to prevent unbounded extraction
        val maxDurationUs = (ExportConfig.MAX_MOTION_DURATION_S * 1_000_000).toLong()
        val safeBeforeDurationUs = beforeDurationUs.coerceIn(0L, maxDurationUs)
        val safeAfterDurationUs = afterDurationUs.coerceIn(0L, maxDurationUs)

        // Reuse caller-provided duration when available to avoid creating
        // an extra MediaMetadataRetriever just for the duration query.
        val durationUs = if (videoDurationUs > 0L) videoDurationUs else getVideoDurationUs(videoUri)
        val clampedCenterUs = if (durationUs > 0L) {
            centerTimestampUs.coerceIn(0L, (durationUs - 1L).coerceAtLeast(0L))
        } else {
            maxOf(0L, centerTimestampUs)
        }
        val startUs = maxOf(0L, clampedCenterUs - safeBeforeDurationUs)
        val endUs = if (durationUs > 0L) {
            minOf(durationUs, clampedCenterUs + safeAfterDurationUs)
        } else {
            clampedCenterUs + safeAfterDurationUs
        }
        val safeEndUs = maxOf(startUs + 1L, endUs)
        val maxStartUsInclusive = maxOf(startUs, clampedCenterUs)

        var firstBudgetException: RemuxBudgetExceededException? = null
        val previousSyncResult = try {
            extractVideoClipOnceInternal(
                videoUri = videoUri,
                startUs = startUs,
                endUs = safeEndUs,
                muteAudio = muteAudio,
                seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                rotation = rotation,
                seekTargetUs = startUs,
                maxStartUsInclusive = maxStartUsInclusive
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemuxBudgetExceededException) {
            firstBudgetException = e
            LogUtils.w(
                context,
                "FrameExporter",
                "PREVIOUS_SYNC exceeded remux budget, will retry later keyframe if available",
                e
            )
            null
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "PREVIOUS_SYNC extraction failed, will retry", e)
            null
        }

        if (previousSyncResult != null && previousSyncResult.videoSamplesWritten > 0) {
            return previousSyncResult
        }
        previousSyncResult?.file?.delete()

        if (firstBudgetException == null) {
            val closestSyncResult = try {
                extractVideoClipOnceInternal(
                    videoUri = videoUri,
                    startUs = startUs,
                    endUs = safeEndUs,
                    muteAudio = muteAudio,
                    seekMode = MediaExtractor.SEEK_TO_CLOSEST_SYNC,
                    rotation = rotation,
                    seekTargetUs = startUs,
                    maxStartUsInclusive = maxStartUsInclusive
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemuxBudgetExceededException) {
                firstBudgetException = e
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "CLOSEST_SYNC exceeded remux budget, will retry later keyframe if available",
                    e
                )
                null
            }

            if (closestSyncResult != null) {
                if (closestSyncResult.videoSamplesWritten > 0) {
                    return closestSyncResult
                }
                closestSyncResult.file.delete()
                return closestSyncResult
            }
        }

        var latestBudgetException: RemuxBudgetExceededException = firstBudgetException!!
        var latestFailedStartUs = latestBudgetException.clipStartUs
        if (latestFailedStartUs >= maxStartUsInclusive) {
            throw latestBudgetException
        }

        val nextSyncResult = try {
            extractVideoClipOnceInternal(
                videoUri = videoUri,
                startUs = startUs,
                endUs = safeEndUs,
                muteAudio = muteAudio,
                seekMode = MediaExtractor.SEEK_TO_NEXT_SYNC,
                rotation = rotation,
                seekTargetUs = latestFailedStartUs + 1L,
                maxStartUsInclusive = maxStartUsInclusive,
                minStartUsExclusive = latestFailedStartUs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemuxBudgetExceededException) {
            latestBudgetException = e
            latestFailedStartUs = maxOf(latestFailedStartUs, e.clipStartUs)
            if (latestFailedStartUs >= maxStartUsInclusive) {
                throw e
            }
            LogUtils.w(
                context,
                "FrameExporter",
                "NEXT_SYNC exceeded remux budget, retrying last sync before captured frame",
                e
            )
            null
        } catch (e: Exception) {
            LogUtils.w(
                context,
                "FrameExporter",
                "NEXT_SYNC retry failed, retrying last sync before captured frame",
                e
            )
            null
        }

        if (nextSyncResult != null && nextSyncResult.videoSamplesWritten > 0) {
            return nextSyncResult
        }
        nextSyncResult?.file?.delete()

        return try {
            extractVideoClipOnceInternal(
                videoUri = videoUri,
                startUs = startUs,
                endUs = safeEndUs,
                muteAudio = muteAudio,
                seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                rotation = rotation,
                seekTargetUs = maxStartUsInclusive,
                maxStartUsInclusive = maxStartUsInclusive,
                minStartUsExclusive = latestFailedStartUs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemuxBudgetExceededException) {
            throw e
        } catch (e: Exception) {
            latestBudgetException.addSuppressed(e)
            throw latestBudgetException
        }
    }

    /**
     * Build a minimal MediaFormat containing only the keys that MediaMuxer requires.
     *
     * Some cameras (Sony, DJI, etc.) embed vendor-specific keys in the track format
     * that cause MediaMuxer.addTrack() to fail at the native layer. Stripping those
     * keys and keeping only mime, dimensions, and codec-specific data (CSD) resolves
     * the issue while preserving playback compatibility.
     */
    internal fun createCleanVideoFormat(original: MediaFormat): MediaFormat {
        val mime = original.getString(MediaFormat.KEY_MIME)
            ?: throw java.io.IOException("Video track has no MIME type")
        val width = try { original.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to get KEY_WIDTH, falling back to 1920", e)
            1920
        }
        val height = try { original.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to get KEY_HEIGHT, falling back to 1080", e)
            1080
        }

        val clean = MediaFormat.createVideoFormat(mime, width, height)

        // Codec-specific data (SPS/PPS for H.264, VPS/SPS/PPS for H.265) — essential for playback.
        // IMPORTANT: rewind() the ByteBuffer — MediaExtractor may return buffers with
        // position already at the limit, causing MediaMuxer to see zero-length CSD.
        for (csdKey in CSD_KEYS) {
            try {
                if (sdkInt >= Build.VERSION_CODES.Q && !original.containsKey(csdKey)) {
                    continue
                }
                val csd = try {
                    original.getByteBuffer(csdKey)
                } catch (e: Exception) {
                    null
                }
                if (csd != null) {
                    csd.rewind()
                    clean.setByteBuffer(csdKey, csd)
                }
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to copy CSD key: $csdKey", e)
            }
        }

        // Optional but helpful keys — copy only if present
        for (key in OPTIONAL_INT_KEYS) {
            try {
                val intVal = try { if (sdkInt >= Build.VERSION_CODES.Q && !original.containsKey(key)) null else original.getInteger(key) } catch (_: Exception) { null }
                if (intVal != null) {
                    if (key == MediaFormat.KEY_MAX_INPUT_SIZE) {
                        if (intVal > 0) {
                            clean.setInteger(key, intVal.coerceAtMost(MAX_SAMPLE_BUFFER_BYTES))
                        }
                    } else {
                        clean.setInteger(key, intVal)
                    }
                }
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to copy optional int key: $key", e)
            }
        }

        // Duration
        try {
            val duration = try { if (sdkInt >= Build.VERSION_CODES.Q && !original.containsKey(MediaFormat.KEY_DURATION)) null else original.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { null }
            if (duration != null) {
                clean.setLong(MediaFormat.KEY_DURATION, duration)
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to copy KEY_DURATION", e)
        }

        return clean
    }

    internal class RemuxBudgetExceededException(
        val clipStartUs: Long,
        message: String,
        cause: Throwable? = null
    ) : java.io.IOException(message, cause)

    /**
     * Ensure [MediaFormat.KEY_MAX_INPUT_SIZE] on [format] is positive and capped at
     * [MAX_SAMPLE_BUFFER_BYTES] before passing the format to [MediaMuxer] or [MediaCodec].
     */
    @SuppressLint("NewApi")
    internal fun sanitizeTrackMaxInputSize(format: MediaFormat): MediaFormat {
        try {
            val maxInputSize = format.getIntegerOrNullSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
            if (maxInputSize != null) {
                if (maxInputSize > MAX_SAMPLE_BUFFER_BYTES) {
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_BUFFER_BYTES)
                } else if (maxInputSize <= 0) {
                    if (sdkInt >= Build.VERSION_CODES.Q) {
                        format.removeKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_SAMPLE_BUFFER_BYTES)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to sanitize KEY_MAX_INPUT_SIZE", e)
        }
        return format
    }

    private fun allocateGrownDirectBuffer(
        previousCapacity: Int,
        minCapacity: Int,
        maxCapacity: Int
    ): ByteBuffer {
        val grown = (previousCapacity.toLong() * 4L).coerceAtMost(maxCapacity.toLong()).toInt()
        val targetCapacity = maxOf(minCapacity, grown).coerceAtMost(maxCapacity)
        return ByteBuffer.allocateDirect(targetCapacity)
    }

    private fun resolveAndValidateClipStartUs(
        seekedPosition: Long,
        startUs: Long,
        endUs: Long,
        maxStartUsInclusive: Long = endUs,
        minStartUsExclusive: Long? = null
    ): Long {
        if (startUs < 0L || endUs < startUs || maxStartUsInclusive < startUs) {
            throw java.io.IOException(
                "Invalid motion clip range [start=$startUs, maxStart=$maxStartUsInclusive, end=$endUs]"
            )
        }
        if (seekedPosition < 0L) {
            throw java.io.IOException(
                "No video sync sample available for motion clip (seeked=$seekedPosition, start=$startUs, end=$endUs)"
            )
        }
        val actualStartUs = seekedPosition
        val maxDurationUs = (ExportConfig.MAX_MOTION_DURATION_S * 1_000_000).toLong()
        val maxClipSpanUs = maxDurationUs * 2 + MAX_KEYFRAME_LOOKBACK_US
        if (actualStartUs > endUs ||
            actualStartUs > maxStartUsInclusive ||
            startUs - actualStartUs > MAX_KEYFRAME_LOOKBACK_US ||
            endUs - actualStartUs > maxClipSpanUs
        ) {
            throw java.io.IOException(
                "Keyframe seek span out of bounds " +
                    "(seeked=$seekedPosition, start=$startUs, maxStart=$maxStartUsInclusive, end=$endUs)"
            )
        }
        if (minStartUsExclusive != null && actualStartUs <= minStartUsExclusive) {
            throw java.io.IOException(
                "Keyframe retry did not advance past budget-exceeded start ($actualStartUs <= $minStartUsExclusive)"
            )
        }
        return actualStartUs
    }

    @SuppressLint("NewApi")
    private fun getExtractorSampleSizeHint(extractor: MediaExtractor): Long {
        return if (sdkInt >= Build.VERSION_CODES.P) {
            extractor.sampleSize
        } else {
            -1L
        }
    }

    internal fun ensureBoundedSampleBuffer(
        currentBuffer: ByteBuffer,
        sampleSizeHint: Long,
        remainingBudgetBytes: Long,
        totalSamplesSoFar: Int,
        clipStartUs: Long
    ): ByteBuffer {
        if (totalSamplesSoFar >= MAX_MUXED_SAMPLES || remainingBudgetBytes <= 0L) {
            throw RemuxBudgetExceededException(
                clipStartUs = clipStartUs,
                message = "Motion clip exceeded remux budget before sample read " +
                    "(remainingBytes=$remainingBudgetBytes, samples=$totalSamplesSoFar/$MAX_MUXED_SAMPLES)"
            )
        }
        if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES) {
            throw RemuxBudgetExceededException(
                clipStartUs = clipStartUs,
                message = "Sample size ($sampleSizeHint) exceeds maximum sample buffer ($MAX_SAMPLE_BUFFER_BYTES bytes)"
            )
        }
        if (sampleSizeHint > remainingBudgetBytes) {
            throw RemuxBudgetExceededException(
                clipStartUs = clipStartUs,
                message = "Sample size ($sampleSizeHint) exceeds remaining remux byte budget ($remainingBudgetBytes)"
            )
        }
        val maxReadBytes = minOf(
            MAX_SAMPLE_BUFFER_BYTES.toLong(),
            remainingBudgetBytes
        ).toInt()
        val baseBuffer = if (sampleSizeHint > currentBuffer.capacity()) {
            allocateGrownDirectBuffer(
                previousCapacity = currentBuffer.capacity(),
                minCapacity = sampleSizeHint.toInt(),
                maxCapacity = maxReadBytes
            )
        } else {
            currentBuffer
        }
        baseBuffer.clear()
        return if (maxReadBytes < baseBuffer.capacity()) {
            baseBuffer.limit(maxReadBytes)
            baseBuffer.slice()
        } else {
            baseBuffer
        }
    }

    private fun seekAndValidateAudioTrack(
        extractor: MediaExtractor,
        clipStartUs: Long,
        endUs: Long
    ): Boolean {
        extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        var trackIndex = extractor.sampleTrackIndex
        var seekedTimeUs = if (trackIndex >= 0) extractor.sampleTime else -1L
        if (trackIndex >= 0 && seekedTimeUs > endUs) {
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            trackIndex = extractor.sampleTrackIndex
            seekedTimeUs = if (trackIndex >= 0) extractor.sampleTime else -1L
        }
        if (trackIndex < 0 || seekedTimeUs < 0L || seekedTimeUs > endUs) {
            return false
        }
        if (clipStartUs - seekedTimeUs > MAX_AUDIO_SEEK_LOOKBACK_US) {
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            trackIndex = extractor.sampleTrackIndex
            seekedTimeUs = if (trackIndex >= 0) extractor.sampleTime else -1L
            if (trackIndex < 0 ||
                seekedTimeUs < 0L ||
                seekedTimeUs > endUs ||
                clipStartUs - seekedTimeUs > MAX_AUDIO_SEEK_LOOKBACK_US
            ) {
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "Audio seek landed out of clip bounds (seeked=$seekedTimeUs, clipStart=$clipStartUs, end=$endUs), skipping audio"
                )
                return false
            }
        }
        return true
    }

    private fun openSharedAssetFileDescriptor(videoUri: Uri): AssetFileDescriptor? {
        return try {
            context.contentResolver.openAssetFileDescriptor(videoUri, "r")
        } catch (_: Exception) {
            null
        }
    }

    private fun configureExtractorDataSource(
        extractor: MediaExtractor,
        videoUri: Uri,
        sharedAfd: AssetFileDescriptor?
    ) {
        if (sharedAfd != null) {
            try {
                val declaredLength = sharedAfd.declaredLength
                if (declaredLength < 0L) {
                    extractor.setDataSource(sharedAfd.fileDescriptor)
                } else {
                    extractor.setDataSource(
                        sharedAfd.fileDescriptor,
                        sharedAfd.startOffset,
                        declaredLength
                    )
                }
                return
            } catch (e: Exception) {
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "Failed to set extractor data source from shared descriptor, falling back to URI",
                    e
                )
            }
        }
        extractor.setDataSource(context, videoUri, null)
    }

    internal suspend fun extractVideoClipOnce(
        videoUri: Uri,
        startUs: Long,
        endUs: Long,
        muteAudio: Boolean,
        seekMode: Int,
        rotation: Int = 0
    ): VideoClipResult = extractVideoClipOnceInternal(
        videoUri = videoUri,
        startUs = startUs,
        endUs = endUs,
        muteAudio = muteAudio,
        seekMode = seekMode,
        rotation = rotation,
        seekTargetUs = startUs,
        maxStartUsInclusive = endUs,
        minStartUsExclusive = null
    )

    private suspend fun extractVideoClipOnceInternal(
        videoUri: Uri,
        startUs: Long,
        endUs: Long,
        muteAudio: Boolean,
        seekMode: Int,
        rotation: Int = 0,
        seekTargetUs: Long = startUs,
        maxStartUsInclusive: Long = endUs,
        minStartUsExclusive: Long? = null
    ): VideoClipResult {
        var tempFile: java.io.File? = null
        var success = false
        try {
            var actualStartUs = startUs
            var videoSamplesWritten = 0
            var audioSamplesWritten = 0
            var audioTrackIndex = -1
            val sharedAfd = openSharedAssetFileDescriptor(videoUri)
            val extractor = extractorFactory()
            val outputClipFile: java.io.File
            try {
                configureExtractorDataSource(extractor, videoUri, sharedAfd)

                // Select only the video track on the primary extractor to avoid multi-track
                // interleaving issues that cause seek/timestamp problems on some devices.
                var videoTrackIndex = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                var videoHintMaxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES
                var audioHintMaxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getStringOrNullSafe(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoTrackIndex == -1) {
                        videoTrackIndex = i
                        videoFormat = format
                        val trackMaxInputSize = format.getIntegerOrNullSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (trackMaxInputSize != null && trackMaxInputSize > 0) {
                            videoHintMaxInputSize = trackMaxInputSize.coerceIn(
                                DEFAULT_SAMPLE_BUFFER_BYTES,
                                MAX_SAMPLE_BUFFER_BYTES
                            )
                        }
                    } else if (mime.startsWith("audio/") && !muteAudio && audioTrackIndex == -1) {
                        audioTrackIndex = i
                        audioFormat = format
                        val trackMaxInputSize = format.getIntegerOrNullSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (trackMaxInputSize != null && trackMaxInputSize > 0) {
                            audioHintMaxInputSize = trackMaxInputSize.coerceIn(
                                DEFAULT_SAMPLE_BUFFER_BYTES,
                                MAX_SAMPLE_BUFFER_BYTES
                            )
                        }
                    }
                }

                val selectedVideoFormat = videoFormat ?: throw java.io.IOException(
                    "No video track found (total tracks: ${extractor.trackCount})"
                )
                if (videoTrackIndex == -1) {
                    throw java.io.IOException(
                        "No video track found (total tracks: ${extractor.trackCount})"
                    )
                }

                sanitizeTrackMaxInputSize(selectedVideoFormat)
                audioFormat?.let { sanitizeTrackMaxInputSize(it) }

                // Resolve and validate the actual clip start (keyframe) ONCE on the primary
                // video extractor and keep its selection & cursor intact for Phase 1 copying.
                extractor.selectTrack(videoTrackIndex)
                extractor.seekTo(seekTargetUs, seekMode)
                actualStartUs = resolveAndValidateClipStartUs(
                    seekedPosition = extractor.sampleTime,
                    startUs = startUs,
                    endUs = endUs,
                    maxStartUsInclusive = maxStartUsInclusive,
                    minStartUsExclusive = minStartUsExclusive
                )
                var videoCursorPositioned = true

                // --- Audio preparation ---
                // MediaMuxer only accepts AAC/AMR-NB/AMR-WB into MP4. Other codecs —
                // notably LPCM from Sony XAVC cameras (ZV-1 etc.), but also
                // AC-3/E-AC-3/MP3/Opus/FLAC — make addTrack() throw. Transcode those
                // tracks to AAC up front via an isolated audio MediaExtractor so the
                // primary video cursor stays untouched.
                val audioMime = audioFormat?.getStringOrNullSafe(MediaFormat.KEY_MIME)
                var encodedAudio: EncodedAudio? = null
                var canMuxCompatibleAudio = audioFormat != null &&
                    audioTrackIndex >= 0 &&
                    isMuxerCompatibleAudioMime(audioMime)

                if (audioFormat != null && audioTrackIndex >= 0 && !canMuxCompatibleAudio) {
                    val audioExtractor = extractorFactory()
                    encodedAudio = try {
                        configureExtractorDataSource(audioExtractor, videoUri, sharedAfd)
                        if (audioTrackIndex >= audioExtractor.trackCount) {
                            throw java.io.IOException(
                                "Audio track index $audioTrackIndex out of bounds (${audioExtractor.trackCount})"
                            )
                        }
                        audioExtractor.selectTrack(audioTrackIndex)
                        transcodeAudioToAac(audioExtractor, audioFormat, actualStartUs, endUs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RemuxBudgetExceededException) {
                        throw e
                    } catch (e: Exception) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "Audio transcode failed ($audioMime), exporting without audio",
                            e
                        )
                        null
                    } finally {
                        try {
                            audioExtractor.release()
                        } catch (e: Exception) {
                            LogUtils.w(context, "FrameExporter", "Failed to release audio extractor", e)
                        }
                    }
                    encodedAudio?.let { sanitizeTrackMaxInputSize(it.format) }
                }

                val initialBufferCapacity = minOf(
                    (if (canMuxCompatibleAudio) {
                        maxOf(videoHintMaxInputSize, audioHintMaxInputSize)
                    } else {
                        videoHintMaxInputSize
                    }).coerceIn(
                        DEFAULT_SAMPLE_BUFFER_BYTES,
                        MAX_SAMPLE_BUFFER_BYTES
                    ).toLong(),
                    MAX_MUXED_CLIP_BYTES
                ).toInt()
                var buffer: ByteBuffer? = ByteBuffer.allocateDirect(initialBufferCapacity)
                val bufferInfo = bufferInfoFactory()
                var muxableVideoFormat: MediaFormat = selectedVideoFormat

                try {
                    while (true) {
                        videoSamplesWritten = 0
                        audioSamplesWritten = 0
                        var retryWithoutAudio = false
                        var totalMuxedBytes = 0L
                        var totalMuxedSamples = 0

                        fun recordMuxedSampleOrThrow(sampleBytes: Int) {
                            totalMuxedBytes += sampleBytes.toLong()
                            totalMuxedSamples++
                            if (totalMuxedBytes > MAX_MUXED_CLIP_BYTES || totalMuxedSamples > MAX_MUXED_SAMPLES) {
                                throw RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Motion clip exceeded remux budget " +
                                        "(bytes=$totalMuxedBytes/$MAX_MUXED_CLIP_BYTES, samples=$totalMuxedSamples/$MAX_MUXED_SAMPLES)"
                                )
                            }
                        }

                        fun buildSampleBufferExceededException(
                            remainingBudgetBytes: Long,
                            cause: Throwable? = null
                        ): RemuxBudgetExceededException {
                            return if (remainingBudgetBytes < MAX_SAMPLE_BUFFER_BYTES) {
                                RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Sample exceeds remaining remux byte budget ($remainingBudgetBytes)",
                                    cause = cause
                                )
                            } else {
                                RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Sample exceeds maximum sample buffer ($MAX_SAMPLE_BUFFER_BYTES bytes)",
                                    cause = cause
                                )
                            }
                        }

                        fun readSampleIntoBoundedBuffer(
                            sourceExtractor: MediaExtractor,
                            skipTrackOnOversizeSample: Boolean
                        ): Pair<Int, ByteBuffer?> {
                            val remainingBudgetBytes = MAX_MUXED_CLIP_BYTES - totalMuxedBytes
                            if (totalMuxedSamples >= MAX_MUXED_SAMPLES || remainingBudgetBytes <= 0L) {
                                throw RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Motion clip exceeded remux budget before sample read " +
                                        "(remainingBytes=$remainingBudgetBytes, samples=$totalMuxedSamples/$MAX_MUXED_SAMPLES)"
                                )
                            }
                            val sampleSizeHint = getExtractorSampleSizeHint(sourceExtractor)
                            if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES) {
                                if (skipTrackOnOversizeSample) {
                                    LogUtils.w(
                                        context,
                                        "FrameExporter",
                                        "Audio sample size ($sampleSizeHint bytes) exceeds limit ($MAX_SAMPLE_BUFFER_BYTES bytes), skipping audio track"
                                    )
                                    return -2 to null
                                }
                                throw RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Sample size ($sampleSizeHint) exceeds maximum sample buffer ($MAX_SAMPLE_BUFFER_BYTES bytes)"
                                )
                            }
                            if (sampleSizeHint > remainingBudgetBytes) {
                                throw RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Sample size ($sampleSizeHint) exceeds remaining remux byte budget ($remainingBudgetBytes)"
                                )
                            }
                            val maxAllowedBytes = minOf(
                                MAX_SAMPLE_BUFFER_BYTES.toLong(),
                                remainingBudgetBytes
                            ).toInt()
                            val existingCapacity = requireNotNull(buffer).capacity()
                            if (sampleSizeHint > 0L && sampleSizeHint > existingCapacity) {
                                buffer = null
                                buffer = allocateGrownDirectBuffer(
                                    previousCapacity = existingCapacity,
                                    minCapacity = sampleSizeHint.toInt(),
                                    maxCapacity = maxAllowedBytes
                                )
                            }
                            var readBuffer = ensureBoundedSampleBuffer(
                                currentBuffer = requireNotNull(buffer),
                                sampleSizeHint = sampleSizeHint,
                                remainingBudgetBytes = remainingBudgetBytes,
                                totalSamplesSoFar = totalMuxedSamples,
                                clipStartUs = actualStartUs
                            )
                            var readBytes: Int
                            while (true) {
                                try {
                                    readBytes = sourceExtractor.readSampleData(readBuffer, 0)
                                    break
                                } catch (e: IllegalArgumentException) {
                                    val cap = readBuffer.capacity()
                                    if (sampleSizeHint < 0L && cap < maxAllowedBytes) {
                                        buffer = null
                                        val grown = allocateGrownDirectBuffer(
                                            previousCapacity = cap,
                                            minCapacity = cap + 1,
                                            maxCapacity = maxAllowedBytes
                                        )
                                        buffer = grown
                                        readBuffer = if (maxAllowedBytes < grown.capacity()) {
                                            grown.limit(maxAllowedBytes)
                                            grown.slice()
                                        } else {
                                            grown
                                        }
                                    } else if (skipTrackOnOversizeSample && remainingBudgetBytes >= MAX_SAMPLE_BUFFER_BYTES) {
                                        LogUtils.w(
                                            context,
                                            "FrameExporter",
                                            "Audio sample exceeds maximum sample buffer ($MAX_SAMPLE_BUFFER_BYTES bytes), skipping audio track",
                                            e
                                        )
                                        return -2 to null
                                    } else {
                                        throw buildSampleBufferExceededException(
                                            remainingBudgetBytes = remainingBudgetBytes,
                                            cause = e
                                        )
                                    }
                                }
                            }
                            if (readBytes < 0 &&
                                readBuffer.capacity().toLong() == remainingBudgetBytes &&
                                remainingBudgetBytes < MAX_SAMPLE_BUFFER_BYTES
                            ) {
                                throw RemuxBudgetExceededException(
                                    clipStartUs = actualStartUs,
                                    message = "Sample read exceeded remaining remux byte budget ($remainingBudgetBytes)"
                                )
                            }
                            if (readBytes > readBuffer.capacity()) {
                                if (skipTrackOnOversizeSample && remainingBudgetBytes >= MAX_SAMPLE_BUFFER_BYTES) {
                                    LogUtils.w(
                                        context,
                                        "FrameExporter",
                                        "Audio sample size ($readBytes bytes) exceeds buffer capacity (${readBuffer.capacity()} bytes), skipping audio track"
                                    )
                                    return -2 to null
                                }
                                throw buildSampleBufferExceededException(
                                    remainingBudgetBytes = remainingBudgetBytes
                                )
                            }
                            return readBytes to readBuffer
                        }

                        // Some cameras (e.g. Sony ZV1 XAVC S/HS) produce MediaFormat entries
                        // with vendor-specific keys that MediaMuxer's native layer rejects.
                        // Try addTrack on the real muxer first; if it fails, recreate
                        // with a cleaned format. This avoids creating a separate test
                        // file + muxer on every export (saves I/O on the majority of devices).
                        var currentTempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
                        tempFile = currentTempFile
                        var muxer: MediaMuxer? = null
                        var muxerStarted = false
                        try {
                            muxer = muxerFactory(currentTempFile.absolutePath)
                            val muxerVideoTrack: Int = try {
                                muxer.addTrack(muxableVideoFormat)
                            } catch (e: Exception) {
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "addTrack failed with original format (${selectedVideoFormat.getStringOrNullSafe(MediaFormat.KEY_MIME)}), using clean format",
                                    e
                                )
                                try {
                                    muxer.release()
                                } catch (releaseException: Exception) {
                                    LogUtils.w(context, "FrameExporter", "Failed to release failed muxer", releaseException)
                                }
                                muxer = null
                                currentTempFile.delete()
                                currentTempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
                                tempFile = currentTempFile
                                val cleanMuxer = muxerFactory(currentTempFile.absolutePath)
                                muxer = cleanMuxer
                                muxableVideoFormat = createCleanVideoFormat(selectedVideoFormat)
                                cleanMuxer.addTrack(muxableVideoFormat)
                            }

                            val activeMuxer = muxer
                            if (rotation in VALID_ROTATIONS) {
                                activeMuxer.setOrientationHint(rotation)
                            }

                            val muxerAudioTrack = when {
                                encodedAudio != null -> try {
                                    activeMuxer.addTrack(encodedAudio.format)
                                } catch (e: Exception) {
                                    LogUtils.w(context, "FrameExporter", "addTrack failed for transcoded AAC, skipping audio", e)
                                    -1
                                }
                                audioFormat != null && canMuxCompatibleAudio -> try {
                                    activeMuxer.addTrack(audioFormat)
                                } catch (e: Exception) {
                                    LogUtils.w(context, "FrameExporter", "addTrack failed for audio, skipping audio", e)
                                    -1
                                }
                                else -> -1
                            }

                            activeMuxer.start()
                            muxerStarted = true

                            // --- Phase 1: Extract video samples (reusing single-seek cursor on first pass) ---
                            if (!videoCursorPositioned) {
                                extractor.selectTrack(videoTrackIndex)
                                extractor.seekTo(seekTargetUs, seekMode)
                                actualStartUs = resolveAndValidateClipStartUs(
                                    seekedPosition = extractor.sampleTime,
                                    startUs = startUs,
                                    endUs = endUs,
                                    maxStartUsInclusive = maxStartUsInclusive,
                                    minStartUsExclusive = minStartUsExclusive
                                )
                                videoCursorPositioned = true
                            }

                            var sampleCount = 0
                            while (true) {
                                if (++sampleCount % 10 == 0) {
                                    currentCoroutineContext().ensureActive()
                                }
                                val trackIndex = extractor.sampleTrackIndex
                                if (trackIndex < 0) break

                                val sampleTime = extractor.sampleTime
                                if (sampleTime < 0 || sampleTime > endUs) break

                                val (sampleSize, readBuffer) = readSampleIntoBoundedBuffer(
                                    sourceExtractor = extractor,
                                    skipTrackOnOversizeSample = false
                                )
                                if (sampleSize < 0) break

                                recordMuxedSampleOrThrow(sampleSize)

                                bufferInfo.offset = 0
                                bufferInfo.size = sampleSize
                                bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                                bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                                activeMuxer.writeSampleData(muxerVideoTrack, requireNotNull(readBuffer), bufferInfo)
                                videoSamplesWritten++

                                if (!extractor.advance()) break
                            }

                            // --- Phase 2: Write audio samples if needed ---
                            if (muxerAudioTrack >= 0 && videoSamplesWritten > 0) {
                                val readyEncodedAudio = encodedAudio
                                if (readyEncodedAudio != null) {
                                    for (packet in readyEncodedAudio.packets) {
                                        if (++sampleCount % 10 == 0) {
                                            currentCoroutineContext().ensureActive()
                                        }
                                        recordMuxedSampleOrThrow(packet.data.size)

                                        bufferInfo.offset = 0
                                        bufferInfo.size = packet.data.size
                                        bufferInfo.presentationTimeUs = packet.presentationTimeUs
                                        bufferInfo.flags = packet.flags
                                        activeMuxer.writeSampleData(
                                            muxerAudioTrack,
                                            ByteBuffer.wrap(packet.data),
                                            bufferInfo
                                        )
                                        audioSamplesWritten++
                                    }
                                } else if (audioTrackIndex >= 0 && canMuxCompatibleAudio) {
                                    extractor.unselectTrack(videoTrackIndex)
                                    videoCursorPositioned = false
                                    extractor.selectTrack(audioTrackIndex)
                                    if (seekAndValidateAudioTrack(extractor, actualStartUs, endUs)) {
                                        while (true) {
                                            if (++sampleCount % 10 == 0) {
                                                currentCoroutineContext().ensureActive()
                                            }
                                            val trackIndex = extractor.sampleTrackIndex
                                            if (trackIndex < 0) break

                                            val sampleTime = extractor.sampleTime
                                            if (sampleTime < 0 || sampleTime > endUs) break
                                            if (actualStartUs - sampleTime > MAX_AUDIO_SEEK_LOOKBACK_US) {
                                                throw RemuxBudgetExceededException(
                                                    clipStartUs = actualStartUs,
                                                    message = "Audio sample timestamp moved before bounded lookback " +
                                                        "(sampleTime=$sampleTime, clipStart=$actualStartUs)"
                                                )
                                            }

                                            val (sampleSize, readBuffer) = readSampleIntoBoundedBuffer(
                                                sourceExtractor = extractor,
                                                skipTrackOnOversizeSample = true
                                            )
                                            if (sampleSize == -2) {
                                                retryWithoutAudio = true
                                                break
                                            }
                                            if (sampleSize < 0) break

                                            recordMuxedSampleOrThrow(sampleSize)

                                            bufferInfo.offset = 0
                                            bufferInfo.size = sampleSize
                                            bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                                            bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                                            activeMuxer.writeSampleData(muxerAudioTrack, requireNotNull(readBuffer), bufferInfo)
                                            audioSamplesWritten++
                                            if (!extractor.advance()) break
                                        }
                                    }
                                    extractor.unselectTrack(audioTrackIndex)

                                    if (!retryWithoutAudio && audioSamplesWritten == 0) {
                                        retryWithoutAudio = true
                                    }
                                }
                            }

                            if (retryWithoutAudio) {
                                if (muxerStarted) {
                                    muxerStarted = false
                                    try { activeMuxer.stop() } catch (_: Exception) { }
                                }
                            } else if (muxerStarted && videoSamplesWritten > 0) {
                                muxerStarted = false
                                activeMuxer.stop()
                            } else if (muxerStarted) {
                                muxerStarted = false
                                try { activeMuxer.stop() } catch (_: Exception) { }
                            }
                        } finally {
                            if (muxerStarted) {
                                try {
                                    muxer?.stop()
                                } catch (_: Exception) {
                                }
                            }
                            try {
                                muxer?.release()
                            } catch (e: Exception) {
                                LogUtils.w(context, "FrameExporter", "Failed to release muxer", e)
                            }
                        }

                        if (retryWithoutAudio) {
                            currentTempFile.delete()
                            tempFile = null
                            encodedAudio = null
                            canMuxCompatibleAudio = false
                            continue
                        }
                        outputClipFile = currentTempFile
                        break
                    }
                } finally {
                    buffer = null
                }
            } finally {
                try {
                    extractor.release()
                } catch (e: Exception) {
                    LogUtils.w(context, "FrameExporter", "Failed to release extractor", e)
                }
                if (sharedAfd != null) {
                    try {
                        sharedAfd.close()
                    } catch (e: Exception) {
                        LogUtils.w(context, "FrameExporter", "Failed to close shared file descriptor", e)
                    }
                }
            }

            success = true
            return VideoClipResult(
                file = outputClipFile,
                actualStartUs = actualStartUs,
                videoSamplesWritten = videoSamplesWritten,
                audioIncluded = audioSamplesWritten > 0,
                hasAudioTrack = audioTrackIndex >= 0
            )
        } finally {
            if (!success) {
                tempFile?.delete()
            }
        }
    }

    private fun createAudioDecoderAdapter(mime: String, audioFormat: MediaFormat): AudioCodecAdapter? {
        sanitizeTrackMaxInputSize(audioFormat)
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "No decoder for $mime, skipping audio", e)
            return null
        }
        try {
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to start decoder for $mime, skipping audio", e)
            try {
                decoder.release()
            } catch (releaseException: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release audio decoder", releaseException)
            }
            return null
        }
        return MediaCodecAdapter(decoder, context, "audio decoder")
    }

    private fun createAacEncoderAdapter(sampleRate: Int, channelCount: Int): AudioCodecAdapter? {
        val aacFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE_PER_CHANNEL * channelCount)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "No AAC encoder available, skipping audio", e)
            return null
        }
        try {
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to start AAC encoder, skipping audio", e)
            try {
                encoder.release()
            } catch (releaseException: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release AAC encoder", releaseException)
            }
            return null
        }
        return MediaCodecAdapter(encoder, context, "AAC encoder")
    }

    /**
     * Transcode the currently selected audio track to AAC-LC in a single non-blocking
     * streaming loop using a fixed <= 16 KB frame-aligned carry buffer.
     *
     * For 16-bit LPCM tracks (Sony XAVC S/HS, Canon, ...), samples are streamed directly
     * from [extractor] into the AAC encoder. For compressed codecs (AC-3/E-AC-3/MP3/Opus/FLAC)
     * or non-16-bit raw audio, decoding and AAC encoding progress cooperatively in a single
     * zero-wait/single-timeout loop without buffering whole-clip PCM in heap memory.
     *
     * @return the encoded AAC format + packets ready for [MediaMuxer], or null when the
     *         track cannot be transcoded (callers then export the clip without audio).
     */
    internal suspend fun transcodeAudioToAac(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): EncodedAudio? {
        val mime = audioFormat.getStringOrNullSafe(MediaFormat.KEY_MIME) ?: return null
        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            val sampleRate = audioFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, -1)
            val channelCount = audioFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, -1)
            if (sampleRate <= 0 || channelCount !in 1..2) {
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "Unsupported PCM layout (rate=$sampleRate, ch=$channelCount), skipping audio"
                )
                return null
            }
            // Only 16-bit PCM can feed the AAC encoder directly.
            // If KEY_PCM_ENCODING is missing or non-16BIT, fall back to MediaCodec decoding.
            val pcmEncoding = audioFormat.getIntegerOrNullSafe(MediaFormat.KEY_PCM_ENCODING)
            if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                if (!seekAndValidateAudioTrack(extractor, clipStartUs, endUs)) {
                    return null
                }
                val maxInputSizeHint = audioFormat.getIntegerOrNullSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
                return streamRawPcmToAac(
                    source = MediaExtractorAudioSampleSource(extractor, sdkInt),
                    clipStartUs = clipStartUs,
                    endUs = endUs,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    maxInputSizeHint = maxInputSizeHint
                )
            }
        }

        if (!seekAndValidateAudioTrack(extractor, clipStartUs, endUs)) {
            return null
        }
        val initialSampleRate = audioFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, -1)
        val initialChannelCount = audioFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, -1)
        return streamCompressedToAac(
            source = MediaExtractorAudioSampleSource(extractor, sdkInt),
            mime = mime,
            audioFormat = audioFormat,
            clipStartUs = clipStartUs,
            endUs = endUs,
            initialSampleRate = initialSampleRate,
            initialChannelCount = initialChannelCount
        )
    }

    /**
     * Stream 16-bit raw PCM samples from [source] directly into the AAC encoder using
     * a fixed <= 16 KB frame-aligned carry buffer.
     */
    internal suspend fun streamRawPcmToAac(
        source: AudioSampleSource,
        clipStartUs: Long,
        endUs: Long,
        sampleRate: Int,
        channelCount: Int,
        maxInputSizeHint: Int? = null
    ): EncodedAudio? {
        if (sampleRate <= 0 || channelCount !in 1..2) {
            LogUtils.w(
                context,
                "FrameExporter",
                "Unsupported PCM layout (rate=$sampleRate, ch=$channelCount), skipping audio"
            )
            return null
        }
        if (source.sampleTrackIndex < 0 || source.sampleTimeUs < 0L || source.sampleTimeUs > endUs) {
            LogUtils.w(context, "FrameExporter", "No PCM data in clip range, skipping audio")
            return null
        }

        val firstHint = source.sampleSizeHint
        if (firstHint > MAX_PCM_BUFFER_BYTES) {
            LogUtils.w(
                context,
                "FrameExporter",
                "PCM stream size exceeded limit ($firstHint > $MAX_PCM_BUFFER_BYTES), skipping audio"
            )
            return null
        }

        val bytesPerFrame = 2 * channelCount
        val carry = PcmCarryBuffer(bytesPerFrame, PCM_CARRY_BUFFER_BYTES)
        val initialReadCapacity = when {
            firstHint > 0L -> firstHint
                .coerceIn(PCM_CARRY_BUFFER_BYTES.toLong(), MAX_PCM_BUFFER_BYTES.toLong())
                .toInt()
            maxInputSizeHint != null && maxInputSizeHint > 0 -> maxInputSizeHint
                .coerceIn(PCM_CARRY_BUFFER_BYTES, DEFAULT_SAMPLE_BUFFER_BYTES)
            else -> FALLBACK_LPCM_READ_BUFFER_BYTES
        }
        var pcmReadBuffer: ByteBuffer? = ByteBuffer.allocateDirect(initialReadCapacity)
        var pendingPcmBuffer: ByteBuffer? = null

        var encoder: AudioCodecAdapter? = null
        try {
            val packets = ArrayList<EncodedAudioPacket>()
            var encoderOutputFormat: MediaFormat? = null
            val encoderInfo = CodecOutputInfo()
            var basePtsUs = 0L
            var sawFirstSample = false
            var framesFed = 0L
            var totalPcmBytes = 0L
            var audioSourceSamples = 0
            var encoderInputsQueued = 0L
            var sourceDone = false
            var encoderInputDone = false
            var encoderOutputDone = false
            var idleRounds = 0
            var rounds = 0

            while (if (encoder != null) !encoderOutputDone else !sourceDone) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                // 1. Pull next raw PCM sample when no pending sample slice is waiting
                if (!sourceDone && pendingPcmBuffer == null && carry.freeCapacity >= bytesPerFrame) {
                    val sampleTime = source.sampleTimeUs
                    if (source.sampleTrackIndex < 0 || sampleTime < 0L || sampleTime > endUs) {
                        sourceDone = true
                        progressed = true
                    } else {
                        if (clipStartUs - sampleTime > MAX_AUDIO_SEEK_LOOKBACK_US) {
                            throw RemuxBudgetExceededException(
                                clipStartUs = clipStartUs,
                                message = "PCM sample timestamp moved before bounded lookback " +
                                    "(sampleTime=$sampleTime, clipStart=$clipStartUs)"
                            )
                        }
                        if (++audioSourceSamples > MAX_AUDIO_SOURCE_SAMPLES) {
                            throw RemuxBudgetExceededException(
                                clipStartUs = clipStartUs,
                                message = "Audio pre-transcode exceeded source sample budget " +
                                    "($audioSourceSamples/$MAX_AUDIO_SOURCE_SAMPLES)"
                            )
                        }
                        val hint = source.sampleSizeHint
                        if (hint > MAX_PCM_BUFFER_BYTES ||
                            (hint > 0L && totalPcmBytes + hint > MAX_PCM_BUFFER_BYTES)
                        ) {
                            LogUtils.w(
                                context,
                                "FrameExporter",
                                "PCM stream size exceeded limit (${totalPcmBytes + hint} > $MAX_PCM_BUFFER_BYTES), skipping audio"
                            )
                            return null
                        }
                        val existingCapacity = requireNotNull(pcmReadBuffer).capacity()
                        if (hint > existingCapacity) {
                            pcmReadBuffer = null
                            pendingPcmBuffer = null
                            pcmReadBuffer = allocateGrownDirectBuffer(
                                previousCapacity = existingCapacity,
                                minCapacity = hint.toInt(),
                                maxCapacity = MAX_PCM_BUFFER_BYTES
                            )
                        }
                        var sampleSize: Int
                        while (true) {
                            requireNotNull(pcmReadBuffer).clear()
                            try {
                                sampleSize = source.readSampleData(requireNotNull(pcmReadBuffer))
                                break
                            } catch (e: Exception) {
                                val cap = requireNotNull(pcmReadBuffer).capacity()
                                if ((e is IllegalArgumentException || e is java.nio.BufferOverflowException) &&
                                    cap < MAX_PCM_BUFFER_BYTES
                                ) {
                                    pcmReadBuffer = null
                                    pendingPcmBuffer = null
                                    pcmReadBuffer = allocateGrownDirectBuffer(
                                        previousCapacity = cap,
                                        minCapacity = cap + 1,
                                        maxCapacity = MAX_PCM_BUFFER_BYTES
                                    )
                                } else {
                                    throw e
                                }
                            }
                        }
                        val readyReadBuffer = requireNotNull(pcmReadBuffer)
                        if (sampleSize < 0) {
                            sourceDone = true
                            progressed = true
                        } else if (sampleSize == 0) {
                            source.advance()
                            progressed = true
                        } else {
                            if (sampleSize > readyReadBuffer.capacity() ||
                                totalPcmBytes + sampleSize > MAX_PCM_BUFFER_BYTES
                            ) {
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "PCM stream size exceeded limit (${totalPcmBytes + sampleSize} > $MAX_PCM_BUFFER_BYTES), skipping audio"
                                )
                                return null
                            }
                            totalPcmBytes += sampleSize
                            if (!sawFirstSample) {
                                sawFirstSample = true
                                basePtsUs = (sampleTime - clipStartUs).coerceAtLeast(0L)
                            }
                            readyReadBuffer.position(0)
                            readyReadBuffer.limit(sampleSize)
                            source.advance()
                            pendingPcmBuffer = readyReadBuffer

                            val readyBytes = carry.size + readyReadBuffer.remaining()
                            if (encoder == null && readyBytes >= bytesPerFrame) {
                                encoder = aacEncoderFactory(sampleRate, channelCount) ?: return null
                            }
                            progressed = true
                        }
                    }
                }

                // 2. Feed aligned PCM frames (or EOS) to AAC encoder non-blockingly
                val activeEncoder = encoder
                if (activeEncoder != null && !encoderInputDone) {
                    val availableBytes = carry.size + (pendingPcmBuffer?.remaining() ?: 0)
                    val shouldQueueData = availableBytes >= bytesPerFrame
                    val shouldQueueEos = !shouldQueueData && sourceDone && pendingPcmBuffer == null
                    if (shouldQueueData || shouldQueueEos) {
                        if (feedAacEncoderInput(
                                encoder = activeEncoder,
                                timeoutUs = 0L,
                                carry = carry,
                                pendingSource = pendingPcmBuffer,
                                shouldQueueData = shouldQueueData,
                                basePtsUs = basePtsUs,
                                sampleRate = sampleRate,
                                framesFed = framesFed,
                                onFramesQueued = { queuedFrames ->
                                    framesFed += queuedFrames
                                    encoderInputsQueued++
                                },
                                onEosQueued = {
                                    encoderInputDone = true
                                    encoderInputsQueued++
                                }
                            )
                        ) {
                            progressed = true
                        }
                    }
                }

                // Spill any unconsumed bytes (sub-frame remainder or backpressure) into <= 16 KB carry
                pendingPcmBuffer?.let { pending ->
                    if (!pending.hasRemaining()) {
                        pendingPcmBuffer = null
                    } else if (carry.freeCapacity > 0) {
                        carry.appendFrom(pending)
                        if (!pending.hasRemaining()) {
                            pendingPcmBuffer = null
                        }
                    }
                }

                // 3. Drain encoded AAC packets non-blockingly
                if (activeEncoder != null && !encoderOutputDone) {
                        if (drainAacEncoderOutput(
                            encoder = activeEncoder,
                            info = encoderInfo,
                            timeoutUs = 0L,
                            packets = packets,
                            clipStartUs = clipStartUs,
                            onFormatChanged = { encoderOutputFormat = it },
                            onOutputDone = { encoderOutputDone = true }
                        )
                    ) {
                        progressed = true
                    }
                }

                // 4. Single-timeout backpressure wait only when the entire 0L round made no progress
                if (!progressed && activeEncoder != null && !encoderOutputDone) {
                    val availableBytes = carry.size + (pendingPcmBuffer?.remaining() ?: 0)
                    val shouldQueueData = availableBytes >= bytesPerFrame
                    val shouldQueueEos = !shouldQueueData && sourceDone && pendingPcmBuffer == null
                    progressed = if (!encoderInputDone && encoderInputsQueued == 0L && (shouldQueueData || shouldQueueEos)) {
                        feedAacEncoderInput(
                            encoder = activeEncoder,
                            timeoutUs = CODEC_DEQUEUE_TIMEOUT_US,
                            carry = carry,
                            pendingSource = pendingPcmBuffer,
                            shouldQueueData = shouldQueueData,
                            basePtsUs = basePtsUs,
                            sampleRate = sampleRate,
                            framesFed = framesFed,
                            onFramesQueued = { queuedFrames ->
                                framesFed += queuedFrames
                                encoderInputsQueued++
                            },
                            onEosQueued = {
                                encoderInputDone = true
                                encoderInputsQueued++
                            }
                        ).also {
                            pendingPcmBuffer?.let { pending ->
                                if (!pending.hasRemaining()) {
                                    pendingPcmBuffer = null
                                } else if (carry.freeCapacity > 0) {
                                    carry.appendFrom(pending)
                                    if (!pending.hasRemaining()) {
                                        pendingPcmBuffer = null
                                    }
                                }
                            }
                        }
                    } else {
                        drainAacEncoderOutput(
                            encoder = activeEncoder,
                            info = encoderInfo,
                            timeoutUs = CODEC_DEQUEUE_TIMEOUT_US,
                            packets = packets,
                            clipStartUs = clipStartUs,
                            onFormatChanged = { encoderOutputFormat = it },
                            onOutputDone = { encoderOutputDone = true }
                        )
                    }
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
                if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                    throw java.io.IOException("AAC encoder stalled")
                }
            }

            if (encoder == null || framesFed == 0L) {
                LogUtils.w(context, "FrameExporter", "No PCM data in clip range, skipping audio")
                return null
            }
            val format = encoderOutputFormat
            if (format == null || packets.isEmpty()) {
                LogUtils.w(context, "FrameExporter", "AAC encoder produced no packets, skipping audio")
                return null
            }
            return EncodedAudio(format, packets)
        } finally {
            pcmReadBuffer = null
            pendingPcmBuffer = null
            encoder?.stopAndRelease()
        }
    }

    /**
     * Cooperatively decode a compressed audio track and encode to AAC-LC inside a single
     * non-blocking loop bridged by a fixed <= 16 KB frame-aligned carry buffer.
     */
    internal suspend fun streamCompressedToAac(
        source: AudioSampleSource,
        mime: String,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long,
        initialSampleRate: Int,
        initialChannelCount: Int
    ): EncodedAudio? {
        sanitizeTrackMaxInputSize(audioFormat)
        val decoder = decoderFactory(mime, audioFormat) ?: return null
        var encoder: AudioCodecAdapter? = null
        var pendingDecoderOutputIndex = -1
        try {
            val packets = ArrayList<EncodedAudioPacket>()
            var encoderOutputFormat: MediaFormat? = null
            val decoderInfo = CodecOutputInfo()
            val encoderInfo = CodecOutputInfo()

            var sampleRate = initialSampleRate
            var channelCount = initialChannelCount
            var lockedSampleRate = -1
            var lockedChannelCount = -1
            var bytesPerFrame = 0
            var carry: PcmCarryBuffer? = null

            var pendingDecoderBuffer: ByteBuffer? = null

            var basePtsUs = 0L
            var sawPcm = false
            var framesFed = 0L
            var totalPcmBytes = 0L
            var audioSourceSamples = 0
            var decoderInputsQueued = 0L
            var encoderInputsQueued = 0L
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderInputDone = false
            var encoderOutputDone = false
            var idleRounds = 0
            var rounds = 0

            fun releasePendingDecoderIfConsumed() {
                val pending = pendingDecoderBuffer
                if (pendingDecoderOutputIndex >= 0) {
                    if (pending == null || !pending.hasRemaining()) {
                        decoder.releaseOutputBuffer(pendingDecoderOutputIndex)
                        pendingDecoderOutputIndex = -1
                        pendingDecoderBuffer = null
                    } else {
                        val activeCarry = carry
                        if (activeCarry != null && activeCarry.freeCapacity > 0) {
                            activeCarry.appendFrom(pending)
                            if (!pending.hasRemaining()) {
                                decoder.releaseOutputBuffer(pendingDecoderOutputIndex)
                                pendingDecoderOutputIndex = -1
                                pendingDecoderBuffer = null
                            }
                        }
                    }
                }
            }

            fun handleDecoderDequeue(timeoutUs: Long): Pair<Boolean, Boolean> {
                // Returns (progressed, abortStream)
                return when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        val encoding = newFormat.getIntegerOrNullSafe(MediaFormat.KEY_PCM_ENCODING)
                        if (encoding != null && encoding != AudioFormat.ENCODING_PCM_16BIT) {
                            LogUtils.w(
                                context,
                                "FrameExporter",
                                "Decoder produced non-16-bit PCM ($encoding), skipping audio"
                            )
                            return true to true
                        }
                        sampleRate = newFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = newFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        if (encoder != null &&
                            (sampleRate != lockedSampleRate || channelCount != lockedChannelCount)
                        ) {
                            LogUtils.w(
                                context,
                                "FrameExporter",
                                "Mid-stream audio format change ($lockedSampleRate/${lockedChannelCount}ch -> $sampleRate/${channelCount}ch), skipping audio"
                            )
                            return true to true
                        }
                        true to false
                    }
                    @Suppress("DEPRECATION")
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> true to false
                    MediaCodec.INFO_TRY_AGAIN_LATER -> false to false
                    else -> {
                        val isEndOfStream =
                            decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isCodecConfig =
                            decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isCodecConfig && decoderInfo.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                totalPcmBytes += decoderInfo.size
                                if (totalPcmBytes > MAX_PCM_BUFFER_BYTES) {
                                    decoder.releaseOutputBuffer(outputIndex)
                                    LogUtils.w(
                                        context,
                                        "FrameExporter",
                                        "Decoded PCM stream size exceeded limit ($totalPcmBytes > $MAX_PCM_BUFFER_BYTES), skipping audio"
                                    )
                                    return true to true
                                }
                                if (!sawPcm) {
                                    if (sampleRate <= 0 || channelCount !in 1..2) {
                                        decoder.releaseOutputBuffer(outputIndex)
                                        LogUtils.w(
                                            context,
                                            "FrameExporter",
                                            "Decoded audio unusable (rate=$sampleRate, ch=$channelCount), skipping audio"
                                        )
                                        return true to true
                                    }
                                    sawPcm = true
                                    lockedSampleRate = sampleRate
                                    lockedChannelCount = channelCount
                                    bytesPerFrame = 2 * channelCount
                                    carry = PcmCarryBuffer(bytesPerFrame, PCM_CARRY_BUFFER_BYTES)
                                    basePtsUs = (decoderInfo.presentationTimeUs - clipStartUs)
                                        .coerceAtLeast(0L)
                                    val startedEncoder = aacEncoderFactory(sampleRate, channelCount)
                                    if (startedEncoder == null) {
                                        decoder.releaseOutputBuffer(outputIndex)
                                        return true to true
                                    }
                                    encoder = startedEncoder
                                }
                                outputBuffer.position(decoderInfo.offset)
                                outputBuffer.limit(decoderInfo.offset + decoderInfo.size)
                                pendingDecoderOutputIndex = outputIndex
                                pendingDecoderBuffer = outputBuffer
                                if (isEndOfStream) {
                                    decoderOutputDone = true
                                }
                                return true to false
                            }
                        }
                        if (isEndOfStream) {
                            decoderOutputDone = true
                        }
                        decoder.releaseOutputBuffer(outputIndex)
                        true to false
                    }
                }
            }

            fun feedDecoderInput(timeoutUs: Long): Boolean {
                val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputIndex < 0) return false
                val sampleTime = source.sampleTimeUs
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                if (source.sampleTrackIndex < 0 || sampleTime < 0L || sampleTime > endUs) {
                    decoder.queueInputBuffer(
                        inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    decoderInputDone = true
                    decoderInputsQueued++
                } else if (inputBuffer == null) {
                    throw java.io.IOException("Audio decoder returned a null input buffer")
                } else {
                    if (clipStartUs - sampleTime > MAX_AUDIO_SEEK_LOOKBACK_US) {
                        throw RemuxBudgetExceededException(
                            clipStartUs = clipStartUs,
                            message = "Compressed audio sample timestamp moved before bounded lookback " +
                                "(sampleTime=$sampleTime, clipStart=$clipStartUs)"
                        )
                    }
                    if (++audioSourceSamples > MAX_AUDIO_SOURCE_SAMPLES) {
                        throw RemuxBudgetExceededException(
                            clipStartUs = clipStartUs,
                            message = "Audio decoder exceeded source sample budget " +
                                "($audioSourceSamples/$MAX_AUDIO_SOURCE_SAMPLES)"
                        )
                    }
                    val sampleSizeHint = source.sampleSizeHint
                    if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES ||
                        sampleSizeHint > inputBuffer.capacity().toLong()
                    ) {
                        throw java.io.IOException("Audio sample too large for decoder input buffer")
                    }
                    inputBuffer.clear()
                    val sampleSize = source.readSampleData(inputBuffer)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        decoderInputDone = true
                        decoderInputsQueued++
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                        source.advance()
                        decoderInputsQueued++
                    }
                }
                return true
            }

            while (if (encoder != null) !encoderOutputDone else !decoderOutputDone) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                // 1. Feed compressed packets to decoder (0L non-blocking)
                if (!decoderInputDone) {
                    if (feedDecoderInput(0L)) {
                        progressed = true
                    }
                }

                // 2. Pull decoded PCM from decoder (0L non-blocking) when carry has space
                val canPullDecoder = !decoderOutputDone &&
                    pendingDecoderOutputIndex < 0 &&
                    (carry == null || carry!!.freeCapacity >= bytesPerFrame)
                if (canPullDecoder) {
                    val (stepProgressed, abort) = handleDecoderDequeue(0L)
                    if (abort) return null
                    if (stepProgressed) progressed = true
                }

                // 3. Feed aligned PCM frames (or EOS) to AAC encoder (0L non-blocking)
                val activeEncoder = encoder
                val activeCarry = carry
                if (activeEncoder != null && activeCarry != null && !encoderInputDone) {
                    val availableBytes = activeCarry.size + (pendingDecoderBuffer?.remaining() ?: 0)
                    val shouldQueueData = availableBytes >= bytesPerFrame
                    val shouldQueueEos =
                        !shouldQueueData && decoderOutputDone && pendingDecoderOutputIndex < 0
                    if (shouldQueueData || shouldQueueEos) {
                        if (feedAacEncoderInput(
                                encoder = activeEncoder,
                                timeoutUs = 0L,
                                carry = activeCarry,
                                pendingSource = pendingDecoderBuffer,
                                shouldQueueData = shouldQueueData,
                                basePtsUs = basePtsUs,
                                sampleRate = lockedSampleRate,
                                framesFed = framesFed,
                                onFramesQueued = { queuedFrames ->
                                    framesFed += queuedFrames
                                    encoderInputsQueued++
                                },
                                onEosQueued = {
                                    encoderInputDone = true
                                    encoderInputsQueued++
                                }
                            )
                        ) {
                            progressed = true
                        }
                    }
                }

                // Spill any unconsumed decoded bytes into <= 16 KB carry & release decoder buffer early
                releasePendingDecoderIfConsumed()

                // 4. Drain encoded AAC packets (0L non-blocking)
                val currentEncoder = encoder
                if (currentEncoder != null && !encoderOutputDone) {
                    if (drainAacEncoderOutput(
                            encoder = currentEncoder,
                            info = encoderInfo,
                            timeoutUs = 0L,
                            packets = packets,
                            clipStartUs = clipStartUs,
                            onFormatChanged = { encoderOutputFormat = it },
                            onOutputDone = { encoderOutputDone = true }
                        )
                    ) {
                        progressed = true
                    }
                }

                // 5. Single-timeout backpressure wait ONLY when the entire 0L round made zero progress
                if (!progressed && (if (encoder != null) !encoderOutputDone else !decoderOutputDone)) {
                    val waitCarry = carry
                    val availableBytes =
                        (waitCarry?.size ?: 0) + (pendingDecoderBuffer?.remaining() ?: 0)
                    val hasFramesForEncoder = bytesPerFrame > 0 && availableBytes >= bytesPerFrame
                    val shouldQueueEos = !hasFramesForEncoder &&
                        decoderOutputDone &&
                        pendingDecoderOutputIndex < 0

                    if (!decoderInputDone && decoderInputsQueued == 0L) {
                        progressed = feedDecoderInput(CODEC_DEQUEUE_TIMEOUT_US)
                    } else if (currentEncoder != null &&
                        waitCarry != null &&
                        !encoderInputDone &&
                        encoderInputsQueued == 0L &&
                        (hasFramesForEncoder || shouldQueueEos)
                    ) {
                        progressed = feedAacEncoderInput(
                            encoder = currentEncoder,
                            timeoutUs = CODEC_DEQUEUE_TIMEOUT_US,
                            carry = waitCarry,
                            pendingSource = pendingDecoderBuffer,
                            shouldQueueData = hasFramesForEncoder,
                            basePtsUs = basePtsUs,
                            sampleRate = lockedSampleRate,
                            framesFed = framesFed,
                            onFramesQueued = { queuedFrames ->
                                framesFed += queuedFrames
                                encoderInputsQueued++
                            },
                            onEosQueued = {
                                encoderInputDone = true
                                encoderInputsQueued++
                            }
                        )
                        releasePendingDecoderIfConsumed()
                    } else if (!decoderOutputDone && !hasFramesForEncoder && pendingDecoderOutputIndex < 0) {
                        val (stepProgressed, abort) = handleDecoderDequeue(CODEC_DEQUEUE_TIMEOUT_US)
                        if (abort) return null
                        progressed = stepProgressed
                    } else if (currentEncoder != null && !encoderOutputDone) {
                        progressed = drainAacEncoderOutput(
                            encoder = currentEncoder,
                            info = encoderInfo,
                            timeoutUs = CODEC_DEQUEUE_TIMEOUT_US,
                            packets = packets,
                            clipStartUs = clipStartUs,
                            onFormatChanged = { encoderOutputFormat = it },
                            onOutputDone = { encoderOutputDone = true }
                        )
                    }
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
                if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                    throw java.io.IOException("Audio transcode stalled")
                }
            }

            if (!sawPcm || lockedSampleRate <= 0 || lockedChannelCount !in 1..2 || framesFed == 0L) {
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "Decoded audio unusable (rate=$sampleRate, ch=$channelCount), skipping audio"
                )
                return null
            }
            val format = encoderOutputFormat
            if (format == null || packets.isEmpty()) {
                LogUtils.w(context, "FrameExporter", "AAC encoder produced no packets, skipping audio")
                return null
            }
            return EncodedAudio(format, packets)
        } finally {
            if (pendingDecoderOutputIndex >= 0) {
                try {
                    decoder.releaseOutputBuffer(pendingDecoderOutputIndex)
                } catch (_: Exception) {
                }
            }
            decoder.stopAndRelease()
            encoder?.stopAndRelease()
        }
    }

    private inline fun feedAacEncoderInput(
        encoder: AudioCodecAdapter,
        timeoutUs: Long,
        carry: PcmCarryBuffer,
        pendingSource: ByteBuffer?,
        shouldQueueData: Boolean,
        basePtsUs: Long,
        sampleRate: Int,
        framesFed: Long,
        onFramesQueued: (Long) -> Unit,
        onEosQueued: () -> Unit
    ): Boolean {
        val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
        if (inputIndex < 0) return false
        val inputBuffer = encoder.getInputBuffer(inputIndex)
        val nextPtsUs = basePtsUs + pcmFramesToUs(framesFed, sampleRate)
        if (!shouldQueueData || inputBuffer == null) {
            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                nextPtsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            onEosQueued()
        } else {
            val writtenBytes = carry.fillEncoderBuffer(pendingSource, inputBuffer)
            if (writtenBytes <= 0) {
                throw java.io.IOException("AAC encoder input buffer smaller than one PCM frame")
            }
            encoder.queueInputBuffer(inputIndex, 0, writtenBytes, nextPtsUs, 0)
            onFramesQueued((writtenBytes / carry.bytesPerFrame).toLong())
        }
        return true
    }

    private inline fun drainAacEncoderOutput(
        encoder: AudioCodecAdapter,
        info: CodecOutputInfo,
        timeoutUs: Long,
        packets: MutableList<EncodedAudioPacket>,
        clipStartUs: Long,
        onFormatChanged: (MediaFormat) -> Unit,
        onOutputDone: () -> Unit
    ): Boolean {
        return when (val outputIndex = encoder.dequeueOutputBuffer(info, timeoutUs)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                onFormatChanged(encoder.outputFormat)
                true
            }
            @Suppress("DEPRECATION")
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> true
            MediaCodec.INFO_TRY_AGAIN_LATER -> false
            else -> {
                // Codec-config packets are delivered to the muxer via the
                // track format (CSD), not as sample data.
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        if (packets.size >= MAX_MUXED_SAMPLES) {
                            encoder.releaseOutputBuffer(outputIndex)
                            throw RemuxBudgetExceededException(
                                clipStartUs = clipStartUs,
                                message = "AAC encoder exceeded packet budget " +
                                    "(${packets.size + 1}/$MAX_MUXED_SAMPLES)"
                            )
                        }
                        val data = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        outputBuffer.get(data)
                        packets.add(
                            EncodedAudioPacket(
                                data = data,
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags
                            )
                        )
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    onOutputDone()
                }
                encoder.releaseOutputBuffer(outputIndex)
                true
            }
        }
    }
    /**
     * Retrieve the video duration in microseconds, or -1 if unavailable.
     */
    private fun getVideoDurationUs(videoUri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return -1L
            durationMs * 1000L
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to retrieve video duration", e)
            -1L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release retriever", e)
            }
        }
    }

    /**
     * Inject Google Motion Photo XMP metadata into JPEG bytes.
     *
     * Inserts an XMP APP1 segment immediately after the JPEG SOI marker (0xFF 0xD8)
     * with GCamera namespace fields and container item descriptors.
     */
    internal fun injectMotionPhotoXmp(
        jpegBytes: ByteArray,
        videoLength: Long,
        presentationTimestampUs: Long
    ): ByteArray {
        val serializer = android.util.Xml.newSerializer()
        val writer = java.io.StringWriter()
        serializer.setOutput(writer)

        val nsXmp = "adobe:ns:meta/"
        val nsRdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val nsGCamera = "http://ns.google.com/photos/1.0/camera/"
        val nsContainer = "http://ns.google.com/photos/1.0/container/"
        val nsItem = "http://ns.google.com/photos/1.0/container/item/"

        serializer.processingInstruction("xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'")

        serializer.setPrefix("x", nsXmp)
        serializer.startTag(nsXmp, "xmpmeta")

        serializer.setPrefix("rdf", nsRdf)
        serializer.startTag(nsRdf, "RDF")

        serializer.setPrefix("GCamera", nsGCamera)
        serializer.setPrefix("Container", nsContainer)
        serializer.setPrefix("Item", nsItem)
        serializer.startTag(nsRdf, "Description")
        serializer.attribute(nsRdf, "about", "")

        serializer.attribute(nsGCamera, "MotionPhoto", "1")
        serializer.attribute(nsGCamera, "MotionPhotoVersion", "1")
        serializer.attribute(nsGCamera, "MotionPhotoPresentationTimestampUs", presentationTimestampUs.toString())

        serializer.startTag(nsContainer, "Directory")
        serializer.startTag(nsRdf, "Seq")

        // Primary image item
        serializer.startTag(nsRdf, "li")
        serializer.attribute(nsRdf, "parseType", "Resource")
        serializer.startTag(nsContainer, "Item")
        serializer.attribute(nsItem, "Mime", "image/jpeg")
        serializer.attribute(nsItem, "Semantic", "Primary")
        serializer.attribute(nsItem, "Padding", "0")
        serializer.endTag(nsContainer, "Item")
        serializer.endTag(nsRdf, "li")

        // Motion photo video item
        serializer.startTag(nsRdf, "li")
        serializer.attribute(nsRdf, "parseType", "Resource")
        serializer.startTag(nsContainer, "Item")
        serializer.attribute(nsItem, "Mime", "video/mp4")
        serializer.attribute(nsItem, "Semantic", "MotionPhoto")
        serializer.attribute(nsItem, "Length", videoLength.toString())
        serializer.attribute(nsItem, "Padding", "0")
        serializer.endTag(nsContainer, "Item")
        serializer.endTag(nsRdf, "li")

        serializer.endTag(nsRdf, "Seq")
        serializer.endTag(nsContainer, "Directory")

        serializer.endTag(nsRdf, "Description")
        serializer.endTag(nsRdf, "RDF")
        serializer.endTag(nsXmp, "xmpmeta")

        serializer.processingInstruction("xpacket end='w'")
        serializer.flush()

        val xmpPayload = writer.toString()
        val xmpBytes = xmpPayload.toByteArray(Charsets.UTF_8)

        val xmpHeader = XMP_NAMESPACE_URI.toByteArray(Charsets.UTF_8)
        val segmentData = xmpHeader + xmpBytes
        val segmentLength = segmentData.size + 2 // +2 for the length field itself

        if (segmentLength > 0xFFFF) {
            throw IllegalArgumentException("XMP segment too large: $segmentLength bytes (max 65535)")
        }

        // Build the new JPEG: SOI + APP1(XMP) + rest of original JPEG (after SOI)
        val result = ByteArrayOutputStream(jpegBytes.size + segmentLength + 4)

        // Validate JPEG SOI marker
        if (jpegBytes.size < 2 ||
            (jpegBytes[0].toInt() and 0xFF) != 0xFF ||
            (jpegBytes[1].toInt() and 0xFF) != 0xD8
        ) {
            throw IllegalArgumentException("Invalid JPEG data: Missing SOI marker")
        }

        // SOI marker
        result.write(0xFF)
        result.write(0xD8)
        // APP1 marker
        result.write(0xFF)
        result.write(0xE1)
        // Segment length (big-endian)
        result.write((segmentLength shr 8) and 0xFF)
        result.write(segmentLength and 0xFF)
        // Segment data
        result.write(segmentData)
        // Original JPEG data after SOI (skip first 2 bytes: 0xFF 0xD8)
        result.write(jpegBytes, 2, jpegBytes.size - 2)

        return result.toByteArray()
    }

    /**
     * Save motion photo to MediaStore using streaming to avoid OOM.
     */
    internal fun saveMotionPhotoToMediaStore(
        jpegBytes: ByteArray,
        videoFile: java.io.File,
        fileName: String,
        config: ExportConfig,
        dateTakenMs: Long? = null
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, ExportFormat.JPEG.mimeType)
            dateTakenMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, config.exportDirectory.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, resolveLegacyOutputFile(fileName, config.exportDirectory).absolutePath)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw java.io.IOException("Failed to create MediaStore entry — storage may be full or unavailable")

        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for MediaStore entry")
            outputStream.use { os ->
                os.write(jpegBytes)

                if (videoFile.exists() && videoFile.length() > 0) {
                    java.io.FileInputStream(videoFile).use { inputStream ->
                        inputStream.copyTo(os, 65536)
                    }
                }
            }

            if (sdkInt >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }

            return uri
        } catch (e: Exception) {
            try { context.contentResolver.delete(uri, null, null) } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete URI after export failure", deleteException) }
            throw e
        }
    }

    /**
     * Save static bitmap to MediaStore in a single streaming pass for JPEG (splicing any
     * prebuilt EXIF APP1 segment before clearing IS_PENDING), with fallback metadata write
     * for non-JPEG formats prior to publishing the entry.
     */
    private fun saveStaticToMediaStore(
        bitmap: Bitmap,
        fileName: String,
        config: ExportConfig,
        dateTakenMs: Long? = null,
        metadata: VideoMetadata? = null,
        prebuiltApp1Segment: ByteArray? = null,
        checkCancellation: () -> Unit = {}
    ): StaticSaveOutcome {
        checkCancellation()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, config.format.mimeType)
            dateTakenMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, config.exportDirectory.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, resolveLegacyOutputFile(fileName, config.exportDirectory).absolutePath)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw java.io.IOException("Failed to create MediaStore entry — storage may be full or unavailable")

        try {
            checkCancellation()
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for MediaStore entry")
            val splicingStream = JpegApp1SplicingOutputStream(outputStream, prebuiltApp1Segment)
            splicingStream.use {
                checkCancellation()
                val compressFormat = config.format.toCompressFormat(config.quality, sdkInt)
                if (!bitmap.compress(compressFormat, config.quality, it)) {
                    throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                }
            }
            checkCancellation()

            var finalFileSize = splicingStream.bytesWritten
            var metadataPreserved = splicingStream.app1Injected

            if (metadata != null && config.preserveMetadata && config.format != ExportFormat.JPEG) {
                metadataPreserved = writeMetadataToUri(uri, metadata)
                checkCancellation()
                val queriedSize = getFileSize(uri)
                if (queriedSize > 0L || finalFileSize == 0L) {
                    finalFileSize = queriedSize
                }
            }

            checkCancellation()
            // Mark as not pending only after both image payload and EXIF metadata are persisted
            if (sdkInt >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }

            return StaticSaveOutcome(
                uri = uri,
                fileSizeBytes = finalFileSize,
                metadataPreserved = metadataPreserved
            )
        } catch (e: Exception) {
            try { context.contentResolver.delete(uri, null, null) } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete URI after export failure", deleteException) }
            throw e
        }
    }

    /**
     * Save bitmap to a custom directory using SAF (DocumentFile).
     * Retained with its exact 4-parameter signature for reflection-based security tests.
     */
    @Suppress("unused")
    private fun saveToCustomDirectory(
        bitmap: Bitmap,
        fileName: String,
        config: ExportConfig,
        treeUri: Uri
    ): Uri = saveStaticToCustomDirectory(
        bitmap = bitmap,
        fileName = fileName,
        config = config,
        treeUri = treeUri,
        metadata = null,
        prebuiltApp1Segment = null
    ).uri

    private fun saveStaticToCustomDirectory(
        bitmap: Bitmap,
        fileName: String,
        config: ExportConfig,
        treeUri: Uri,
        metadata: VideoMetadata?,
        prebuiltApp1Segment: ByteArray?,
        checkCancellation: () -> Unit = {}
    ): StaticSaveOutcome {
        checkCancellation()
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.IOException("Failed to access custom directory — permission may have been revoked")
        val docFile = tree.createFile(config.format.mimeType, fileName)
            ?: throw java.io.IOException("Failed to create file in custom directory")
        val uri = validateCustomDocumentUri(docFile.uri, treeUri)
        try {
            checkCancellation()
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for custom directory")
            val splicingStream = JpegApp1SplicingOutputStream(outputStream, prebuiltApp1Segment)
            splicingStream.use {
                checkCancellation()
                val compressFormat = config.format.toCompressFormat(config.quality, sdkInt)
                if (!bitmap.compress(compressFormat, config.quality, it)) {
                    throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                }
            }
            checkCancellation()

            var finalFileSize = splicingStream.bytesWritten
            var metadataPreserved = splicingStream.app1Injected

            if (metadata != null && config.preserveMetadata && config.format != ExportFormat.JPEG) {
                metadataPreserved = writeMetadataToUri(uri, metadata)
                checkCancellation()
                val queriedSize = getFileSize(uri)
                if (queriedSize > 0L || finalFileSize == 0L) {
                    finalFileSize = queriedSize
                }
            }

            checkCancellation()
            return StaticSaveOutcome(
                uri = uri,
                fileSizeBytes = finalFileSize,
                metadataPreserved = metadataPreserved
            )
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete file after export failure", deleteException) }
            throw e
        }
    }

    /**
     * Save motion photo to a custom directory using SAF (DocumentFile).
     */
    private fun saveMotionPhotoToCustomDirectory(
        jpegBytes: ByteArray,
        videoFile: java.io.File,
        fileName: String,
        treeUri: Uri
    ): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.IOException("Failed to access custom directory — permission may have been revoked")
        val docFile = tree.createFile(ExportFormat.JPEG.mimeType, fileName)
            ?: throw java.io.IOException("Failed to create file in custom directory")
        val uri = validateCustomDocumentUri(docFile.uri, treeUri)
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for custom directory")
            outputStream.use { os ->
                os.write(jpegBytes)
                if (videoFile.exists() && videoFile.length() > 0) {
                    java.io.FileInputStream(videoFile).use { inputStream ->
                        inputStream.copyTo(os, 65536)
                    }
                }
            }
            return uri
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete file after export failure", deleteException) }
            throw e
        }
    }

    private fun validateCustomDocumentUri(uri: Uri, treeUri: Uri): Uri {
        // A provider can return an arbitrary URI from createDocument, including file://.
        // Validate before opening it or attempting cleanup with the app's permissions.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
            uri.authority != treeUri.authority ||
            !DocumentsContract.isDocumentUri(context, uri) ||
            !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(treeUri)
        ) {
            throw java.io.IOException("Custom directory provider returned an invalid document URI")
        }
        return uri
    }

    /**
     * Write metadata to an image stored via MediaStore or SAF.
     */
    private fun writeMetadataToUri(uri: Uri, metadata: VideoMetadata): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                MetadataWriter.writeExifData(exif, metadata)
                exif.saveAttributes()
                true
            } ?: false
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to write metadata to URI", e)
            false
        }
    }

    /**
     * Prebuild a complete JPEG EXIF APP1 segment (starting with 0xFF 0xE1) using AndroidX
     * ExifInterface on a 28-byte minimal valid JPEG stub in [Context.getCacheDir].
     */
    internal fun buildJpegExifApp1Segment(
        metadata: VideoMetadata,
        width: Int,
        height: Int
    ): ByteArray? {
        var stubFile: java.io.File? = null
        return try {
            stubFile = java.io.File.createTempFile("exif_stub_", ".jpg", context.cacheDir)
            stubFile.writeBytes(createMinimalJpegStub(width, height))
            val exif = androidx.exifinterface.media.ExifInterface(stubFile.absolutePath)
            MetadataWriter.writeExifData(exif, metadata)
            exif.saveAttributes()
            extractApp1Segment(stubFile.readBytes())
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to prebuild JPEG EXIF APP1 segment", e)
            null
        } finally {
            stubFile?.delete()
        }
    }

    /**
     * Create a 28-byte minimal valid JPEG containing SOI (2B), SOF0 with target dimensions (13B),
     * minimal SOS + 1 scan byte (11B), and EOI (2B).
     */
    internal fun createMinimalJpegStub(width: Int, height: Int): ByteArray {
        val w = width.coerceIn(1, 0xFFFF)
        val h = height.coerceIn(1, 0xFFFF)
        return byteArrayOf(
            // SOI (2 bytes)
            0xFF.toByte(), 0xD8.toByte(),
            // SOF0 (13 bytes): marker + length(11) + precision(8) + height + width + 1 component
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            ((h shr 8) and 0xFF).toByte(), (h and 0xFF).toByte(),
            ((w shr 8) and 0xFF).toByte(), (w and 0xFF).toByte(),
            0x01,
            0x01, 0x11, 0x00,
            // Minimal SOS (11 bytes): marker + length(8) + 1 component + spectral(0..63) + 1 scan byte
            0xFF.toByte(), 0xDA.toByte(),
            0x00, 0x08,
            0x01,
            0x01, 0x00,
            0x00, 0x3F, 0x00,
            0x00,
            // EOI (2 bytes)
            0xFF.toByte(), 0xD9.toByte()
        )
    }

    /**
     * Slice the complete 0xFF 0xE1 APP1 EXIF segment from offset 2 of an ExifInterface-saved JPEG.
     */
    internal fun extractApp1Segment(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.size < 6 ||
            (jpegBytes[0].toInt() and 0xFF) != 0xFF ||
            (jpegBytes[1].toInt() and 0xFF) != 0xD8 ||
            (jpegBytes[2].toInt() and 0xFF) != 0xFF ||
            (jpegBytes[3].toInt() and 0xFF) != 0xE1
        ) {
            throw java.io.IOException("Missing SOI or APP1 marker in EXIF stub output")
        }
        val segmentLength = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
        if (segmentLength < 2) {
            throw java.io.IOException("Invalid APP1 segment length: $segmentLength")
        }
        val app1EndExclusive = 4 + segmentLength
        if (app1EndExclusive > jpegBytes.size) {
            throw java.io.IOException("Truncated APP1 segment: end=$app1EndExclusive, size=${jpegBytes.size}")
        }
        return jpegBytes.copyOfRange(2, app1EndExclusive)
    }

    /**
     * Write EXIF metadata into JPEG bytes via a temp file and return the enriched bytes.
     *
     * This must be used for motion photos because ExifInterface.saveAttributes() rewrites
     * the JPEG structure. If called on the final combined file (JPEG + appended MP4), the
     * appended video data would be stripped, breaking the motion photo.
     */
    private fun writeExifToJpegBytes(jpegBytes: ByteArray, metadata: VideoMetadata): Pair<ByteArray, Boolean> {
        val tempFile = java.io.File.createTempFile("exif_", ".jpg", context.cacheDir)
        try {
            tempFile.writeBytes(jpegBytes)
            val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
            MetadataWriter.writeExifData(exif, metadata)
            exif.saveAttributes()
            return Pair(tempFile.readBytes(), true)
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to write EXIF to JPEG bytes", e)
            return Pair(jpegBytes, false) // Return original bytes on failure
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Scale bitmap if max resolution is specified.
     */
    private fun scaleBitmap(bitmap: Bitmap, maxResolution: Int?): Bitmap {
        if (maxResolution == null) return bitmap

        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= maxResolution) return bitmap

        val scale = maxResolution.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        return bitmap.scale(newWidth, newHeight, true)
    }

    /**
     * Generate a filename for the exported image.
     */
    private fun generateFileName(
        frame: CapturedFrame,
        config: ExportConfig,
        isMotion: Boolean = false
    ): String {
        val extension = if (isMotion) ExportFormat.JPEG.extension else config.format.extension
        val timestamp = System.currentTimeMillis()
        val timeStr = frame.timestampUs / 1000 // Convert to ms

        config.customFileName?.takeIf { it.isNotBlank() }?.let { custom ->
            val baseName = sanitizeFileName(custom).substringBeforeLast('.')
                .ifBlank { DEFAULT_CUSTOM_FILENAME }
            return "${baseName}_${timestamp}_${timeStr}.${extension}"
        }

        val prefix = if (isMotion) "MVIMG" else "IMG"
        return "${prefix}_${timestamp}_${timeStr}.${extension}"
    }

    internal fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[\\p{Cc}\\p{Cn}\\p{Cf}]"), "_") // Strip control, unassigned, and format characters (including BIDI/RTL overrides)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")          // Replace illegal filesystem characters
            .replace(Regex("\\.\\.+"), "_")                  // Prevent path traversal sequences
            .replace(Regex("_+"), "_")                      // Collapse multiple underscores
            .trim { it == '_' || it == '.' || it.isWhitespace() }
            .ifBlank { DEFAULT_CUSTOM_FILENAME }
    }

    /**
     * Get file size from a content URI.
     */
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Resolve output path for pre-Android 10 devices where RELATIVE_PATH is unavailable.
     */
    private fun resolveLegacyOutputFile(
        fileName: String,
        exportDirectory: ExportDirectory
    ): java.io.File {
        val relativePath = exportDirectory.relativePath
        val rootSegment = relativePath.substringBefore('/')
        val childSegment = relativePath.substringAfter('/', "")
        val publicDirectory = when (rootSegment.uppercase()) {
            "DCIM" -> Environment.DIRECTORY_DCIM
            "MOVIES" -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_PICTURES
        }
        val baseDir = Environment.getExternalStoragePublicDirectory(publicDirectory)
        val targetDir = if (childSegment.isNotBlank()) {
            java.io.File(baseDir, childSegment)
        } else {
            baseDir
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw java.io.IOException("Failed to create output directory: ${targetDir.absolutePath}")
        }
        return java.io.File(targetDir, fileName)
    }

    private fun cleanupFailedOutput(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.delete(uri, null)
            } else {
                val docFile = DocumentFile.fromSingleUri(context, uri)
                docFile?.delete() ?: context.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to cleanup output file on cancellation", e)
        }
    }
}

/**
 * Lightweight streaming [OutputStream] wrapper that counts exact written bytes and splices a
 * prebuilt JPEG `APP1` segment right after an opening `0xFF 0xD8` SOI marker without buffering
 * the compressed image in memory.
 */
internal class JpegApp1SplicingOutputStream(
    private val delegate: OutputStream,
    private val app1Segment: ByteArray?
) : OutputStream() {

    companion object {
        private const val STATE_AWAIT_FIRST_BYTE = 0
        private const val STATE_AWAIT_SECOND_BYTE = 1
        private const val STATE_PASSTHROUGH = 2
        private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    }

    private var state: Int = if (app1Segment != null) STATE_AWAIT_FIRST_BYTE else STATE_PASSTHROUGH
    private var firstByte: Int = 0
    private var closed: Boolean = false

    var bytesWritten: Long = 0L
        private set

    var app1Injected: Boolean = false
        private set

    override fun write(b: Int) {
        when (state) {
            STATE_PASSTHROUGH -> {
                delegate.write(b)
                bytesWritten += 1L
            }
            STATE_AWAIT_FIRST_BYTE -> {
                val unsigned = b and 0xFF
                if (unsigned == 0xFF) {
                    firstByte = unsigned
                    state = STATE_AWAIT_SECOND_BYTE
                } else {
                    state = STATE_PASSTHROUGH
                    delegate.write(b)
                    bytesWritten += 1L
                }
            }
            STATE_AWAIT_SECOND_BYTE -> {
                val unsigned = b and 0xFF
                state = STATE_PASSTHROUGH
                if (unsigned == 0xD8) {
                    val app1 = app1Segment!!
                    delegate.write(JPEG_SOI)
                    bytesWritten += JPEG_SOI.size.toLong()
                    delegate.write(app1)
                    bytesWritten += app1.size.toLong()
                    app1Injected = true
                } else {
                    delegate.write(firstByte)
                    bytesWritten += 1L
                    delegate.write(b)
                    bytesWritten += 1L
                }
            }
        }
    }

    override fun write(b: ByteArray) {
        if (state == STATE_PASSTHROUGH) {
            delegate.write(b)
            bytesWritten += b.size.toLong()
            return
        }
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if ((off or len or (b.size - (len + off)) or (off + len)) < 0) {
            throw IndexOutOfBoundsException("off=$off, len=$len, size=${b.size}")
        }
        if (len == 0) return

        when (state) {
            STATE_PASSTHROUGH -> {
                writeChunk(b, off, len)
            }
            STATE_AWAIT_FIRST_BYTE -> {
                val b0 = b[off].toInt() and 0xFF
                if (b0 != 0xFF) {
                    state = STATE_PASSTHROUGH
                    writeChunk(b, off, len)
                } else if (len == 1) {
                    firstByte = b0
                    state = STATE_AWAIT_SECOND_BYTE
                } else {
                    val b1 = b[off + 1].toInt() and 0xFF
                    state = STATE_PASSTHROUGH
                    if (b1 == 0xD8) {
                        val app1 = app1Segment!!
                        delegate.write(JPEG_SOI)
                        bytesWritten += JPEG_SOI.size.toLong()
                        delegate.write(app1)
                        bytesWritten += app1.size.toLong()
                        app1Injected = true
                        val remaining = len - 2
                        if (remaining > 0) {
                            delegate.write(b, off + 2, remaining)
                            bytesWritten += remaining.toLong()
                        }
                    } else {
                        writeChunk(b, off, len)
                    }
                }
            }
            STATE_AWAIT_SECOND_BYTE -> {
                val b1 = b[off].toInt() and 0xFF
                state = STATE_PASSTHROUGH
                if (b1 == 0xD8) {
                    val app1 = app1Segment!!
                    delegate.write(JPEG_SOI)
                    bytesWritten += JPEG_SOI.size.toLong()
                    delegate.write(app1)
                    bytesWritten += app1.size.toLong()
                    app1Injected = true
                    val remaining = len - 1
                    if (remaining > 0) {
                        delegate.write(b, off + 1, remaining)
                        bytesWritten += remaining.toLong()
                    }
                } else {
                    delegate.write(firstByte)
                    bytesWritten += 1L
                    writeChunk(b, off, len)
                }
            }
        }
    }

    private fun writeChunk(b: ByteArray, off: Int, len: Int) {
        if (off == 0 && len == b.size) {
            delegate.write(b)
        } else {
            delegate.write(b, off, len)
        }
        bytesWritten += len.toLong()
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (state == STATE_AWAIT_SECOND_BYTE) {
                state = STATE_PASSTHROUGH
                delegate.write(firstByte)
                bytesWritten += 1L
            }
        } finally {
            delegate.close()
        }
    }
}

internal fun ExportFormat.toCompressFormat(
    quality: Int,
    sdkInt: Int = Build.VERSION.SDK_INT
): Bitmap.CompressFormat {
    return Bitmap.CompressFormat.JPEG
}

/**
 * Convert MediaExtractor sample flags to MediaCodec buffer flags.
 *
 * MediaExtractor.SAMPLE_FLAG_SYNC (1) maps to MediaCodec.BUFFER_FLAG_KEY_FRAME (1).
 * Other extractor-specific flags (e.g. SAMPLE_FLAG_ENCRYPTED) are filtered out
 * since they have no valid MediaCodec.BufferInfo counterpart.
 */
private fun convertSampleToCodecFlags(sampleFlags: Int): Int {
    var codecFlags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    return codecFlags
}

/**
 * Whether MediaMuxer can embed an audio track with this MIME type directly
 * into an MP4 container. Only AAC, AMR-NB and AMR-WB are supported; anything
 * else (LPCM, AC-3/E-AC-3, MP3, Opus, FLAC, ...) must be transcoded to AAC.
 */
internal fun isMuxerCompatibleAudioMime(mime: String?): Boolean = when (mime) {
    MediaFormat.MIMETYPE_AUDIO_AAC,
    MediaFormat.MIMETYPE_AUDIO_AMR_NB,
    MediaFormat.MIMETYPE_AUDIO_AMR_WB -> true
    else -> false
}

/** Convert a count of per-channel PCM frames to microseconds. */
internal fun pcmFramesToUs(frames: Long, sampleRate: Int): Long =
    if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L

/**
 * Bounded frame-aligned carry buffer (capacity <= [FrameExporter.PCM_CARRY_BUFFER_BYTES])
 * that bridges arbitrary-sized PCM chunks to AAC encoder input [ByteBuffer]s without
 * whole-clip heap buffering or dynamic resizing.
 */
internal class PcmCarryBuffer(
    val bytesPerFrame: Int,
    val capacity: Int = FrameExporter.PCM_CARRY_BUFFER_BYTES
) {
    init {
        require(bytesPerFrame > 0) { "bytesPerFrame must be positive: $bytesPerFrame" }
        require(capacity in bytesPerFrame..FrameExporter.PCM_CARRY_BUFFER_BYTES) {
            "capacity ($capacity) must be in $bytesPerFrame..${FrameExporter.PCM_CARRY_BUFFER_BYTES}"
        }
    }

    private val storage = ByteArray(capacity)
    private var readPos = 0
    private var writePos = 0

    val size: Int
        get() = writePos - readPos

    val freeCapacity: Int
        get() = capacity - size

    val availableAlignedBytes: Int
        get() {
            val current = size
            return current - (current % bytesPerFrame)
        }

    val hasAlignedFrames: Boolean
        get() = size >= bytesPerFrame

    fun appendFrom(src: ByteBuffer): Int {
        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
        val toCopy = minOf(freeCapacity, src.remaining())
        if (toCopy <= 0) return 0
        if (toCopy > capacity - writePos) {
            val remaining = writePos - readPos
            System.arraycopy(storage, readPos, storage, 0, remaining)
            readPos = 0
            writePos = remaining
        }
        src.get(storage, writePos, toCopy)
        writePos += toCopy
        return toCopy
    }

    fun drainAlignedTo(dst: ByteBuffer): Int {
        val dstAligned = dst.remaining() - (dst.remaining() % bytesPerFrame)
        if (dstAligned <= 0) return 0
        val toWrite = minOf(availableAlignedBytes, dstAligned)
        if (toWrite <= 0) return 0
        dst.put(storage, readPos, toWrite)
        readPos += toWrite
        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
        return toWrite
    }

    /**
     * Clears [dst] and fills it with frame-aligned PCM bytes from this carry buffer
     * and [src]. When the carry buffer is empty, aligned frames are transferred directly
     * from [src] to [dst] (single copy), storing only any sub-frame tail (< [bytesPerFrame])
     * into this carry buffer.
     *
     * @return number of aligned bytes written into [dst], or 0 if [dst] cannot fit one frame
     *         or fewer than [bytesPerFrame] total bytes are available.
     */
    fun fillEncoderBuffer(src: ByteBuffer?, dst: ByteBuffer): Int {
        dst.clear()
        val maxAlignedDst = dst.remaining() - (dst.remaining() % bytesPerFrame)
        if (maxAlignedDst <= 0) return 0

        var totalWritten = 0
        if (size > 0) {
            if (size < bytesPerFrame && src != null && src.hasRemaining()) {
                appendFrom(src)
            }
            totalWritten += drainAlignedTo(dst)
        }

        if (size == 0 && src != null && src.hasRemaining()) {
            val remainingAlignedDst = dst.remaining() - (dst.remaining() % bytesPerFrame)
            val srcAligned = src.remaining() - (src.remaining() % bytesPerFrame)
            val directBytes = minOf(remainingAlignedDst, srcAligned)
            if (directBytes > 0) {
                val oldLimit = src.limit()
                src.limit(src.position() + directBytes)
                dst.put(src)
                src.limit(oldLimit)
                totalWritten += directBytes
            }
            if (src.hasRemaining() && src.remaining() < bytesPerFrame) {
                appendFrom(src)
            }
        }
        return totalWritten
    }
}

internal class CodecOutputInfo {
    var offset: Int = 0
    var size: Int = 0
    var presentationTimeUs: Long = 0L
    var flags: Int = 0
}

internal interface AudioSampleSource {
    val sampleTrackIndex: Int
    val sampleTimeUs: Long
    val sampleSizeHint: Long
    fun readSampleData(buffer: ByteBuffer): Int
    fun advance(): Boolean
}

internal class MediaExtractorAudioSampleSource(
    private val extractor: MediaExtractor,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : AudioSampleSource {
    override val sampleTrackIndex: Int
        get() = extractor.sampleTrackIndex

    override val sampleTimeUs: Long
        get() = extractor.sampleTime

    @get:SuppressLint("NewApi")
    override val sampleSizeHint: Long
        get() = if (sdkInt >= Build.VERSION_CODES.P) extractor.sampleSize else -1L

    override fun readSampleData(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)

    override fun advance(): Boolean = extractor.advance()
}

internal interface AudioCodecAdapter {
    val outputFormat: MediaFormat
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int)
    fun stopAndRelease()
}

private class MediaCodecAdapter(
    private val codec: MediaCodec,
    private val context: Context,
    private val label: String
) : AudioCodecAdapter {
    private val nativeBufferInfo = MediaCodec.BufferInfo()

    override val outputFormat: MediaFormat
        get() = codec.outputFormat

    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
        val index = codec.dequeueOutputBuffer(nativeBufferInfo, timeoutUs)
        if (index >= 0) {
            info.offset = nativeBufferInfo.offset
            info.size = nativeBufferInfo.size
            info.presentationTimeUs = nativeBufferInfo.presentationTimeUs
            info.flags = nativeBufferInfo.flags
        }
        return index
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun releaseOutputBuffer(index: Int) {
        codec.releaseOutputBuffer(index, false)
    }

    override fun stopAndRelease() {
        try {
            codec.stop()
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to stop $label", e)
        }
        try {
            codec.release()
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to release $label", e)
        }
    }
}
