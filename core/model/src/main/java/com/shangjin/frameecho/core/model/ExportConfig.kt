package com.shangjin.frameecho.core.model

/**
 * Configuration for exporting frames.
 */
data class ExportConfig(
    /** Image format */
    val format: ExportFormat = ExportFormat.JPEG,
    /** Quality (1-100), 100 = best quality / lossless where supported */
    val quality: Int = 100,
    /** Whether to preserve original video metadata in the exported image */
    val preserveMetadata: Boolean = true,
    /** Whether to export as motion photo (dynamic) */
    val motionPhoto: Boolean = false,
    /**
     * Duration in seconds for the motion photo clip before and after the selected frame.
     * Default is 1.5s before + 1.5s after = 3s total, following Google Motion Photo spec.
     * The user can adjust this value.
     */
    val motionDurationBeforeS: Float = 1.5f,
    val motionDurationAfterS: Float = 1.5f,
    /** Whether to mute audio in the exported motion photo */
    val muteAudio: Boolean = false,
    /** HDR tone-mapping strategy for SDR export */
    val hdrToneMap: HdrToneMapStrategy = HdrToneMapStrategy.AUTO,
    /** Optional output resolution override. Null = original resolution. */
    val maxResolution: Int? = null,
    /** Optional custom base filename (without extension). Null/blank = default naming. */
    val customFileName: String? = null,
    /** Default export location in shared storage. */
    val exportDirectory: ExportDirectory = ExportDirectory.PICTURES_FRAMEECHO
) {
    /** Total motion photo duration in seconds */
    val totalMotionDurationS: Float
        get() = motionDurationBeforeS + motionDurationAfterS

    companion object {
        const val MAX_MOTION_DURATION_S = 5.0f
    }

    init {
        require(quality in 1..100) { "Quality must be between 1 and 100" }
        require(motionDurationBeforeS >= 0f) { "Motion duration before must be non-negative" }
        require(motionDurationAfterS >= 0f) { "Motion duration after must be non-negative" }
        require(motionDurationBeforeS <= MAX_MOTION_DURATION_S) { "Motion duration before must not exceed $MAX_MOTION_DURATION_S seconds" }
        require(motionDurationAfterS <= MAX_MOTION_DURATION_S) { "Motion duration after must not exceed $MAX_MOTION_DURATION_S seconds" }
        require(maxResolution == null || maxResolution > 0) { "Max resolution must be positive" }
        require(customFileName == null || customFileName.length <= 80) { "Custom filename too long" }
    }
}

enum class ExportDirectory(val relativePath: String) {
    PICTURES_FRAMEECHO("Pictures/FrameEcho"),
    DCIM_FRAMEECHO("DCIM/FrameEcho"),
    MOVIES_FRAMEECHO("Movies/FrameEcho"),
    PICTURES("Pictures"),
    DCIM("DCIM"),
    MOVIES("Movies")
}

/**
 * Strategy for tone-mapping HDR content when exporting to SDR formats.
 */
enum class HdrToneMapStrategy {
    /** Automatically choose based on source and target format */
    AUTO,
    /** Force tone-map to SDR */
    FORCE_SDR,
    /** Preserve HDR data if the target format supports it */
    PRESERVE_HDR,
    /** Let the system/GPU handle tone-mapping */
    SYSTEM
}
