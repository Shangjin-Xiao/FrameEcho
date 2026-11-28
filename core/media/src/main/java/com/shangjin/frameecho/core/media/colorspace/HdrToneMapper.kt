package com.shangjin.frameecho.core.media.colorspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace as AndroidColorSpace
import android.graphics.Paint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.shangjin.frameecho.core.model.ColorGamut
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ColorSpaceType
import com.shangjin.frameecho.core.model.TransferFunction
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Perceptual HDR-to-SDR tone-mapping and color-gamut conversion engine.
 *
 * Primary HDR frame extraction uses Media3's GPU OpenGL shader pipeline and sets
 * `CapturedFrame.isToneMapped = true`, allowing [process] to pass already-mapped `sRGB`
 * bitmaps straight through without double compression.
 *
 * When GPU extraction is unavailable on legacy devices or specific codecs and
 * `FrameExtractor` falls back to `MediaMetadataRetriever` (`RGBA_F16` on API 28+ or
 * unmapped 8-bit `ARGB_8888`), this software engine:
 * 1. Decodes `SMPTE ST 2084 (PQ)` (`0..10000 nits`), `ARIB STD-B67 (HLG)` (`0..1000 nits`),
 *    or `scRGB` (`EXTENDED_SRGB` / `LINEAR_EXTENDED_SRGB`) signals into linear HDR light,
 *    using [ColorSpaceInfo] even when the decoded [Bitmap] lacks or mislabels its color space.
 * 2. Applies the **ITU-R BT.2408 Annex 5 perceptual EETF** (`maxRGB` norm) so specular
 *    highlights from `203 nits` up to `10000 nits` roll off smoothly and strictly monotonically
 *    instead of hard-clipping PQ code values `>= 149/255` to pure white.
 * 3. Converts wide-gamut `BT.2020` / `DCI-P3` / `Display-P3` primaries into `BT.709 / sRGB`
 *    with constant-luminance out-of-gamut soft compression.
 * 4. Encodes with the IEC 61966-2-1 `sRGB` OETF and applies **Bayer 8x8 ordered dithering**
 *    (`isDither = true`) to suppress 8-bit gradient banding.
 */
object HdrToneMapper {

    // SMPTE ST 2084 (PQ) constants
    private const val PQ_M1 = 2610.0 / 16384.0          // 0.1593017578125
    private const val PQ_M2 = (2523.0 / 4096.0) * 128.0 // 78.84375
    private const val PQ_C1 = 3424.0 / 4096.0           // 0.8359375
    private const val PQ_C2 = (2413.0 / 4096.0) * 32.0  // 18.8515625
    private const val PQ_C3 = (2392.0 / 4096.0) * 32.0  // 18.6875
    private const val PQ_MAX_NITS = 10_000f

