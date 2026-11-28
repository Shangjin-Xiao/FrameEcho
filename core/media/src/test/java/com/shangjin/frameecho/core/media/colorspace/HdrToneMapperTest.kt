package com.shangjin.frameecho.core.media.colorspace

import android.graphics.Bitmap
import android.graphics.ColorSpace as AndroidColorSpace
import com.shangjin.frameecho.core.model.ColorGamut
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ColorSpaceType
import com.shangjin.frameecho.core.model.TransferFunction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.Buffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

class HdrToneMapperTest {

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } answers {
            createPixelBackedArgbBitmap(firstArg(), secondArg())
        }
        every {
            Bitmap.createBitmap(
                any<Int>(),
                any<Int>(),
                any<Bitmap.Config>(),
                any<Boolean>(),
                any<AndroidColorSpace>()
            )
        } answers {
            createPixelBackedArgbBitmap(firstArg(), secondArg(), arg(4))
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `SDR and already tone-mapped HDR bitmaps pass through untouched`() {
        val sourceBitmap = createPixelBackedArgbBitmap(4, 4)
        val sdrInfo = ColorSpaceInfo(type = ColorSpaceType.SDR)
        val hdr10Info = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )

        assertSame(sourceBitmap, HdrToneMapper.process(sourceBitmap, sdrInfo))
        assertSame(
            sourceBitmap,
            HdrToneMapper.process(sourceBitmap, hdr10Info, isAlreadyToneMapped = true)
        )
    }

    @Test
    fun `PQ code values 149, 200, and 255 produce strictly increasing brightness without clipping all to 255`() {
        val hdr10Info = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )

        val inputPixels = intArrayOf(
            packGray(149), // ~210.2 nits (just above 203 nits SDR reference white)
            packGray(200), // ~1346.9 nits specular highlight
            packGray(255)  // 10000.0 nits PQ mastering peak
        )
        val inputBitmap = createPixelBackedArgbBitmap(
            width = 3,
            height = 1,
            initialPixels = inputPixels,
            taggedColorSpace = null
        )

        val outputBitmap = HdrToneMapper.process(inputBitmap, hdr10Info)
        assertNotSame(inputBitmap, outputBitmap)

        val outPixels = IntArray(3)
        outputBitmap.getPixels(outPixels, 0, 3, 0, 0, 3, 1)

        val luma149 = srgbLuma8(outPixels[0])
        val luma200 = srgbLuma8(outPixels[1])
        val luma255 = srgbLuma8(outPixels[2])

        assertTrue(
            "Expected PQ 149/255 ($luma149) < PQ 200/255 ($luma200)",
            luma149 < luma200
        )
        assertTrue(
            "Expected PQ 200/255 ($luma200) < PQ 255/255 ($luma255)",
            luma200 < luma255
        )
        assertTrue(
            "PQ 149/255 (~210 nits) must not hard-clip to 255, was $luma149",
            luma149 < 220.0
        )
        assertTrue(
            "PQ 200/255 (~1347 nits) must retain highlight headroom below 255, was $luma200",
            luma200 < 252.0
        )
    }

    @Test
    fun `full PQ highlight ramp from 149 to 255 is non-decreasing across perceptual EETF`() {
        var prev = HdrToneMapper.bt2408Eetf(HdrToneMapper.pqEotfNits(149f / 255f))
        for (code in 150..255) {
            val current = HdrToneMapper.bt2408Eetf(HdrToneMapper.pqEotfNits(code / 255f))
            assertTrue(
                "EETF must be monotonic at PQ code $code: prev=$prev, current=$current",
                current > prev
            )
            prev = current
        }
    }

    @Test
    fun `untagged and SRGB-mislabeled BT2020 PQ bitmaps restore BT709 gamut and tonal hierarchy`() {
        val hdr10Info = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )
        val mislabeledSrgb = mockk<AndroidColorSpace> {
            every { name } returns "sRGB IEC61966-2.1"
        }

        // Pixel 0: Mid-tone saturated BT.2020 red (R=140 ~145 nits, G=60 ~2 nits, B=60 ~2 nits)
        //          Note: In BT.2020 -> BT.709, pure BT.2020 red contributes negative green before
        //          gamut compression and boosts red relative to green/blue compared to naive sRGB!
        // Pixel 1: Shadow gray (PQ 80/255 ~10 nits)
        // Pixel 2: Midtone diffuse white (PQ 148/255 ~200.6 nits)
        // Pixel 3: Specular highlight (PQ 220/255 ~2955 nits)
        val rawPixels = intArrayOf(
            packRgb(140, 85, 85),
            packGray(80),
            packGray(148),
            packGray(220)
        )

        val bt709ReferenceInfo = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT709,
            bitDepth = 10
        )
        val bt709Control = HdrToneMapper.process(
            createPixelBackedArgbBitmap(4, 1, rawPixels.clone(), null),
            bt709ReferenceInfo
        )
        val bt709Out = IntArray(4)
        bt709Control.getPixels(bt709Out, 0, 4, 0, 0, 4, 1)
        val bt709Red = (bt709Out[0] ushr 16) and 0xFF
        val bt709Green = (bt709Out[0] ushr 8) and 0xFF
        val bt709Blue = bt709Out[0] and 0xFF
        // Without BT.2020->BT.709 matrix, equal G/B input stays equal G/B in output
        assertEquals(bt709Green, bt709Blue, 2)

        for (tag in listOf(null, mislabeledSrgb)) {
            val source = createPixelBackedArgbBitmap(
                width = 4,
                height = 1,
                initialPixels = rawPixels.clone(),
                taggedColorSpace = tag
            )
            val mapped = HdrToneMapper.process(source, hdr10Info)
            val out = IntArray(4)
            mapped.getPixels(out, 0, 4, 0, 0, 4, 1)

            // Verify BT.2020 -> BT.709 gamut conversion expanded red saturation vs BT.709 control:
            // BT.2020 Red primary (0.708, 0.292) boosts R_709 (+1.6605) and subtracts G_709 (-0.1246)
            // far more than B_709 (-0.0182), yielding higher R and lower G than un-converted BT.709!
            val outRed = (out[0] ushr 16) and 0xFF
            val outGreen = (out[0] ushr 8) and 0xFF
            val outBlue = out[0] and 0xFF
            assertTrue("Expected BT.2020->BT.709 red boost ($outRed > $bt709Red)", outRed > bt709Red)
            assertTrue(
                "Expected BT.2020->BT.709 matrix to suppress green ($outGreen < $bt709Green and $outGreen < $outBlue)",
                outGreen < bt709Green && outGreen < outBlue
            )

            // Verify tonal hierarchy shadow < diffuse white < specular highlight
            val shadow = srgbLuma8(out[1])
            val diffuse = srgbLuma8(out[2])
            val highlight = srgbLuma8(out[3])
            assertTrue("Expected shadow ($shadow) < diffuse ($diffuse)", shadow < diffuse)
            assertTrue("Expected diffuse ($diffuse) < highlight ($highlight)", diffuse < highlight)
        }
    }

    @Test
    fun `untagged BT2020 HLG bitmap restores OOTF contrast, BT709 gamut, and highlight roll-off`() {
        val hlgInfo = ColorSpaceInfo(
            type = ColorSpaceType.HLG,
            transferFunction = TransferFunction.HLG,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )

        // HLG 38% (~97/255 mid-gray), 75% (191/255 = 203 nits reference white),
        // 90% (230/255 ~527 nits), 100% (255/255 = 1000 nits peak), plus saturated BT.2020 green
        val rawPixels = intArrayOf(
            packGray(97),
            packGray(191),
            packGray(230),
            packGray(255),
            packRgb(80, 185, 80)
        )
        val untaggedHlgBitmap = createPixelBackedArgbBitmap(
            width = 5,
            height = 1,
            initialPixels = rawPixels,
            taggedColorSpace = null
        )

        val mapped = HdrToneMapper.process(untaggedHlgBitmap, hlgInfo)
        val out = IntArray(5)
        mapped.getPixels(out, 0, 5, 0, 0, 5, 1)

        val lumaMid = srgbLuma8(out[0])
        val lumaRefWhite = srgbLuma8(out[1])
        val lumaHigh = srgbLuma8(out[2])
        val lumaPeak = srgbLuma8(out[3])

        assertTrue("HLG mid ($lumaMid) < refWhite ($lumaRefWhite)", lumaMid < lumaRefWhite)
        assertTrue("HLG refWhite ($lumaRefWhite) < 90% highlight ($lumaHigh)", lumaRefWhite < lumaHigh)
        assertTrue("HLG 90% highlight ($lumaHigh) < 100% peak ($lumaPeak)", lumaHigh < lumaPeak)
        assertTrue("HLG 75% reference white should not clip to 255, was $lumaRefWhite", lumaRefWhite < 230.0)

        // Saturated BT.2020 green stays in valid [0..255] bounds without channel wrap/negative clip artifacts
        val grR = (out[4] ushr 16) and 0xFF
        val grG = (out[4] ushr 8) and 0xFF
        val grB = out[4] and 0xFF
        assertTrue("Expected green dominance ($grG > $grR and $grG > $grB)", grG > grR && grG > grB)
    }

    @Test
    fun `RGBA_F16 extended sRGB and raw PQ bitmaps tone-map highlights smoothly without clipping`() {
        val hdr10Info = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )
        val scRgbLinearCs = mockk<AndroidColorSpace> {
            every { name } returns "scRGB IEC 61966-2-2:2003"
        }

        // 3 pixels in LINEAR_EXTENDED_SRGB where 1.0 = 203 nits SDR white, 4.0 = 812 nits, 20.0 = 4060 nits
        val f16ScRgb = createPixelBackedRgbaF16Bitmap(
            width = 3,
            height = 1,
            rgbaFloats = floatArrayOf(
                1.0f, 1.0f, 1.0f, 1.0f,
                4.0f, 4.0f, 4.0f, 1.0f,
                20.0f, 20.0f, 20.0f, 1.0f
            ),
            taggedColorSpace = scRgbLinearCs
        )

        val mapped = HdrToneMapper.process(f16ScRgb, hdr10Info)
        val out = IntArray(3)
        mapped.getPixels(out, 0, 3, 0, 0, 3, 1)

        val luma1x = srgbLuma8(out[0])
        val luma4x = srgbLuma8(out[1])
        val luma20x = srgbLuma8(out[2])

        assertTrue("Expected 1.0x ($luma1x) < 4.0x ($luma4x)", luma1x < luma4x)
        assertTrue("Expected 4.0x ($luma4x) < 20.0x ($luma20x)", luma4x < luma20x)
        assertTrue("Expected 1.0x SDR white not clipped ($luma1x < 220)", luma1x < 220.0)
    }

    @Test
    fun `ordered spatial dithering breaks up flat 8-bit quantization steps on mid-LSB HDR values`() {
        val hdr10Info = ColorSpaceInfo(
            type = ColorSpaceType.HDR10,
            transferFunction = TransferFunction.PQ,
            colorGamut = ColorGamut.BT2020,
            bitDepth = 10
        )
        // Fill an 8x8 block with a constant fractional midtone PQ value whose mapped sRGB value
        // lands mid-way between two 8-bit integers, verifying Bayer dithering distributes across both.
        val block = IntArray(64) { packGray(110) }
        val input = createPixelBackedArgbBitmap(8, 8, block)
        val output = HdrToneMapper.process(input, hdr10Info)

        val outPixels = IntArray(64)
        output.getPixels(outPixels, 0, 8, 0, 0, 8, 8)
        val distinctReds = outPixels.map { (it ushr 16) and 0xFF }.toSet()

        // Ordered dither must produce adjacent quantized levels (at most 1 LSB apart) rather than severe noise
        assertTrue("Dithered levels must stay within 1 LSB: $distinctReds", distinctReds.max() - distinctReds.min() <= 1)
        assertFalse(distinctReds.isEmpty())
    }

    // ── Test helpers ────────────────────────────────────────────────────

    private fun packGray(v: Int): Int = packRgb(v, v, v)

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun srgbLuma8(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) {
        assertTrue(
            "Expected $expected ± $delta, but was $actual",
            abs(expected - actual) <= delta
        )
    }

    private fun createPixelBackedArgbBitmap(
        width: Int,
        height: Int,
        initialPixels: IntArray = IntArray(width * height),
        taggedColorSpace: AndroidColorSpace? = null
    ): Bitmap {
        val backing = initialPixels.copyOf(width * height)
        return mockk(relaxed = true) {
            every { this@mockk.width } returns width
            every { this@mockk.height } returns height
            every { config } returns Bitmap.Config.ARGB_8888
            every { isRecycled } returns false
            every { colorSpace } returns taggedColorSpace
            every {
                getPixels(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val dst = arg<IntArray>(0)
                val offset = arg<Int>(1)
                val stride = arg<Int>(2)
                val x = arg<Int>(3)
                val y = arg<Int>(4)
                val w = arg<Int>(5)
                val h = arg<Int>(6)
                for (row in 0 until h) {
                    System.arraycopy(
                        backing,
                        (y + row) * width + x,
                        dst,
                        offset + row * stride,
                        w
                    )
                }
            }
            every {
                setPixels(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val src = arg<IntArray>(0)
                val offset = arg<Int>(1)
                val stride = arg<Int>(2)
                val x = arg<Int>(3)
                val y = arg<Int>(4)
                val w = arg<Int>(5)
                val h = arg<Int>(6)
                for (row in 0 until h) {
                    System.arraycopy(
                        src,
                        offset + row * stride,
                        backing,
                        (y + row) * width + x,
                        w
                    )
                }
            }
        }
    }

    private fun createPixelBackedRgbaF16Bitmap(
        width: Int,
        height: Int,
        rgbaFloats: FloatArray,
        taggedColorSpace: AndroidColorSpace? = null
    ): Bitmap {
        val shorts = ShortArray(width * height * 4) { idx ->
            floatToHalfBits(rgbaFloats[idx]).toShort()
        }
        return mockk(relaxed = true) {
            every { this@mockk.width } returns width
            every { this@mockk.height } returns height
            every { config } returns Bitmap.Config.RGBA_F16
            every { isRecycled } returns false
            every { colorSpace } returns taggedColorSpace
            every { copyPixelsToBuffer(any<Buffer>()) } answers {
                val dst = firstArg<Buffer>() as ShortBuffer
                dst.put(shorts)
            }
        }
    }

    private fun floatToHalfBits(value: Float): Int {
        val fbits = value.toBits()
        val sign = (fbits ushr 16) and 0x8000
        val rounded = (fbits and 0x7FFFFFFF) + 0x1000
        if (rounded >= 0x47800000) {
            if ((fbits and 0x7FFFFFFF) >= 0x47800000) {
                if (rounded < 0x7F800000) return sign or 0x7C00
                return sign or 0x7C00 or ((fbits and 0x007FFFFF) ushr 13)
            }
            return sign or 0x7BFF
        }
        if (rounded >= 0x38800000) {
            return sign or ((rounded - 0x38000000) ushr 13)
        }
        if (rounded < 0x33000000) {
            return sign
        }
        val exp = (fbits and 0x7FFFFFFF) ushr 23
        return sign or ((((fbits and 0x7FFFFF) or 0x800000) + (0x800000 ushr (exp - 102))) ushr (126 - exp))
    }
}
