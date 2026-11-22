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
import com.shangjin.frameecho.core.media.utils.getIntegerOrNullSafe
import com.shangjin.frameecho.core.media.utils.getLongOrNullSafe
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
        private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
        /** Max consecutive rounds with no codec progress before giving up (~6 s). */
        private const val MAX_CODEC_IDLE_ROUNDS = 600
        private const val AUDIO_COPY_CHUNK_BYTES = 64 * 1024
        private const val PCM_READ_BUFFER_BYTES = 256 * 1024
        internal const val MAX_PCM_BUFFER_BYTES = 15 * 1024 * 1024
        internal const val DEFAULT_SAMPLE_BUFFER_BYTES = 1024 * 1024
        internal const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024

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
                val presentationTimestampUs =
                    (frame.timestampUs - clipResult.actualStartUs).coerceAtLeast(0L)

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
    private data class VideoClipResult(
        val file: java.io.File,
        val actualStartUs: Long,
        val videoSamplesWritten: Int,
        val audioIncluded: Boolean,
        val hasAudioTrack: Boolean = false
    )

    /** One encoded AAC packet ready for [MediaMuxer.writeSampleData]. */
    private class EncodedAudioPacket(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /** AAC-encoded audio held in memory until the muxer has started. */
    private class EncodedAudio(
        val format: MediaFormat,
        val packets: List<EncodedAudioPacket>
    )

    /** 16-bit PCM audio for the clip range, ready to be AAC-encoded. */
    private class PcmAudio(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
        /** PTS of the first PCM frame, rebased against the clip start. */
        val basePtsUs: Long
    )

    /**
     * Extract a video clip around [centerTimestampUs] using MediaExtractor + MediaMuxer.
     *
     * The clip always starts at the nearest keyframe before the requested start time
     * to ensure the resulting MP4 is playable. Skipping the keyframe and starting at
     * a P/B-frame produces an unplayable file.
     */
    private suspend fun extractVideoClip(
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
        val safeBeforeDurationUs = minOf(beforeDurationUs, maxDurationUs)
        val safeAfterDurationUs = minOf(afterDurationUs, maxDurationUs)

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

        val previousSyncResult = try {
            extractVideoClipOnce(
                videoUri = videoUri,
                startUs = startUs,
                endUs = safeEndUs,
                muteAudio = muteAudio,
                seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                rotation = rotation
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "PREVIOUS_SYNC extraction failed, will retry", e)
            null
        }

        if (previousSyncResult != null && previousSyncResult.videoSamplesWritten > 0) {
            return previousSyncResult
        }
        previousSyncResult?.file?.delete()

        return extractVideoClipOnce(
            videoUri = videoUri,
            startUs = startUs,
            endUs = safeEndUs,
            muteAudio = muteAudio,
            seekMode = MediaExtractor.SEEK_TO_CLOSEST_SYNC,
            rotation = rotation
        )
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
            val duration = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !original.containsKey(MediaFormat.KEY_DURATION)) null else original.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { null }
            if (duration != null) {
                clean.setLong(MediaFormat.KEY_DURATION, duration)
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to copy KEY_DURATION", e)
        }

        return clean
    }

    /**
     * Ensure [MediaFormat.KEY_MAX_INPUT_SIZE] on [format] is positive and capped at
     * [MAX_SAMPLE_BUFFER_BYTES] before passing the format to [MediaMuxer] or [MediaCodec].
     */
    internal fun sanitizeTrackMaxInputSize(format: MediaFormat): MediaFormat {
        try {
            val maxInputSize = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ) {
                    null
                } else {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                }
            } catch (_: Exception) {
                null
            }
            if (maxInputSize != null) {
                if (maxInputSize > MAX_SAMPLE_BUFFER_BYTES) {
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_BUFFER_BYTES)
                } else if (maxInputSize <= 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    private suspend fun extractVideoClipOnce(
        videoUri: Uri,
        startUs: Long,
        endUs: Long,
        muteAudio: Boolean,
        seekMode: Int,
        rotation: Int = 0
    ): VideoClipResult {
        var tempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
        var success = false
        try {
            var actualStartUs = startUs
            var videoSamplesWritten = 0
            var audioSamplesWritten = 0
            var audioTrackIndex = -1
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, videoUri, null)

                // Select only one track at a time during extraction to avoid multi-track
                // interleaving issues that cause seek/timestamp problems on some devices.
                var videoTrackIndex = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                var videoHintMaxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES
                var audioHintMaxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoTrackIndex == -1) {
                        videoTrackIndex = i
                        videoFormat = format
                        val trackMaxInputSize = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                !format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                            ) {
                                null
                            } else {
                                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (trackMaxInputSize != null && trackMaxInputSize > 0) {
                            videoHintMaxInputSize = trackMaxInputSize.coerceIn(
                                DEFAULT_SAMPLE_BUFFER_BYTES,
                                MAX_SAMPLE_BUFFER_BYTES
                            )
                        }
                    } else if (mime.startsWith("audio/") && !muteAudio && audioTrackIndex == -1) {
                        audioTrackIndex = i
                        audioFormat = format
                        val trackMaxInputSize = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                !format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                            ) {
                                null
                            } else {
                                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            }
                        } catch (_: Exception) {
                            null
                        }
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

                // --- Audio preparation ---
                // MediaMuxer only accepts AAC/AMR-NB/AMR-WB into MP4. Other codecs —
                // notably LPCM from Sony XAVC cameras (ZV-1 etc.), but also
                // AC-3/E-AC-3/MP3/Opus/FLAC — make addTrack() throw, which used to
                // silently drop the sound. Transcode those tracks to AAC up front.
                val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)
                var encodedAudio: EncodedAudio? = null
                var canMuxCompatibleAudio = audioFormat != null &&
                    audioTrackIndex >= 0 &&
                    isMuxerCompatibleAudioMime(audioMime)

                if (audioFormat != null && audioTrackIndex >= 0 && !canMuxCompatibleAudio) {
                    // Resolve the actual clip start (keyframe) first so transcoded audio PTS
                    // can be rebased against the same origin as the video samples.
                    extractor.selectTrack(videoTrackIndex)
                    extractor.seekTo(startUs, seekMode)
                    val initialSeekedPosition = extractor.sampleTime
                    if (initialSeekedPosition >= 0) {
                        actualStartUs = initialSeekedPosition
                    }
                    extractor.unselectTrack(videoTrackIndex)

                    extractor.selectTrack(audioTrackIndex)
                    encodedAudio = try {
                        transcodeAudioToAac(extractor, audioFormat, actualStartUs, endUs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "Audio transcode failed ($audioMime), exporting without audio",
                            e
                        )
                        null
                    }
                    extractor.unselectTrack(audioTrackIndex)
                    encodedAudio?.let { sanitizeTrackMaxInputSize(it.format) }
                }

                val initialBufferCapacity = if (canMuxCompatibleAudio) {
                    maxOf(videoHintMaxInputSize, audioHintMaxInputSize)
                } else {
                    videoHintMaxInputSize
                }.coerceIn(
                    DEFAULT_SAMPLE_BUFFER_BYTES,
                    MAX_SAMPLE_BUFFER_BYTES
                )
                var buffer: ByteBuffer? = ByteBuffer.allocateDirect(initialBufferCapacity)
                val bufferInfo = MediaCodec.BufferInfo()
                var muxableVideoFormat: MediaFormat = selectedVideoFormat

                while (true) {
                    videoSamplesWritten = 0
                    audioSamplesWritten = 0
                    var retryWithoutAudio = false

                    // Some cameras (e.g. Sony ZV1 XAVC S/HS) produce MediaFormat entries
                    // with vendor-specific keys that MediaMuxer's native layer rejects.
                    // Try addTrack on the real muxer first; if it fails, recreate
                    // with a cleaned format. This avoids creating a separate test
                    // file + muxer on every export (saves I/O on the majority of devices).
                    var muxer = MediaMuxer(
                        tempFile.absolutePath,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                    val muxerVideoTrack: Int = try {
                        muxer.addTrack(muxableVideoFormat)
                    } catch (e: Exception) {
                        LogUtils.w(
                            context,
                            "FrameExporter",
                            "addTrack failed with original format (${selectedVideoFormat.getString(MediaFormat.KEY_MIME)}), using clean format",
                            e
                        )
                        try {
                            muxer.release()
                        } catch (releaseException: Exception) {
                            LogUtils.w(context, "FrameExporter", "Failed to release failed muxer", releaseException)
                        }
                        tempFile.delete()
                        tempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
                        muxer = MediaMuxer(
                            tempFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        muxableVideoFormat = createCleanVideoFormat(selectedVideoFormat)
                        muxer.addTrack(muxableVideoFormat)
                    }
                    var muxerStarted = false
                    try {
                        if (rotation in VALID_ROTATIONS) {
                            muxer.setOrientationHint(rotation)
                        }

                        val muxerAudioTrack = when {
                            encodedAudio != null -> try {
                                muxer.addTrack(encodedAudio.format)
                            } catch (e: Exception) {
                                LogUtils.w(context, "FrameExporter", "addTrack failed for transcoded AAC, skipping audio", e)
                                -1
                            }
                            audioFormat != null && canMuxCompatibleAudio -> try {
                                muxer.addTrack(audioFormat)
                            } catch (e: Exception) {
                                LogUtils.w(context, "FrameExporter", "addTrack failed for audio, skipping audio", e)
                                -1
                            }
                            else -> -1
                        }

                        muxer.start()
                        muxerStarted = true

                        // --- Phase 1: Extract video samples (single-track seek for accuracy) ---
                        extractor.selectTrack(videoTrackIndex)
                        extractor.seekTo(startUs, seekMode)

                        val seekedPosition = extractor.sampleTime
                        if (seekedPosition >= 0) {
                            actualStartUs = seekedPosition
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

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val sampleSizeHint = extractor.sampleSize
                                if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES) {
                                    throw java.io.IOException(
                                        "Video sample size ($sampleSizeHint bytes) exceeds limit ($MAX_SAMPLE_BUFFER_BYTES bytes)"
                                    )
                                }
                                val existingCapacity = requireNotNull(buffer).capacity()
                                if (sampleSizeHint > 0 && sampleSizeHint > existingCapacity) {
                                    buffer = null
                                    buffer = allocateGrownDirectBuffer(
                                        previousCapacity = existingCapacity,
                                        minCapacity = sampleSizeHint.toInt(),
                                        maxCapacity = MAX_SAMPLE_BUFFER_BYTES
                                    )
                                }
                            }

                            val currentBuffer = requireNotNull(buffer)
                            currentBuffer.clear()
                            val sampleSize = extractor.readSampleData(currentBuffer, 0)
                            if (sampleSize < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                            bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                            muxer.writeSampleData(muxerVideoTrack, currentBuffer, bufferInfo)
                            videoSamplesWritten++

                            if (!extractor.advance()) break
                        }
                        extractor.unselectTrack(videoTrackIndex)

                        // --- Phase 2: Write audio samples if needed ---
                        if (muxerAudioTrack >= 0 && videoSamplesWritten > 0) {
                            val readyEncodedAudio = encodedAudio
                            if (readyEncodedAudio != null) {
                                for (packet in readyEncodedAudio.packets) {
                                    if (++sampleCount % 10 == 0) {
                                        currentCoroutineContext().ensureActive()
                                    }
                                    bufferInfo.offset = 0
                                    bufferInfo.size = packet.data.size
                                    bufferInfo.presentationTimeUs = packet.presentationTimeUs
                                    bufferInfo.flags = packet.flags
                                    muxer.writeSampleData(
                                        muxerAudioTrack,
                                        ByteBuffer.wrap(packet.data),
                                        bufferInfo
                                    )
                                    audioSamplesWritten++
                                }
                            } else if (audioTrackIndex >= 0) {
                                extractor.selectTrack(audioTrackIndex)
                                extractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                                while (true) {
                                    if (++sampleCount % 10 == 0) {
                                        currentCoroutineContext().ensureActive()
                                    }
                                    val trackIndex = extractor.sampleTrackIndex
                                    if (trackIndex < 0) break

                                    val sampleTime = extractor.sampleTime
                                    if (sampleTime < 0 || sampleTime > endUs) break

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        val sampleSizeHint = extractor.sampleSize
                                        if (sampleSizeHint > MAX_SAMPLE_BUFFER_BYTES) {
                                            LogUtils.w(
                                                context,
                                                "FrameExporter",
                                                "Audio sample size ($sampleSizeHint bytes) exceeds limit ($MAX_SAMPLE_BUFFER_BYTES bytes), skipping audio track"
                                            )
                                            retryWithoutAudio = true
                                            break
                                        }
                                        val existingCapacity = requireNotNull(buffer).capacity()
                                        if (sampleSizeHint > 0 && sampleSizeHint > existingCapacity) {
                                            buffer = null
                                            buffer = allocateGrownDirectBuffer(
                                                previousCapacity = existingCapacity,
                                                minCapacity = sampleSizeHint.toInt(),
                                                maxCapacity = MAX_SAMPLE_BUFFER_BYTES
                                            )
                                        }
                                    }

                                    val currentBuffer = requireNotNull(buffer)
                                    currentBuffer.clear()
                                    val sampleSize = extractor.readSampleData(currentBuffer, 0)
                                    if (sampleSize < 0) break

                                    bufferInfo.offset = 0
                                    bufferInfo.size = sampleSize
                                    bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                                    bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                                    muxer.writeSampleData(muxerAudioTrack, currentBuffer, bufferInfo)
                                    audioSamplesWritten++
                                    if (!extractor.advance()) break
                                }
                                extractor.unselectTrack(audioTrackIndex)

                                if (!retryWithoutAudio && audioSamplesWritten == 0) {
                                    retryWithoutAudio = true
                                }
                            }
                        }

                        if (retryWithoutAudio) {
                            if (muxerStarted) {
                                try { muxer.stop() } catch (_: Exception) { }
                            }
                        } else if (muxerStarted && videoSamplesWritten > 0) {
                            muxer.stop()
                        } else if (muxerStarted) {
                            try { muxer.stop() } catch (_: Exception) { }
                        }
                    } finally {
                        try {
                            muxer.release()
                        } catch (e: Exception) {
                            LogUtils.w(context, "FrameExporter", "Failed to release muxer", e)
                        }
                    }

                    if (retryWithoutAudio) {
                        tempFile.delete()
                        tempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
                        encodedAudio = null
                        canMuxCompatibleAudio = false
                        continue
                    }
                    break
                }
            } finally {
                try {
                    extractor.release()
                } catch (e: Exception) {
                    LogUtils.w(context, "FrameExporter", "Failed to release extractor", e)
                }
            }

            success = true
            return VideoClipResult(
                file = tempFile,
                actualStartUs = actualStartUs,
                videoSamplesWritten = videoSamplesWritten,
                audioIncluded = audioSamplesWritten > 0,
                hasAudioTrack = audioTrackIndex >= 0
            )
        } finally {
            if (!success) {
                tempFile.delete()
            }
        }
    }

    /**
     * Transcode the currently selected audio track to AAC so it can be embedded
     * in the MP4 clip. Used for every codec [isMuxerCompatibleAudioMime] rejects.
     *
     * Stage 1 obtains 16-bit PCM for the clip range — copied directly for LPCM
     * sources (Sony XAVC, etc.) or decoded with [MediaCodec] for compressed
     * codecs (AC-3/E-AC-3/MP3/Opus/FLAC, ...). Stage 2 encodes that PCM to AAC.
     * Both stages are buffered in memory, bounded by
     * [ExportConfig.MAX_MOTION_DURATION_S] (≤ 10 s ≈ 2 MB PCM / 250 KB AAC).
     *
     * @return the encoded audio, or null when the track cannot be transcoded —
     *         callers then export the clip without audio.
     */
    private suspend fun transcodeAudioToAac(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): EncodedAudio? {
        val pcm = decodeToPcm(extractor, audioFormat, clipStartUs, endUs) ?: return null
        if (pcm.bytes.isEmpty()) {
            LogUtils.w(context, "FrameExporter", "No PCM data in clip range, skipping audio")
            return null
        }
        return encodePcmToAac(pcm)
    }

    /**
     * Obtain 16-bit PCM for [clipStartUs, endUs] from the selected audio track.
     * Raw PCM tracks are copied as-is; anything else is decoded first.
     */
    private suspend fun decodeToPcm(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): PcmAudio? {
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
        if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) {
            return decodeCompressedToPcm(extractor, audioFormat, clipStartUs, endUs)
        }

        // LPCM track (Sony XAVC S/HS, Canon, ...): samples are already raw PCM.
        val sampleRate = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) -1 else audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { -1 }
        val channelCount = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) -1 else audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { -1 }
        if (sampleRate <= 0 || channelCount !in 1..2) {
            LogUtils.w(context, "FrameExporter",
                "Unsupported PCM layout (rate=$sampleRate, ch=$channelCount), skipping audio")
            return null
        }
        // Only 16-bit PCM can feed the AAC encoder directly.
        // Requires KEY_PCM_ENCODING to be explicitly present and ENCODING_PCM_16BIT.
        // If KEY_PCM_ENCODING is missing or non-16BIT, fall back to MediaCodec decoding.
        val pcmEncoding = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) null else audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) } catch (_: Exception) { null }
        if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            return readPcmFromExtractor(extractor, clipStartUs, endUs, sampleRate, channelCount)
        }

        return decodeCompressedToPcm(extractor, audioFormat, clipStartUs, endUs)
    }

    /**
     * Copy raw PCM samples from the (selected, seeked) extractor into memory.
     */
    private suspend fun readPcmFromExtractor(
        extractor: MediaExtractor,
        clipStartUs: Long,
        endUs: Long,
        sampleRate: Int,
        channelCount: Int
    ): PcmAudio {
        val pcmStream = ByteArrayOutputStream(PCM_READ_BUFFER_BYTES)
        var buffer: ByteBuffer? = ByteBuffer.allocateDirect(PCM_READ_BUFFER_BYTES)
        val chunk = ByteArray(AUDIO_COPY_CHUNK_BYTES)
        var basePtsUs = 0L
        var firstSampleTimeUs = -1L
        var sampleCount = 0

        while (true) {
            if (++sampleCount % 10 == 0) {
                currentCoroutineContext().ensureActive()
            }
            if (extractor.sampleTrackIndex < 0) break
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0 || sampleTime > endUs) break

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sampleSizeHint = extractor.sampleSize
                if (sampleSizeHint > MAX_PCM_BUFFER_BYTES ||
                    (sampleSizeHint > 0 && pcmStream.size().toLong() + sampleSizeHint > MAX_PCM_BUFFER_BYTES)
                ) {
                    LogUtils.w(
                        context,
                        "FrameExporter",
                        "PCM sample size ($sampleSizeHint bytes, buffered=${pcmStream.size()}) exceeds limit ($MAX_PCM_BUFFER_BYTES bytes), aborting read"
                    )
                    return PcmAudio(ByteArray(0), sampleRate, channelCount, basePtsUs)
                }
                val existingCapacity = requireNotNull(buffer).capacity()
                if (sampleSizeHint > 0 && sampleSizeHint > existingCapacity) {
                    buffer = null
                    buffer = allocateGrownDirectBuffer(
                        previousCapacity = existingCapacity,
                        minCapacity = sampleSizeHint.toInt(),
                        maxCapacity = MAX_PCM_BUFFER_BYTES
                    )
                }
            }

            val currentBuffer = requireNotNull(buffer)
            currentBuffer.clear()
            val sampleSize = extractor.readSampleData(currentBuffer, 0)
            if (sampleSize < 0) break
            if (sampleSize > currentBuffer.capacity() ||
                pcmStream.size().toLong() + sampleSize > MAX_PCM_BUFFER_BYTES
            ) {
                LogUtils.w(
                    context,
                    "FrameExporter",
                    "PCM buffer size exceeded limit (${pcmStream.size().toLong() + sampleSize} > $MAX_PCM_BUFFER_BYTES), aborting read"
                )
                return PcmAudio(ByteArray(0), sampleRate, channelCount, basePtsUs)
            }

            if (firstSampleTimeUs < 0L) {
                firstSampleTimeUs = sampleTime
                basePtsUs = (sampleTime - clipStartUs).coerceAtLeast(0L)
            }
            // Android's MediaExtractor.readSampleData(buf, offset) leaves position at offset
            // and limit at offset + sampleSize; set both explicitly instead of calling flip()
            // (which would collapse limit to 0 when position is already 0 on real devices).
            currentBuffer.position(0)
            currentBuffer.limit(sampleSize)
            while (currentBuffer.hasRemaining()) {
                val n = minOf(chunk.size, currentBuffer.remaining())
                currentBuffer.get(chunk, 0, n)
                pcmStream.write(chunk, 0, n)
            }
            if (!extractor.advance()) break
        }
        return PcmAudio(pcmStream.toByteArray(), sampleRate, channelCount, basePtsUs)
    }

    /**
     * Decode a compressed audio track (AC-3/E-AC-3/MP3/Opus/FLAC, ...) to PCM.
     */
    private suspend fun decodeCompressedToPcm(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        clipStartUs: Long,
        endUs: Long
    ): PcmAudio? {
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
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

        try {
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val pcmStream = ByteArrayOutputStream(PCM_READ_BUFFER_BYTES)
            val chunk = ByteArray(AUDIO_COPY_CHUNK_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleRate = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) -1 else audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { -1 }
            var channelCount = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) -1 else audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { -1 }
            var basePtsUs = 0L
            var sawPcm = false
            var inputDone = false
            var outputDone = false
            var idleRounds = 0
            var rounds = 0

            while (!outputDone) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        progressed = true
                        val sampleTime = extractor.sampleTime
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (extractor.sampleTrackIndex < 0 || sampleTime < 0 || sampleTime > endUs) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else if (inputBuffer == null) {
                            throw java.io.IOException("Audio decoder returned a null input buffer")
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val sampleSizeHint = extractor.sampleSize
                                if (sampleSizeHint > Int.MAX_VALUE ||
                                    sampleSizeHint > inputBuffer.capacity().toLong()
                                ) {
                                    throw java.io.IOException(
                                        "Audio sample too large for decoder input buffer"
                                    )
                                }
                            }
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val newFormat = decoder.outputFormat
                        val encoding = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) null else newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) } catch (_: Exception) { null }
                        if (encoding != null && encoding != AudioFormat.ENCODING_PCM_16BIT) {
                            LogUtils.w(context, "FrameExporter",
                                "Decoder produced non-16-bit PCM ($encoding), skipping audio")
                            return null
                        }
                        sampleRate = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate else newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { sampleRate }
                        channelCount = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount else newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { channelCount }
                    }
                    @Suppress("DEPRECATION")
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        progressed = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        progressed = true
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            bufferInfo.size > 0
                        ) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                if (!sawPcm) {
                                    sawPcm = true
                                    basePtsUs = (bufferInfo.presentationTimeUs - clipStartUs)
                                        .coerceAtLeast(0L)
                                }
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                while (outputBuffer.hasRemaining()) {
                                    val n = minOf(chunk.size, outputBuffer.remaining())
                                    outputBuffer.get(chunk, 0, n)
                                    pcmStream.write(chunk, 0, n)
                                    if (pcmStream.size() > MAX_PCM_BUFFER_BYTES) {
                                        LogUtils.w(context, "FrameExporter",
                                            "Decoded PCM buffer size exceeded limit (${pcmStream.size()} > $MAX_PCM_BUFFER_BYTES), skipping audio")
                                        return null
                                    }
                                }
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
                if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                    throw java.io.IOException("Audio decoder stalled")
                }
            }

            if (!sawPcm || sampleRate <= 0 || channelCount !in 1..2) {
                LogUtils.w(context, "FrameExporter",
                    "Decoded audio unusable (rate=$sampleRate, ch=$channelCount), skipping audio")
                return null
            }
            return PcmAudio(pcmStream.toByteArray(), sampleRate, channelCount, basePtsUs)
        } finally {
            try {
                decoder.stop()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to stop audio decoder", e)
            }
            try {
                decoder.release()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release audio decoder", e)
            }
        }
    }

    /**
     * Encode 16-bit PCM to AAC-LC packets, buffered in memory. The returned
     * format is the encoder's output format (carries the CSD the muxer needs).
     */
    private suspend fun encodePcmToAac(pcm: PcmAudio): EncodedAudio? {
        val bytesPerFrame = 2 * pcm.channelCount // 16-bit samples
        val aacFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            pcm.sampleRate,
            pcm.channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE_PER_CHANNEL * pcm.channelCount)
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

        try {
            val packets = ArrayList<EncodedAudioPacket>()
            var outputFormat: MediaFormat? = null
            val bufferInfo = MediaCodec.BufferInfo()
            var inputOffset = 0
            var framesFed = 0L
            var inputDone = false
            var outputDone = false
            var idleRounds = 0
            var rounds = 0

            while (!outputDone) {
                if (++rounds % 10 == 0) {
                    currentCoroutineContext().ensureActive()
                }
                var progressed = false

                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        progressed = true
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        val nextPtsUs = pcm.basePtsUs + pcmFramesToUs(framesFed, pcm.sampleRate)
                        if (inputBuffer == null || inputOffset >= pcm.bytes.size) {
                            encoder.queueInputBuffer(
                                inputIndex, 0, 0, nextPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            var chunkSize = minOf(
                                inputBuffer.capacity(),
                                pcm.bytes.size - inputOffset
                            )
                            chunkSize -= chunkSize % bytesPerFrame
                            if (chunkSize <= 0) {
                                throw java.io.IOException(
                                    "AAC encoder input buffer smaller than one PCM frame"
                                )
                            }
                            inputBuffer.clear()
                            inputBuffer.put(pcm.bytes, inputOffset, chunkSize)
                            encoder.queueInputBuffer(inputIndex, 0, chunkSize, nextPtsUs, 0)
                            framesFed += chunkSize / bytesPerFrame
                            inputOffset += chunkSize
                        }
                    }
                }

                when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        outputFormat = encoder.outputFormat
                    }
                    @Suppress("DEPRECATION")
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        progressed = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        progressed = true
                        // Codec-config packets are delivered to the muxer via the
                        // track format (CSD), not as sample data.
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            bufferInfo.size > 0
                        ) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
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
                    }
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
                if (idleRounds > MAX_CODEC_IDLE_ROUNDS) {
                    throw java.io.IOException("AAC encoder stalled")
                }
            }

            val format = outputFormat
            if (format == null || packets.isEmpty()) {
                LogUtils.w(context, "FrameExporter", "AAC encoder produced no packets, skipping audio")
                return null
            }
            return EncodedAudio(format, packets)
        } finally {
            try {
                encoder.stop()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to stop AAC encoder", e)
            }
            try {
                encoder.release()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release AAC encoder", e)
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
