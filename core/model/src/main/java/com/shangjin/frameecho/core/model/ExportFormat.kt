package com.shangjin.frameecho.core.model

/**
 * Supported image export formats.
 */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val supportsAlpha: Boolean = false,
    val supportsHdr: Boolean = false
) {
    JPEG(
        extension = "jpg",
        mimeType = "image/jpeg",
        supportsAlpha = false,
        supportsHdr = false
    )
}
