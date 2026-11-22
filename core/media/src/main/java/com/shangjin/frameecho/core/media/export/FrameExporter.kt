package com.shangjin.frameecho.core.media.export

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
import android.os.ParcelFileDescriptor
import com.shangjin.frameecho.core.media.utils.getIntegerOrNullSafe
import com.shangjin.frameecho.core.media.utils.getIntegerSafe
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
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val mediaHooks: MediaHooks = MediaHooks()
) {

    constructor(context: Context) : this(context, Build.VERSION.SDK_INT, MediaHooks())

    internal constructor(context: Context, mediaHooks: MediaHooks) : this(
        context = context,
        sdkInt = Build.VERSION.SDK_INT,
        mediaHooks = mediaHooks
    )

    private data class StaticSaveOutcome(
        val uri: Uri,
        val fileSizeBytes: Long,
        val metadataPreserved: Boolean
    )

    internal class MediaHooks(
        val createExtractor: () -> MediaExtractor = { MediaExtractor() },
        val createMuxer: (String, Int) -> MediaMuxer = { path, format -> MediaMuxer(path, format) },
        val createDecoder: (String) -> MediaCodec = { mime -> MediaCodec.createDecoderByType(mime) },
        val createEncoder: (String) -> MediaCodec = { mime -> MediaCodec.createEncoderByType(mime) },
        val createBufferInfo: () -> MediaCodec.BufferInfo = { MediaCodec.BufferInfo() },
        val createAacFormat: (Int, Int) -> MediaFormat = { sampleRate, channelCount ->
            MediaFormat.createAudioFormat(
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
        }
    )

    companion object {
        private const val XMP_NAMESPACE_URI = "http://ns.adobe.com/xap/1.0/\u0000"
        private const val DEFAULT_CUSTOM_FILENAME = "FrameEcho"

        // AAC transcoding for audio codecs MediaMuxer cannot embed in MP4
        // (LPCM from Sony/Canon cameras, AC-3/E-AC-3, MP3, Opus, FLAC, ...).
        internal const val AAC_BIT_RATE_PER_CHANNEL = 96_000
        internal const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
        /** Max consecutive rounds with no codec progress before giving up (~6 s). */
        internal const val MAX_CODEC_IDLE_ROUNDS = 600
        /** Fixed upper bound (16 KiB) for the frame-aligned streaming PCM carry buffer. */
        internal const val PCM_CARRY_BUFFER_BYTES = 16 * 1024
        internal const val MAX_PCM_BUFFER_BYTES = 15 * 1024 * 1024
        private const val DEFAULT_SAMPLE_BUFFER_BYTES = 1024 * 1024

        // Motion photo remux bounds to prevent sparse-keyframe lookback or
        // sample/chunk amplification from writing unbounded data (CWE-770).
        internal const val MAX_KEYFRAME_LOOKBACK_US = 10_000_000L
        internal const val MAX_AUDIO_SEEK_LOOKBACK_US = 2_000_000L
        internal const val MAX_MUXED_CLIP_BYTES = 128L * 1024 * 1024
        internal const val MAX_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024
        internal const val MAX_MUXED_SAMPLES = 6_000
        internal const val MAX_AUDIO_SOURCE_SAMPLES = 6_000

        private val CSD_KEYS = arrayOf("csd-0", "csd-1", "csd-2")
        private val OPTIONAL_INT_KEYS = arrayOf(
            MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_MAX_INPUT_SIZE
        )
        private val VALID_ROTATIONS = intArrayOf(0, 90, 180, 270)
    }

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
        try {
            // Ensure we have a software bitmap (HARDWARE bitmaps can't be compressed)
            softBitmap = ensureSoftwareBitmap(bitmap)

            // Apply HDR tone mapping if needed
            processedBitmap = HdrToneMapper.process(
                bitmap = softBitmap,
                colorSpaceInfo = frame.colorSpace
            )

            // Scale if needed
            finalBitmap = scaleBitmap(processedBitmap, config.maxResolution)

            val resultWidth = finalBitmap.width
            val resultHeight = finalBitmap.height

            // Generate output file
            val fileName = generateFileName(frame, config)

            // Prebuild standard EXIF APP1 segment from a 28-byte minimal JPEG stub before opening
            // the destination stream so JPEG static frames are written once with zero rewrite IPC.
            val prebuiltApp1Segment = if (config.preserveMetadata && config.format == ExportFormat.JPEG) {
                buildJpegExifApp1Segment(frame.metadata, resultWidth, resultHeight)
            } else {
                null
            }

            // Save to MediaStore or custom directory (scoped storage compatible)
            val dateTakenMs = frame.metadata.dateTime?.let { DateTimeUtils.parseToMillis(it) }
            val saveOutcome = if (customExportTreeUri != null) {
                saveStaticToCustomDirectory(
                    bitmap = finalBitmap,
                    fileName = fileName,
                    config = config,
                    treeUri = customExportTreeUri,
                    metadata = frame.metadata,
                    prebuiltApp1Segment = prebuiltApp1Segment
                )
            } else {
                saveStaticToMediaStore(
                    bitmap = finalBitmap,
                    fileName = fileName,
                    config = config,
                    dateTakenMs = dateTakenMs,
                    metadata = frame.metadata,
                    prebuiltApp1Segment = prebuiltApp1Segment
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
                colorSpaceInfo = frame.colorSpace
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !original.containsKey(csdKey)) {
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
                val intVal = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !original.containsKey(key)) null else original.getInteger(key) } catch (_: Exception) { null }
                if (intVal != null) {
                    clean.setInteger(key, intVal)
                }
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to copy optional int key: $key", e)
            }
        }

        // Duration
        try {
            val duration = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !original.containsKey(MediaFormat.KEY_DURATION)) null else original.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { null }
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

    private fun getExtractorSampleSizeHint(extractor: MediaExtractor): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
        if (sampleSizeHint > remainingBudgetBytes) {
            throw RemuxBudgetExceededException(
                clipStartUs = clipStartUs,
                message = "Sample size ($sampleSizeHint) exceeds remaining remux byte budget ($remainingBudgetBytes)"
            )
        }
        if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES) {
            throw RemuxBudgetExceededException(
                clipStartUs = clipStartUs,
                message = "Sample size ($sampleSizeHint) exceeds maximum sample buffer ($MAX_SAMPLE_BUFFER_BYTES bytes)"
            )
        }
        val maxReadBytes = minOf(
            MAX_SAMPLE_BUFFER_BYTES.toLong(),
            remainingBudgetBytes
        ).toInt()
        val baseBuffer = if (sampleSizeHint > currentBuffer.capacity()) {
            ByteBuffer.allocateDirect(sampleSizeHint.toInt())
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

    private fun setExtractorDataSource(
        extractor: MediaExtractor,
        videoUri: Uri,
        sharedPfd: ParcelFileDescriptor?
    ) {
        if (sharedPfd != null) {
            try {
                extractor.setDataSource(sharedPfd.fileDescriptor)
                return
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "setDataSource(fd) failed, falling back to videoUri", e)
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
            val sharedPfd = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "openFileDescriptor failed, falling back to videoUri", e)
                null
            }
            val extractor = mediaHooks.createExtractor()
            var nativeAudioExtractor: MediaExtractor? = null
            val outputClipFile: java.io.File
            try {
                setExtractorDataSource(extractor, videoUri, sharedPfd)

                // Select only the video track for extraction to avoid multi-track
                // interleaving issues that cause seek/timestamp problems on some devices.
                var videoTrackIndex = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                var maxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoTrackIndex == -1) {
                        videoTrackIndex = i
                        videoFormat = format
                    } else if (mime.startsWith("audio/") && !muteAudio && audioTrackIndex == -1) {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                    val trackMaxInputSize = format.getIntegerOrNullSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (trackMaxInputSize != null && trackMaxInputSize > 0) {
                        maxInputSize = maxOf(
                            maxInputSize,
                            trackMaxInputSize.coerceAtMost(MAX_SAMPLE_BUFFER_BYTES)
                        )
                    }
                }

                if (videoTrackIndex == -1 || videoFormat == null) {
                    throw java.io.IOException(
                        "No video track found (total tracks: ${extractor.trackCount})"
                    )
                }

                // Seek the primary video track ONCE to resolve and validate actualStartUs
                // BEFORE audio transcode or MediaMuxer creation, keeping this positioned
                // cursor intact so Phase 1 writes samples directly without re-seeking.
                extractor.selectTrack(videoTrackIndex)
                extractor.seekTo(seekTargetUs, seekMode)
                actualStartUs = resolveAndValidateClipStartUs(
                    seekedPosition = extractor.sampleTime,
                    startUs = startUs,
                    endUs = endUs,
                    maxStartUsInclusive = maxStartUsInclusive,
                    minStartUsExclusive = minStartUsExclusive
                )

                // --- Audio preparation ---
                // MediaMuxer only accepts AAC/AMR-NB/AMR-WB into MP4. Other codecs —
                // notably LPCM from Sony XAVC cameras (ZV-1 etc.), but also
                // AC-3/E-AC-3/MP3/Opus/FLAC — make addTrack() throw. Stream-transcode
                // those tracks to AAC up front via an isolated audio MediaExtractor.
                // For compatible native audio tracks, pre-probe bounded seek availability
                // on an isolated MediaExtractor so we never register an empty audio track
                // with MediaMuxer (which Android 8's MPEG4Writer rejects at stop()).
                val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)
                var encodedAudio: EncodedAudio? = null
                if (audioFormat != null && audioTrackIndex >= 0) {
                    if (!isMuxerCompatibleAudioMime(audioMime)) {
                        val audioExtractor = mediaHooks.createExtractor()
                        try {
                            setExtractorDataSource(audioExtractor, videoUri, sharedPfd)
                            audioExtractor.selectTrack(audioTrackIndex)
                            encodedAudio = transcodeAudioToAac(audioExtractor, audioFormat, actualStartUs, endUs)
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
                            encodedAudio = null
                        } finally {
                            try {
                                audioExtractor.release()
                            } catch (e: Exception) {
                                LogUtils.w(context, "FrameExporter", "Failed to release audio extractor", e)
                            }
                        }
                    } else {
                        val candidateAudioExtractor = mediaHooks.createExtractor()
                        var keepAudioExtractor = false
                        try {
                            setExtractorDataSource(candidateAudioExtractor, videoUri, sharedPfd)
                            candidateAudioExtractor.selectTrack(audioTrackIndex)
                            if (seekAndValidateAudioTrack(candidateAudioExtractor, actualStartUs, endUs)) {
                                nativeAudioExtractor = candidateAudioExtractor
                                keepAudioExtractor = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogUtils.w(
                                context,
                                "FrameExporter",
                                "Native audio probe failed ($audioMime), skipping audio",
                                e
                            )
                        } finally {
                            if (!keepAudioExtractor) {
                                try {
                                    candidateAudioExtractor.release()
                                } catch (e: Exception) {
                                    LogUtils.w(context, "FrameExporter", "Failed to release audio extractor", e)
                                }
                            }
                        }
                    }
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
                    muxer = mediaHooks.createMuxer(
                        currentTempFile.absolutePath,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                    val muxerVideoTrack: Int = try {
                        muxer.addTrack(videoFormat)
                    } catch (e: Exception) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "addTrack failed with original format (${videoFormat.getString(MediaFormat.KEY_MIME)}), using clean format",
                            e
                        )
                        // Release the failed muxer and recreate with clean format
                        try {
                            muxer.release()
                        } catch (releaseException: Exception) {
                            LogUtils.w(context, "FrameExporter", "Failed to release failed muxer", releaseException)
                        }
                        muxer = null
                        currentTempFile.delete()
                        currentTempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
                        tempFile = currentTempFile
                        val cleanMuxer = mediaHooks.createMuxer(
                            currentTempFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        muxer = cleanMuxer
                        cleanMuxer.addTrack(createCleanVideoFormat(videoFormat))
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
                        nativeAudioExtractor != null && audioFormat != null -> try {
                            activeMuxer.addTrack(audioFormat)
                        } catch (e: Exception) {
                            LogUtils.w(context, "FrameExporter", "addTrack failed for audio, skipping audio", e)
                            -1
                        }
                        else -> -1
                    }

                    activeMuxer.start()
                    muxerStarted = true

                    // --- Phase 1: Extract video samples (reusing single-seek cursor) ---
                    val initialBufferCap = minOf(
                        maxInputSize.toLong(),
                        MAX_SAMPLE_BUFFER_BYTES.toLong(),
                        MAX_MUXED_CLIP_BYTES
                    ).toInt()
                    var buffer = ByteBuffer.allocateDirect(initialBufferCap)
                    val bufferInfo = mediaHooks.createBufferInfo()

                    var sampleCount = 0
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
                        cause: Throwable
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

                    fun readSampleIntoBoundedBuffer(sourceExtractor: MediaExtractor): Int {
                        val remainingBudgetBytes = MAX_MUXED_CLIP_BYTES - totalMuxedBytes
                        val sampleSizeHint = getExtractorSampleSizeHint(sourceExtractor)
                        buffer = ensureBoundedSampleBuffer(
                            currentBuffer = buffer,
                            sampleSizeHint = sampleSizeHint,
                            remainingBudgetBytes = remainingBudgetBytes,
                            totalSamplesSoFar = totalMuxedSamples,
                            clipStartUs = actualStartUs
                        )
                        val maxAllowedBytes = minOf(
                            MAX_SAMPLE_BUFFER_BYTES.toLong(),
                            remainingBudgetBytes
                        ).toInt()
                        var readBytes: Int
                        while (true) {
                            try {
                                readBytes = sourceExtractor.readSampleData(buffer, 0)
                                break
                            } catch (e: IllegalArgumentException) {
                                if (sampleSizeHint < 0L && buffer.capacity() < maxAllowedBytes) {
                                    val nextCapacity = minOf(
                                        maxAllowedBytes.toLong(),
                                        maxOf(buffer.capacity().toLong() * 2L, DEFAULT_SAMPLE_BUFFER_BYTES.toLong())
                                    ).toInt()
                                    buffer = ByteBuffer.allocateDirect(nextCapacity)
                                } else {
                                    throw buildSampleBufferExceededException(
                                        remainingBudgetBytes = remainingBudgetBytes,
                                        cause = e
                                    )
                                }
                            }
                        }
                        if (readBytes < 0 &&
                            buffer.capacity().toLong() == remainingBudgetBytes &&
                            remainingBudgetBytes < MAX_SAMPLE_BUFFER_BYTES
                        ) {
                            throw RemuxBudgetExceededException(
                                clipStartUs = actualStartUs,
                                message = "Sample read exceeded remaining remux byte budget ($remainingBudgetBytes)"
                            )
                        }
                        return readBytes
                    }

                    while (true) {
                        if (++sampleCount % 10 == 0) {
                            currentCoroutineContext().ensureActive()
                        }
                        val trackIndex = extractor.sampleTrackIndex
                        if (trackIndex < 0) break

                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > endUs) break

                        val sampleSize = readSampleIntoBoundedBuffer(extractor)
                        if (sampleSize < 0) break

                        recordMuxedSampleOrThrow(sampleSize)

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                        bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                        activeMuxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoSamplesWritten++

                        extractor.advance()
                    }

                    // --- Phase 2: Write audio samples if needed ---
                    if (muxerAudioTrack >= 0 && videoSamplesWritten > 0) {
                        if (encodedAudio != null) {
                            // Transcoded AAC packets were encoded before muxer.start();
                            // write them now that the muxer is running.
                            for (packet in encodedAudio.packets) {
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
                        } else if (nativeAudioExtractor != null) {
                            val activeAudioExtractor = nativeAudioExtractor
                            while (true) {
                                if (++sampleCount % 10 == 0) {
                                    currentCoroutineContext().ensureActive()
                                }
                                val trackIndex = activeAudioExtractor.sampleTrackIndex
                                if (trackIndex < 0) break

                                val sampleTime = activeAudioExtractor.sampleTime
                                if (sampleTime < 0 || sampleTime > endUs) break
                                if (actualStartUs - sampleTime > MAX_AUDIO_SEEK_LOOKBACK_US) {
                                    throw RemuxBudgetExceededException(
                                        clipStartUs = actualStartUs,
                                        message = "Audio sample timestamp moved before bounded lookback " +
                                            "(sampleTime=$sampleTime, clipStart=$actualStartUs)"
                                    )
                                }

                                val sampleSize = readSampleIntoBoundedBuffer(activeAudioExtractor)
                                if (sampleSize < 0) break

                                recordMuxedSampleOrThrow(sampleSize)

                                bufferInfo.offset = 0
                                bufferInfo.size = sampleSize
                                bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                                bufferInfo.flags = convertSampleToCodecFlags(activeAudioExtractor.sampleFlags)

                                activeMuxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                                audioSamplesWritten++
                                activeAudioExtractor.advance()
                            }
                        }
                    }

                    if (muxerStarted && videoSamplesWritten > 0) {
                        muxerStarted = false
                        activeMuxer.stop()
                    } else if (muxerStarted) {
                        muxerStarted = false
                        try { activeMuxer.stop() } catch (_: Exception) { }
                    }
                    outputClipFile = currentTempFile
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
            } finally {
                try {
                    nativeAudioExtractor?.release()
                } catch (e: Exception) {
                    LogUtils.w(context, "FrameExporter", "Failed to release audio extractor", e)
                }
                try {
                    extractor.release()
                } catch (e: Exception) {
                    LogUtils.w(context, "FrameExporter", "Failed to release extractor", e)
                }
                try {
                    sharedPfd?.close()
                } catch (e: Exception) {
                    LogUtils.w(context, "FrameExporter", "Failed to close shared file descriptor", e)
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

    /**
     * Transcode the currently selected audio track to AAC in a single streaming loop
     * so it can be embedded in the MP4 clip before [MediaMuxer.start].
     *
     * Raw 16-bit LPCM tracks (Sony XAVC, Canon, ...) and compressed tracks
     * (AC-3/E-AC-3/MP3/Opus/FLAC, ...) stream through a fixed 16 KiB frame-aligned
     * [PcmCarryBuffer] directly into the AAC [MediaCodec] encoder without buffering
     * the full clip's uncompressed PCM on the heap.
     *
     * @return the encoded audio, or null when the track cannot be transcoded —
     *         callers then export the clip without audio.
     */
    internal suspend fun transcodeAudioToAac(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): EncodedAudio? {
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
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
            // If KEY_PCM_ENCODING is missing or non-16-bit, fall back to MediaCodec decoding.
            val pcmEncoding = audioFormat.getIntegerOrNullSafe(MediaFormat.KEY_PCM_ENCODING)
            if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                return streamRawPcmToAac(
                    extractor = extractor,
                    audioFormat = audioFormat,
                    clipStartUs = clipStartUs,
                    endUs = endUs,
                    sampleRate = sampleRate,
                    channelCount = channelCount
                )
            }
        }
        return streamCompressedAudioToAac(extractor, audioFormat, clipStartUs, endUs)
    }

    private fun startAacEncoder(sampleRate: Int, channelCount: Int): MediaCodec? {
        val aacFormat = mediaHooks.createAacFormat(sampleRate, channelCount)
        val encoder = try {
            mediaHooks.createEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "No AAC encoder available, skipping audio", e)
            return null
        }
        return try {
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoder
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to start AAC encoder, skipping audio", e)
            try {
                encoder.release()
            } catch (releaseException: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release AAC encoder", releaseException)
            }
            null
        }
    }

    private fun stopAndReleaseCodec(codec: MediaCodec?, label: String) {
        if (codec == null) return
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

    private inner class AacEncodeSession(
        val encoder: MediaCodec,
        val sampleRate: Int,
        val channelCount: Int,
        val basePtsUs: Long,
        val clipStartUs: Long = 0L
    ) {
        val bytesPerFrame: Int = 2 * channelCount
        val packets = ArrayList<EncodedAudioPacket>()
        private val bufferInfo: MediaCodec.BufferInfo = mediaHooks.createBufferInfo()
        private var outputFormat: MediaFormat? = null
        var framesFed: Long = 0L
        var inputDone: Boolean = false
        var outputDone: Boolean = false

        fun drainOutput(timeoutUs: Long): Boolean {
            if (outputDone) return false
            return when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = encoder.outputFormat
                    true
                }
                @Suppress("DEPRECATION")
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> true
                MediaCodec.INFO_TRY_AGAIN_LATER -> false
                else -> {
                    if (outputIndex < 0) return false
                    // Codec-config packets are delivered via track format CSD, not sample data.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        bufferInfo.size > 0
                    ) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            if (packets.size >= MAX_MUXED_SAMPLES) {
                                encoder.releaseOutputBuffer(outputIndex, false)
                                throw RemuxBudgetExceededException(
                                    clipStartUs = clipStartUs,
                                    message = "AAC encoder exceeded packet budget " +
                                        "(${packets.size + 1}/$MAX_MUXED_SAMPLES)"
                                )
                            }
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)
                            packets.add(
                                EncodedAudioPacket(
                                    data = data,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags
                                )
                            )
                        }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    true
                }
            }
        }

        fun feedInput(
            carryBuffer: PcmCarryBuffer,
            sourceExhausted: Boolean,
            timeoutUs: Long
        ): Boolean {
            if (inputDone) return false
            val hasFrame = carryBuffer.hasCompleteFrame(bytesPerFrame)
            if (!hasFrame && !sourceExhausted) return false

            val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) return false

            val inputBuffer = encoder.getInputBuffer(inputIndex)
            val nextPtsUs = basePtsUs + pcmFramesToUs(framesFed, sampleRate)
            if (!hasFrame) {
                carryBuffer.clear()
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    nextPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                inputDone = true
                return true
            }

            if (inputBuffer == null) {
                throw java.io.IOException("AAC encoder returned a null input buffer")
            }
            inputBuffer.clear()
            val bytesWritten = carryBuffer.readFrameAlignedInto(inputBuffer, bytesPerFrame)
            if (bytesWritten <= 0) {
                throw java.io.IOException("AAC encoder input buffer smaller than one PCM frame")
            }
            encoder.queueInputBuffer(inputIndex, 0, bytesWritten, nextPtsUs, 0)
            framesFed += bytesWritten / bytesPerFrame
            return true
        }

        fun buildResult(): EncodedAudio? {
            val format = outputFormat
            if (format == null || packets.isEmpty()) {
                LogUtils.w(context, "FrameExporter", "AAC encoder produced no packets, skipping audio")
                return null
            }
            return EncodedAudio(format, packets)
        }
    }

    /**
     * Stream 16-bit raw LPCM samples from the seeked [extractor] through a bounded
     * 16 KiB [PcmCarryBuffer] directly into the AAC encoder in a single loop.
     */
    private suspend fun streamRawPcmToAac(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long,
        sampleRate: Int,
        channelCount: Int
    ): EncodedAudio? {
        if (!seekAndValidateAudioTrack(extractor, clipStartUs, endUs)) {
            LogUtils.w(context, "FrameExporter", "No PCM data in clip range, skipping audio")
            return null
        }

        val bytesPerFrame = 2 * channelCount
        val initialReadBufferSize = audioFormat
            .getIntegerSafe(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            .coerceIn(PCM_CARRY_BUFFER_BYTES, MAX_PCM_BUFFER_BYTES)
        var rawSampleBuffer = ByteBuffer.allocateDirect(initialReadBufferSize).apply {
            limit(0)
        }
        val carryBuffer = PcmCarryBuffer(PCM_CARRY_BUFFER_BYTES)
        var session: AacEncodeSession? = null
        var totalPcmBytes = 0L
        var audioSourceSamples = 0
        var rawSourceDone = false
        var idleRounds = 0
        var rounds = 0

        try {
            while (true) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                if (session != null) {
                    if (session.drainOutput(0L)) {
                        progressed = true
                    }
                    val sourceExhausted = rawSourceDone && !rawSampleBuffer.hasRemaining()
                    if (session.feedInput(carryBuffer, sourceExhausted, 0L)) {
                        progressed = true
                    }
                    if (session.outputDone) {
                        break
                    }
                }

                if (rawSampleBuffer.hasRemaining() && carryBuffer.freeSpace > 0) {
                    if (carryBuffer.writeFrom(rawSampleBuffer) > 0) {
                        progressed = true
                    }
                } else if (!rawSourceDone && !rawSampleBuffer.hasRemaining() && carryBuffer.freeSpace > 0) {
                    val trackIdx = extractor.sampleTrackIndex
                    val sampleTime = extractor.sampleTime
                    if (trackIdx < 0 || sampleTime < 0 || sampleTime > endUs) {
                        rawSourceDone = true
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
                        val sampleSizeHint = getExtractorSampleSizeHint(extractor)
                        if (sampleSizeHint > 0L) {
                            if (sampleSizeHint > MAX_PCM_BUFFER_BYTES ||
                                totalPcmBytes + sampleSizeHint > MAX_PCM_BUFFER_BYTES
                            ) {
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "PCM stream size exceeded limit (${totalPcmBytes + sampleSizeHint} > $MAX_PCM_BUFFER_BYTES), aborting read"
                                )
                                return null
                            }
                            if (sampleSizeHint.toInt() > rawSampleBuffer.capacity()) {
                                rawSampleBuffer = ByteBuffer.allocateDirect(sampleSizeHint.toInt())
                            }
                        }
                        var sampleSize: Int
                        while (true) {
                            rawSampleBuffer.clear()
                            try {
                                sampleSize = extractor.readSampleData(rawSampleBuffer, 0)
                                break
                            } catch (e: Exception) {
                                if ((e is IllegalArgumentException || e is java.nio.BufferOverflowException) &&
                                    rawSampleBuffer.capacity() < MAX_PCM_BUFFER_BYTES
                                ) {
                                    val grownCapacity = (rawSampleBuffer.capacity().toLong() * 2L)
                                        .coerceAtMost(MAX_PCM_BUFFER_BYTES.toLong())
                                        .toInt()
                                    rawSampleBuffer = ByteBuffer.allocateDirect(grownCapacity)
                                } else {
                                    throw e
                                }
                            }
                        }
                        if (sampleSize < 0) {
                            rawSampleBuffer.limit(0)
                            rawSourceDone = true
                            progressed = true
                        } else if (sampleSize == 0) {
                            rawSampleBuffer.limit(0)
                            extractor.advance()
                            progressed = true
                        } else {
                            totalPcmBytes += sampleSize
                            if (totalPcmBytes > MAX_PCM_BUFFER_BYTES) {
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "PCM stream size exceeded limit ($totalPcmBytes > $MAX_PCM_BUFFER_BYTES), aborting read"
                                )
                                return null
                            }
                            if (session == null) {
                                val basePtsUs = (sampleTime - clipStartUs).coerceAtLeast(0L)
                                val encoder = startAacEncoder(sampleRate, channelCount) ?: return null
                                session = AacEncodeSession(
                                    encoder = encoder,
                                    sampleRate = sampleRate,
                                    channelCount = channelCount,
                                    basePtsUs = basePtsUs,
                                    clipStartUs = clipStartUs
                                )
                            }
                            rawSampleBuffer.position(0)
                            rawSampleBuffer.limit(sampleSize)
                            extractor.advance()
                            carryBuffer.writeFrom(rawSampleBuffer)
                            progressed = true
                        }
                    }
                }

                val currentSession = session
                if (currentSession != null) {
                    val sourceExhausted = rawSourceDone && !rawSampleBuffer.hasRemaining()
                    if (currentSession.feedInput(carryBuffer, sourceExhausted, 0L)) {
                        progressed = true
                    }
                    if (currentSession.drainOutput(0L)) {
                        progressed = true
                    }
                    if (currentSession.outputDone) {
                        break
                    }
                }

                if (rawSourceDone &&
                    !rawSampleBuffer.hasRemaining() &&
                    !carryBuffer.hasCompleteFrame(bytesPerFrame) &&
                    (currentSession == null || currentSession.framesFed == 0L)
                ) {
                    LogUtils.w(context, "FrameExporter", "No PCM data in clip range, skipping audio")
                    return null
                }

                if (!progressed) {
                    val activeSession = session ?: break
                    val sourceExhausted = rawSourceDone && !rawSampleBuffer.hasRemaining()
                    val canFeed = !activeSession.inputDone &&
                        (carryBuffer.hasCompleteFrame(bytesPerFrame) || sourceExhausted)
                    val preferInputWait = canFeed && (activeSession.framesFed == 0L || idleRounds % 2 == 1)
                    progressed = if (preferInputWait) {
                        activeSession.feedInput(carryBuffer, sourceExhausted, CODEC_DEQUEUE_TIMEOUT_US)
                    } else {
                        activeSession.drainOutput(CODEC_DEQUEUE_TIMEOUT_US)
                    }
                    idleRounds = if (progressed) 0 else idleRounds + 1
                    if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                        throw java.io.IOException("AAC encoder stalled")
                    }
                } else {
                    idleRounds = 0
                }
            }

            return session?.buildResult()
        } finally {
            stopAndReleaseCodec(session?.encoder, "AAC encoder")
        }
    }

    private inner class CompressedDecodePipe(
        val decoder: MediaCodec,
        private val extractor: MediaExtractor,
        private val clipStartUs: Long,
        private val endUs: Long,
        val carryBuffer: PcmCarryBuffer,
        var sampleRate: Int,
        var channelCount: Int
    ) {
        private val bufferInfo: MediaCodec.BufferInfo = mediaHooks.createBufferInfo()
        var session: AacEncodeSession? = null
        var pendingOutputIndex: Int = -1
        private var pendingOutputBuffer: ByteBuffer? = null
        private var pendingOutputFlags: Int = 0
        private var audioSourceSamples: Int = 0
        var totalPcmBytes: Long = 0L
        var inputsQueued: Long = 0L
        var inputDone: Boolean = false
        var outputDone: Boolean = false

        val sourceExhausted: Boolean
            get() = outputDone && pendingOutputIndex < 0

        fun feedInput(timeoutUs: Long): Boolean {
            if (inputDone) return false
            val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) return false

            val inputBuffer = decoder.getInputBuffer(inputIndex)
            val trackIdx = extractor.sampleTrackIndex
            val sampleTime = if (trackIdx >= 0) extractor.sampleTime else -1L
            if (trackIdx < 0 || sampleTime < 0L || sampleTime > endUs) {
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                inputDone = true
                return true
            }
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
            if (inputBuffer == null) {
                throw java.io.IOException("Audio decoder returned a null input buffer")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sampleSizeHint = extractor.sampleSize
                if (sampleSizeHint > Int.MAX_VALUE ||
                    sampleSizeHint > inputBuffer.capacity().toLong()
                ) {
                    throw java.io.IOException("Audio sample too large for decoder input buffer")
                }
            }
            inputBuffer.clear()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                inputDone = true
            } else {
                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                extractor.advance()
                inputsQueued++
            }
            return true
        }

        /**
         * Drain decoded 16-bit PCM into [carryBuffer].
         * @return 1 when progress was made, 0 when try-again-later/blocked, -1 to abort transcode.
         */
        fun drainOutput(timeoutUs: Long): Int {
            if (pendingOutputIndex >= 0) {
                val pendingBuf = pendingOutputBuffer
                var copied = 0
                if (pendingBuf != null && pendingBuf.hasRemaining()) {
                    copied = carryBuffer.writeFrom(pendingBuf)
                }
                if (pendingBuf == null || !pendingBuf.hasRemaining()) {
                    val releaseIdx = pendingOutputIndex
                    pendingOutputIndex = -1
                    pendingOutputBuffer = null
                    decoder.releaseOutputBuffer(releaseIdx, false)
                    if (pendingOutputFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    return 1
                }
                return if (copied > 0) 1 else 0
            }

            if (outputDone || carryBuffer.freeSpace <= 0) return 0

            return when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder.outputFormat
                    val encoding = newFormat.getIntegerOrNullSafe(MediaFormat.KEY_PCM_ENCODING)
                    if (encoding != null && encoding != AudioFormat.ENCODING_PCM_16BIT) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "Decoder produced non-16-bit PCM ($encoding), skipping audio"
                        )
                        return -1
                    }
                    val newRate = newFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    val newChannels = newFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    val existingSession = session
                    if (existingSession != null &&
                        (newRate != existingSession.sampleRate || newChannels != existingSession.channelCount)
                    ) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "Decoder output format changed mid-stream ($newRate Hz, $newChannels ch), skipping audio"
                        )
                        return -1
                    }
                    sampleRate = newRate
                    channelCount = newChannels
                    1
                }
                @Suppress("DEPRECATION")
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> 1
                MediaCodec.INFO_TRY_AGAIN_LATER -> 0
                else -> {
                    if (outputIndex < 0) return 0
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            if (sampleRate <= 0 || channelCount !in 1..2) {
                                decoder.releaseOutputBuffer(outputIndex, false)
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "Decoded audio unusable (rate=$sampleRate, ch=$channelCount), skipping audio"
                                )
                                return -1
                            }
                            totalPcmBytes += bufferInfo.size
                            if (totalPcmBytes > MAX_PCM_BUFFER_BYTES) {
                                decoder.releaseOutputBuffer(outputIndex, false)
                                LogUtils.w(
                                    context,
                                    "FrameExporter",
                                    "Decoded PCM stream size exceeded limit ($totalPcmBytes > $MAX_PCM_BUFFER_BYTES), skipping audio"
                                )
                                return -1
                            }
                            if (session == null) {
                                val basePtsUs = (bufferInfo.presentationTimeUs - clipStartUs)
                                    .coerceAtLeast(0L)
                                val encoder = startAacEncoder(sampleRate, channelCount)
                                if (encoder == null) {
                                    decoder.releaseOutputBuffer(outputIndex, false)
                                    return -1
                                }
                                session = AacEncodeSession(
                                    encoder = encoder,
                                    sampleRate = sampleRate,
                                    channelCount = channelCount,
                                    basePtsUs = basePtsUs,
                                    clipStartUs = clipStartUs
                                )
                            }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            carryBuffer.writeFrom(outputBuffer)
                            if (outputBuffer.hasRemaining()) {
                                pendingOutputIndex = outputIndex
                                pendingOutputBuffer = outputBuffer
                                pendingOutputFlags = bufferInfo.flags
                                return 1
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (isEos) {
                        outputDone = true
                    }
                    1
                }
            }
        }

        fun releaseAll() {
            if (pendingOutputIndex >= 0) {
                try {
                    decoder.releaseOutputBuffer(pendingOutputIndex, false)
                } catch (_: Exception) {
                }
                pendingOutputIndex = -1
                pendingOutputBuffer = null
            }
            stopAndReleaseCodec(decoder, "audio decoder")
            stopAndReleaseCodec(session?.encoder, "AAC encoder")
        }
    }

    /**
     * Decode a compressed audio track (AC-3/E-AC-3/MP3/Opus/FLAC, ...) and encode to
     * AAC concurrently within a single non-blocking loop bridged by a 16 KiB [PcmCarryBuffer].
     */
    private suspend fun streamCompressedAudioToAac(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): EncodedAudio? {
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
        if (!seekAndValidateAudioTrack(extractor, clipStartUs, endUs)) {
            return null
        }
        val decoder = try {
            mediaHooks.createDecoder(mime)
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

        val pipe = CompressedDecodePipe(
            decoder = decoder,
            extractor = extractor,
            clipStartUs = clipStartUs,
            endUs = endUs,
            carryBuffer = PcmCarryBuffer(PCM_CARRY_BUFFER_BYTES),
            sampleRate = audioFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, -1),
            channelCount = audioFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, -1)
        )
        try {
            var idleRounds = 0
            var rounds = 0

            while (true) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                // 1. Drain ready AAC output & push buffered PCM frames (0L non-blocking)
                pipe.session?.let { activeSession ->
                    if (activeSession.drainOutput(0L)) {
                        progressed = true
                    }
                    if (activeSession.feedInput(pipe.carryBuffer, pipe.sourceExhausted, 0L)) {
                        progressed = true
                    }
                    if (activeSession.outputDone) {
                        return activeSession.buildResult()
                    }
                }

                // 2. Feed compressed packets into decoder (0L non-blocking)
                if (pipe.feedInput(0L)) {
                    progressed = true
                }

                // 3. Drain decoded PCM into bounded 16 KiB carry buffer (0L non-blocking)
                val drainStatus = pipe.drainOutput(0L)
                if (drainStatus < 0) return null
                if (drainStatus > 0) {
                    progressed = true
                }

                // Immediately push newly decoded PCM frames to encoder within the same 0L round
                pipe.session?.let { currentSession ->
                    if (currentSession.feedInput(pipe.carryBuffer, pipe.sourceExhausted, 0L)) {
                        progressed = true
                    }
                    if (currentSession.drainOutput(0L)) {
                        progressed = true
                    }
                    if (currentSession.outputDone) {
                        return currentSession.buildResult()
                    }
                }

                // 4. Single-wait backpressure: wait CODEC_DEQUEUE_TIMEOUT_US (10 ms) at most once
                //    when no sub-step progressed during the 0L polling pass.
                if (!progressed) {
                    val currentSession = pipe.session
                    val encoderCanFeed = currentSession != null &&
                        !currentSession.inputDone &&
                        (pipe.carryBuffer.hasCompleteFrame(currentSession.bytesPerFrame) || pipe.sourceExhausted)

                    if (encoderCanFeed) {
                        val preferEncoderInput = currentSession.framesFed == 0L || idleRounds % 2 == 1
                        progressed = if (preferEncoderInput) {
                            currentSession.feedInput(pipe.carryBuffer, pipe.sourceExhausted, CODEC_DEQUEUE_TIMEOUT_US)
                        } else {
                            currentSession.drainOutput(CODEC_DEQUEUE_TIMEOUT_US)
                        }
                    } else if (!pipe.outputDone && pipe.pendingOutputIndex < 0 && pipe.carryBuffer.freeSpace > 0) {
                        val preferDecoderInput = !pipe.inputDone &&
                            (pipe.inputsQueued == 0L || idleRounds % 2 == 1)
                        if (preferDecoderInput) {
                            progressed = pipe.feedInput(CODEC_DEQUEUE_TIMEOUT_US)
                        } else {
                            val waitStatus = pipe.drainOutput(CODEC_DEQUEUE_TIMEOUT_US)
                            if (waitStatus < 0) return null
                            progressed = waitStatus > 0
                        }
                    } else if (currentSession != null && !currentSession.outputDone) {
                        progressed = currentSession.drainOutput(CODEC_DEQUEUE_TIMEOUT_US)
                    }

                    if (pipe.session?.outputDone == true) {
                        return pipe.session?.buildResult()
                    }

                    idleRounds = if (progressed) 0 else idleRounds + 1
                    if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                        throw java.io.IOException("Audio transcode codec stalled")
                    }
                } else {
                    idleRounds = 0
                }

                val bytesPerFrame = if (pipe.channelCount in 1..2) 2 * pipe.channelCount else 0
                if (pipe.sourceExhausted &&
                    !pipe.carryBuffer.hasCompleteFrame(bytesPerFrame) &&
                    (pipe.session == null || pipe.session?.framesFed == 0L)
                ) {
                    LogUtils.w(
                        context,
                        "FrameExporter",
                        "Decoded audio unusable (rate=${pipe.sampleRate}, ch=${pipe.channelCount}), skipping audio"
                    )
                    return null
                }
            }
        } finally {
            pipe.releaseAll()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        prebuiltApp1Segment: ByteArray? = null
    ): StaticSaveOutcome {
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
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for MediaStore entry")
            val splicingStream = JpegApp1SplicingOutputStream(outputStream, prebuiltApp1Segment)
            splicingStream.use {
                val compressFormat = config.format.toCompressFormat(config.quality, sdkInt)
                if (!bitmap.compress(compressFormat, config.quality, it)) {
                    throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                }
            }

            var finalFileSize = splicingStream.bytesWritten
            var metadataPreserved = splicingStream.app1Injected

            if (metadata != null && config.preserveMetadata && config.format != ExportFormat.JPEG) {
                metadataPreserved = writeMetadataToUri(uri, metadata)
                val queriedSize = getFileSize(uri)
                if (queriedSize > 0L || finalFileSize == 0L) {
                    finalFileSize = queriedSize
                }
            }

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
        prebuiltApp1Segment: ByteArray?
    ): StaticSaveOutcome {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.IOException("Failed to access custom directory — permission may have been revoked")
        val docFile = tree.createFile(config.format.mimeType, fileName)
            ?: throw java.io.IOException("Failed to create file in custom directory")
        val uri = validateCustomDocumentUri(docFile.uri, treeUri)
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for custom directory")
            val splicingStream = JpegApp1SplicingOutputStream(outputStream, prebuiltApp1Segment)
            splicingStream.use {
                val compressFormat = config.format.toCompressFormat(config.quality, sdkInt)
                if (!bitmap.compress(compressFormat, config.quality, it)) {
                    throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                }
            }

            var finalFileSize = splicingStream.bytesWritten
            var metadataPreserved = splicingStream.app1Injected

            if (metadata != null && config.preserveMetadata && config.format != ExportFormat.JPEG) {
                metadataPreserved = writeMetadataToUri(uri, metadata)
                val queriedSize = getFileSize(uri)
                if (queriedSize > 0L || finalFileSize == 0L) {
                    finalFileSize = queriedSize
                }
            }

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
 * Bounded (≤ 16 KiB) carry buffer that transfers 16-bit PCM slices aligned to
 * `2 * channelCount` frame boundaries without accumulating full-clip PCM on the heap.
 */
internal class PcmCarryBuffer(
    capacityBytes: Int = FrameExporter.PCM_CARRY_BUFFER_BYTES
) {
    init {
        require(capacityBytes in 4..FrameExporter.PCM_CARRY_BUFFER_BYTES) {
            "Carry buffer capacity ($capacityBytes) must be in 4..${FrameExporter.PCM_CARRY_BUFFER_BYTES}"
        }
    }

    private val buffer = ByteArray(capacityBytes)
    private var readOffset = 0
    private var writeOffset = 0

    val capacity: Int
        get() = buffer.size

    val size: Int
        get() = writeOffset - readOffset

    val freeSpace: Int
        get() = buffer.size - size

    fun isEmpty(): Boolean = readOffset == writeOffset

    fun hasCompleteFrame(bytesPerFrame: Int): Boolean =
        bytesPerFrame > 0 && size >= bytesPerFrame

    fun readableFrameAlignedBytes(bytesPerFrame: Int): Int {
        if (bytesPerFrame <= 0) return 0
        val available = size
        return available - (available % bytesPerFrame)
    }

    fun writeFrom(source: ByteBuffer): Int {
        val sourceRemaining = source.remaining()
        if (sourceRemaining <= 0) return 0
        if (readOffset == writeOffset) {
            readOffset = 0
            writeOffset = 0
        }
        val toCopy = minOf(freeSpace, sourceRemaining)
        if (toCopy <= 0) return 0
        if (writeOffset + toCopy > buffer.size && readOffset > 0) {
            val unread = writeOffset - readOffset
            System.arraycopy(buffer, readOffset, buffer, 0, unread)
            readOffset = 0
            writeOffset = unread
        }
        source.get(buffer, writeOffset, toCopy)
        writeOffset += toCopy
        return toCopy
    }

    fun readFrameAlignedInto(target: ByteBuffer, bytesPerFrame: Int): Int {
        if (bytesPerFrame <= 0) return 0
        val alignedAvailable = readableFrameAlignedBytes(bytesPerFrame)
        if (alignedAvailable <= 0) return 0
        val targetRemaining = target.remaining()
        val alignedTargetCapacity = targetRemaining - (targetRemaining % bytesPerFrame)
        if (alignedTargetCapacity <= 0) return 0
        val toWrite = minOf(alignedAvailable, alignedTargetCapacity)
        target.put(buffer, readOffset, toWrite)
        readOffset += toWrite
        if (readOffset == writeOffset) {
            readOffset = 0
            writeOffset = 0
        }
        return toWrite
    }

    fun clear() {
        readOffset = 0
        writeOffset = 0
    }
}
