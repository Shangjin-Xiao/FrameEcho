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
         * (e.g. PNG/WEBP requested but JPEG produced because motion photos require JPEG).
         */
        val requestedFormat: ExportFormat? = null,
        /**
         * True when a motion photo was exported without audio even though the user
         * did not enable mute — e.g. the source audio codec could neither be
         * embedded nor transcoded. Lets the UI warn instead of succeeding silently.
         */
        val audioDropped: Boolean = false
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
