package com.shangjin.frameecho.core.media.metadata

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.shangjin.frameecho.core.media.utils.DateTimeUtils
import com.shangjin.frameecho.core.media.utils.ExifUtils
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.media.utils.getIntegerSafe
import com.shangjin.frameecho.core.model.VideoMetadata

/**
 * Extracts metadata from video files for lossless preservation in exported images.
 *
 * Uses both [MediaMetadataRetriever] and [MediaExtractor] to capture the fullest
 * possible set of original recording data:
 * - Creation date/time
 * - GPS location (latitude, longitude, altitude)
 * - Device information (make, model)
 * - Camera settings (ISO, exposure time, f-number, focal length)
 * - Video technical details (resolution, frame rate, bitrate, codec, duration, rotation)
 */
object MetadataExtractor {

    private const val TAG = "MetadataExtractor"

    internal val KEYS_ISO = arrayOf("iso", "iso-speed", "iso_speed", "com.android.iso")
    internal val KEYS_EXPOSURE_TIME = arrayOf(
        "exposure-time",
        "exposure_time",
        "shutter-speed",
        "shutter_speed",
        "com.android.exposure-time"
    )
    internal val KEYS_F_NUMBER = arrayOf(
        "f-number",
        "f_number",
        "aperture",
        "com.android.aperture"
    )
    internal val KEYS_FOCAL_LENGTH = arrayOf(
        "focal-length",
        "focal_length",
        "focal-length-mm",
        "com.android.focal-length"
    )

    /**
     * Extract all available metadata from a video file.
     *
     * @param context Android context
     * @param videoUri URI of the video file
     * @return VideoMetadata with all available fields populated
     */
    fun extract(context: Context, videoUri: Uri): VideoMetadata {
        return extract(context, videoUri, null, null)
    }

    /**
     * Optimized extract method that allows reusing existing MediaMetadataRetriever and MediaFormat.
     *
     * @param context Android context
     * @param videoUri URI of the video file
     * @param retriever Optional existing MediaMetadataRetriever (must be setDataSource'd)
     * @param mediaFormat Optional existing MediaFormat
     * @return VideoMetadata with all available fields populated
     */
    fun extract(
        context: Context,
        videoUri: Uri,
        retriever: MediaMetadataRetriever? = null,
        mediaFormat: MediaFormat? = null
    ): VideoMetadata {
        val useRetriever = retriever ?: MediaMetadataRetriever()
        val shouldRelease = retriever == null

        return try {
            if (shouldRelease) {
                useRetriever.setDataSource(context, videoUri)
            }

            val base = extractFromRetriever(useRetriever, videoUri)

            // Enrich with MediaExtractor track-level data (frame rate, codec, etc.)
            val format = mediaFormat ?: getVideoTrackFormat(context, videoUri)
            if (format != null) {
                enrichWithTrackFormat(base, format)
            } else {
                base
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "Failed to extract metadata", e)
            VideoMetadata(sourceFileName = videoUri.lastPathSegment)
        } finally {
            if (shouldRelease) {
                try {
                    useRetriever.release()
                } catch (e: Exception) {
                    LogUtils.w(context, TAG, "Failed to release retriever", e)
                }
            }
        }
    }

    // ── MediaMetadataRetriever pass ─────────────────────────────────────

    internal fun extractFromRetriever(
        retriever: MediaMetadataRetriever,
        videoUri: Uri
    ): VideoMetadata {
        val dateTime = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DATE
        )?.let { normalizeDateTime(it) }

