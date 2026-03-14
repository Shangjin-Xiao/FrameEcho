package com.shangjin.frameecho.core.media.colorspace

import android.media.MediaFormat
import android.os.Build
import com.shangjin.frameecho.core.media.utils.getIntegerSafe
import com.shangjin.frameecho.core.model.ColorGamut
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ColorSpaceType
import com.shangjin.frameecho.core.model.TransferFunction

/**
 * Detects and manages color space information from video MediaFormat.
 *
 * Handles:
 * - SDR (BT.709 / sRGB)
 * - HDR10 (PQ + BT.2020 + static metadata)
 * - HDR10+ (PQ + BT.2020 + dynamic metadata)
 * - HLG (Hybrid Log-Gamma + BT.2020)
 * - Dolby Vision (various profiles)
 */
object ColorSpaceDetector {

    // MediaFormat keys for color information
    private const val KEY_COLOR_TRANSFER = "color-transfer"
    private const val KEY_COLOR_STANDARD = "color-standard"
    private const val KEY_COLOR_RANGE = "color-range"
    private const val KEY_HDR_STATIC_INFO = "hdr-static-info"
    private const val KEY_PROFILE = "profile"

    // Color transfer values (from MediaCodecInfo.CodecProfileLevel)
    private const val COLOR_TRANSFER_SDR = 3     // SRGB
    private const val COLOR_TRANSFER_ST2084 = 6  // PQ (HDR10, Dolby Vision)
    private const val COLOR_TRANSFER_HLG = 7     // HLG

    // Color standard values
    private const val COLOR_STANDARD_BT709 = 1
    private const val COLOR_STANDARD_BT2020 = 6

    // Dolby Vision MIME types
    private const val MIME_DOLBY_VISION = "video/dolby-vision"
    private const val MIME_DOLBY_VISION_DVHE = "video/dvhe"
    private const val MIME_DOLBY_VISION_DVAV = "video/dvav"

    // HDR10+ detection
    private const val FEATURE_HDR10_PLUS = "hdr10-plus"
    private const val KEY_HDR10_PLUS_INFO = "hdr10-plus-info"

    // Bit depth detection MIME fragments
    private const val MIME_FRAGMENT_HEVC = "hevc"
    private const val MIME_FRAGMENT_H265 = "h265"
    private const val MIME_FRAGMENT_AV01 = "av01"
    private const val MIME_FRAGMENT_AV1 = "av1"

    // Profile values for bit depth detection
    private const val PROFILE_HEVC_MAIN_10 = 2
    private const val PROFILE_HEVC_MAIN_10_HDR10 = 4096
    private const val PROFILE_AV1_MIN_10BIT = 1

    // Bit depth values
    private const val BIT_DEPTH_8 = 8
    private const val BIT_DEPTH_10 = 10

