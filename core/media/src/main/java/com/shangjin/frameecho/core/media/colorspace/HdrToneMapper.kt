package com.shangjin.frameecho.core.media.colorspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace as AndroidColorSpace
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.shangjin.frameecho.core.model.ColorSpaceInfo

/**
 * Handles HDR-to-SDR tone mapping and color space conversions.
 *
 * When exporting HDR content to SDR formats (e.g., JPEG), proper tone mapping
 * is essential to avoid washed-out or clipped images.
 */
object HdrToneMapper {

    private val srgbColorSpace by lazy { AndroidColorSpace.get(AndroidColorSpace.Named.SRGB) }

    /**
     * Process a bitmap for export, applying tone mapping if necessary.
     *
     * All supported export formats (JPEG/PNG/WebP) are SDR, so HDR sources
     * are always tone-mapped; SDR sources pass through untouched.
     *
     * @param bitmap Source bitmap (may be HDR)
     * @param colorSpaceInfo Source color space information
     * @return Processed bitmap ready for export
     */
    fun process(
        bitmap: Bitmap,
        colorSpaceInfo: ColorSpaceInfo
    ): Bitmap {
        // If source is SDR, no processing needed
        if (!colorSpaceInfo.isHdr) return bitmap

        return toneMapToSdr(bitmap, colorSpaceInfo)
    }

    private fun toneMapToSdr(bitmap: Bitmap, colorSpaceInfo: ColorSpaceInfo): Bitmap {
        val targetColorSpace = srgbColorSpace

        // Create output bitmap in sRGB color space.
        // Always use ARGB_8888: this function produces SDR output and
        // RGBA_1010102 (10-bit) is unsupported by Bitmap.compress for JPEG/PNG/WebP.
        val config = Bitmap.Config.ARGB_8888

        val output = createBitmap(
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
}