        val location = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_LOCATION
        )
        val (latitude, longitude) = parseLocation(location)
        val altitude = parseAltitude(location)

        val width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0

        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0

        val rotation = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        )?.toIntOrNull() ?: 0

        val duration = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0

        val bitrate = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_BITRATE
        )?.toLongOrNull() ?: 0

        // CAPTURE_FRAMERATE is the original sensor capture rate (if available)
        val captureFrameRate = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
        )?.toFloatOrNull() ?: 0f

        val codec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

        // Author/writer fields may contain device info on some camera apps
        val author = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_AUTHOR
        )
        val writer = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_WRITER
        )

        val make = author?.takeIf { it.isNotBlank() }
        val model = writer?.takeIf { it.isNotBlank() && it != author }

        return VideoMetadata(
            dateTime = dateTime,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            make = make,
            model = model,
            rotation = rotation,
            videoWidth = width,
            videoHeight = height,
            frameRate = captureFrameRate,
            bitrate = bitrate,
            codec = codec,
            durationMs = duration,
            sourceFileName = videoUri.lastPathSegment
        )
    }

    // ── MediaExtractor enrichment ───────────────────────────────────────

    /**
     * Enrich metadata with track-level information from MediaExtractor.
     * This gives us codec-mime, accurate frame rate, and more fields
     * that MediaMetadataRetriever sometimes misses.
     */
    internal fun enrichWithTrackFormat(
        base: VideoMetadata,
        format: MediaFormat
    ): VideoMetadata {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: base.codec
        val trackWidth = format.getIntegerSafe(MediaFormat.KEY_WIDTH, base.videoWidth)
        val trackHeight = format.getIntegerSafe(MediaFormat.KEY_HEIGHT, base.videoHeight)

        // Frame rate: prefer MediaExtractor's KEY_FRAME_RATE over capture rate
        // CAPTURE_FRAMERATE is the sensor rate (e.g. 240fps for slo-mo) which diverges
        // from the playback frame rate; use track frame rate when available.
        val trackFrameRate = format.getIntegerSafe(MediaFormat.KEY_FRAME_RATE, 0)
        val effectiveFrameRate = when {
            trackFrameRate > 0 -> trackFrameRate.toFloat()
            base.frameRate > 0f -> base.frameRate
            else -> 0f
        }

        // Bitrate from track
        val trackBitrate = format.getIntegerSafe(MediaFormat.KEY_BIT_RATE, 0).toLong()
        val effectiveBitrate = if (base.bitrate > 0) base.bitrate else trackBitrate

        // Rotation from track (container-level rotation)
        val trackRotation = format.getIntegerSafe(MediaFormat.KEY_ROTATION, base.rotation)

        val trackIso = getIntFromKeys(format, *KEYS_ISO)
        val trackExposureTime = getStringFromKeys(format, *KEYS_EXPOSURE_TIME)
            ?.takeIf { it.isNotBlank() }
        val trackFNumber = getFloatFromKeys(format, *KEYS_F_NUMBER)
        val trackFocalLength = getFloatFromKeys(format, *KEYS_FOCAL_LENGTH)

        return base.copy(
            codec = mime ?: base.codec,
            videoWidth = if (trackWidth > 0) trackWidth else base.videoWidth,
            videoHeight = if (trackHeight > 0) trackHeight else base.videoHeight,
            frameRate = effectiveFrameRate,
            bitrate = effectiveBitrate,
            rotation = trackRotation,
            iso = base.iso ?: trackIso,
            exposureTime = base.exposureTime ?: ExifUtils.normalizeExposureTime(trackExposureTime),
            fNumber = base.fNumber ?: trackFNumber?.toDouble(),
            focalLength = base.focalLength ?: trackFocalLength?.toDouble()
        )
    }

    private fun getStringFromKeys(format: MediaFormat, vararg keys: String): String? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            val value = runCatching { format.getString(key) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun getIntFromKeys(format: MediaFormat, vararg keys: String): Int? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            val value = runCatching { format.getInteger(key) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun getFloatFromKeys(format: MediaFormat, vararg keys: String): Float? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            val floatValue = runCatching { format.getFloat(key) }.getOrNull()
            if (floatValue != null) return floatValue

            val stringValue = runCatching { format.getString(key) }.getOrNull()
            val parsedFloat = stringValue?.toFloatOrNull()
            if (parsedFloat != null) return parsedFloat
        }
        return null
    }

    private fun parseAltitude(location: String?): Double? {
        if (location.isNullOrBlank()) return null
        val clean = location.trimEnd('/')
        val secondSignIndex = clean.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
        if (secondSignIndex < 0) return null
        val thirdSignIndex = clean.indexOfAny(charArrayOf('+', '-'), startIndex = secondSignIndex + 1)
        if (thirdSignIndex < 0) return null
        return clean.substring(thirdSignIndex).toDoubleOrNull()
    }

    // ── GPS location parsing ────────────────────────────────────────────

    /**
     * Parse GPS location string from video metadata.
     * Format is typically "+DD.DDDD+DDD.DDDD/" or "+DD.DDDD-DDD.DDDD/"
     */
    internal fun parseLocation(location: String?): Pair<Double?, Double?> {
        if (location.isNullOrBlank()) return Pair(null, null)

        return try {
            val clean = location.trim().trimEnd('/')
            val secondSignIndex = clean.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
            if (secondSignIndex < 0) return Pair(null, null)
            val thirdSignIndex = clean.indexOfAny(charArrayOf('+', '-'), startIndex = secondSignIndex + 1)

            val lat = clean.substring(0, secondSignIndex).toDouble()
            val lon = if (thirdSignIndex > secondSignIndex) {
                clean.substring(secondSignIndex, thirdSignIndex).toDouble()
            } else {
                clean.substring(secondSignIndex).toDouble()
            }
            Pair(lat, lon)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    // ── Date/time normalisation ─────────────────────────────────────────

    private fun normalizeDateTime(dateStr: String): String {
        return DateTimeUtils.normalizeToIso(dateStr)
    }

    // ── Track format helpers ────────────────────────────────────────────

    /**
     * Get the MediaFormat for the first video track.
     */
    fun getVideoTrackFormat(context: Context, videoUri: Uri): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, videoUri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return format
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

}
