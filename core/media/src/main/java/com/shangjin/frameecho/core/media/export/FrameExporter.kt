package com.shangjin.frameecho.core.media.export

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import androidx.core.graphics.scale
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Exports captured frames as images with full metadata and color space handling.
 *
 * Supports:
 * - Static image export (JPEG, PNG, WebP, HEIF, AVIF)
 * - Motion photo export (Google Motion Photo format)
 * - HDR tone mapping for SDR formats
 * - Full EXIF metadata preservation
 * - MediaStore integration for gallery visibility
 */
class FrameExporter(private val context: Context) {

    companion object {
        private const val XMP_NAMESPACE_URI = "http://ns.adobe.com/xap/1.0/\u0000"
        private const val DEFAULT_CUSTOM_FILENAME = "FrameEcho"
    }

    /**
     * Resolve the effective export config by probing the same encoder stack used for export.
     *
     * HEIF/AVIF support is device-, codec-, and size-dependent. Mirroring HeifWriter/
     * AvifWriter initialization avoids false "unsupported" results when MIME-based checks
     * disagree with the actual encoder selection logic in androidx.heifwriter.
     */
    private fun resolveEffectiveConfig(config: ExportConfig, width: Int, height: Int): ExportConfig {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return when (config.format) {
                ExportFormat.HEIF, ExportFormat.AVIF -> config.copy(format = ExportFormat.JPEG)
                else -> config
            }
        }

        val isSupported = when (config.format) {
            ExportFormat.HEIF, ExportFormat.AVIF -> canInitializeNextGenWriter(
                format = config.format,
                width = width,
                height = height,
                quality = config.quality
            )
            else -> true
        }

