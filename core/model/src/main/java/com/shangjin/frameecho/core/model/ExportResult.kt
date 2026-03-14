package com.shangjin.frameecho.core.model

/**
 * Represents the result of an export operation.
 */
sealed class ExportResult {
    data class Success(
        val outputPath: String,
        val width: Int,
        val height: Int,
        val fileSizeBytes: Long,
        /** The actual format of the exported file. */
        val format: ExportFormat,
        val isMotionPhoto: Boolean = false,
        val metadataPreserved: Boolean = false,
        /**
         * The format originally requested by the user.
         * Non-null only when the actual [format] differs from what the user selected
         * (e.g. HEIF/AVIF requested but JPEG produced because the device lacks native encoding).
         */
        val requestedFormat: ExportFormat? = null
    ) : ExportResult() {
        /** Whether the actual format differs from what the user requested. */
        val formatFallbackOccurred: Boolean
            get() = requestedFormat != null && requestedFormat != format
    }

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ExportResult()
}
