package com.shangjin.frameecho.core.model

/**
 * Represents a captured video frame with all associated metadata.
 */
data class CapturedFrame(
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val colorSpace: ColorSpaceInfo,
    val metadata: VideoMetadata,
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 100,
    /** Whether HDR-to-SDR tone mapping to sRGB has already been applied during extraction */
    val isToneMapped: Boolean = false
)