        return if (isSupported) config else config.copy(format = ExportFormat.JPEG)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("RestrictedApi")
    private fun canInitializeNextGenWriter(
        format: ExportFormat,
        width: Int,
        height: Int,
        quality: Int
    ): Boolean {
        val suffix = when (format) {
            ExportFormat.HEIF -> ".heic"
            ExportFormat.AVIF -> ".avif"
            else -> return true
        }
        val tempFile = java.io.File.createTempFile("codec_probe_", suffix, context.cacheDir)
        return try {
            when (format) {
                ExportFormat.HEIF -> {
                    val writer = HeifWriter.Builder(
                        tempFile.absolutePath,
                        width,
                        height,
                        HeifWriter.INPUT_MODE_BITMAP
                    )
                        .setQuality(quality)
                        .setMaxImages(1)
                        .build()
                    writer.close()
                }
                ExportFormat.AVIF -> {
                    val writer = AvifWriter.Builder(
                        tempFile.absolutePath,
                        width,
                        height,
                        AvifWriter.INPUT_MODE_BITMAP
                    )
                        .setQuality(quality)
                        .setMaxImages(1)
                        .build()
                    writer.close()
                }
                else -> Unit
            }
            true
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "${format.name} writer probe failed, falling back to JPEG", e)
            false
        } finally {
            tempFile.delete()
        }
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
        try {
            // Ensure we have a software bitmap (HARDWARE bitmaps can't be compressed)
            softBitmap = ensureSoftwareBitmap(bitmap)

            // Apply HDR tone mapping if needed
            processedBitmap = HdrToneMapper.process(
                bitmap = softBitmap,
                colorSpaceInfo = frame.colorSpace,
                targetFormat = config.format,
                strategy = config.hdrToneMap
            )

            // Scale if needed
            finalBitmap = scaleBitmap(processedBitmap, config.maxResolution)

            val resultWidth = finalBitmap.width
            val resultHeight = finalBitmap.height

            // HEIF encoding is supported on API 28+ via HeifWriter (requires HEVC encoder).
            // AVIF encoding is supported on API 28+ via AvifWriter (requires AV1 encoder).
            // On API < 28 or when no hardware encoder is available, fall back to JPEG.
            val requestedFormat = config.format
            val effectiveConfig = resolveEffectiveConfig(
                config = config,
                width = resultWidth,
                height = resultHeight
            )

            // Generate output file
            val fileName = generateFileName(frame, effectiveConfig)

            // Save to MediaStore or custom directory (scoped storage compatible)
            val dateTakenMs = frame.metadata.dateTime?.let { DateTimeUtils.parseToMillis(it) }
            val outputUri = if (customExportTreeUri != null) {
                saveToCustomDirectory(finalBitmap, fileName, effectiveConfig, customExportTreeUri)
            } else {
                saveToMediaStore(finalBitmap, fileName, effectiveConfig, dateTakenMs)
            }

            // Write metadata
            if (config.preserveMetadata) {
                writeMetadataToUri(outputUri, frame.metadata)
            }

            ExportResult.Success(
                outputPath = outputUri.toString(),
                width = resultWidth,
                height = resultHeight,
                fileSizeBytes = getFileSize(outputUri),
                format = effectiveConfig.format,
                isMotionPhoto = false,
                metadataPreserved = config.preserveMetadata,
                requestedFormat = if (requestedFormat != effectiveConfig.format) requestedFormat else null
            )
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
        try {
            val requestedFormat = config.format
            val effectiveConfig = if (config.format != ExportFormat.JPEG) {
                config.copy(format = ExportFormat.JPEG)
            } else {
                config
            }
            softBitmap = ensureSoftwareBitmap(bitmap)

            processedBitmap = HdrToneMapper.process(
                bitmap = softBitmap,
                colorSpaceInfo = frame.colorSpace,
                targetFormat = effectiveConfig.format,
                strategy = config.hdrToneMap
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
            val exifJpegBytes = if (config.preserveMetadata) {
                writeExifToJpegBytes(jpegBytes, frame.metadata)
            } else {
                jpegBytes
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
                videoDurationUs = frame.metadata.durationMs * 1000L
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
                val outputUri = if (customExportTreeUri != null) {
                    saveMotionPhotoToCustomDirectory(xmpJpegBytes, videoClipFile, fileName, customExportTreeUri)
                } else {
                    saveMotionPhotoToMediaStore(xmpJpegBytes, videoClipFile, fileName, effectiveConfig, dateTakenMs)
                }

                ExportResult.Success(
                    outputPath = outputUri.toString(),
                    width = resultWidth,
                    height = resultHeight,
                    fileSizeBytes = getFileSize(outputUri),
                    format = effectiveConfig.format,
                    isMotionPhoto = true,
                    metadataPreserved = config.preserveMetadata,
                    requestedFormat = if (requestedFormat != effectiveConfig.format) requestedFormat else null
                )
            } finally {
                videoClipFile.delete()
            }
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
     */
    private data class VideoClipResult(
        val file: java.io.File,
        val actualStartUs: Long,
        val videoSamplesWritten: Int
    )

    /**
     * Extract a video clip around [centerTimestampUs] using MediaExtractor + MediaMuxer.
     *
     * The clip always starts at the nearest keyframe before the requested start time
     * to ensure the resulting MP4 is playable. Skipping the keyframe and starting at
     * a P/B-frame produces an unplayable file.
     */
    private fun extractVideoClip(
        videoUri: Uri,
        centerTimestampUs: Long,
        beforeDurationUs: Long,
        afterDurationUs: Long,
        muteAudio: Boolean = false,
        videoDurationUs: Long = -1L
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
                seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            )
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
            seekMode = MediaExtractor.SEEK_TO_CLOSEST_SYNC
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
    private fun createCleanVideoFormat(original: MediaFormat): MediaFormat {
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
        for (csdKey in arrayOf("csd-0", "csd-1", "csd-2")) {
            try {
                if (original.containsKey(csdKey)) {
                    val csd = original.getByteBuffer(csdKey)
                    if (csd != null) {
                        csd.rewind()
                        clean.setByteBuffer(csdKey, csd)
                    }
                }
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to copy CSD key: $csdKey", e)
            }
        }

        // Optional but helpful keys — copy only if present
        val intKeys = arrayOf(
            MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_MAX_INPUT_SIZE,
        )
        for (key in intKeys) {
            try {
                if (original.containsKey(key)) {
                    clean.setInteger(key, original.getInteger(key))
                }
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to copy optional int key: $key", e)
            }
        }

        // Duration
        try {
            if (original.containsKey(MediaFormat.KEY_DURATION)) {
                clean.setLong(MediaFormat.KEY_DURATION, original.getLong(MediaFormat.KEY_DURATION))
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to copy KEY_DURATION", e)
        }

        return clean
    }

    private fun extractVideoClipOnce(
        videoUri: Uri,
        startUs: Long,
        endUs: Long,
        muteAudio: Boolean,
        seekMode: Int
    ): VideoClipResult {
        var tempFile = java.io.File.createTempFile("motion_clip_", ".mp4", context.cacheDir)
        var actualStartUs = startUs
        var videoSamplesWritten = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)

            // Select only the video track for extraction to avoid multi-track
            // interleaving issues that cause seek/timestamp problems on some devices.
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            var maxInputSize = 1024 * 1024

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
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                throw java.io.IOException(
                    "No video track found (total tracks: ${extractor.trackCount})"
                )
            }

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
                muxer.addTrack(videoFormat)
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter",
                    "addTrack failed with original format (${videoFormat.getString(MediaFormat.KEY_MIME)}), using clean format", e)
                // Release the failed muxer and recreate with clean format
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
                muxer.addTrack(createCleanVideoFormat(videoFormat))
            }
            var muxerStarted = false
            try {
                val muxerAudioTrack = if (audioFormat != null) {
                    try {
                        muxer.addTrack(audioFormat)
                    } catch (e: Exception) {
                        LogUtils.w(context, "FrameExporter", "addTrack failed for audio, skipping audio", e)
                        -1
                    }
                } else -1

                muxer.start()
                muxerStarted = true

                // --- Phase 1: Extract video samples (single-track seek for accuracy) ---
                extractor.selectTrack(videoTrackIndex)
                extractor.seekTo(startUs, seekMode)

                val seekedPosition = extractor.sampleTime
                if (seekedPosition >= 0) {
                    actualStartUs = seekedPosition
                }

                var buffer = ByteBuffer.allocateDirect(maxInputSize)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break

                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) break

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val sampleSizeHint = extractor.sampleSize
                        if (sampleSizeHint > 0 &&
                            sampleSizeHint <= Int.MAX_VALUE &&
                            sampleSizeHint.toInt() > buffer.capacity()
                        ) {
                            buffer = ByteBuffer.allocateDirect(sampleSizeHint.toInt())
                        }
                    }

                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                    bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                    muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                    videoSamplesWritten++