    /**
     * Detect color space from a video track's MediaFormat.
     */
    fun detect(format: MediaFormat): ColorSpaceInfo {
        val transfer = format.getIntegerSafe(KEY_COLOR_TRANSFER, COLOR_TRANSFER_SDR)
        val standard = format.getIntegerSafe(KEY_COLOR_STANDARD, COLOR_STANDARD_BT709)
        val hasStaticMetadata = format.containsKey(KEY_HDR_STATIC_INFO)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val profile = format.getIntegerSafe(KEY_PROFILE, 0)
        val bitDepth = detectBitDepth(format)

        // Detect Dolby Vision first (it has its own MIME type)
        if (isDolbyVision(mime)) {
            return ColorSpaceInfo(
                type = ColorSpaceType.DOLBY_VISION,
                transferFunction = TransferFunction.PQ,
                colorGamut = ColorGamut.BT2020,
                // Dolby Vision is always at least 10-bit
                bitDepth = maxOf(bitDepth, BIT_DEPTH_10),
                hasStaticHdrMetadata = hasStaticMetadata,
                hasDynamicHdrMetadata = true
            )
        }

        // Detect based on transfer function
        return when (transfer) {
            COLOR_TRANSFER_ST2084 -> {
                val hasDynamicMetadata = detectHdr10Plus(format)
                ColorSpaceInfo(
                    type = if (hasDynamicMetadata) ColorSpaceType.HDR10_PLUS else ColorSpaceType.HDR10,
                    transferFunction = TransferFunction.PQ,
                    colorGamut = if (standard == COLOR_STANDARD_BT2020) ColorGamut.BT2020 else ColorGamut.BT709,
                    bitDepth = bitDepth,
                    hasStaticHdrMetadata = hasStaticMetadata,
                    hasDynamicHdrMetadata = hasDynamicMetadata
                )
            }
            COLOR_TRANSFER_HLG -> {
                ColorSpaceInfo(
                    type = ColorSpaceType.HLG,
                    transferFunction = TransferFunction.HLG,
                    colorGamut = if (standard == COLOR_STANDARD_BT2020) ColorGamut.BT2020 else ColorGamut.BT709,
                    bitDepth = bitDepth,
                    hasStaticHdrMetadata = hasStaticMetadata,
                    hasDynamicHdrMetadata = false
                )
            }
            else -> {
                ColorSpaceInfo(
                    type = ColorSpaceType.SDR,
                    transferFunction = TransferFunction.SRGB,
                    colorGamut = if (standard == COLOR_STANDARD_BT2020) ColorGamut.BT2020 else ColorGamut.BT709,
                    bitDepth = BIT_DEPTH_8,
                    hasStaticHdrMetadata = false,
                    hasDynamicHdrMetadata = false
                )
            }
        }
    }

    /**
     * Check if the MIME type indicates Dolby Vision.
     */
    private fun isDolbyVision(mime: String): Boolean {
        return mime.equals(MIME_DOLBY_VISION, ignoreCase = true) ||
               mime.equals(MIME_DOLBY_VISION_DVHE, ignoreCase = true) ||
               mime.equals(MIME_DOLBY_VISION_DVAV, ignoreCase = true)
    }

    /**
     * Detect HDR10+ dynamic metadata presence.
     */
    private fun detectHdr10Plus(format: MediaFormat): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On Android 12+, check for HDR10+ dynamic metadata feature
            val features = try {
                format.getFeatureEnabled(FEATURE_HDR10_PLUS)
            } catch (_: Exception) {
                false
            }
            if (features) return true
        }
        // Fallback: check for HDR10+ specific keys
        return format.containsKey(KEY_HDR10_PLUS_INFO)
    }

    /**
     * Detect video bit depth from format.
     *
     * Checks codec profile directly without gating on color-format constants,
     * because real 10-bit HEVC/AV1 streams may report any color format value.
     * The transfer-function fallback catches HDR streams that lack profile info.
     */
    private fun detectBitDepth(format: MediaFormat): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val profile = format.getIntegerSafe(KEY_PROFILE, 0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.contains(MIME_FRAGMENT_HEVC) || mime.contains(MIME_FRAGMENT_H265)) {
                if (profile == PROFILE_HEVC_MAIN_10 || profile == PROFILE_HEVC_MAIN_10_HDR10) return BIT_DEPTH_10
            }
            if (mime.contains(MIME_FRAGMENT_AV01) || mime.contains(MIME_FRAGMENT_AV1)) {
                if (profile >= PROFILE_AV1_MIN_10BIT) return BIT_DEPTH_10
            }
        }

        // HDR transfer functions indicate 10-bit content
        val transfer = format.getIntegerSafe(KEY_COLOR_TRANSFER, COLOR_TRANSFER_SDR)
        if (transfer == COLOR_TRANSFER_ST2084 || transfer == COLOR_TRANSFER_HLG) {
            return BIT_DEPTH_10
        }

        return BIT_DEPTH_8
    }

}