    // ARIB STD-B67 / ITU-R BT.2100 (HLG) constants
    private const val HLG_A = 0.17883277
    private const val HLG_B = 1.0 - 4.0 * HLG_A // 0.28466892
    private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)
    private const val HLG_SYSTEM_GAMMA = 1.2
    private const val HLG_OOTF_EXP = HLG_SYSTEM_GAMMA - 1.0 // 0.2
    private const val HLG_MAX_NITS = 1_000f

    // ITU-R BT.2408 reference & target peak luminances (nits)
    internal const val SDR_REFERENCE_WHITE_NITS = 203f
    private const val PQ_TARGET_SDR_PEAK_NITS = 350f
    private const val HLG_TARGET_SDR_PEAK_NITS = 300f

    // Precomputed 8-bit PQ code-value -> nits LUT to avoid Math.pow per channel on 4K frames.
    private val pq8BitToNitsLut: FloatArray by lazy {
        FloatArray(256) { code -> pqEotfNits(code / 255f) }
    }

    // Precomputed 8-bit HLG code-value -> scene-linear [0, 1] LUT.
    private val hlg8BitToSceneLinearLut: FloatArray by lazy {
        FloatArray(256) { code -> hlgInverseOetf(code / 255f) }
    }

    // Precomputed 8-bit sRGB EOTF LUT for scRGB / fallback linearizing.
    private val srgb8BitToLinearLut: FloatArray by lazy {
        FloatArray(256) { code -> srgbEotf(code / 255f) }
    }

    // Standard 8x8 Bayer threshold matrix (0..63) for sub-LSB ordered spatial dithering.
    private val BAYER_8X8 = intArrayOf(
         0, 32,  8, 40,  2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44,  4, 36, 14, 46,  6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
         3, 35, 11, 43,  1, 33,  9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47,  7, 39, 13, 45,  5, 37,
        63, 31, 55, 23, 61, 29, 53, 21
    )

    private val srgbColorSpace: AndroidColorSpace? by lazy {
        safeGetNamedColorSpace(AndroidColorSpace.Named.SRGB)
    }

    private val extendedSrgbColorSpace: AndroidColorSpace? by lazy {
        safeGetNamedColorSpace(AndroidColorSpace.Named.EXTENDED_SRGB)
    }

    private val linearExtendedSrgbColorSpace: AndroidColorSpace? by lazy {
        safeGetNamedColorSpace(AndroidColorSpace.Named.LINEAR_EXTENDED_SRGB)
    }

    private val bt2020PqColorSpace: AndroidColorSpace? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeGetApi34NamedColorSpace(isPq = true)
        } else {
            null
        }
    }

    private val bt2020HlgColorSpace: AndroidColorSpace? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeGetApi34NamedColorSpace(isPq = false)
        } else {
            null
        }
    }

    private enum class BitmapColorSpaceClass {
        UNTAGGED_HDR,
        EXTENDED_SRGB,
        LINEAR_EXTENDED_SRGB,
        EXPLICIT_HDR_PQ,
        EXPLICIT_HDR_HLG,
        STANDARD_SRGB,
        OTHER_SDR
    }

    private enum class F16SignalEncoding {
        PQ,
        HLG,
        EXTENDED_SRGB,
        LINEAR_EXTENDED_SRGB,
        SCENE_LINEAR,
        SRGB
    }

    /**
     * Process a bitmap for SDR export, applying HDR-to-SDR tone mapping when needed.
     *
     * @param bitmap Source bitmap (may be HDR or already GPU tone-mapped)
     * @param colorSpaceInfo Source video color space metadata
     * @param isAlreadyToneMapped True when the bitmap was already tone-mapped to `sRGB`
     *   during extraction (e.g. via Media3 GPU OpenGL shaders)
     * @return SDR `ARGB_8888` `sRGB` bitmap ready for compression
     */
    fun process(
        bitmap: Bitmap,
        colorSpaceInfo: ColorSpaceInfo,
        isAlreadyToneMapped: Boolean = false
    ): Bitmap {
        if (!colorSpaceInfo.isHdr || isAlreadyToneMapped) return bitmap
        return toneMapToSdr(bitmap, colorSpaceInfo)
    }

    private fun toneMapToSdr(bitmap: Bitmap, colorSpaceInfo: ColorSpaceInfo): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        val initialCsClass = classifyBitmapColorSpace(bitmap)
        if (bitmap.config == Bitmap.Config.ARGB_8888 &&
            initialCsClass == BitmapColorSpaceClass.STANDARD_SRGB
        ) {
            // MediaMetadataRetriever / platform decoder already tone-mapped to SDR sRGB.
            return bitmap
        }

        var tempSoftwareBitmap: Bitmap? = null
        return try {
            val working = if (bitmap.config == Bitmap.Config.HARDWARE) {
                val copied = bitmap.copy(Bitmap.Config.RGBA_F16, false)
                    ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: return bitmap
                if (copied !== bitmap) {
                    tempSoftwareBitmap = copied
                }
                copied
            } else {
                bitmap
            }

            val csClass = if (working === bitmap) {
                initialCsClass
            } else {
                classifyBitmapColorSpace(working)
            }

            when (csClass) {
                BitmapColorSpaceClass.STANDARD_SRGB -> {
                    when (working.config) {
                        Bitmap.Config.ARGB_8888 -> {
                            if (working === tempSoftwareBitmap) {
                                tempSoftwareBitmap = null
                            }
                            working
                        }
                        Bitmap.Config.RGBA_F16 -> {
                            toneMapRgbaF16Bitmap(working, colorSpaceInfo, csClass)
                        }
                        else -> drawFallbackWithDither(working)
                    }
                }
                BitmapColorSpaceClass.OTHER_SDR -> {
                    // Already SDR (e.g. Display-P3, BT.709, Linear sRGB): convert gamut/transfer
                    // to sRGB via Canvas with dithering without applying a second PQ/HLG curve.
                    drawFallbackWithDither(working)
                }
                BitmapColorSpaceClass.UNTAGGED_HDR,
                BitmapColorSpaceClass.EXTENDED_SRGB,
                BitmapColorSpaceClass.LINEAR_EXTENDED_SRGB,
                BitmapColorSpaceClass.EXPLICIT_HDR_PQ,
                BitmapColorSpaceClass.EXPLICIT_HDR_HLG -> {
                    if (working.config == Bitmap.Config.RGBA_F16) {
                        toneMapRgbaF16Bitmap(working, colorSpaceInfo, csClass)
                    } else {
                        val argbSource = if (working.config == Bitmap.Config.ARGB_8888) {
                            working
                        } else {
                            val copied = working.copy(Bitmap.Config.ARGB_8888, false)
                            if (copied != null && copied !== bitmap && copied !== tempSoftwareBitmap) {
                                tempSoftwareBitmap?.let { if (!it.isRecycled) it.recycle() }
                                tempSoftwareBitmap = copied
                            }
                            copied
                        }
                        if (argbSource != null) {
                            toneMapArgb8888Bitmap(argbSource, colorSpaceInfo, csClass)
                        } else {
                            drawFallbackWithDither(working)
                        }
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            drawFallbackWithDither(bitmap)
        } finally {
            val temp = tempSoftwareBitmap
            if (temp != null && temp !== bitmap && !temp.isRecycled) {
                temp.recycle()
            }
        }
    }

    private fun toneMapArgb8888Bitmap(
        source: Bitmap,
        colorSpaceInfo: ColorSpaceInfo,
        csClass: BitmapColorSpaceClass
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = createSdrOutputBitmap(width, height)
        val rowPixels = IntArray(width)
        val transfer = when (csClass) {
            BitmapColorSpaceClass.EXPLICIT_HDR_PQ -> TransferFunction.PQ
            BitmapColorSpaceClass.EXPLICIT_HDR_HLG -> TransferFunction.HLG
            else -> resolveEffectiveTransfer(colorSpaceInfo)
        }
        val gamut = when (csClass) {
            BitmapColorSpaceClass.EXPLICIT_HDR_PQ,
            BitmapColorSpaceClass.EXPLICIT_HDR_HLG -> ColorGamut.BT2020
            else -> resolveEffectiveGamut(colorSpaceInfo)
        }

        for (y in 0 until height) {
            source.getPixels(rowPixels, 0, width, 0, y, width, 1)
            toneMapArgbRow(
                rowPixels = rowPixels,
                width = width,
                y = y,
                transfer = transfer,
                gamut = gamut
            )
            output.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun toneMapRgbaF16Bitmap(
        source: Bitmap,
        colorSpaceInfo: ColorSpaceInfo,
        csClass: BitmapColorSpaceClass
    ): Bitmap {
        val width = source.width
        val height = source.height
        val totalShorts = width.toLong() * height.toLong() * 4L
        if (totalShorts <= 0L || totalShorts > Int.MAX_VALUE.toLong()) {
            return drawFallbackWithDither(source)
        }

        val shortBuffer = ShortBuffer.allocate(totalShorts.toInt())
        source.copyPixelsToBuffer(shortBuffer)
        val halfPixels = shortBuffer.array()
        val encoding = detectF16Encoding(csClass, colorSpaceInfo)
        val gamut = when (csClass) {
            BitmapColorSpaceClass.EXPLICIT_HDR_PQ,
            BitmapColorSpaceClass.EXPLICIT_HDR_HLG -> ColorGamut.BT2020
            else -> resolveEffectiveGamut(colorSpaceInfo)
        }

        val output = createSdrOutputBitmap(width, height)
        val rowPixels = IntArray(width)
        var srcIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rHalf = halfToFloat(halfPixels[srcIndex].toInt() and 0xFFFF)
                val gHalf = halfToFloat(halfPixels[srcIndex + 1].toInt() and 0xFFFF)
                val bHalf = halfToFloat(halfPixels[srcIndex + 2].toInt() and 0xFFFF)
                val aHalf = halfToFloat(halfPixels[srcIndex + 3].toInt() and 0xFFFF)
                srcIndex += 4

                rowPixels[x] = toneMapF16Sample(
                    r = rHalf,
                    g = gHalf,
                    b = bHalf,
                    a = aHalf,
                    x = x,
                    y = y,
                    encoding = encoding,
                    gamut = gamut
                )
            }
            output.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun drawFallbackWithDither(source: Bitmap): Bitmap {
        val output = createSdrOutputBitmap(source.width, source.height)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isFilterBitmap = true
            isDither = true
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun createSdrOutputBitmap(width: Int, height: Int): Bitmap {
        val targetCs = srgbColorSpace
        return if (targetCs != null) {
            createBitmap(width, height, Bitmap.Config.ARGB_8888, true, targetCs)
        } else {
            createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun safeGetNamedColorSpace(named: AndroidColorSpace.Named): AndroidColorSpace? {
        return try {
            AndroidColorSpace.get(named)
        } catch (_: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun safeGetApi34NamedColorSpace(isPq: Boolean): AndroidColorSpace? {
        val named = if (isPq) {
            AndroidColorSpace.Named.BT2020_PQ
        } else {
            AndroidColorSpace.Named.BT2020_HLG
        }
        return safeGetNamedColorSpace(named)
    }

    private fun classifyBitmapColorSpace(bitmap: Bitmap): BitmapColorSpaceClass {
        val bitmapCs = try {
            bitmap.colorSpace
        } catch (_: RuntimeException) {
            null
        } ?: return BitmapColorSpaceClass.UNTAGGED_HDR

        if (bitmapCs == extendedSrgbColorSpace) return BitmapColorSpaceClass.EXTENDED_SRGB
        if (bitmapCs == linearExtendedSrgbColorSpace) return BitmapColorSpaceClass.LINEAR_EXTENDED_SRGB
        if (bitmapCs == bt2020PqColorSpace) return BitmapColorSpaceClass.EXPLICIT_HDR_PQ
        if (bitmapCs == bt2020HlgColorSpace) return BitmapColorSpaceClass.EXPLICIT_HDR_HLG
        if (bitmapCs == srgbColorSpace) return BitmapColorSpaceClass.STANDARD_SRGB

        val csName = try {
            bitmapCs.name
        } catch (_: RuntimeException) {
            ""
        }
        return when {
            csName.startsWith("scRGB-nl", ignoreCase = true) ||
                (csName.contains("Extended sRGB", ignoreCase = true) &&
                    !csName.contains("Linear", ignoreCase = true)) -> BitmapColorSpaceClass.EXTENDED_SRGB
            csName.startsWith("scRGB", ignoreCase = true) ||
                csName.contains("Linear Extended", ignoreCase = true) -> BitmapColorSpaceClass.LINEAR_EXTENDED_SRGB
            csName.contains("PQ", ignoreCase = true) ||
                csName.contains("2084") -> BitmapColorSpaceClass.EXPLICIT_HDR_PQ
            csName.contains("HLG", ignoreCase = true) ||
                csName.contains("STD-B67", ignoreCase = true) -> BitmapColorSpaceClass.EXPLICIT_HDR_HLG
            csName.startsWith("sRGB", ignoreCase = true) -> BitmapColorSpaceClass.STANDARD_SRGB
            else -> BitmapColorSpaceClass.OTHER_SDR
        }
    }

    private fun detectF16Encoding(
        csClass: BitmapColorSpaceClass,
        colorSpaceInfo: ColorSpaceInfo
    ): F16SignalEncoding {
        return when (csClass) {
            BitmapColorSpaceClass.STANDARD_SRGB -> F16SignalEncoding.SRGB
            BitmapColorSpaceClass.EXTENDED_SRGB -> F16SignalEncoding.EXTENDED_SRGB
            BitmapColorSpaceClass.LINEAR_EXTENDED_SRGB -> F16SignalEncoding.LINEAR_EXTENDED_SRGB
            BitmapColorSpaceClass.EXPLICIT_HDR_PQ -> F16SignalEncoding.PQ
            BitmapColorSpaceClass.EXPLICIT_HDR_HLG -> F16SignalEncoding.HLG
            BitmapColorSpaceClass.UNTAGGED_HDR,
            BitmapColorSpaceClass.OTHER_SDR -> when (resolveEffectiveTransfer(colorSpaceInfo)) {
                TransferFunction.HLG -> F16SignalEncoding.HLG
                TransferFunction.LINEAR -> F16SignalEncoding.SCENE_LINEAR
                else -> F16SignalEncoding.PQ
            }
        }
    }

    internal fun resolveEffectiveTransfer(colorSpaceInfo: ColorSpaceInfo): TransferFunction {
        return when (colorSpaceInfo.transferFunction) {
            TransferFunction.PQ,
            TransferFunction.HLG,
            TransferFunction.LINEAR -> colorSpaceInfo.transferFunction
            TransferFunction.SRGB,
            TransferFunction.GAMMA_2_2 -> when (colorSpaceInfo.type) {
                ColorSpaceType.HLG -> TransferFunction.HLG
                ColorSpaceType.HDR10,
                ColorSpaceType.HDR10_PLUS,
                ColorSpaceType.DOLBY_VISION -> TransferFunction.PQ
                ColorSpaceType.SDR -> colorSpaceInfo.transferFunction
            }
        }
    }

    internal fun resolveEffectiveGamut(colorSpaceInfo: ColorSpaceInfo): ColorGamut {
        if (colorSpaceInfo.colorGamut != ColorGamut.BT709) {
            return colorSpaceInfo.colorGamut
        }
        return when (colorSpaceInfo.type) {
            ColorSpaceType.HDR10,
            ColorSpaceType.HDR10_PLUS,
            ColorSpaceType.HLG,
            ColorSpaceType.DOLBY_VISION -> colorSpaceInfo.colorGamut
            ColorSpaceType.SDR -> ColorGamut.BT709
        }
    }

    internal fun toneMapArgbRow(
        rowPixels: IntArray,
        width: Int,
        y: Int,
        transfer: TransferFunction,
        gamut: ColorGamut,
        enableDither: Boolean = true
    ) {
        val pqLut = if (transfer == TransferFunction.PQ) pq8BitToNitsLut else null
        val hlgLut = if (transfer == TransferFunction.HLG) hlg8BitToSceneLinearLut else null
        val srgbLut = if (transfer != TransferFunction.PQ && transfer != TransferFunction.HLG) {
            srgb8BitToLinearLut
        } else {
            null
        }

        for (x in 0 until width) {
            val argb = rowPixels[x]
            val a = (argb ushr 24) and 0xFF
            val r8 = (argb ushr 16) and 0xFF
            val g8 = (argb ushr 8) and 0xFF
            val b8 = argb and 0xFF

            val rNits: Float
            val gNits: Float
            val bNits: Float
            val sourcePeakNits: Float
            val targetPeakNits: Float

            if (pqLut != null) {
                rNits = pqLut[r8]
                gNits = pqLut[g8]
                bNits = pqLut[b8]
                sourcePeakNits = PQ_MAX_NITS
                targetPeakNits = PQ_TARGET_SDR_PEAK_NITS
            } else if (hlgLut != null) {
                val rs = hlgLut[r8]
                val gs = hlgLut[g8]
                val bs = hlgLut[b8]
                val ys = sceneLuminance(rs, gs, bs, gamut)
                val ootfScale = if (ys > 0f) {
                    (HLG_MAX_NITS * ys.toDouble().pow(HLG_OOTF_EXP)).toFloat()
                } else {
                    0f
                }
                rNits = rs * ootfScale
                gNits = gs * ootfScale
                bNits = bs * ootfScale
                sourcePeakNits = HLG_MAX_NITS
                targetPeakNits = HLG_TARGET_SDR_PEAK_NITS
            } else {
                val rl = if (transfer == TransferFunction.LINEAR) r8 / 255f else srgbLut!![r8]
                val gl = if (transfer == TransferFunction.LINEAR) g8 / 255f else srgbLut!![g8]
                val bl = if (transfer == TransferFunction.LINEAR) b8 / 255f else srgbLut!![b8]
                rNits = rl * PQ_MAX_NITS
                gNits = gl * PQ_MAX_NITS
                bNits = bl * PQ_MAX_NITS
                sourcePeakNits = PQ_MAX_NITS
                targetPeakNits = PQ_TARGET_SDR_PEAK_NITS
            }

            rowPixels[x] = mapLinearHdrNitsToSrgbPixel(
                rNits = rNits,
                gNits = gNits,
                bNits = bNits,
                alpha8 = a,
                sourcePeakNits = sourcePeakNits,
                targetPeakNits = targetPeakNits,
                gamut = gamut,
                x = x,
                y = y,
                enableDither = enableDither
            )
        }
    }

    private fun toneMapF16Sample(
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        x: Int,
        y: Int,
        encoding: F16SignalEncoding,
        gamut: ColorGamut,
        enableDither: Boolean = true
    ): Int {
        val safeR = if (r.isNaN()) 0f else r
        val safeG = if (g.isNaN()) 0f else g
        val safeB = if (b.isNaN()) 0f else b
        val alpha8 = ((if (a.isNaN()) 1f else a).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

        return when (encoding) {
            F16SignalEncoding.EXTENDED_SRGB -> {
                val lin709R = extendedSrgbEotf(safeR)
                val lin709G = extendedSrgbEotf(safeG)
                val lin709B = extendedSrgbEotf(safeB)
                // Transform extended BT.709 (where wide-gamut BT.2020 colors have negative channels)
                // into non-negative BT.2020 linear light before applying EETF & gamut compression.
                val r2020 = max(0f, 0.6274039f * lin709R + 0.3292830f * lin709G + 0.0433131f * lin709B)
                val g2020 = max(0f, 0.0690973f * lin709R + 0.9195404f * lin709G + 0.0113623f * lin709B)
                val b2020 = max(0f, 0.0163914f * lin709R + 0.0880133f * lin709G + 0.8955953f * lin709B)
                mapLinearHdrNitsToSrgbPixel(
                    rNits = (r2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    gNits = (g2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    bNits = (b2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    alpha8 = alpha8,
                    sourcePeakNits = PQ_MAX_NITS,
                    targetPeakNits = PQ_TARGET_SDR_PEAK_NITS,
                    gamut = ColorGamut.BT2020,
                    x = x,
                    y = y,
                    enableDither = enableDither
                )
            }
            F16SignalEncoding.LINEAR_EXTENDED_SRGB -> {
                val linR = safeR.coerceIn(-65504f, 65504f)
                val linG = safeG.coerceIn(-65504f, 65504f)
                val linB = safeB.coerceIn(-65504f, 65504f)
                val r2020 = max(0f, 0.6274039f * linR + 0.3292830f * linG + 0.0433131f * linB)
                val g2020 = max(0f, 0.0690973f * linR + 0.9195404f * linG + 0.0113623f * linB)
                val b2020 = max(0f, 0.0163914f * linR + 0.0880133f * linG + 0.8955953f * linB)
                mapLinearHdrNitsToSrgbPixel(
                    rNits = (r2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    gNits = (g2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    bNits = (b2020 * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    alpha8 = alpha8,
                    sourcePeakNits = PQ_MAX_NITS,
                    targetPeakNits = PQ_TARGET_SDR_PEAK_NITS,
                    gamut = ColorGamut.BT2020,
                    x = x,
                    y = y,
                    enableDither = enableDither
                )
            }
            F16SignalEncoding.PQ -> {
                mapLinearHdrNitsToSrgbPixel(
                    rNits = pqEotfNits(safeR),
                    gNits = pqEotfNits(safeG),
                    bNits = pqEotfNits(safeB),
                    alpha8 = alpha8,
                    sourcePeakNits = PQ_MAX_NITS,
                    targetPeakNits = PQ_TARGET_SDR_PEAK_NITS,
                    gamut = gamut,
                    x = x,
                    y = y,
                    enableDither = enableDither
                )
            }
            F16SignalEncoding.HLG -> {
                val rs = hlgInverseOetf(safeR)
                val gs = hlgInverseOetf(safeG)
                val bs = hlgInverseOetf(safeB)
                val ys = sceneLuminance(rs, gs, bs, gamut)
                val ootfScale = if (ys > 0f) {
                    (HLG_MAX_NITS * ys.toDouble().pow(HLG_OOTF_EXP)).toFloat()
                } else {
                    0f
                }
                mapLinearHdrNitsToSrgbPixel(
                    rNits = rs * ootfScale,
                    gNits = gs * ootfScale,
                    bNits = bs * ootfScale,
                    alpha8 = alpha8,
                    sourcePeakNits = HLG_MAX_NITS,
                    targetPeakNits = HLG_TARGET_SDR_PEAK_NITS,
                    gamut = gamut,
                    x = x,
                    y = y,
                    enableDither = enableDither
                )
            }
            F16SignalEncoding.SCENE_LINEAR -> {
                mapLinearHdrNitsToSrgbPixel(
                    rNits = (max(0f, safeR) * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    gNits = (max(0f, safeG) * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    bNits = (max(0f, safeB) * SDR_REFERENCE_WHITE_NITS).coerceAtMost(PQ_MAX_NITS),
                    alpha8 = alpha8,
                    sourcePeakNits = PQ_MAX_NITS,
                    targetPeakNits = PQ_TARGET_SDR_PEAK_NITS,
                    gamut = gamut,
                    x = x,
                    y = y,
                    enableDither = enableDither
                )
            }
            F16SignalEncoding.SRGB -> {
                val dither = if (enableDither) {
                    val threshold = BAYER_8X8[((y and 7) shl 3) or (x and 7)]
                    (threshold + 0.5f) / 64f - 0.5f
                } else {
                    0f
                }
                val outR = quantize8BitWithDither(safeR.coerceIn(0f, 1f), dither)
                val outG = quantize8BitWithDither(safeG.coerceIn(0f, 1f), dither)
                val outB = quantize8BitWithDither(safeB.coerceIn(0f, 1f), dither)
                (alpha8 shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    private fun mapLinearHdrNitsToSrgbPixel(
        rNits: Float,
        gNits: Float,
        bNits: Float,
        alpha8: Int,
        sourcePeakNits: Float,
        targetPeakNits: Float,
        gamut: ColorGamut,
        x: Int,
        y: Int,
        enableDither: Boolean
    ): Int {
        // 1. Hue-preserving ITU-R BT.2408 Annex 5 EETF using maxRGB norm
        val maxChannelNits = max(rNits, max(gNits, bNits))
        val mappedR: Float
        val mappedG: Float
        val mappedB: Float
        if (maxChannelNits <= 0f) {
            mappedR = 0f
            mappedG = 0f
            mappedB = 0f
        } else {
            val mappedMax = bt2408Eetf(
                luminanceNits = maxChannelNits,
                sourcePeakNits = sourcePeakNits,
                targetPeakNits = targetPeakNits
            )
            val ratio = mappedMax / maxChannelNits
            mappedR = rNits * ratio
            mappedG = gNits * ratio
            mappedB = bNits * ratio
        }

        // 2. Linear primary gamut conversion into BT.709 / sRGB
        var r709: Float
        var g709: Float
        var b709: Float
        when (gamut) {
            ColorGamut.BT2020 -> {
                r709 = 1.6604910f * mappedR - 0.5876411f * mappedG - 0.0728499f * mappedB
                g709 = -0.1245505f * mappedR + 1.1328999f * mappedG - 0.0083494f * mappedB
                b709 = -0.0181508f * mappedR - 0.1005789f * mappedG + 1.1187297f * mappedB
            }
            ColorGamut.DCI_P3,
            ColorGamut.DISPLAY_P3 -> {
                r709 = 1.2249401f * mappedR - 0.2249402f * mappedG
                g709 = -0.0420569f * mappedR + 1.0420571f * mappedG
                b709 = -0.0196376f * mappedR - 0.0786361f * mappedG + 1.0982735f * mappedB
            }
            ColorGamut.BT709 -> {
                r709 = mappedR
                g709 = mappedG
                b709 = mappedB
            }
        }

        // 3. Constant-luminance out-of-gamut soft compression (prevents negative-channel clipping
        //    hue shifts on saturated BT.2020 neon colors).
        val minC = min(r709, min(g709, b709))
        if (minC < 0f) {
            val luma = (0.2126f * r709 + 0.7152f * g709 + 0.0722f * b709).coerceAtLeast(0f)
            if (luma <= 0f) {
                r709 = 0f
                g709 = 0f
                b709 = 0f
            } else {
                val desat = luma / (luma - minC)
                r709 = luma + desat * (r709 - luma)
                g709 = luma + desat * (g709 - luma)
                b709 = luma + desat * (b709 - luma)
            }
        }
        val maxC = max(r709, max(g709, b709))
        if (maxC > 1f) {
            val invMax = 1f / maxC
            r709 *= invMax
            g709 *= invMax
            b709 *= invMax
        }

        // 4. IEC 61966-2-1 sRGB OETF + Bayer 8x8 ordered dithering
        val sr = linearToSrgb(r709)
        val sg = linearToSrgb(g709)
        val sb = linearToSrgb(b709)

        val dither = if (enableDither) {
            val threshold = BAYER_8X8[((y and 7) shl 3) or (x and 7)]
            (threshold + 0.5f) / 64f - 0.5f
        } else {
            0f
        }

        val outR = quantize8BitWithDither(sr, dither)
        val outG = quantize8BitWithDither(sg, dither)
        val outB = quantize8BitWithDither(sb, dither)

        return (alpha8 shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun quantize8BitWithDither(srgb: Float, dither: Float): Int {
        if (srgb <= 0f) return 0
        if (srgb >= 1f) return 255
        return (srgb * 255f + dither + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun sceneLuminance(r: Float, g: Float, b: Float, gamut: ColorGamut): Float {
        return when (gamut) {
            ColorGamut.BT2020 -> 0.2627f * r + 0.6780f * g + 0.0593f * b
            ColorGamut.DCI_P3,
            ColorGamut.DISPLAY_P3 -> 0.2289746f * r + 0.6917385f * g + 0.0792869f * b
            ColorGamut.BT709 -> 0.2126f * r + 0.7152f * g + 0.0722f * b
        }.coerceAtLeast(0f)
    }

    /**
     * ITU-R BT.2408 Annex 5 Electrical-Electrical Transfer Function (EETF).
     *
     * Maps HDR linear luminance `[0, sourcePeakNits]` to normalized SDR linear light `[0, 1]`
     * with 1:1 preservation below the perceptual knee `Ks` and a strictly monotonic C1 Hermite
     * spline roll-off across `[Ks, 1.0]`.
     */
    internal fun bt2408Eetf(
        luminanceNits: Float,
        sourcePeakNits: Float = PQ_MAX_NITS,
        targetPeakNits: Float = PQ_TARGET_SDR_PEAK_NITS
    ): Float {
        if (luminanceNits <= 0f) return 0f
        if (targetPeakNits >= sourcePeakNits) {
            return (luminanceNits / targetPeakNits).coerceIn(0f, 1f)
        }

        val clampedNits = luminanceNits.coerceIn(0f, sourcePeakNits)
        val sourcePeakPq = pqOetf(sourcePeakNits)
        if (sourcePeakPq <= 0f) return 0f

        val e1 = (pqOetf(clampedNits) / sourcePeakPq).coerceIn(0f, 1f)
        val maxLum = (pqOetf(targetPeakNits) / sourcePeakPq).coerceIn(0f, 1f)
        val ks = (1.5f * maxLum - 0.5f).coerceAtLeast(0f)

        val e2 = if (e1 <= ks || ks >= 0.9999f) {
            e1.coerceAtMost(maxLum)
        } else {
            val t = ((e1 - ks) / (1f - ks)).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            (2f * t3 - 3f * t2 + 1f) * ks +
                (t3 - 2f * t2 + t) * (1f - ks) +
                (-2f * t3 + 3f * t2) * maxLum
        }

        val targetNits = pqEotfNits(e2 * sourcePeakPq)
        return (targetNits / targetPeakNits).coerceIn(0f, 1f)
    }

    /**
     * SMPTE ST 2084 (PQ) EOTF: non-linear `[0, 1]` -> absolute luminance `[0, 10000]` nits.
     */
    internal fun pqEotfNits(ePrime: Float): Float {
        val e = ePrime.coerceIn(0f, 1f).toDouble()
        if (e <= 0.0) return 0f
        val ep = e.pow(1.0 / PQ_M2)
        val num = max(ep - PQ_C1, 0.0)
        val den = PQ_C2 - PQ_C3 * ep
        if (den <= 0.0) return PQ_MAX_NITS
        return ((num / den).pow(1.0 / PQ_M1) * 10_000.0).toFloat().coerceIn(0f, PQ_MAX_NITS)
    }

    /**
     * SMPTE ST 2084 (PQ) inverse EOTF (OETF): absolute luminance `[0, 10000]` nits -> `[0, 1]`.
     */
    internal fun pqOetf(nits: Float): Float {
        val y = (nits.coerceIn(0f, PQ_MAX_NITS) / 10_000f).toDouble()
        if (y <= 0.0) return 0f
        val ym = y.pow(PQ_M1)
        val num = PQ_C1 + PQ_C2 * ym
        val den = 1.0 + PQ_C3 * ym
        return (num / den).pow(PQ_M2).toFloat().coerceIn(0f, 1f)
    }

    /**
     * ARIB STD-B67 / ITU-R BT.2100 (HLG) inverse OETF: non-linear `[0, 1]` -> scene-linear `[0, 1]`.
     */
    internal fun hlgInverseOetf(ePrime: Float): Float {
        val e = ePrime.coerceIn(0f, 1f).toDouble()
        if (e <= 0.0) return 0f
        val linear = if (e <= 0.5) {
            (e * e) / 3.0
        } else {
            (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0
        }
        return linear.toFloat().coerceIn(0f, 1f)
    }

    /**
     * IEC 61966-2-1 sRGB OETF: linear `[0, 1]` -> non-linear sRGB `[0, 1]`.
     */
    internal fun linearToSrgb(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) {
            12.92f * v
        } else {
            (1.055 * v.toDouble().pow(1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun srgbEotf(encoded: Float): Float {
        val v = encoded.coerceIn(0f, 1f)
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055) / 1.055).pow(2.4).toFloat()
        }
    }

    internal fun extendedSrgbEotf(encoded: Float): Float {
        if (encoded.isNaN()) return 0f
        val clamped = encoded.coerceIn(-65504f, 65504f)
        val v = abs(clamped)
        val magnitude = if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055) / 1.055).pow(2.4).toFloat()
        }
        return if (clamped < 0f) -magnitude else magnitude
    }

    /**
     * Pure bitwise IEEE 754 binary16 -> binary32 conversion (safe across Android API 26+ and JVM).
     */
    internal fun halfToFloat(halfBits: Int): Float {
        val h = halfBits and 0xFFFF
        val sign = (h ushr 15) and 0x1
        val exp = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF

        return when (exp) {
            0 -> {
                if (mantissa == 0) {
                    Float.fromBits(sign shl 31)
                } else {
                    val value = mantissa.toFloat() * 5.9604645e-8f // 2^-24
                    if (sign != 0) -value else value
                }
            }
            0x1F -> {
                if (mantissa == 0) {
                    Float.fromBits((sign shl 31) or 0x7F800000)
                } else {
                    Float.NaN
                }
            }
            else -> {
                Float.fromBits((sign shl 31) or ((exp + 112) shl 23) or (mantissa shl 13))
            }
        }
    }
}
