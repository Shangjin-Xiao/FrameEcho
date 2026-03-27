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
    ),
    PNG(
        extension = "png",
        mimeType = "image/png",
        supportsAlpha = true,
        supportsHdr = false
    ),
    WEBP(
        extension = "webp",
        mimeType = "image/webp",
        supportsAlpha = true,
        supportsHdr = false
    ),
    HEIF(
        extension = "heic",
        mimeType = "image/heif",
        supportsAlpha = false,
        supportsHdr = true
    ),
    AVIF(
        extension = "avif",
        mimeType = "image/avif",
        supportsAlpha = true,
        supportsHdr = true
    )
}
