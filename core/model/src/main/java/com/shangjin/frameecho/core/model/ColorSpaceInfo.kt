package com.shangjin.frameecho.core.model

/**
 * Color space information for proper HDR/SDR handling.
 */
data class ColorSpaceInfo(
    val type: ColorSpaceType = ColorSpaceType.SDR,
    val transferFunction: TransferFunction = TransferFunction.SRGB,
    val colorGamut: ColorGamut = ColorGamut.BT709,
    val bitDepth: Int = 8,
    /** Whether the source has HDR static metadata (SMPTE ST 2086) */
    val hasStaticHdrMetadata: Boolean = false,
    /** Whether the source has HDR dynamic metadata (e.g., Dolby Vision RPU) */
    val hasDynamicHdrMetadata: Boolean = false
) {
    val isHdr: Boolean
        get() = type != ColorSpaceType.SDR
}

/**
 * Supported color space types.
 */
enum class ColorSpaceType {
    SDR,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION
}

/**
 * Electro-optical transfer functions.
 */
enum class TransferFunction {
    SRGB,
    LINEAR,
    PQ,       // Perceptual Quantizer (SMPTE ST 2084) — HDR10, Dolby Vision
    HLG,      // Hybrid Log-Gamma (ARIB STD-B67)
    GAMMA_2_2
}

/**
 * Color gamut / primaries.
 */
enum class ColorGamut {
    BT709,     // Standard sRGB / Rec. 709
    BT2020,    // Wide color gamut for HDR
    DCI_P3,    // Display P3
    DISPLAY_P3
}
