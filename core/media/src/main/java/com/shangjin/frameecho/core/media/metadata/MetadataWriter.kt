package com.shangjin.frameecho.core.media.metadata

import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.media.utils.DateTimeUtils
import com.shangjin.frameecho.core.media.utils.ExifUtils
import com.shangjin.frameecho.core.model.VideoMetadata
import java.io.File

/**
 * Writes video metadata into exported image EXIF data.
 *
 * Preserves original video information including:
 * - Date/time of recording
 * - GPS coordinates
 * - Device information
 * - Camera settings (ISO, exposure, etc.)
 * - Custom XMP data for video-specific info
 */
object MetadataWriter {

    /**
     * Write video metadata to an image file's EXIF data.
     *
     * @param imageFile The exported image file
     * @param metadata Video metadata to write
     */
    fun writeMetadata(imageFile: File, metadata: VideoMetadata) {
        try {
            val exif = ExifInterface(imageFile)
            writeExifData(exif, metadata)
            exif.saveAttributes()
        } catch (e: Exception) {
            // Log but don't fail the export
            android.util.Log.w("MetadataWriter", "Failed to write EXIF metadata", e)
        }
    }

    /**
     * Write EXIF attributes from [metadata] into an already-opened [ExifInterface].
     *
     * This is the single source of truth for EXIF attribute population and is
     * called both from [writeMetadata] (file-based) and from [FrameExporter]
     * (URI / byte-array based).
     *
     * **Note:** the caller is responsible for calling [ExifInterface.saveAttributes]
     * after this method returns.
     */
    internal fun writeExifData(exif: ExifInterface, metadata: VideoMetadata) {
        // Date/Time
        metadata.dateTime?.let { dateTime ->
            val exifDate = DateTimeUtils.convertToExif(dateTime)
            exifDate?.let {
                exif.setAttribute(ExifInterface.TAG_DATETIME, it)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, it)
            }
        }

        // GPS Location
        val latitude = metadata.latitude
        val longitude = metadata.longitude
        if (latitude != null && longitude != null) {
            exif.setLatLong(latitude, longitude)
        }

        // GPS Altitude
        metadata.altitude?.let { alt ->
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, ExifUtils.formatRational(alt))
            exif.setAttribute(
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                if (alt >= 0) "0" else "1"
            )
        }

        // Device info
        metadata.make?.let {
            exif.setAttribute(ExifInterface.TAG_MAKE, it)
        }
        metadata.model?.let {
            exif.setAttribute(ExifInterface.TAG_MODEL, it)
        }

        // Camera settings
        metadata.iso?.let {
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
        }
        metadata.exposureTime?.let { raw ->
            val normalized = ExifUtils.normalizeExposureTime(raw)
            if (normalized != null) {
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, normalized)
            }
        }
        metadata.fNumber?.let {
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
        }
        metadata.focalLength?.let {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, ExifUtils.formatRational(it))
        }

        // Image dimensions (effective after rotation)
        val effectiveWidth: Int
        val effectiveHeight: Int
        if (metadata.rotation == 90 || metadata.rotation == 270) {
            effectiveWidth = metadata.videoHeight
            effectiveHeight = metadata.videoWidth
        } else {
            effectiveWidth = metadata.videoWidth
            effectiveHeight = metadata.videoHeight
        }
        if (effectiveWidth > 0) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, effectiveWidth.toString())
        }
        if (effectiveHeight > 0) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, effectiveHeight.toString())
        }

        // Software tag
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FrameEcho")

        // User comment with video source info
        val comment = buildUserComment(metadata)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
    }

    /**
     * Build the user comment string with sanitized metadata values.
     * Exposed as internal for testing to avoid Android framework dependencies.
     */
    internal fun buildUserComment(metadata: VideoMetadata): String {
        return buildString {
            append("Source: video frame capture")
            metadata.sourceFileName?.let { append("; File: ${sanitizeMetadataString(it)}") }
            metadata.codec?.let { append("; Codec: ${sanitizeMetadataString(it)}") }
            metadata.frameRate.takeIf { it > 0 }?.let { append("; FPS: $it") }
        }
    }

    /**
     * Sanitize metadata strings to prevent injection or corruption.
     * - Removes control characters (including newlines)
     * - Truncates excessively long strings
     */
    private fun sanitizeMetadataString(value: String): String {
        return value
            .filter { it >= ' ' && it != '\u007F' } // Keep only printable ASCII/Unicode (remove < 32 and DEL)
            .trim()
            .take(100) // Truncate to 100 chars
    }
}
