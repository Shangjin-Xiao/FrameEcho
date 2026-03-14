package com.shangjin.frameecho.core.model

/**
 * Video metadata to be preserved in exported images.
 * Maps to EXIF and XMP metadata fields.
 */
data class VideoMetadata(
    /** Original creation date/time in ISO 8601 format */
    val dateTime: String? = null,
    /** GPS latitude */
    val latitude: Double? = null,
    /** GPS longitude */
    val longitude: Double? = null,
    /** GPS altitude in meters */
    val altitude: Double? = null,
    /** Camera/device make */
    val make: String? = null,
    /** Camera/device model */
    val model: String? = null,
    /** ISO sensitivity */
    val iso: Int? = null,
    /** Exposure time in seconds (e.g., "1/60") */
    val exposureTime: String? = null,
    /** F-number / aperture */
    val fNumber: Double? = null,
    /** Focal length in mm */
    val focalLength: Double? = null,
    /** Video rotation in degrees */
    val rotation: Int = 0,
    /** Original video width */
    val videoWidth: Int = 0,
    /** Original video height */
    val videoHeight: Int = 0,
    /** Video frame rate */
    val frameRate: Float = 0f,
    /** Video bitrate */
    val bitrate: Long = 0,
    /** Video codec name */
    val codec: String? = null,
    /** Duration in milliseconds */
    val durationMs: Long = 0,
    /** Source file name */
    val sourceFileName: String? = null
)
