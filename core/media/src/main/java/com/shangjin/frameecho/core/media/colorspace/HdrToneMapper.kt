package com.shangjin.frameecho.core.media.colorspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace as AndroidColorSpace
import android.graphics.Paint
import android.os.Build
import com.shangjin.frameecho.core.model.ColorGamut
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ColorSpaceType
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.HdrToneMapStrategy

/**
 * Handles HDR-to-SDR tone mapping and color space conversions.
 *
 * When exporting HDR content to SDR formats (e.g., JPEG), proper tone mapping
 * is essential to avoid washed-out or clipped images.
 */
object HdrToneMapper {

    /**
     * Process a bitmap for export, applying tone mapping if necessary.
     *
     * @param bitmap Source bitmap (may be HDR)
     * @param colorSpaceInfo Source color space information
     * @param targetFormat Target export format
     * @param strategy Tone mapping strategy
     * @return Processed bitmap ready for export
     */
    fun process(
        bitmap: Bitmap,
        colorSpaceInfo: ColorSpaceInfo,
        targetFormat: ExportFormat,
        strategy: HdrToneMapStrategy
    ): Bitmap {
        // If source is SDR, no processing needed
        if (!colorSpaceInfo.isHdr) return bitmap

        val shouldToneMap = when (strategy) {
            HdrToneMapStrategy.AUTO -> !targetFormat.supportsHdr
            HdrToneMapStrategy.FORCE_SDR -> true
            // Preserve HDR only when the target actually supports it.
            HdrToneMapStrategy.PRESERVE_HDR -> !targetFormat.supportsHdr
            HdrToneMapStrategy.SYSTEM -> !targetFormat.supportsHdr
        }

        if (!shouldToneMap) return bitmap

        return toneMapToSdr(bitmap, colorSpaceInfo)
    }

    private fun toneMapToSdr(bitmap: Bitmap, colorSpaceInfo: ColorSpaceInfo): Bitmap {
        val targetColorSpace = AndroidColorSpace.get(AndroidColorSpace.Named.SRGB)

        // Create output bitmap in sRGB color space.
        // Always use ARGB_8888: this function produces SDR output and
        // RGBA_1010102 (10-bit) is unsupported by Bitmap.compress for JPEG/PNG/WebP.
        val config = Bitmap.Config.ARGB_8888

        val output = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            config,
            false, // not hardware - we need to draw on it
            targetColorSpace
        )

        // Draw the HDR bitmap onto the SDR canvas
        // Android's Canvas handles the color space conversion automatically.
        // Create a local Paint to avoid thread-safety issues with a shared instance.
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isFilterBitmap = true
            isDither = false
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return output
    }

    /**
     * Get the appropriate Android ColorSpace for the given color gamut.
     */
    fun getAndroidColorSpace(gamut: ColorGamut): AndroidColorSpace {
        return when (gamut) {
            ColorGamut.BT709 -> AndroidColorSpace.get(AndroidColorSpace.Named.SRGB)
            ColorGamut.BT2020 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    AndroidColorSpace.get(AndroidColorSpace.Named.BT2020)
                } else {
                    // Fallback: use Display P3 as closest available
                    AndroidColorSpace.get(AndroidColorSpace.Named.DISPLAY_P3)
                }
            }
            ColorGamut.DCI_P3 -> AndroidColorSpace.get(AndroidColorSpace.Named.DCI_P3)
            ColorGamut.DISPLAY_P3 -> AndroidColorSpace.get(AndroidColorSpace.Named.DISPLAY_P3)
        }
    }
}