                    extractor.advance()
                }

                // --- Phase 2: Extract audio samples if needed ---
                if (muxerAudioTrack >= 0 && audioTrackIndex >= 0 && videoSamplesWritten > 0) {
                    extractor.unselectTrack(videoTrackIndex)
                    extractor.selectTrack(audioTrackIndex)
                    extractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    while (true) {
                        val trackIndex = extractor.sampleTrackIndex
                        if (trackIndex < 0) break

                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > endUs) break

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val sampleSizeHint = extractor.sampleSize
                            if (sampleSizeHint > 0 &&
                                sampleSizeHint <= Int.MAX_VALUE &&
                                sampleSizeHint.toInt() > buffer.capacity()
                            ) {
                                buffer = ByteBuffer.allocateDirect(sampleSizeHint.toInt())
                            }
                        }

                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = (sampleTime - actualStartUs).coerceAtLeast(0L)
                        bufferInfo.flags = convertSampleToCodecFlags(extractor.sampleFlags)

                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                }

                if (muxerStarted && videoSamplesWritten > 0) {
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
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                LogUtils.w(context, "FrameExporter", "Failed to release extractor", e)
            }
        }

        return VideoClipResult(tempFile, actualStartUs, videoSamplesWritten)
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
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            os.write(buffer, 0, bytesRead)
                        }
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
     * Save bitmap to MediaStore for scoped storage compatibility.
     */
    private fun saveToMediaStore(
        bitmap: Bitmap,
        fileName: String,
        config: ExportConfig,
        dateTakenMs: Long? = null
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, config.format.mimeType)
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
            if ((config.format == ExportFormat.HEIF || config.format == ExportFormat.AVIF) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                encodeNextGenFormatToUri(bitmap, uri, config.format, config.quality)
            } else {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Failed to open output stream for MediaStore entry")
                outputStream.use {
                    val compressFormat = config.format.toCompressFormat(config.quality)
                    if (!bitmap.compress(compressFormat, config.quality, it)) {
                        throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                    }
                }
            }

            // Mark as not pending
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
     * Save bitmap to a custom directory using SAF (DocumentFile).
     */
    private fun saveToCustomDirectory(
        bitmap: Bitmap,
        fileName: String,
        config: ExportConfig,
        treeUri: Uri
    ): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.IOException("Failed to access custom directory — permission may have been revoked")
        val docFile = tree.createFile(config.format.mimeType, fileName)
            ?: throw java.io.IOException("Failed to create file in custom directory")
        val uri = docFile.uri
        try {
            if ((config.format == ExportFormat.HEIF || config.format == ExportFormat.AVIF) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                encodeNextGenFormatToUri(bitmap, uri, config.format, config.quality)
            } else {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Failed to open output stream for custom directory")
                outputStream.use {
                    val compressFormat = config.format.toCompressFormat(config.quality)
                    if (!bitmap.compress(compressFormat, config.quality, it)) {
                        throw java.io.IOException("Failed to compress bitmap (format: ${config.format}, config: ${bitmap.config})")
                    }
                }
            }
            return uri
        } catch (e: Exception) {
            try { docFile.delete() } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete file after export failure", deleteException) }
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
        val uri = docFile.uri
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for custom directory")
            outputStream.use { os ->
                os.write(jpegBytes)
                if (videoFile.exists() && videoFile.length() > 0) {
                    java.io.FileInputStream(videoFile).use { inputStream ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            os.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            return uri
        } catch (e: Exception) {
            try { docFile.delete() } catch (deleteException: Exception) { LogUtils.w(context, "FrameExporter", "Failed to delete file after export failure", deleteException) }
            throw e
        }
    }

    /**
     * Encode a bitmap as HEIF or AVIF and write the result to the given URI.
     *
     * Uses HeifWriter (HEVC) for HEIF and AvifWriter (AV1) for AVIF.
     * Both writers only support writing to a file path, so we encode to a temp file
     * and then copy the bytes to the target URI via ContentResolver.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("RestrictedApi")
    private fun encodeNextGenFormatToUri(bitmap: Bitmap, uri: Uri, format: ExportFormat, quality: Int) {
        val suffix = if (format == ExportFormat.AVIF) ".avif" else ".heif"
        val tempFile = java.io.File.createTempFile("ngimg_", suffix, context.cacheDir)
        try {
            when (format) {
                ExportFormat.HEIF -> {
                    val writer = HeifWriter.Builder(
                        tempFile.absolutePath,
                        bitmap.width,
                        bitmap.height,
                        HeifWriter.INPUT_MODE_BITMAP
                    )
                        .setQuality(quality)
                        .setMaxImages(1)
                        .build()
                    writer.start()
                    writer.addBitmap(bitmap)
                    writer.stop(0)
                    writer.close()
                }
                ExportFormat.AVIF -> {
                    val writer = AvifWriter.Builder(
                        tempFile.absolutePath,
                        bitmap.width,
                        bitmap.height,
                        AvifWriter.INPUT_MODE_BITMAP
                    )
                        .setQuality(quality)
                        .setMaxImages(1)
                        .build()
                    writer.start()
                    writer.addBitmap(bitmap)
                    writer.stop(0)
                    writer.close()
                }
                else -> throw IllegalArgumentException("Unsupported format for next-gen encoder: $format")
            }

            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Failed to open output stream for $format export")
            outputStream.use { os ->
                java.io.FileInputStream(tempFile).use { it.copyTo(os) }
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Write metadata to an image stored via MediaStore.
     */
    private fun writeMetadataToUri(uri: Uri, metadata: VideoMetadata) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                MetadataWriter.writeExifData(exif, metadata)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to write metadata to URI", e)
        }
    }

    /**
     * Write EXIF metadata into JPEG bytes via a temp file and return the enriched bytes.
     *
     * This must be used for motion photos because ExifInterface.saveAttributes() rewrites
     * the JPEG structure. If called on the final combined file (JPEG + appended MP4), the
     * appended video data would be stripped, breaking the motion photo.
     */
    private fun writeExifToJpegBytes(jpegBytes: ByteArray, metadata: VideoMetadata): ByteArray {
        val tempFile = java.io.File.createTempFile("exif_", ".jpg", context.cacheDir)
        try {
            tempFile.writeBytes(jpegBytes)
            val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
            MetadataWriter.writeExifData(exif, metadata)
            exif.saveAttributes()
            return tempFile.readBytes()
        } catch (e: Exception) {
            LogUtils.w(context, "FrameExporter", "Failed to write EXIF to JPEG bytes", e)
            return jpegBytes // Return original bytes on failure
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

    /**
     * Sanitize custom filename to prevent path traversal and illegal characters.
     */
    internal fun sanitizeFileName(fileName: String): String {
        return fileName
            // 1. Remove control characters (0x00-0x1F, 0x7F)
            .filter { it >= ' ' && it != '\u007F' }
            // 2. Replace illegal filesystem characters with underscores
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 3. Collapse multiple dots to prevent path traversal (e.g., ".." or "...")
            .replace(Regex("\\.\\.+"), "_")
            // 4. Trim leading/trailing underscores, dots, and whitespace to prevent hidden files
            //    and ensure we don't start/end with separators or traversal markers.
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
}

/**
 * Convert ExportFormat to Android's Bitmap.CompressFormat.
 *
 * HEIF/AVIF encoding is handled separately via HeifWriter/AvifWriter in
 * [FrameExporter.encodeNextGenFormatToUri] on API 28+. This function is only
 * called for formats that use Bitmap.compress. HEIF and AVIF fall back to
 * JPEG here for safety (callers must not reach this path on API 28+).
 */
internal fun ExportFormat.toCompressFormat(
    quality: Int,
    sdkInt: Int = Build.VERSION.SDK_INT
): Bitmap.CompressFormat {
    return when (this) {
        ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ExportFormat.PNG -> Bitmap.CompressFormat.PNG
        ExportFormat.WEBP -> getWebpCompressFormat(quality, sdkInt)
        ExportFormat.HEIF, ExportFormat.AVIF -> {
            Bitmap.CompressFormat.JPEG
        }
    }
}

@SuppressLint("NewApi")
private fun getWebpCompressFormat(quality: Int, sdkInt: Int): Bitmap.CompressFormat {
    return if (sdkInt >= Build.VERSION_CODES.R) {
        getWebpCompressFormatApi30(quality)
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun getWebpCompressFormatApi30(quality: Int): Bitmap.CompressFormat {
    return if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS
    else Bitmap.CompressFormat.WEBP_LOSSY
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
