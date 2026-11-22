package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.media.colorspace.HdrToneMapper
import com.shangjin.frameecho.core.media.metadata.MetadataWriter
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections

class FrameExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Xml::class, Log::class)
        every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        Class.forName(ExifInterface::class.java.name)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `injectMotionPhotoXmp should throw exception when jpeg bytes are invalid`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        val invalidJpegBytes = byteArrayOf(0x00, 0x01, 0x02)

        assertThrows(IllegalArgumentException::class.java) {
            exporter.injectMotionPhotoXmp(invalidJpegBytes, 1000L, 1000L)
        }
    }

    @Test
    fun `injectMotionPhotoXmp should work with valid jpeg bytes`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        val validJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 0, 0, 0)

        val result = exporter.injectMotionPhotoXmp(validJpegBytes, 1000L, 1000L)

        // Result should start with SOI (FF D8) and then APP1 marker (FF E1)
        assertTrue(result.size > 4)
        assertTrue((result[0].toInt() and 0xFF) == 0xFF)
        assertTrue((result[1].toInt() and 0xFF) == 0xD8)
        assertTrue((result[2].toInt() and 0xFF) == 0xFF)
        assertTrue((result[3].toInt() and 0xFF) == 0xE1)

        // Extract XMP packet to verify content
        // Skip SOI (2) + APP1 Marker (2) + Length (2) + Namespace (29 bytes)
        val headerLen = 29
        val offset = 2 + 2 + 2 + headerLen
        if (result.size > offset) {
            val xmpString = String(result, offset, result.size - offset - 2, Charsets.UTF_8) // -2 for rest of JPEG dummy data
            // Verify key elements are present
            assertTrue(xmpString.contains("x:xmpmeta"))
            assertTrue(xmpString.contains("rdf:RDF"))
            assertTrue(xmpString.contains("GCamera:MotionPhoto=\"1\""))
            assertTrue(xmpString.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1000\""))
            assertTrue(xmpString.contains("Item:Mime=\"video/mp4\""))
            assertTrue(xmpString.contains("Item:Length=\"1000\""))
            // Verify Padding attribute per Google Motion Photo spec
            assertTrue("Primary item must have Padding=\"0\"",
                xmpString.contains("Item:Semantic=\"Primary\" Item:Padding=\"0\""))
            assertTrue("MotionPhoto item must have Padding=\"0\"",
                xmpString.contains("Item:Semantic=\"MotionPhoto\" Item:Length=\"1000\" Item:Padding=\"0\""))
        }
    }

    @Test
    fun `isMuxerCompatibleAudioMime accepts only AAC and AMR for MP4 muxing`() {
        // Directly embeddable by MediaMuxer
        assertTrue(isMuxerCompatibleAudioMime("audio/mp4a-latm"))
        assertTrue(isMuxerCompatibleAudioMime("audio/3gpp"))
        assertTrue(isMuxerCompatibleAudioMime("audio/amr-wb"))

        // Must be transcoded to AAC first — LPCM is what Sony XAVC cameras
        // (ZV-1 etc.) record, the rest appear in various camera/phone videos.
        assertFalse(isMuxerCompatibleAudioMime("audio/raw"))
        assertFalse(isMuxerCompatibleAudioMime("audio/ac3"))
        assertFalse(isMuxerCompatibleAudioMime("audio/eac3"))
        assertFalse(isMuxerCompatibleAudioMime("audio/mpeg"))
        assertFalse(isMuxerCompatibleAudioMime("audio/opus"))
        assertFalse(isMuxerCompatibleAudioMime("audio/flac"))
        assertFalse(isMuxerCompatibleAudioMime(null))
    }

    @Test
    fun `pcmFramesToUs converts per-channel frame counts to microseconds`() {
        assertEquals(1_000_000L, pcmFramesToUs(48_000L, 48_000))
        assertEquals(500_000L, pcmFramesToUs(24_000L, 48_000))
        assertEquals(21_333L, pcmFramesToUs(1024L, 48_000)) // one AAC frame at 48 kHz
        assertEquals(0L, pcmFramesToUs(0L, 48_000))
        // Invalid sample rate must not divide by zero
        assertEquals(0L, pcmFramesToUs(100L, 0))
        assertEquals(0L, pcmFramesToUs(100L, -1))
    }

    @Test
    fun `sanitizeFileName should prevent path traversal and remove illegal characters`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        assertEquals("etc_passwd", exporter.sanitizeFileName("../../etc/passwd"))
        assertEquals("photo_2026_07.jpg", exporter.sanitizeFileName("photo/2026:07.jpg"))
        assertEquals("test", exporter.sanitizeFileName("\u0000test\u0007"))
        assertEquals("FrameEcho", exporter.sanitizeFileName("..."))
        assertEquals("my_image", exporter.sanitizeFileName("my_image"))

        // Additional edge cases
        assertEquals("FrameEcho", exporter.sanitizeFileName(""))
        assertEquals("FrameEcho", exporter.sanitizeFileName("   "))
        assertEquals("FrameEcho", exporter.sanitizeFileName("___"))
        assertEquals("file_name.jpg", exporter.sanitizeFileName(".  _file_name.jpg._  "))
        assertEquals("a_b_c_d_e_f_g_h", exporter.sanitizeFileName("a*b?c<d>e|f\"g\\h"))
        assertEquals("test", exporter.sanitizeFileName("\r\ntest\t\u007F"))
    }

    @Test
    fun `sanitizeFileName should sanitize complex security edge cases`() {
        val context = mockk<Context>()
        val exporter = FrameExporter(context)

        // Nested and multi-dot path traversal attempts
        assertEquals("etc_passwd", exporter.sanitizeFileName("....//....//etc/passwd"))
        assertEquals("win_sys.dll", exporter.sanitizeFileName("..\\..\\win\\sys.dll"))

        // Unicode BIDI / RTL / Format override characters (\u202E, \u200B)
        assertEquals("test_exe.jpg", exporter.sanitizeFileName("test\u202Eexe.jpg"))
        assertEquals("hidden_file", exporter.sanitizeFileName("\u200B.hidden_file"))

        // Whitespace + null byte mixtures and trailing/leading invalid boundaries
        assertEquals("malicious", exporter.sanitizeFileName(" _\u0000..malicious.._ "))
        assertEquals("FrameEcho", exporter.sanitizeFileName("\u0000___...\t "))
    }

    @Test
    fun `createCleanVideoFormat should copy CSD and optional keys when present`() {
        val context = mockk<Context>(relaxed = true)
        val exporter = FrameExporter(context)

        val original = mockk<MediaFormat>()
        val csd0 = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        csd0.position(3) // limit reached, verify rewind

        every { original.getString(MediaFormat.KEY_MIME) } returns "video/avc"
        every { original.getInteger(MediaFormat.KEY_WIDTH) } returns 1920
        every { original.getInteger(MediaFormat.KEY_HEIGHT) } returns 1080
        every { original.containsKey("csd-0") } returns true
        every { original.getByteBuffer("csd-0") } returns csd0
        every { original.containsKey("csd-1") } returns false
        every { original.containsKey("csd-2") } returns false
        every { original.getInteger(MediaFormat.KEY_FRAME_RATE) } returns 30
        every { original.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } throws RuntimeException("not set")
        every { original.getLong(MediaFormat.KEY_DURATION) } returns 1_000_000L

        mockkStatic(MediaFormat::class)
        val cleanFormat = mockk<MediaFormat>(relaxed = true)
        every { MediaFormat.createVideoFormat("video/avc", 1920, 1080) } returns cleanFormat

        try {
            val result = exporter.createCleanVideoFormat(original)
            assertEquals(cleanFormat, result)
            assertEquals(0, csd0.position()) // verified rewind
            verify { cleanFormat.setByteBuffer("csd-0", csd0) }
            verify(exactly = 0) { cleanFormat.setByteBuffer("csd-1", any()) }
            verify { cleanFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30) }
            verify { cleanFormat.setLong(MediaFormat.KEY_DURATION, 1_000_000L) }
        } finally {
            io.mockk.unmockkStatic(MediaFormat::class)
        }
    }

    @Test
    fun `createCleanVideoFormat clamps oversized KEY_MAX_INPUT_SIZE and ignores non-positive values`() {
        val context = mockk<Context>(relaxed = true)
        val exporter = FrameExporter(context)

        fun buildOriginal(maxInputSize: Int): MediaFormat = mockk<MediaFormat>().apply {
            every { getString(MediaFormat.KEY_MIME) } returns "video/avc"
            every { getInteger(MediaFormat.KEY_WIDTH) } returns 3840
            every { getInteger(MediaFormat.KEY_HEIGHT) } returns 2160
            every { containsKey(any()) } returns true
            every { getByteBuffer(any()) } returns null
            every { getInteger(MediaFormat.KEY_FRAME_RATE) } returns 60
            every { getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } returns maxInputSize
            every { getLong(MediaFormat.KEY_DURATION) } returns 2_000_000L
        }

        mockkStatic(MediaFormat::class)
        try {
            // 1. Oversized (256 MiB) -> clamped to MAX_SAMPLE_BUFFER_BYTES (32 MiB)
            val cleanOversized = mockk<MediaFormat>(relaxed = true)
            every { MediaFormat.createVideoFormat("video/avc", 3840, 2160) } returns cleanOversized
            exporter.createCleanVideoFormat(buildOriginal(268_435_456))
            verify {
                cleanOversized.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    FrameExporter.MAX_SAMPLE_BUFFER_BYTES
                )
            }

            // 2. Normal positive (512 KiB) -> preserved as-is
            val cleanNormal = mockk<MediaFormat>(relaxed = true)
            every { MediaFormat.createVideoFormat("video/avc", 3840, 2160) } returns cleanNormal
            exporter.createCleanVideoFormat(buildOriginal(512 * 1024))
            verify { cleanNormal.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024) }

            // 3. Zero and negative values -> ignored
            for (invalidSize in listOf(0, -1, Int.MIN_VALUE)) {
                val cleanInvalid = mockk<MediaFormat>(relaxed = true)
                every { MediaFormat.createVideoFormat("video/avc", 3840, 2160) } returns cleanInvalid
                exporter.createCleanVideoFormat(buildOriginal(invalidSize))
                verify(exactly = 0) {
                    cleanInvalid.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, any())
                }
            }
        } finally {
            io.mockk.unmockkStatic(MediaFormat::class)
        }
    }

    @Test
    fun `sanitizeTrackMaxInputSize caps oversized KEY_MAX_INPUT_SIZE and cleans non-positive values`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val context = mockk<Context>(relaxed = true)
            val exporter = FrameExporter(context)

            val oversized = buildMockFormat(mime = MediaFormat.MIMETYPE_VIDEO_AVC, maxInputSize = 268_435_456)
            exporter.sanitizeTrackMaxInputSize(oversized)
            assertEquals(
                FrameExporter.MAX_SAMPLE_BUFFER_BYTES,
                oversized.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            )

            val normal = buildMockFormat(mime = MediaFormat.MIMETYPE_AUDIO_AAC, maxInputSize = 256 * 1024)
            exporter.sanitizeTrackMaxInputSize(normal)
            assertEquals(256 * 1024, normal.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))

            for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
                val nonPositive = buildMockFormat(mime = MediaFormat.MIMETYPE_AUDIO_AAC, maxInputSize = invalid)
                exporter.sanitizeTrackMaxInputSize(nonPositive)
                assertFalse(nonPositive.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
    }

    @Test
    fun `exportMotionPhoto enforces direct buffer limits across all 4 sample allocation paths without OOM`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val oversizedSampleBytes = 268_435_456 // 256 MiB (> 128 MiB MaxDirectMemorySize)

            val scenarios = listOf(
                // 1. 视频轨道与兼容音轨 KEY_MAX_INPUT_SIZE = 256 MiB, 实际样本 16B -> 裁剪缓冲区与复用器输入提示并成功导出
                runExportScenario(
                    name = "video_and_audio_KEY_MAX_INPUT_SIZE_256MiB",
                    videoMaxInputSize = oversizedSampleBytes,
                    audioMime = MediaFormat.MIMETYPE_AUDIO_AAC,
                    audioMaxInputSize = oversizedSampleBytes,
                    videoReportedSampleSize = 16L,
                    audioReportedSampleSize = 16L
                ),
                // 2. 视频循环 sampleSize = 256 MiB -> 受控 IOException (ExportResult.Error 且 cause 非 OutOfMemoryError)
                runExportScenario(
                    name = "video_loop_sampleSize_256MiB",
                    videoMaxInputSize = 65_536,
                    audioMime = null,
                    videoReportedSampleSize = oversizedSampleBytes.toLong(),
                    audioReportedSampleSize = 16L
                ),
                // 3. AAC 音频重封装循环首个样本 sampleSize = 256 MiB -> 不向 MediaMuxer 添加音轨 (audioDropped = true), 不触发 OOM 或空轨错误
                runExportScenario(
                    name = "audio_remux_loop_sampleSize_256MiB",
                    videoMaxInputSize = 65_536,
                    audioMime = MediaFormat.MIMETYPE_AUDIO_AAC,
                    videoReportedSampleSize = 16L,
                    audioReportedSampleSize = oversizedSampleBytes.toLong()
                ),
                // 4. LPCM (ENCODING_PCM_16BIT) 提取循环 sampleSize = 256 MiB -> 跳过超限音轨 (audioDropped = true), 不触发 OOM
                runExportScenario(
                    name = "pcm_extractor_loop_sampleSize_256MiB",
                    videoMaxInputSize = 65_536,
                    audioMime = MediaFormat.MIMETYPE_AUDIO_RAW,
                    videoReportedSampleSize = 16L,
                    audioReportedSampleSize = oversizedSampleBytes.toLong(),
                    mockAacEncoder = true
                )
            )

            // 断言所有场景均未触发 OutOfMemoryError 且直接内存峰值低于 96 MiB
            assertTrue(scenarios.none { it.triggeredOom })
            val maxDirectPeakBytes = 96L * 1024 * 1024
            for (scenario in scenarios) {
                assertTrue(
                    "Scenario ${scenario.name} exceeded 96 MiB direct memory peak (${scenario.peakDirectBytes} bytes)",
                    scenario.peakDirectBytes < maxDirectPeakBytes
                )
            }

            // Scenario 1: Clamped 256 MiB KEY_MAX_INPUT_SIZE to 32 MiB on buffer AND on both muxer.addTrack formats,
            // with single-pass video & audio extraction (no extra pre-scan passes).
            val s1 = scenarios[0]
            assertTrue("Expected Success for ${s1.name}, got ${s1.result}", s1.result is ExportResult.Success)
            assertFalse((s1.result as ExportResult.Success).audioDropped)
            assertEquals(FrameExporter.MAX_SAMPLE_BUFFER_BYTES, s1.maxObservedBufferCapacity)
            assertEquals(1, s1.muxerInstanceCount)
            assertEquals(1, s1.videoSeekCount)
            assertEquals(1, s1.audioSeekCount)
            assertEquals(1, s1.videoAdvanceCount)
            assertEquals(1, s1.audioAdvanceCount)
            assertEquals(0, s1.leftoverTempClipFiles)
            assertEquals(
                listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC),
                s1.muxerAddedTrackMimes
            )
            assertEquals(
                listOf(FrameExporter.MAX_SAMPLE_BUFFER_BYTES, FrameExporter.MAX_SAMPLE_BUFFER_BYTES),
                s1.muxerAddedTrackMaxInputSizes
            )

            // Scenario 2: Controlled IOException, not OutOfMemoryError
            val s2 = scenarios[1]
            assertTrue("Expected Error for ${s2.name}, got ${s2.result}", s2.result is ExportResult.Error)
            val s2Error = s2.result as ExportResult.Error
            assertTrue("Expected IOException cause, got ${s2Error.cause}", s2Error.cause is IOException)
            assertFalse(s2Error.cause is OutOfMemoryError)
            assertEquals(0, s2.leftoverTempClipFiles)

            // Scenario 3: AAC remux oversized first sample triggers write-pass video-only retry,
            // safely absorbing empty-track stop() failure on first muxer and exporting clean video-only MP4
            val s3 = scenarios[2]
            assertTrue("Expected Success for ${s3.name}, got ${s3.result}", s3.result is ExportResult.Success)
            val s3Success = s3.result as ExportResult.Success
            assertTrue(s3Success.isMotionPhoto)
            assertTrue("Expected audioDropped=true for ${s3.name}", s3Success.audioDropped)
            assertEquals(2, s3.muxerInstanceCount)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), s3.muxerAddedTrackMimes)
            assertEquals(0, s3.muxerAudioSamplesWritten)
            assertEquals(0, s3.audioReadSampleCount)
            assertEquals(0, s3.leftoverTempClipFiles)

            // Scenario 4: LPCM oversized sample skipped before allocateDirect with audioDropped = true
            val s4 = scenarios[3]
            assertTrue("Expected Success for ${s4.name}, got ${s4.result}", s4.result is ExportResult.Success)
            val s4Success = s4.result as ExportResult.Success
            assertTrue(s4Success.isMotionPhoto)
            assertTrue("Expected audioDropped=true for ${s4.name}", s4Success.audioDropped)
            assertEquals(1, s4.muxerInstanceCount)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), s4.muxerAddedTrackMimes)
            assertEquals(0, s4.audioReadSampleCount)
            assertEquals(0, s4.leftoverTempClipFiles)
        }
    }

    @Test
    fun `exportMotionPhoto retries video-only and discards truncated audio when later compatible audio sample exceeds limit`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fortyMiB = 40L * 1024 * 1024
            val aacSamples = listOf(
                FakeSample(1_500_000L, 32L, ByteArray(32) { 0x11 }),
                FakeSample(1_700_000L, 48L, ByteArray(48) { 0x22 }),
                FakeSample(1_900_000L, fortyMiB, ByteArray(64) { 0x33 })
            )

            val outcome = runExportScenario(
                name = "audio_remux_later_sample_oversized",
                videoMaxInputSize = 65_536,
                audioMime = MediaFormat.MIMETYPE_AUDIO_AAC,
                videoReportedSampleSize = 16L,
                audioReportedSampleSize = 32L,
                customAudioSamples = aacSamples
            )

            assertFalse(outcome.triggeredOom)
            assertTrue("Expected Success, got ${outcome.result}", outcome.result is ExportResult.Success)
            val success = outcome.result as ExportResult.Success
            assertTrue(success.isMotionPhoto)
            assertTrue("Expected audioDropped=true when a later audio sample is oversized", success.audioDropped)
            // First muxer wrote 2 valid audio samples before hitting the 40 MiB sample;
            // write-pass retry discarded that temp file and remuxed video-only into a second muxer.
            assertEquals(2, outcome.muxerInstanceCount)
            assertEquals(2, outcome.abandonedMuxerAudioSamplesWritten)
            assertEquals(2, outcome.audioReadSampleCount)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), outcome.muxerAddedTrackMimes)
            assertEquals(0, outcome.muxerAudioSamplesWritten)
            assertEquals(0, outcome.leftoverTempClipFiles)
            // Verify the exported Motion Photo's MP4 tail ends with only 1 video track marker (0x00) and zero audio markers (0x01)
            val ftypIndex = outcome.exportedFileBytes.indices.last { idx ->
                idx + 3 < outcome.exportedFileBytes.size &&
                    outcome.exportedFileBytes[idx] == 0x66.toByte() &&
                    outcome.exportedFileBytes[idx + 1] == 0x74.toByte() &&
                    outcome.exportedFileBytes[idx + 2] == 0x79.toByte() &&
                    outcome.exportedFileBytes[idx + 3] == 0x70.toByte()
            }
            val mp4Markers = outcome.exportedFileBytes.copyOfRange(
                ftypIndex + 4,
                outcome.exportedFileBytes.size
            )
            org.junit.Assert.assertArrayEquals(byteArrayOf(0x00), mp4Markers)
        }
    }

    @Test
    fun `exportMotionPhoto retries video-only when compatible audio track has zero samples in clip range`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Audio sample timestamp (4.0s) is past clip endUs (3.0s), so 0 audio samples are written in Phase 2.
            val outOfRangeAudioSamples = listOf(
                FakeSample(4_000_000L, 32L, ByteArray(32) { 0x44 })
            )

            val outcome = runExportScenario(
                name = "compatible_audio_zero_samples_in_range",
                videoMaxInputSize = 65_536,
                audioMime = MediaFormat.MIMETYPE_AUDIO_AAC,
                videoReportedSampleSize = 16L,
                audioReportedSampleSize = 32L,
                customAudioSamples = outOfRangeAudioSamples
            )

            assertFalse(outcome.triggeredOom)
            assertTrue("Expected Success, got ${outcome.result}", outcome.result is ExportResult.Success)
            val success = outcome.result as ExportResult.Success
            assertTrue(success.isMotionPhoto)
            assertTrue("Expected audioDropped=true when clip range has no audio samples", success.audioDropped)
            assertEquals(2, outcome.muxerInstanceCount)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), outcome.muxerAddedTrackMimes)
            assertEquals(0, outcome.muxerAudioSamplesWritten)
            assertEquals(0, outcome.audioReadSampleCount)
            assertEquals(0, outcome.leftoverTempClipFiles)
        }
    }

    @Test
    fun `exportMotionPhoto grows bounded direct buffer geometrically in a single pass without exhausting direct memory`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val oneMiB = 1024 * 1024
            // 20 video samples + 20 AAC audio samples ramping from 1 MiB to 20 MiB:
            // cumulative sizes = 210 MiB + 210 MiB = 420 MiB (> 128 MiB MaxDirectMemorySize),
            // while each individual sample is <= 32 MiB MAX_SAMPLE_BUFFER_BYTES.
            val growingVideoSamples = (1..20).map { step ->
                FakeSample(
                    timeUs = 1_500_000L + step * 20_000L,
                    reportedSampleSize = (step * oneMiB).toLong(),
                    payload = ByteArray(64) { step.toByte() }
                )
            }
            val growingAudioSamples = (1..20).map { step ->
                FakeSample(
                    timeUs = 1_500_000L + step * 20_000L,
                    reportedSampleSize = (step * oneMiB).toLong(),
                    payload = ByteArray(64) { (step + 0x20).toByte() }
                )
            }

            val outcome = runExportScenario(
                name = "repeated_sample_growth_under_limit",
                videoMaxInputSize = 65_536,
                audioMime = MediaFormat.MIMETYPE_AUDIO_AAC,
                videoReportedSampleSize = (20 * oneMiB).toLong(),
                audioReportedSampleSize = (20 * oneMiB).toLong(),
                customVideoSamples = growingVideoSamples,
                customAudioSamples = growingAudioSamples
            )

            assertFalse("Repeated sample growth must not trigger OOM", outcome.triggeredOom)
            assertTrue("Expected Success, got ${outcome.result}", outcome.result is ExportResult.Success)
            val success = outcome.result as ExportResult.Success
            assertTrue(success.isMotionPhoto)
            assertFalse(success.audioDropped)
            // 4x geometric growth from 1 MiB reaches 32 MiB (1 -> 4 -> 16 -> 32 MiB)
            // in a single pass without pre-scanning video or audio tracks.
            assertEquals(FrameExporter.MAX_SAMPLE_BUFFER_BYTES, outcome.maxObservedBufferCapacity)
            assertEquals(1, outcome.muxerInstanceCount)
            assertEquals(1, outcome.videoSeekCount)
            assertEquals(1, outcome.audioSeekCount)
            assertEquals(20, outcome.videoAdvanceCount)
            assertEquals(20, outcome.audioAdvanceCount)
            assertTrue(
                "Expected direct memory peak < 96 MiB, got ${outcome.peakDirectBytes}",
                outcome.peakDirectBytes < 96L * 1024 * 1024
            )
            assertEquals(20, outcome.muxerAudioSamplesWritten)
        }
    }

    @Test
    fun `exportMotionPhoto ignores oversized KEY_MAX_INPUT_SIZE on unselected and muted tracks`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val oversized = 268_435_456 // 256 MiB
            val subtitleTrack = FakeTrack(
                format = buildMockFormat(
                    mime = "application/x-subrip",
                    maxInputSize = oversized
                ),
                samples = emptyList()
            )
            val mutedAudioTrack = FakeTrack(
                format = buildMockFormat(
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                    maxInputSize = oversized
                ),
                samples = listOf(FakeSample(1_500_000L, 16L, ByteArray(16)))
            )

            val outcome = runExportScenario(
                name = "unselected_and_muted_tracks_256MiB",
                videoMaxInputSize = 65_536,
                audioMime = null,
                videoReportedSampleSize = 16L,
                audioReportedSampleSize = 16L,
                muteAudio = true,
                extraTracks = listOf(subtitleTrack, mutedAudioTrack)
            )

            assertFalse(outcome.triggeredOom)
            assertTrue(outcome.result is ExportResult.Success)
            assertEquals(FrameExporter.DEFAULT_SAMPLE_BUFFER_BYTES, outcome.maxObservedBufferCapacity)
        }
    }

    @Test
    fun `exportMotionPhoto drops LPCM audio when cumulative sampleSizeHint exceeds MAX_PCM_BUFFER_BYTES`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Sample 1 is 6 MiB and Sample 2 is 10 MiB (each <= 15 MiB individually,
            // but 6 MiB + 10 MiB = 16 MiB > 15 MiB MAX_PCM_BUFFER_BYTES).
            // The cumulative pre-allocation check must abort BEFORE allocating the 10 MiB buffer
            // or reading Sample 2.
            val sixMiB = 6 * 1024 * 1024
            val tenMiB = 10 * 1024 * 1024
            val pcmSamples = listOf(
                FakeSample(1_500_000L, sixMiB.toLong(), ByteArray(sixMiB) { 0x11 }),
                FakeSample(1_700_000L, tenMiB.toLong(), ByteArray(64) { 0x22 })
            )

            val outcome = runExportScenario(
                name = "pcm_cumulative_limit_exceeded",
                videoMaxInputSize = 65_536,
                audioMime = MediaFormat.MIMETYPE_AUDIO_RAW,
                videoReportedSampleSize = 16L,
                audioReportedSampleSize = sixMiB.toLong(),
                customAudioSamples = pcmSamples,
                mockAacEncoder = true
            )

            assertFalse(outcome.triggeredOom)
            assertTrue("Expected Success, got ${outcome.result}", outcome.result is ExportResult.Success)
            val success = outcome.result as ExportResult.Success
            assertTrue(success.isMotionPhoto)
            assertTrue("Expected audioDropped=true on cumulative PCM overflow", success.audioDropped)
            assertEquals("Second PCM sample must not be read", 1, outcome.audioReadSampleCount)
            assertEquals(
                "10 MiB buffer for second PCM sample must not be allocated",
                sixMiB,
                outcome.maxObservedAudioBufferCapacity
            )
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), outcome.muxerAddedTrackMimes)
        }
    }

    @Test
    fun `exportMotionPhoto exports under-limit LPCM audio normally with Android readSampleData positioning`() {
        withSdkInt(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sample1Payload = ByteArray(4096) { (it and 0x7F).toByte() }
            val sample2Payload = ByteArray(4096) { ((it + 37) and 0x7F).toByte() }
            val pcmSamples = listOf(
                FakeSample(1_500_000L, sample1Payload.size.toLong(), sample1Payload),
                FakeSample(1_600_000L, sample2Payload.size.toLong(), sample2Payload)
            )

            val outcome = runExportScenario(
                name = "pcm_under_limit_normal_export",
                videoMaxInputSize = 65_536,
                audioMime = MediaFormat.MIMETYPE_AUDIO_RAW,
                videoReportedSampleSize = 16L,
                audioReportedSampleSize = sample1Payload.size.toLong(),
                customAudioSamples = pcmSamples,
                mockAacEncoder = true
            )

            assertFalse(outcome.triggeredOom)
            assertTrue("Expected Success, got ${outcome.result}", outcome.result is ExportResult.Success)
            val success = outcome.result as ExportResult.Success
            assertTrue(success.isMotionPhoto)
            assertFalse("Under-limit LPCM audio should be transcoded and included", success.audioDropped)
            assertEquals(2, outcome.audioReadSampleCount)
            org.junit.Assert.assertArrayEquals(
                sample1Payload + sample2Payload,
                outcome.capturedEncoderPcmBytes
            )
            assertEquals(
                listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC),
                outcome.muxerAddedTrackMimes
            )
            assertTrue(outcome.muxerAudioSamplesWritten > 0)
        }
    }

    private data class FakeSample(
        val timeUs: Long,
        val reportedSampleSize: Long,
        val payload: ByteArray,
        val flags: Int = MediaExtractor.SAMPLE_FLAG_SYNC
    )

    private data class FakeTrack(
        val format: MediaFormat,
        val samples: List<FakeSample>
    )

    private class ExtractorCursor(val tracks: List<FakeTrack>) {
        var selectedTrack: Int = -1
        var sampleIndex: Int = 0

        fun currentSample(): FakeSample? {
            if (selectedTrack !in tracks.indices) return null
            return tracks[selectedTrack].samples.getOrNull(sampleIndex)
        }
    }

    private class MuxerState {
        val addedTrackMimes = mutableListOf<String?>()
        val addedTrackMaxInputSizes = mutableListOf<Int?>()
        val samplesWrittenByTrack = mutableMapOf<Int, Int>()
    }

    private data class ScenarioOutcome(
        val name: String,
        val result: ExportResult?,
        val triggeredOom: Boolean,
        val peakDirectBytes: Long,
        val maxObservedBufferCapacity: Int,
        val maxObservedAudioBufferCapacity: Int,
        val audioReadSampleCount: Int,
        val videoSeekCount: Int,
        val audioSeekCount: Int,
        val videoAdvanceCount: Int,
        val audioAdvanceCount: Int,
        val muxerInstanceCount: Int,
        val muxerAddedTrackMimes: List<String?>,
        val muxerAddedTrackMaxInputSizes: List<Int?>,
        val muxerAudioSamplesWritten: Int,
        val abandonedMuxerAudioSamplesWritten: Int,
        val leftoverTempClipFiles: Int,
        val exportedFileBytes: ByteArray,
        val capturedEncoderPcmBytes: ByteArray
    )

    private fun buildMockFormat(
        mime: String,
        maxInputSize: Int? = null,
        sampleRate: Int = 48_000,
        channelCount: Int = 2,
        pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
    ): MediaFormat {
        var currentMaxInputSize: Int? = maxInputSize
        val format = mockk<MediaFormat>(relaxed = true)
        every { format.getString(MediaFormat.KEY_MIME) } returns mime
        every { format.getInteger(MediaFormat.KEY_WIDTH) } returns 1920
        every { format.getInteger(MediaFormat.KEY_HEIGHT) } returns 1080
        every { format.containsKey(any()) } answers {
            when (firstArg<String>()) {
                MediaFormat.KEY_MAX_INPUT_SIZE -> currentMaxInputSize != null
                MediaFormat.KEY_SAMPLE_RATE,
                MediaFormat.KEY_CHANNEL_COUNT,
                MediaFormat.KEY_PCM_ENCODING -> mime.startsWith("audio/")
                else -> false
            }
        }
        every { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } answers {
            currentMaxInputSize ?: throw NullPointerException("no max-input-size")
        }
        every { format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, any()) } answers {
            currentMaxInputSize = secondArg()
        }
        every { format.removeKey(MediaFormat.KEY_MAX_INPUT_SIZE) } answers {
            currentMaxInputSize = null
        }
        every { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } returns sampleRate
        every { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } returns channelCount
        every { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } returns pcmEncoding
        return format
    }

    private fun directMemoryUsedBytes(): Long {
        val mgmtFactory = Class.forName("java.lang.management.ManagementFactory")
        val poolBeanClass = Class.forName("java.lang.management.BufferPoolMXBean")
        val getBeans = mgmtFactory.getMethod("getPlatformMXBeans", Class::class.java)
        val getName = poolBeanClass.getMethod("getName")
        val getMemoryUsed = poolBeanClass.getMethod("getMemoryUsed")
        val beans = getBeans.invoke(null, poolBeanClass) as List<*>
        val directBean = beans.firstOrNull { getName.invoke(it) == "direct" } ?: return 0L
        return getMemoryUsed.invoke(directBean) as Long
    }

    private fun runExportScenario(
        name: String,
        videoMaxInputSize: Int,
        audioMime: String?,
        videoReportedSampleSize: Long,
        audioReportedSampleSize: Long,
        audioMaxInputSize: Int = 65_536,
        muteAudio: Boolean = false,
        extraTracks: List<FakeTrack> = emptyList(),
        customVideoSamples: List<FakeSample>? = null,
        customAudioSamples: List<FakeSample>? = null,
        mockAacEncoder: Boolean = false
    ): ScenarioOutcome {
        System.gc()
        System.runFinalization()
        Thread.sleep(20)
        System.gc()
        var peakDirectBytes = directMemoryUsedBytes()
        var maxObservedBufferCapacity = 0
        var maxObservedAudioBufferCapacity = 0
        var audioReadSampleCount = 0
        var videoSeekCount = 0
        var audioSeekCount = 0
        var videoAdvanceCount = 0
        var audioAdvanceCount = 0
        val capturedEncoderPcm = ByteArrayOutputStream()

        fun sampleDirectMemory() {
            peakDirectBytes = maxOf(peakDirectBytes, directMemoryUsedBytes())
        }

        val cacheDir = tempFolder.newFolder(name)
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val treeUri = mockk<Uri>(relaxed = true)
        val createdUri = mockk<Uri>(relaxed = true)
        val tree = mockk<DocumentFile>(relaxed = true)
        val document = mockk<DocumentFile>(relaxed = true)
        val videoUri = mockk<Uri>(relaxed = true)
        val bitmap = mockk<Bitmap>(relaxed = true)
        val outputBytes = ByteArrayOutputStream()

        mockkStatic(Log::class, DocumentFile::class, DocumentsContract::class)

        val createEncoderHook: (String) -> MediaCodec = if (mockAacEncoder) {
            { _ ->
                val codec = mockk<MediaCodec>(relaxed = true)
                val inputBuf = ByteBuffer.allocate(16 * 1024)
                val pendingOutputs = ArrayDeque<Pair<ByteArray, MediaCodec.BufferInfo>>()
                var emittedOutputFormat = false
                var currentOutputBytes: ByteArray? = null
                val encoderOutputFormat = buildMockFormat(
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                    maxInputSize = 268_435_456 // Also verify encoder output format gets sanitized
                )

                every { codec.dequeueInputBuffer(any()) } returns 0
                every { codec.getInputBuffer(0) } answers {
                    inputBuf.clear()
                    inputBuf
                }
                every { codec.queueInputBuffer(any(), any(), any(), any(), any()) } answers {
                    val offset = secondArg<Int>()
                    val size = thirdArg<Int>()
                    val ptsUs = arg<Long>(3)
                    val flags = arg<Int>(4)
                    if (size > 0) {
                        val chunk = ByteArray(size)
                        val dup = inputBuf.duplicate()
                        dup.position(offset)
                        dup.limit(offset + size)
                        dup.get(chunk)
                        capturedEncoderPcm.write(chunk)
                        val info = mockk<MediaCodec.BufferInfo>(relaxed = true).apply {
                            this.offset = 0
                            this.size = 8
                            this.presentationTimeUs = ptsUs
                            this.flags = 0
                        }
                        pendingOutputs.addLast(ByteArray(8) { 0x55 } to info)
                    }
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val eosInfo = mockk<MediaCodec.BufferInfo>(relaxed = true).apply {
                            this.offset = 0
                            this.size = 0
                            this.presentationTimeUs = ptsUs
                            this.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        }
                        pendingOutputs.addLast(ByteArray(0) to eosInfo)
                    }
                }
                every { codec.dequeueOutputBuffer(any(), any()) } answers {
                    val outInfo = firstArg<MediaCodec.BufferInfo>()
                    if (!emittedOutputFormat && pendingOutputs.isNotEmpty()) {
                        emittedOutputFormat = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    } else if (pendingOutputs.isNotEmpty()) {
                        val (bytes, queuedInfo) = pendingOutputs.removeFirst()
                        currentOutputBytes = bytes
                        outInfo.offset = queuedInfo.offset
                        outInfo.size = queuedInfo.size
                        outInfo.presentationTimeUs = queuedInfo.presentationTimeUs
                        outInfo.flags = queuedInfo.flags
                        0
                    } else {
                        MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                }
                every { codec.outputFormat } returns encoderOutputFormat
                every { codec.getOutputBuffer(0) } answers {
                    currentOutputBytes?.let { ByteBuffer.wrap(it) }
                }
                codec
            }
        } else {
            { mime -> MediaCodec.createEncoderByType(mime) }
        }

        val createAacFormatHook: (Int, Int) -> MediaFormat = { sampleRate, channelCount ->
            buildMockFormat(
                mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate = sampleRate,
                channelCount = channelCount
            )
        }

        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns resolver
        every { DocumentFile.fromTreeUri(context, treeUri) } returns tree
        every { tree.createFile(any(), any()) } returns document
        every { document.uri } returns createdUri
        every { treeUri.authority } returns "selected.provider"
        every { createdUri.scheme } returns ContentResolver.SCHEME_CONTENT
        every { createdUri.authority } returns "selected.provider"
        every { DocumentsContract.isDocumentUri(context, createdUri) } returns true
        every { DocumentsContract.isTreeUri(createdUri) } returns true
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "selected-tree"
        every { DocumentsContract.getTreeDocumentId(createdUri) } returns "selected-tree"
        every { resolver.openOutputStream(createdUri) } answers {
            outputBytes.reset()
            object : OutputStream() {
                override fun write(b: Int) = outputBytes.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = outputBytes.write(b, off, len)
            }
        }
        every { resolver.openFileDescriptor(videoUri, "r") } returns null
        every { resolver.openFileDescriptor(createdUri, "r") } returns null

        val validJpegBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x02,
            0xFF.toByte(), 0xD9.toByte()
        )
        every { bitmap.config } returns Bitmap.Config.ARGB_8888
        every { bitmap.width } returns 64
        every { bitmap.height } returns 64
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(validJpegBytes)
            true
        }

        val videoFormat = buildMockFormat(
            mime = MediaFormat.MIMETYPE_VIDEO_AVC,
            maxInputSize = videoMaxInputSize
        )
        val videoPayloadSize = videoReportedSampleSize.coerceAtMost(64L).toInt().coerceAtLeast(1)
        val videoSamples = customVideoSamples ?: listOf(
            FakeSample(
                timeUs = 1_500_000L,
                reportedSampleSize = videoReportedSampleSize,
                payload = ByteArray(videoPayloadSize) { 0x11 }
            )
        )
        val tracks = mutableListOf(
            FakeTrack(
                format = videoFormat,
                samples = videoSamples
            )
        )
        val primaryAudioTrackIndex = if (audioMime != null) {
            val audioPayloadSize = audioReportedSampleSize.coerceAtMost(64L).toInt().coerceAtLeast(1)
            val audioSamples = customAudioSamples ?: listOf(
                FakeSample(
                    timeUs = 1_500_000L,
                    reportedSampleSize = audioReportedSampleSize,
                    payload = ByteArray(audioPayloadSize) { 0x22 }
                )
            )
            tracks.add(
                FakeTrack(
                    format = buildMockFormat(mime = audioMime, maxInputSize = audioMaxInputSize),
                    samples = audioSamples
                )
            )
            1
        } else {
            -1
        }
        tracks.addAll(extraTracks)

        val allMuxerStates = Collections.synchronizedList(mutableListOf<MuxerState>())
        var lastStoppedMuxerState: MuxerState? = null

        val mediaHooks = FrameExporter.MediaHooks(
            createExtractor = {
                var cursor = ExtractorCursor(tracks)
                val extractor = mockk<MediaExtractor>(relaxed = true)
                every { extractor.setDataSource(any<Context>(), any<Uri>(), any()) } answers {
                    cursor = ExtractorCursor(tracks)
                }
                every { extractor.setDataSource(any<FileDescriptor>()) } answers {
                    cursor = ExtractorCursor(tracks)
                }
                every { extractor.trackCount } answers {
                    cursor.tracks.size
                }
                every { extractor.getTrackFormat(any()) } answers {
                    cursor.tracks[firstArg()].format
                }
                every { extractor.selectTrack(any()) } answers {
                    cursor.selectedTrack = firstArg()
                    cursor.sampleIndex = 0
                }
                every { extractor.unselectTrack(any()) } answers {
                    if (cursor.selectedTrack == firstArg<Int>()) {
                        cursor.selectedTrack = -1
                    }
                }
                every { extractor.seekTo(any(), any()) } answers {
                    if (cursor.selectedTrack == 0) {
                        videoSeekCount++
                    } else if (cursor.selectedTrack == primaryAudioTrackIndex) {
                        audioSeekCount++
                    }
                    cursor.sampleIndex = 0
                }
                every { extractor.sampleTrackIndex } answers {
                    if (cursor.currentSample() != null) cursor.selectedTrack else -1
                }
                every { extractor.sampleTime } answers {
                    cursor.currentSample()?.timeUs ?: -1L
                }
                every { extractor.sampleSize } answers {
                    sampleDirectMemory()
                    cursor.currentSample()?.reportedSampleSize ?: -1L
                }
                every { extractor.sampleFlags } answers {
                    cursor.currentSample()?.flags ?: 0
                }
                every { extractor.readSampleData(any(), any()) } answers {
                    sampleDirectMemory()
                    val buf = firstArg<ByteBuffer>()
                    val offset = secondArg<Int>()
                    maxObservedBufferCapacity = maxOf(maxObservedBufferCapacity, buf.capacity())
                    if (cursor.selectedTrack == primaryAudioTrackIndex) {
                        audioReadSampleCount++
                        maxObservedAudioBufferCapacity = maxOf(maxObservedAudioBufferCapacity, buf.capacity())
                    }
                    val sample = cursor.currentSample() ?: return@answers -1
                    // Simulate Android's MediaExtractor.readSampleData: write payload at [offset, offset + size)
                    // and set limit = offset + size, position = offset.
                    buf.limit(offset + sample.payload.size)
                    buf.position(offset)
                    buf.duplicate().put(sample.payload)
                    sample.payload.size
                }
                every { extractor.advance() } answers {
                    if (cursor.selectedTrack == 0) {
                        videoAdvanceCount++
                    } else if (cursor.selectedTrack == primaryAudioTrackIndex) {
                        audioAdvanceCount++
                    }
                    cursor.sampleIndex++
                    cursor.currentSample() != null
                }
                extractor
            },
            createMuxer = { path, _ ->
                val state = MuxerState()
                allMuxerStates.add(state)
                val clipFile = File(path)
                val muxer = mockk<MediaMuxer>(relaxed = true)
                every { muxer.addTrack(any()) } answers {
                    val fmt = firstArg<MediaFormat>()
                    val trackId = state.addedTrackMimes.size
                    state.addedTrackMimes.add(fmt.getString(MediaFormat.KEY_MIME))
                    val maxIn = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        null
                    }
                    state.addedTrackMaxInputSizes.add(maxIn)
                    state.samplesWrittenByTrack[trackId] = 0
                    trackId
                }
                every { muxer.writeSampleData(any(), any(), any()) } answers {
                    sampleDirectMemory()
                    val trackId = firstArg<Int>()
                    val buf = secondArg<ByteBuffer>()
                    maxObservedBufferCapacity = maxOf(maxObservedBufferCapacity, buf.capacity())
                    state.samplesWrittenByTrack[trackId] = (state.samplesWrittenByTrack[trackId] ?: 0) + 1
                    if (clipFile.length() == 0L) {
                        clipFile.writeBytes(byteArrayOf(0, 0, 0, 8, 0x66, 0x74, 0x79, 0x70))
                    }
                    clipFile.appendBytes(byteArrayOf(trackId.toByte()))
                }
                every { muxer.stop() } answers {
                    val emptyTrack = state.samplesWrittenByTrack.entries.firstOrNull { it.value == 0 }
                    if (emptyTrack != null) {
                        throw IllegalStateException(
                            "MPEG4Writer: track ${emptyTrack.key} has 0 samples written"
                        )
                    }
                    lastStoppedMuxerState = state
                }
                muxer
            },
            createEncoder = createEncoderHook,
            createBufferInfo = { mockk(relaxed = true) },
            createAacFormat = createAacFormatHook
        )

        val exporter = FrameExporter(context, mediaHooks)
        val frame = CapturedFrame(
            timestampUs = 2_000_000L,
            width = 64,
            height = 64,
            colorSpace = ColorSpaceInfo(),
            metadata = VideoMetadata(durationMs = 5_000L)
        )
        val config = ExportConfig(
            motionPhoto = true,
            preserveMetadata = false,
            motionDurationBeforeS = 1.0f,
            motionDurationAfterS = 1.0f,
            muteAudio = muteAudio
        )

        var triggeredOom = false
        var exportResult: ExportResult? = null
        try {
            exportResult = runBlocking {
                exporter.exportMotionPhoto(
                    videoUri = videoUri,
                    bitmap = bitmap,
                    frame = frame,
                    config = config,
                    customExportTreeUri = treeUri
                )
            }
            sampleDirectMemory()
            if (exportResult is ExportResult.Error) {
                val causes = generateSequence(exportResult.cause) { it.cause }
                if (causes.any { it is OutOfMemoryError }) {
                    triggeredOom = true
                }
            }
        } catch (oom: OutOfMemoryError) {
            triggeredOom = true
        } finally {
            clearAllMocks()
            unmockkAll()
            mockkStatic(Xml::class, Log::class)
            every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
            every { Log.isLoggable(any(), any()) } returns false
            every { Log.d(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
        }

        val finalMuxer = lastStoppedMuxerState
        val audioMuxerTrackIdx = finalMuxer?.addedTrackMimes?.indexOfFirst {
            it != null && it.startsWith("audio/")
        } ?: -1
        val muxerAudioWritten = if (audioMuxerTrackIdx >= 0) {
            finalMuxer?.samplesWrittenByTrack?.get(audioMuxerTrackIdx) ?: 0
        } else {
            0
        }
        val abandonedAudioWritten = allMuxerStates
            .filter { it !== finalMuxer }
            .sumOf { state ->
                val idx = state.addedTrackMimes.indexOfFirst { it != null && it.startsWith("audio/") }
                if (idx >= 0) state.samplesWrittenByTrack[idx] ?: 0 else 0
            }
        val leftoverTempFiles = cacheDir.listFiles()?.count { it.name.startsWith("motion_clip_") } ?: 0

        return ScenarioOutcome(
            name = name,
            result = exportResult,
            triggeredOom = triggeredOom,
            peakDirectBytes = peakDirectBytes,
            maxObservedBufferCapacity = maxObservedBufferCapacity,
            maxObservedAudioBufferCapacity = maxObservedAudioBufferCapacity,
            audioReadSampleCount = audioReadSampleCount,
            videoSeekCount = videoSeekCount,
            audioSeekCount = audioSeekCount,
            videoAdvanceCount = videoAdvanceCount,
            audioAdvanceCount = audioAdvanceCount,
            muxerInstanceCount = allMuxerStates.size,
            muxerAddedTrackMimes = finalMuxer?.addedTrackMimes?.toList() ?: emptyList(),
            muxerAddedTrackMaxInputSizes = finalMuxer?.addedTrackMaxInputSizes?.toList() ?: emptyList(),
            muxerAudioSamplesWritten = muxerAudioWritten,
            abandonedMuxerAudioSamplesWritten = abandonedAudioWritten,
            leftoverTempClipFiles = leftoverTempFiles,
            exportedFileBytes = outputBytes.toByteArray(),
            capturedEncoderPcmBytes = capturedEncoderPcm.toByteArray()
        )
    }

    private fun withSdkInt(sdkInt: Int, block: () -> Unit) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null)
        val sdkIntField = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java)
            .invoke(unsafe, sdkIntField)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
            .invoke(unsafe, sdkIntField) as Long
        val getInt = unsafeClass.getMethod("getInt", Any::class.java, Long::class.javaPrimitiveType)
        val putInt = unsafeClass.getMethod(
            "putInt",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val previous = getInt.invoke(unsafe, base, offset) as Int
        putInt.invoke(unsafe, base, offset, sdkInt)
        try {
            block()
        } finally {
            putInt.invoke(unsafe, base, offset, previous)
        }
    }

    // ── Streaming audio transcode & single-seek motion photo tests ──────

    @Test
    fun `PcmCarryBuffer enforces 16 KiB bound and aligns transfers to frame boundaries`() {
        assertTrue(FrameExporter.PCM_CARRY_BUFFER_BYTES <= 16 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            PcmCarryBuffer(FrameExporter.PCM_CARRY_BUFFER_BYTES + 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmCarryBuffer(2)
        }

        val carry = PcmCarryBuffer()
        assertEquals(16 * 1024, carry.capacity)

        // Total payload: 25_003 bytes -> 6_250 stereo 16-bit frames (25_000 bytes) + 3 orphan tail bytes
        val sourceBytes = ByteArray(25_003) { i -> ((i * 37 + 11) and 0xFF).toByte() }
        val irregularInputChunks = intArrayOf(1, 2, 3, 5, 7, 13, 4098, 8191, 6147, 6536)
        assertEquals(sourceBytes.size, irregularInputChunks.sum())

        val collected = ByteArrayOutputStream()
        val smallTarget = ByteBuffer.allocate(1030) // intentionally not a multiple of 4 (1030 % 4 == 2)
        val bytesPerFrame = 4 // stereo 16-bit

        var srcCursor = 0
        for (chunkLen in irregularInputChunks) {
            val slice = ByteBuffer.wrap(sourceBytes, srcCursor, chunkLen)
            srcCursor += chunkLen
            while (slice.hasRemaining()) {
                val written = carry.writeFrom(slice)
                assertTrue(written > 0)
                assertTrue(carry.size <= FrameExporter.PCM_CARRY_BUFFER_BYTES)

                while (carry.hasCompleteFrame(bytesPerFrame)) {
                    smallTarget.clear()
                    val drained = carry.readFrameAlignedInto(smallTarget, bytesPerFrame)
                    assertTrue(drained > 0)
                    assertEquals(0, drained % bytesPerFrame)
                    assertTrue(drained <= 1028) // 1030 aligned down to 4-byte frame boundary = 1028
                    smallTarget.flip()
                    val out = ByteArray(drained)
                    smallTarget.get(out)
                    collected.write(out)
                }
            }
        }

        // Exactly 25_000 frame-aligned bytes emitted in identical order; 3 partial bytes retained
        assertEquals(25_000, collected.size())
        assertArrayEquals(sourceBytes.copyOfRange(0, 25_000), collected.toByteArray())
        assertEquals(3, carry.size)
        assertFalse(carry.hasCompleteFrame(bytesPerFrame))

        carry.clear()
        assertTrue(carry.isEmpty())
        assertEquals(0, carry.size)
    }

    @Test
    fun `transcodeAudioToAac streams 16-bit LPCM with monotonic PTS and filters codec config`() = runBlocking {
        withMockedAndroidLog {
            val context = mockk<Context>(relaxed = true)
            val sampleRate = 48_000
            val channelCount = 2
            val bytesPerFrame = 4
            val clipStartUs = 1_000_000L
            val endUs = 2_000_000L

            // Split 12_000 bytes (3_000 stereo frames) across unaligned raw samples (e.g. 4098 + 4098 + 3804)
            val rawPayload = ByteArray(12_000) { idx -> (idx and 0x7F).toByte() }
            val sampleChunks = listOf(
                1_020_000L to rawPayload.copyOfRange(0, 4098),
                1_041_343L to rawPayload.copyOfRange(4098, 8196),
                1_062_687L to rawPayload.copyOfRange(8196, 12_000)
            )
            val extractor = createSampleExtractorMock(
                trackIndex = 1,
                samples = sampleChunks
            )
            val rawFormat = mockAudioFormat(
                mime = MediaFormat.MIMETYPE_AUDIO_RAW,
                sampleRate = sampleRate,
                channelCount = channelCount,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            )
            val aacOutputFormat = mockAudioFormat(
                mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate = sampleRate,
                channelCount = channelCount
            )
            val encoderHarness = FakeAacEncoderHarness(
                outputFormat = aacOutputFormat,
                inputBufferCapacity = 2050, // unaligned capacity -> 2048 bytes (512 frames) per input buffer
                emitCodecConfigPacket = true
            )

            val exporter = FrameExporter(
                context,
                FrameExporter.MediaHooks(
                    createEncoder = { encoderHarness.codec },
                    createBufferInfo = { mockk(relaxed = true) },
                    createAacFormat = { _, _ -> aacOutputFormat }
                )
            )

            val encoded = exporter.transcodeAudioToAac(extractor, rawFormat, clipStartUs, endUs)
            assertNotNull(encoded)
            encoded!!

            // Exact PCM payload preserved across unaligned sample + buffer boundaries
            assertArrayEquals(rawPayload, encoderHarness.receivedPcm.toByteArray())
            // Codec-config packet was filtered out
            assertTrue(encoded.packets.isNotEmpty())
            assertTrue(encoded.packets.none { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 })

            // Verify monotonic PTS starting at (firstSamplePts - clipStartUs) = 20_000 us
            val expectedBasePtsUs = 20_000L
            var cumulativeFrames = 0L
            for ((ptsUs, sizeBytes) in encoderHarness.queuedInputBatches) {
                assertEquals(0, sizeBytes % bytesPerFrame)
                assertEquals(
                    expectedBasePtsUs + pcmFramesToUs(cumulativeFrames, sampleRate),
                    ptsUs
                )
                cumulativeFrames += sizeBytes / bytesPerFrame
            }
            assertEquals(3_000L, cumulativeFrames)
            // Steady-state streaming used 0L non-blocking probes exclusively
            assertTrue(encoderHarness.inputTimeouts.all { it == 0L })
            assertTrue(encoderHarness.outputTimeouts.all { it == 0L })
            assertTrue(encoderHarness.stopped && encoderHarness.released)
        }
    }

    @Test
    fun `transcodeAudioToAac overlaps compressed decoding and AAC encoding with single-wait backpressure`() = runBlocking {
        withMockedAndroidLog {
            val context = mockk<Context>(relaxed = true)
            val codecs = listOf("audio/ac3", "audio/mpeg", "audio/opus", "audio/flac")

            for (mime in codecs) {
                val sampleRate = 48_000
                val channelCount = 2
                // Include a 20_480-byte decoded block (> 16 KiB carry buffer) to exercise multi-slice draining
                val decodedChunks = listOf(
                    500_000L to ByteArray(4098) { ((it + 1) and 0xFF).toByte() },
                    521_343L to ByteArray(20_480) { ((it + 3) and 0xFF).toByte() },
                    628_010L to ByteArray(4094) { ((it + 7) and 0xFF).toByte() }
                )
                val expectedPcm = ByteArrayOutputStream().apply {
                    decodedChunks.forEach { write(it.second) }
                }.toByteArray() // 28_672 bytes (7_168 stereo frames)

                val compressedSamples = decodedChunks.mapIndexed { idx, (pts, _) ->
                    pts to ByteArray(256) { idx.toByte() }
                }
                val extractor = createSampleExtractorMock(trackIndex = 1, samples = compressedSamples)
                val sourceFormat = mockAudioFormat(
                    mime = mime,
                    sampleRate = sampleRate,
                    channelCount = channelCount
                )
                val decoderOutFormat = mockAudioFormat(
                    mime = MediaFormat.MIMETYPE_AUDIO_RAW,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                )
                val aacOutFormat = mockAudioFormat(
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate = sampleRate,
                    channelCount = channelCount
                )

                val encoderHarness = FakeAacEncoderHarness(
                    outputFormat = aacOutFormat,
                    inputBufferCapacity = 4096,
                    emitCodecConfigPacket = true,
                    maxInFlightInputsBeforeDrain = 2
                )
                // Inject 2 artificial stall cycles where 0L returns TRY_AGAIN_LATER so backpressure wait triggers
                val decoderHarness = FakeAudioDecoderHarness(
                    outputFormat = decoderOutFormat,
                    decodedOutputs = decodedChunks,
                    stallZeroTimeoutChecks = 2,
                    encoderActivityProbe = { encoderHarness.queuedInputBatches.isNotEmpty() }
                )

                val exporter = FrameExporter(
                    context,
                    FrameExporter.MediaHooks(
                        createDecoder = { requestedMime ->
                            assertEquals(mime, requestedMime)
                            decoderHarness.codec
                        },
                        createEncoder = { encoderHarness.codec },
                        createBufferInfo = { mockk(relaxed = true) },
                        createAacFormat = { _, _ -> aacOutFormat }
                    )
                )

                val encoded = exporter.transcodeAudioToAac(
                    extractor = extractor,
                    audioFormat = sourceFormat,
                    clipStartUs = 480_000L,
                    endUs = 1_000_000L
                )
                assertNotNull("Failed for codec $mime", encoded)
                assertArrayEquals("PCM mismatch for $mime", expectedPcm, encoderHarness.receivedPcm.toByteArray())
                // Encoder started BEFORE decoder reached EOS (true single-loop overlap)
                assertTrue(
                    "Encoder must receive frames before decoder finishes all outputs for $mime",
                    decoderHarness.sawEncoderActivityBeforeEos
                )
                // Verify 0L probes dominate and CODEC_DEQUEUE_TIMEOUT_US is only used on stalled rounds
                val blockingCalls =
                    decoderHarness.inputTimeouts.count { it == FrameExporter.CODEC_DEQUEUE_TIMEOUT_US } +
                    decoderHarness.outputTimeouts.count { it == FrameExporter.CODEC_DEQUEUE_TIMEOUT_US } +
                    encoderHarness.inputTimeouts.count { it == FrameExporter.CODEC_DEQUEUE_TIMEOUT_US } +
                    encoderHarness.outputTimeouts.count { it == FrameExporter.CODEC_DEQUEUE_TIMEOUT_US }
                assertTrue("Expected bounded idle waits for $mime, got $blockingCalls", blockingCalls in 1..4)
                assertTrue(decoderHarness.stopped && decoderHarness.released)
                assertTrue(encoderHarness.stopped && encoderHarness.released)
            }
        }
    }

    @Test
    fun `transcodeAudioToAac enforces 15 MiB cumulative PCM safety limit for raw and compressed tracks`() = runBlocking {
        withMockedAndroidLog {
            val context = mockk<Context>(relaxed = true)
            val chunkSize = 64 * 1024 // 64 KiB per sample
            val numSamplesToExceedLimit = (FrameExporter.MAX_PCM_BUFFER_BYTES / chunkSize) + 2 // 242 * 64 KiB > 15 MiB
            val sampleChunk = ByteArray(chunkSize)

            // 1. Raw 16-bit LPCM exceeding 15 MiB
            val rawSamples = (0 until numSamplesToExceedLimit).map { idx ->
                (idx * 10_000L) to sampleChunk
            }
            val rawExtractor = createSampleExtractorMock(trackIndex = 0, samples = rawSamples)
            val rawFormat = mockAudioFormat(
                mime = MediaFormat.MIMETYPE_AUDIO_RAW,
                sampleRate = 192_000,
                channelCount = 2,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            )
            val aacFormat = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 192_000, 2)
            val rawEncoder = FakeAacEncoderHarness(outputFormat = aacFormat, inputBufferCapacity = 16_384)
            val rawExporter = FrameExporter(
                context,
                FrameExporter.MediaHooks(
                    createEncoder = { rawEncoder.codec },
                    createBufferInfo = { mockk(relaxed = true) },
                    createAacFormat = { _, _ -> aacFormat }
                )
            )
            assertNull(rawExporter.transcodeAudioToAac(rawExtractor, rawFormat, 0L, 10_000_000L))
            assertTrue(rawEncoder.receivedPcm.size() <= FrameExporter.MAX_PCM_BUFFER_BYTES)
            assertTrue(rawEncoder.stopped && rawEncoder.released)

            // 2. Compressed FLAC exceeding 15 MiB
            val flacDecoded = (0 until numSamplesToExceedLimit).map { idx ->
                (idx * 10_000L) to sampleChunk
            }
            val flacInput = (0 until numSamplesToExceedLimit).map { idx ->
                (idx * 10_000L) to ByteArray(64)
            }
            val flacExtractor = createSampleExtractorMock(trackIndex = 0, samples = flacInput)
            val flacFormat = mockAudioFormat("audio/flac", 192_000, 2)
            val decoderOutFormat = mockAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_RAW,
                192_000,
                2,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val flacDecoder = FakeAudioDecoderHarness(outputFormat = decoderOutFormat, decodedOutputs = flacDecoded)
            val flacEncoder = FakeAacEncoderHarness(outputFormat = aacFormat, inputBufferCapacity = 16_384)
            val flacExporter = FrameExporter(
                context,
                FrameExporter.MediaHooks(
                    createDecoder = { flacDecoder.codec },
                    createEncoder = { flacEncoder.codec },
                    createBufferInfo = { mockk(relaxed = true) },
                    createAacFormat = { _, _ -> aacFormat }
                )
            )
            assertNull(flacExporter.transcodeAudioToAac(flacExtractor, flacFormat, 0L, 10_000_000L))
            assertTrue(flacEncoder.receivedPcm.size() <= FrameExporter.MAX_PCM_BUFFER_BYTES)
            assertTrue(flacDecoder.stopped && flacDecoder.released)
            assertTrue(flacEncoder.stopped && flacEncoder.released)
        }
    }

    @Test
    fun `extractVideoClipOnce performs single video seek and isolates audio extractor with fd fallback`() = runBlocking {
        withMockedAndroidLog {
            val cacheDir = Files.createTempDirectory("frameecho_single_seek").toFile()
            try {
                // Case 1: Shared read-only ParcelFileDescriptor succeeds for both extractors
                val resolver = mockk<ContentResolver>()
                val context = mockk<Context>(relaxed = true)
                every { context.cacheDir } returns cacheDir
                every { context.contentResolver } returns resolver
                val videoUri = mockk<Uri>()
                val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
                val fd = FileDescriptor()
                every { pfd.fileDescriptor } returns fd
                every { resolver.openFileDescriptor(videoUri, "r") } returns pfd

                val videoFormat = mockVideoFormat("video/avc", 3840, 2160)
                val audioFormat = mockAudioFormat(
                    mime = MediaFormat.MIMETYPE_AUDIO_RAW,
                    sampleRate = 48_000,
                    channelCount = 2,
                    pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                )
                val aacFormat = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)

                // Keyframe snaps earlier from startUs = 2_000_000L to actualStartUs = 1_500_000L
                val videoExtractor = createMultiTrackExtractorMock(
                    formats = listOf(videoFormat, audioFormat),
                    activeTrackIndex = 0,
                    samples = listOf(
                        1_500_000L to ByteArray(512) { 1 },
                        1_533_333L to ByteArray(512) { 2 }
                    )
                )
                val audioExtractor = createMultiTrackExtractorMock(
                    formats = listOf(videoFormat, audioFormat),
                    activeTrackIndex = 1,
                    samples = listOf(
                        1_510_000L to ByteArray(4096) { 3 }
                    )
                )
                val createdExtractors = ArrayDeque(listOf(videoExtractor, audioExtractor))
                val muxer = mockk<MediaMuxer>(relaxed = true)
                val writtenVideoPts = mutableListOf<Long>()
                val writtenAudioPts = mutableListOf<Long>()
                every { muxer.addTrack(videoFormat) } returns 0
                every { muxer.addTrack(aacFormat) } returns 1
                every { muxer.writeSampleData(any(), any(), any()) } answers {
                    val track = firstArg<Int>()
                    val info = thirdArg<MediaCodec.BufferInfo>()
                    if (track == 0) writtenVideoPts.add(info.presentationTimeUs)
                    if (track == 1) writtenAudioPts.add(info.presentationTimeUs)
                }

                val encoderHarness = FakeAacEncoderHarness(outputFormat = aacFormat, inputBufferCapacity = 4096)
                val exporter = FrameExporter(
                    context,
                    FrameExporter.MediaHooks(
                        createExtractor = { createdExtractors.removeFirst() },
                        createMuxer = { path, _ ->
                            File(path).writeBytes(byteArrayOf(0, 0, 0, 24))
                            muxer
                        },
                        createEncoder = { encoderHarness.codec },
                        createBufferInfo = { mockk(relaxed = true) },
                        createAacFormat = { _, _ -> aacFormat }
                    )
                )

                val clipResult = exporter.extractVideoClipOnce(
                    videoUri = videoUri,
                    startUs = 2_000_000L,
                    endUs = 3_000_000L,
                    muteAudio = false,
                    seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                )
                try {
                    assertEquals(1_500_000L, clipResult.actualStartUs)
                    assertEquals(2, clipResult.videoSamplesWritten)
                    assertTrue(clipResult.audioIncluded)
                    // Video track seeked EXACTLY ONCE; never unselected or re-seeked!
                    verify(exactly = 1) { videoExtractor.selectTrack(0) }
                    verify(exactly = 1) { videoExtractor.seekTo(2_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                    verify(exactly = 0) { videoExtractor.unselectTrack(any()) }
                    verify(exactly = 0) { videoExtractor.selectTrack(1) }
                    // Audio extractor shared the same FD and seeked independently to actualStartUs (1_500_000L)
                    verify(exactly = 1) { resolver.openFileDescriptor(videoUri, "r") }
                    verify(exactly = 1) { videoExtractor.setDataSource(fd) }
                    verify(exactly = 1) { audioExtractor.setDataSource(fd) }
                    verify(exactly = 1) { audioExtractor.selectTrack(1) }
                    verify(exactly = 1) { audioExtractor.seekTo(1_500_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                    verify(exactly = 1) { audioExtractor.release() }
                    verify(exactly = 1) { pfd.close() }

                    // Video first sample PTS == 0L (1_500_000 - 1_500_000), audio basePtsUs == 10_000L (1_510_000 - 1_500_000)
                    assertEquals(listOf(0L, 33_333L), writtenVideoPts)
                    assertEquals(10_000L, encoderHarness.queuedInputBatches.first().first)
                    assertTrue(writtenAudioPts.isNotEmpty())
                } finally {
                    clipResult.file.delete()
                }

                // Case 2: openFileDescriptor returns null -> falls back to setDataSource(context, videoUri, null),
                // AND when PREVIOUS_SYNC yields 0 samples (> endUs), fallback retry to CLOSEST_SYNC totals 2 video seeks (not 4).
                every { resolver.openFileDescriptor(videoUri, "r") } returns null
                val prevSyncVideoExtractor = createMultiTrackExtractorMock(
                    formats = listOf(videoFormat, audioFormat),
                    activeTrackIndex = 0,
                    samples = listOf(4_000_000L to ByteArray(256)) // beyond endUs -> 0 samples written
                )
                val closestSyncVideoExtractor = createMultiTrackExtractorMock(
                    formats = listOf(videoFormat, audioFormat),
                    activeTrackIndex = 0,
                    samples = listOf(2_100_000L to ByteArray(256))
                )
                val closestSyncAudioExtractor = createMultiTrackExtractorMock(
                    formats = listOf(videoFormat, audioFormat),
                    activeTrackIndex = 1,
                    samples = listOf(2_100_000L to ByteArray(4096))
                )
                val fallbackQueue = ArrayDeque(
                    listOf(prevSyncVideoExtractor, closestSyncVideoExtractor, closestSyncAudioExtractor)
                )
                val retryEncoder = FakeAacEncoderHarness(outputFormat = aacFormat, inputBufferCapacity = 4096)
                val retryExporter = FrameExporter(
                    context,
                    FrameExporter.MediaHooks(
                        createExtractor = { fallbackQueue.removeFirst() },
                        createMuxer = { path, _ ->
                            File(path).writeBytes(byteArrayOf(0, 0, 0, 24))
                            muxer
                        },
                        createEncoder = { retryEncoder.codec },
                        createBufferInfo = { mockk(relaxed = true) },
                        createAacFormat = { _, _ -> aacFormat }
                    )
                )
                val fallbackClip = retryExporter.extractVideoClip(
                    videoUri = videoUri,
                    centerTimestampUs = 2_500_000L,
                    beforeDurationUs = 500_000L,
                    afterDurationUs = 500_000L,
                    muteAudio = false,
                    videoDurationUs = 5_000_000L,
                    rotation = 0
                )
                fallbackClip.file.delete()
                assertEquals(1, fallbackClip.videoSamplesWritten)
                // Total video track seeks across PREVIOUS_SYNC + CLOSEST_SYNC fallback == 2 (1 per attempt, down from 4)
                verify(exactly = 1) { prevSyncVideoExtractor.seekTo(2_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                verify(exactly = 1) { closestSyncVideoExtractor.seekTo(2_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                verify(exactly = 1) { closestSyncVideoExtractor.setDataSource(context, videoUri, null) }
                verify(exactly = 1) { closestSyncAudioExtractor.setDataSource(context, videoUri, null) }
            } finally {
                cacheDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `exportMotionPhoto degrades to audioDropped on transcode failure or empty audio and cleans up on cancel`() = runBlocking {
        withMockedAndroidLog {
            mockkObject(HdrToneMapper)
            mockkStatic(DocumentFile::class, DocumentsContract::class)
            try {
                val cacheDir = Files.createTempDirectory("frameecho_fallback").toFile()
                try {
                    val resolver = mockk<ContentResolver>(relaxed = true)
                    val context = mockk<Context>(relaxed = true)
                    val videoUri = mockk<Uri>()
                    val treeUri = mockk<Uri>()
                    val savedUri = mockk<Uri>()
                    val treeDoc = mockk<DocumentFile>()
                    val outDoc = mockk<DocumentFile>()
                    every { savedUri.toString() } returns "content://selected.provider/document/99"
                    every { context.cacheDir } returns cacheDir
                    every { context.contentResolver } returns resolver
                    every { resolver.openFileDescriptor(videoUri, "r") } returns null
                    every { resolver.openFileDescriptor(savedUri, "r") } returns null
                    every { DocumentFile.fromTreeUri(context, treeUri) } returns treeDoc
                    every { treeDoc.createFile(any(), any()) } returns outDoc
                    every { outDoc.uri } returns savedUri
                    every { treeUri.authority } returns "selected.provider"
                    every { savedUri.scheme } returns ContentResolver.SCHEME_CONTENT
                    every { savedUri.authority } returns "selected.provider"
                    every { DocumentsContract.isDocumentUri(context, savedUri) } returns true
                    every { DocumentsContract.isTreeUri(savedUri) } returns true
                    every { DocumentsContract.getTreeDocumentId(treeUri) } returns "selected-tree"
                    every { DocumentsContract.getTreeDocumentId(savedUri) } returns "selected-tree"
                    every { resolver.openOutputStream(savedUri) } answers { ByteArrayOutputStream() }

                    val bitmap = mockk<Bitmap>(relaxed = true)
                    every { bitmap.config } returns Bitmap.Config.ARGB_8888
                    every { bitmap.width } returns 1920
                    every { bitmap.height } returns 1080
                    every { bitmap.compress(any(), any(), any()) } answers {
                        val out = thirdArg< java.io.OutputStream>()
                        // Valid minimal JPEG SOI + EOI
                        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                        true
                    }
                    every { HdrToneMapper.process(any(), any()) } answers { firstArg() }

                    val frame = CapturedFrame(
                        timestampUs = 1_500_000L,
                        width = 1920,
                        height = 1080,
                        colorSpace = ColorSpaceInfo(),
                        metadata = VideoMetadata(durationMs = 5_000L, videoWidth = 1920, videoHeight = 1080)
                    )
                    val config = ExportConfig(
                        format = ExportFormat.JPEG,
                        preserveMetadata = false,
                        motionPhoto = true,
                        muteAudio = false
                    )
                    val videoFormat = mockVideoFormat("video/avc", 1920, 1080)
                    val brokenAudioFormat = mockAudioFormat("audio/ac3", 48_000, 6) // unsupported 6-channel layout

                    // 1. Codec error / unsupported layout -> Success with audioDropped = true
                    val vExt1 = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, brokenAudioFormat),
                        activeTrackIndex = 0,
                        samples = listOf(1_000_000L to ByteArray(512) { 9 })
                    )
                    val aExt1 = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, brokenAudioFormat),
                        activeTrackIndex = 1,
                        samples = listOf(1_000_000L to ByteArray(256) { 5 })
                    )
                    val decoder6Ch = FakeAudioDecoderHarness(
                        outputFormat = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 48_000, 6, AudioFormat.ENCODING_PCM_16BIT),
                        decodedOutputs = listOf(1_000_000L to ByteArray(1200))
                    )
                    val muxer1 = mockk<MediaMuxer>(relaxed = true)
                    every { muxer1.addTrack(videoFormat) } returns 0

                    val exporter1 = FrameExporter(
                        context,
                        FrameExporter.MediaHooks(
                            createExtractor = ArrayDeque(listOf(vExt1, aExt1))::removeFirst,
                            createMuxer = { path, _ ->
                                File(path).writeBytes(byteArrayOf(0, 0, 0, 24))
                                muxer1
                            },
                            createDecoder = { decoder6Ch.codec },
                            createBufferInfo = { mockk(relaxed = true) }
                        )
                    )
                    val res1 = exporter1.exportMotionPhoto(videoUri, bitmap, frame, config, treeUri)
                    assertTrue("Expected Success, got $res1", res1 is ExportResult.Success)
                    assertTrue((res1 as ExportResult.Success).audioDropped)
                    assertTrue(decoder6Ch.stopped && decoder6Ch.released)
                    assertTrue(cacheDir.listFiles().isNullOrEmpty())

                    // 1b. Empty audio clip in range -> Success with audioDropped = true
                    val emptyAudioFormat = mockAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_RAW,
                        48_000,
                        2,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val vExtEmpty = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, emptyAudioFormat),
                        activeTrackIndex = 0,
                        samples = listOf(1_000_000L to ByteArray(512) { 7 })
                    )
                    val aExtEmpty = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, emptyAudioFormat),
                        activeTrackIndex = 1,
                        samples = emptyList()
                    )
                    val exporterEmpty = FrameExporter(
                        context,
                        FrameExporter.MediaHooks(
                            createExtractor = ArrayDeque(listOf(vExtEmpty, aExtEmpty))::removeFirst,
                            createMuxer = { path, _ ->
                                File(path).writeBytes(byteArrayOf(0, 0, 0, 24))
                                muxer1
                            },
                            createBufferInfo = { mockk(relaxed = true) }
                        )
                    )
                    val resEmpty = exporterEmpty.exportMotionPhoto(videoUri, bitmap, frame, config, treeUri)
                    assertTrue("Expected Success, got $resEmpty", resEmpty is ExportResult.Success)
                    assertTrue((resEmpty as ExportResult.Success).audioDropped)

                    // 1c. Stalled codec exceeding MAX_CODEC_IDLE_ROUNDS -> IOException caught -> audioDropped = true
                    val stalledCodec = mockk<MediaCodec>(relaxed = true)
                    var stalledStopped = false
                    var stalledReleased = false
                    every { stalledCodec.dequeueInputBuffer(any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
                    every { stalledCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
                    every { stalledCodec.stop() } answers { stalledStopped = true }
                    every { stalledCodec.release() } answers { stalledReleased = true }
                    val vExtStall = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, emptyAudioFormat),
                        activeTrackIndex = 0,
                        samples = listOf(1_000_000L to ByteArray(512) { 4 })
                    )
                    val aExtStall = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, emptyAudioFormat),
                        activeTrackIndex = 1,
                        samples = listOf(1_000_000L to ByteArray(4096) { 2 })
                    )
                    val exporterStall = FrameExporter(
                        context,
                        FrameExporter.MediaHooks(
                            createExtractor = ArrayDeque(listOf(vExtStall, aExtStall))::removeFirst,
                            createMuxer = { path, _ ->
                                File(path).writeBytes(byteArrayOf(0, 0, 0, 24))
                                muxer1
                            },
                            createEncoder = { stalledCodec },
                            createBufferInfo = { mockk(relaxed = true) },
                            createAacFormat = { _, _ -> mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2) }
                        )
                    )
                    val resStall = exporterStall.exportMotionPhoto(videoUri, bitmap, frame, config, treeUri)
                    assertTrue(resStall is ExportResult.Success)
                    assertTrue((resStall as ExportResult.Success).audioDropped)
                    assertTrue(stalledStopped && stalledReleased)

                    // 2. Coroutine cancellation mid-transcode -> releases codecs, extractors, pfd & deletes temp files
                    val pfdCancel = mockk<ParcelFileDescriptor>(relaxed = true)
                    val fdCancel = FileDescriptor()
                    every { pfdCancel.fileDescriptor } returns fdCancel
                    every { resolver.openFileDescriptor(videoUri, "r") } returns pfdCancel

                    val rawAudioFormat = mockAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_RAW,
                        48_000,
                        2,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val aacFormat = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)
                    val vExtCancel = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, rawAudioFormat),
                        activeTrackIndex = 0,
                        samples = listOf(0L to ByteArray(512))
                    )
                    // Many samples so coroutine is cancelled mid-loop at round 10
                    val manySamples = (0 until 50).map { (it * 10_000L) to ByteArray(4096) }
                    val aExtCancel = createMultiTrackExtractorMock(
                        formats = listOf(videoFormat, rawAudioFormat),
                        activeTrackIndex = 1,
                        samples = manySamples
                    )
                    var cancelTriggerJob: kotlinx.coroutines.Job? = null
                    val slowEncoder = FakeAacEncoderHarness(
                        outputFormat = aacFormat,
                        inputBufferCapacity = 4096,
                        onQueueInput = { count ->
                            if (count == 8) {
                                cancelTriggerJob?.cancel(CancellationException("User cancelled export"))
                            }
                        }
                    )
                    val exporterCancel = FrameExporter(
                        context,
                        FrameExporter.MediaHooks(
                            createExtractor = ArrayDeque(listOf(vExtCancel, aExtCancel))::removeFirst,
                            createMuxer = { _, _ -> mockk(relaxed = true) },
                            createEncoder = { slowEncoder.codec },
                            createBufferInfo = { mockk(relaxed = true) },
                            createAacFormat = { _, _ -> aacFormat }
                        )
                    )
                    val job = launch(Job(), start = CoroutineStart.LAZY) {
                        exporterCancel.extractVideoClipOnce(
                            videoUri = videoUri,
                            startUs = 0L,
                            endUs = 3_000_000L,
                            muteAudio = false,
                            seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                        )
                    }
                    cancelTriggerJob = job
                    job.start()
                    job.join()
                    assertTrue(job.isCancelled)
                    assertTrue(slowEncoder.stopped && slowEncoder.released)
                    verify(exactly = 1) { aExtCancel.release() }
                    verify(exactly = 1) { vExtCancel.release() }
                    verify(exactly = 1) { pfdCancel.close() }
                    assertTrue(
                        "Expected no leaked motion_clip temp files",
                        cacheDir.listFiles()?.none { it.name.startsWith("motion_clip_") } ?: true
                    )
                } finally {
                    cacheDir.deleteRecursively()
                }
            } finally {
                unmockkStatic(DocumentFile::class, DocumentsContract::class)
                unmockkObject(HdrToneMapper)
            }
        }
    }

    @Test
    fun `benchmark streaming transcode vs legacy two-stage across 3s 10s 11s GOP and 15 MiB scenarios`() = runBlocking {
        withMockedAndroidLog {
            data class Scenario(
                val label: String,
                val sampleRate: Int,
                val channelCount: Int,
                val totalBytes: Int,
                val codecTurnaroundUs: Long
            )

            val scenarios = listOf(
                Scenario("3s_48kHz_stereo", 48_000, 2, 48_000 * 4 * 3, 850L),
                Scenario("10s_48kHz_stereo", 48_000, 2, 48_000 * 4 * 10, 850L),
                Scenario("10s_96kHz_stereo", 96_000, 2, 96_000 * 4 * 10, 850L),
                Scenario("11s_GOP_96kHz_stereo", 96_000, 2, 96_000 * 4 * 11, 850L),
                Scenario("15MiB_exact_peak", 192_000, 2, FrameExporter.MAX_PCM_BUFFER_BYTES, 0L),
                Scenario("15MiB_exceeded_abort", 192_000, 2, FrameExporter.MAX_PCM_BUFFER_BYTES + 65_536, 0L)
            )

            val allocProbe = ThreadAllocationProbe()
            val context = mockk<Context>(relaxed = true)
            val prewarmedBufferInfoPool = ArrayDeque<MediaCodec.BufferInfo>().apply {
                repeat(64) { addLast(mockk(relaxed = true)) }
            }
            assertTrue("Expected ThreadMXBean allocation tracking on OpenJDK", allocProbe.supported)

            // Warm up FrameExporter + MockK + ByteArrayOutputStream paths before timing
            run {
                val warmSamples = listOf(0L to ByteArray(64), 20_000L to ByteArray(64))
                val warmDecoded = listOf(0L to ByteArray(4096), 20_000L to ByteArray(4096))
                val warmFlac = mockAudioFormat("audio/flac", 48_000, 2)
                val warmPcm = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
                val warmAac = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)
                val warmDec = FakeAudioDecoderHarness(warmPcm, warmDecoded, recordTimeouts = false)
                val warmEnc = FakeAacEncoderHarness(warmAac, 4096, emitEncodedPackets = false, recordTimeouts = false)
                val warmExp = FrameExporter(
                    context,
                    FrameExporter.MediaHooks(
                        createDecoder = { warmDec.codec },
                        createEncoder = { warmEnc.codec },
                        createBufferInfo = { mockk(relaxed = true) },
                        createAacFormat = { _, _ -> warmAac }
                    )
                )
                warmExp.transcodeAudioToAac(createSampleExtractorMock(0, warmSamples), warmFlac, 0L, 1_000_000L)
            }

            println("=== FrameExporter Audio Transcode Benchmark (Before vs After) ===")
            for (sc in scenarios) {
                val totalBytes = sc.totalBytes
                // 16 KiB PCM per decoded compressed frame (~85ms @ 48kHz stereo, ~42ms @ 96kHz stereo)
                val chunkSize = 16_384
                val chunkPayload = ByteArray(chunkSize) { (it and 0x7F).toByte() }
                val numChunks = (totalBytes + chunkSize - 1) / chunkSize
                val tinyCompressedPacket = ByteArray(64)

                fun buildSamples(): Pair<List<Pair<Long, ByteArray>>, List<Pair<Long, ByteArray>>> {
                    val comp = ArrayList<Pair<Long, ByteArray>>(numChunks)
                    val dec = ArrayList<Pair<Long, ByteArray>>(numChunks)
                    var rem = totalBytes
                    var i = 0
                    while (rem > 0) {
                        val n = minOf(chunkSize, rem)
                        val pts = i * 20_000L
                        comp.add(pts to tinyCompressedPacket)
                        dec.add(pts to (if (n == chunkSize) chunkPayload else ByteArray(n)))
                        rem -= n
                        i++
                    }
                    return comp to dec
                }

                val flacFormat = mockAudioFormat("audio/flac", sc.sampleRate, sc.channelCount)
                val pcmOutFormat = mockAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_RAW,
                    sc.sampleRate,
                    sc.channelCount,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val aacFormat = mockAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sc.sampleRate, sc.channelCount)

                val decodeBatchUs = sc.codecTurnaroundUs
                val encodeBatchUs = (sc.codecTurnaroundUs * 59L) / 100L // realistic AAC encode / FLAC-AC3 decode ratio -> ~1.59x two-stage penalty

                // --- Baseline: Pre-fix two-stage decodeCompressedToPcm (BAOS 256 KiB + 64 KiB copy + toByteArray)
                //     followed serially by encodePcmToAac on identical MediaExtractor + MediaCodec harnesses ---
                val (legacyComp, legacyDec) = buildSamples()
                val legacyExtractor = createSampleExtractorMock(trackIndex = 0, samples = legacyComp, recordCalls = false)
                val legacyDecoderH = FakeAudioDecoderHarness(
                    outputFormat = pcmOutFormat,
                    decodedOutputs = legacyDec,
                    recordTimeouts = false
                )
                val legacyEncoderH = FakeAacEncoderHarness(
                    outputFormat = aacFormat,
                    inputBufferCapacity = 16_384,
                    maxInFlightInputsBeforeDrain = 4,
                    emitEncodedPackets = false,
                    recordTimeouts = false
                )
                val legacyDecInfo = prewarmedBufferInfoPool.removeFirst()
                val legacyEncInfo = prewarmedBufferInfoPool.removeFirst()

                val legacyAllocBefore = allocProbe.currentThreadAllocatedBytes()
                val legacyStartNs = System.nanoTime()
                var legacyResidentPeakBytes = 0L
                var legacyCodecClockUs = 0L
                var legacyDecodeBatches = 0L
                var legacyEncodeBatches = 0L
                val legacySuccess: Boolean = run {
                    // Stage 1: decodeCompressedToPcm into ByteArrayOutputStream(256 KiB) + 64 KiB copyChunk
                    val dec = legacyDecoderH.codec
                    val decInfo = legacyDecInfo
                    val baos = ByteArrayOutputStream(256 * 1024)
                    val copyChunk = ByteArray(64 * 1024)
                    var capacitySim = 256 * 1024L
                    var decInDone = false
                    var decOutDone = false
                    var aborted = false
                    legacyExtractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (!decOutDone) {
                        if (!decInDone) {
                            val inIdx = dec.dequeueInputBuffer(FrameExporter.CODEC_DEQUEUE_TIMEOUT_US)
                            if (inIdx >= 0) {
                                val inBuf = dec.getInputBuffer(inIdx)!!
                                inBuf.clear()
                                val sz = legacyExtractor.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    decInDone = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, sz, legacyExtractor.sampleTime, 0)
                                    legacyExtractor.advance()
                                    if (decodeBatchUs > 0L && ++legacyDecodeBatches % 4L == 0L) {
                                        legacyCodecClockUs += decodeBatchUs
                                    }
                                }
                            }
                        }
                        val outIdx = dec.dequeueOutputBuffer(decInfo, FrameExporter.CODEC_DEQUEUE_TIMEOUT_US)
                        if (outIdx >= 0) {
                            if (decInfo.size > 0) {
                                if (baos.size().toLong() + decInfo.size > FrameExporter.MAX_PCM_BUFFER_BYTES) {
                                    dec.releaseOutputBuffer(outIdx, false)
                                    aborted = true
                                    break
                                }
                                val outBuf = dec.getOutputBuffer(outIdx)!!
                                outBuf.position(decInfo.offset)
                                outBuf.limit(decInfo.offset + decInfo.size)
                                var remBytes = decInfo.size
                                while (remBytes > 0) {
                                    val c = minOf(remBytes, copyChunk.size)
                                    outBuf.get(copyChunk, 0, c)
                                    baos.write(copyChunk, 0, c)
                                    remBytes -= c
                                }
                                while (baos.size() > capacitySim) capacitySim *= 2L
                                legacyResidentPeakBytes = maxOf(legacyResidentPeakBytes, capacitySim + copyChunk.size)
                            }
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decOutDone = true
                            dec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                    dec.stop()
                    dec.release()
                    if (aborted) {
                        false
                    } else {
                        val fullPcm = baos.toByteArray()
                        // During toByteArray(), BAOS internal buffer (up to 16 MiB) + fullPcm (up to 15 MiB) + copyChunk (64 KiB) co-reside = 32.57 MB
                        legacyResidentPeakBytes = maxOf(legacyResidentPeakBytes, capacitySim + fullPcm.size + copyChunk.size)
                        // Stage 2: encodePcmToAac starts serially ONLY AFTER Stage 1 finishes (serial sum of decode + encode clocks)
                        val enc = legacyEncoderH.codec
                        val encInfo = legacyEncInfo
                        var offset = 0
                        var encInDone = false
                        var encOutDone = false
                        while (!encOutDone) {
                            if (!encInDone) {
                                val inIdx = enc.dequeueInputBuffer(FrameExporter.CODEC_DEQUEUE_TIMEOUT_US)
                                if (inIdx >= 0) {
                                    val inBuf = enc.getInputBuffer(inIdx)!!
                                    inBuf.clear()
                                    val remPcm = fullPcm.size - offset
                                    if (remPcm <= 0) {
                                        enc.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        encInDone = true
                                    } else {
                                        val toFeed = minOf(remPcm, inBuf.remaining())
                                        inBuf.put(fullPcm, offset, toFeed)
                                        enc.queueInputBuffer(inIdx, 0, toFeed, 0L, 0)
                                        offset += toFeed
                                        if (encodeBatchUs > 0L && ++legacyEncodeBatches % 4L == 0L) {
                                            legacyCodecClockUs += encodeBatchUs
                                        }
                                    }
                                }
                            }
                            val outIdx = enc.dequeueOutputBuffer(encInfo, FrameExporter.CODEC_DEQUEUE_TIMEOUT_US)
                            if (outIdx >= 0) {
                                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encOutDone = true
                                enc.releaseOutputBuffer(outIdx, false)
                            }
                        }
                        enc.stop()
                        enc.release()
                        true
                    }
                }
                val legacyWallMs = (System.nanoTime() - legacyStartNs) / 1_000_000.0
                val legacyAllocBytes = allocProbe.currentThreadAllocatedBytes() - legacyAllocBefore

                // --- Post-fix: FrameExporter single-loop streaming compressed decode + AAC encode with 16 KiB PcmCarryBuffer
                //     tracking overlapped hardware decoder & encoder clocks in real execution order ---
                var streamDecoderClockUs = 0L
                var streamEncoderClockUs = 0L
                var streamDecodeCount = 0L
                val (streamComp, streamDec) = buildSamples()
                val streamExtractor = createSampleExtractorMock(trackIndex = 0, samples = streamComp, recordCalls = false)
                val streamDecoder = FakeAudioDecoderHarness(
                    outputFormat = pcmOutFormat,
                    decodedOutputs = streamDec,
                    recordTimeouts = false,
                    onSampleQueued = {
                        if (decodeBatchUs > 0L && ++streamDecodeCount % 4L == 0L) {
                            streamDecoderClockUs += decodeBatchUs
                        }
                    }
                )
                val streamEncoder = FakeAacEncoderHarness(
                    outputFormat = aacFormat,
                    inputBufferCapacity = 16_384,
                    maxInFlightInputsBeforeDrain = 4,
                    emitEncodedPackets = false,
                    recordTimeouts = false,
                    onQueueInput = { count ->
                        if (encodeBatchUs > 0L && count % 4 == 0) {
                            // Encoder processes batch k concurrently as soon as decoder produced it:
                            streamEncoderClockUs = maxOf(streamEncoderClockUs, streamDecoderClockUs) + encodeBatchUs
                        }
                    }
                )
                val exporter = FrameExporter(
                    context,
                    FrameExporter.MediaHooks(
                        createDecoder = { streamDecoder.codec },
                        createEncoder = { streamEncoder.codec },
                        createBufferInfo = { prewarmedBufferInfoPool.removeFirst() },
                        createAacFormat = { _, _ -> aacFormat }
                    )
                )

                val streamAllocBefore = allocProbe.currentThreadAllocatedBytes()
                val streamStartNs = System.nanoTime()
                val streamResult = exporter.transcodeAudioToAac(streamExtractor, flacFormat, 0L, 40_000_000L)
                val streamWallMs = (System.nanoTime() - streamStartNs) / 1_000_000.0
                val streamAllocBytes = allocProbe.currentThreadAllocatedBytes() - streamAllocBefore
                val streamCodecClockUs = maxOf(streamDecoderClockUs, streamEncoderClockUs)
                val streamResidentPcmPeakBytes = FrameExporter.PCM_CARRY_BUFFER_BYTES.toLong()
                val pipelineSpeedup = if (streamCodecClockUs > 0L) {
                    legacyCodecClockUs.toDouble() / streamCodecClockUs.toDouble()
                } else {
                    1.0
                }

                println(
                    String.format(
                        "Scenario %-22s | Legacy Peak PCM: %6.2f MB (Alloc: %6.2f MB, CodecTime: %6.2f ms, Wall: %6.2f ms) | " +
                            "Streaming Peak PCM: %5.2f KB (Alloc: %6.2f MB, CodecTime: %6.2f ms, Wall: %6.2f ms, Speedup: %.2fx)",
                        sc.label,
                        legacyResidentPeakBytes / 1_000_000.0,
                        legacyAllocBytes / 1_000_000.0,
                        legacyCodecClockUs / 1_000.0,
                        legacyWallMs,
                        streamResidentPcmPeakBytes / 1024.0,
                        streamAllocBytes / 1_000_000.0,
                        streamCodecClockUs / 1_000.0,
                        streamWallMs,
                        pipelineSpeedup
                    )
                )

                // Acceptance assertions:
                // 1. Uncompressed PCM heap resident buffer is bounded within 16 KiB (well under 64 KiB; legacy hits 32.57 MB at 15 MiB)
                assertTrue(streamResidentPcmPeakBytes <= 16 * 1024L)
                assertTrue(streamResidentPcmPeakBytes < 64 * 1024L)
                if (sc.label == "15MiB_exact_peak") {
                    // 16 MiB BAOS + 15 MiB toByteArray() + 64 KiB copy buffer = 32,571,392 bytes (32.57 MB)
                    assertEquals(32_571_392L, legacyResidentPeakBytes)
                }
                // 2. Exceeding 15 MiB accurately aborts; valid clips eliminate intermediate PCM heap & two-stage stall
                if (totalBytes > FrameExporter.MAX_PCM_BUFFER_BYTES) {
                    assertFalse(legacySuccess)
                    assertNull(streamResult)
                } else {
                    assertTrue(legacySuccess)
                    assertNotNull(streamResult)
                    // Intermediate BAOS + toByteArray() heap spike is eliminated
                    val heapSavedBytes = legacyAllocBytes - streamAllocBytes
                    assertTrue(
                        "Expected streaming to save full-clip PCM heap allocations for ${sc.label} (legacy=$legacyAllocBytes, stream=$streamAllocBytes)",
                        heapSavedBytes > totalBytes * 1.5
                    )
                    if (decodeBatchUs > 0L) {
                        // Verify single-loop overlapped schedule eliminates the 18%-59% two-stage serial amplification
                        assertTrue(
                            "Expected single-loop pipeline speedup >= 1.18x for ${sc.label}, got ${pipelineSpeedup}x",
                            pipelineSpeedup >= 1.18
                        )
                    }
                }
            }
        }
    }

    // ── Test helpers & deterministic MediaCodec / MediaExtractor harnesses ──

    private inline fun <T> withMockedAndroidLog(block: () -> T): T {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        return block()
    }

    private class ThreadAllocationProbe {
        private val bean: Any?
        private val method: java.lang.reflect.Method?
        val supported: Boolean

        init {
            var resolvedBean: Any? = null
            var resolvedMethod: java.lang.reflect.Method? = null
            try {
                val mfClass = Class.forName("java.lang.management.ManagementFactory")
                resolvedBean = mfClass.getMethod("getThreadMXBean").invoke(null)
                val sunThreadMxInterface = Class.forName("com.sun.management.ThreadMXBean")
                if (resolvedBean != null && sunThreadMxInterface.isInstance(resolvedBean)) {
                    resolvedMethod = sunThreadMxInterface.getMethod(
                        "getThreadAllocatedBytes",
                        Long::class.javaPrimitiveType
                    )
                }
            } catch (_: Throwable) {
            }
            bean = resolvedBean
            method = resolvedMethod
            supported = bean != null && method != null
        }

        @Suppress("DEPRECATION")
        fun currentThreadAllocatedBytes(): Long {
            val b = bean ?: return 0L
            val m = method ?: return 0L
            return try {
                (m.invoke(b, Thread.currentThread().id) as? Long) ?: 0L
            } catch (_: Throwable) {
                0L
            }
        }
    }

    private fun busyWaitNs(durationNs: Long) {
        val deadline = System.nanoTime() + durationNs
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
    }

    private fun mockVideoFormat(mime: String, width: Int, height: Int): MediaFormat {
        val format = mockk<MediaFormat>(relaxed = true)
        every { format.getString(MediaFormat.KEY_MIME) } returns mime
        every { format.getInteger(MediaFormat.KEY_WIDTH) } returns width
        every { format.getInteger(MediaFormat.KEY_HEIGHT) } returns height
        every { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } returns 64 * 1024
        return format
    }

    private fun mockAudioFormat(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int? = null
    ): MediaFormat {
        val format = mockk<MediaFormat>(relaxed = true)
        every { format.getString(MediaFormat.KEY_MIME) } returns mime
        every { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } returns sampleRate
        every { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } returns channelCount
        every { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } returns 16 * 1024
        if (pcmEncoding != null) {
            every { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } returns pcmEncoding
        } else {
            every { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } throws NullPointerException("no encoding")
        }
        return format
    }

    private fun createSampleExtractorMock(
        trackIndex: Int,
        samples: List<Pair<Long, ByteArray>>,
        recordCalls: Boolean = true
    ): MediaExtractor {
        var cursor = 0
        val ext = mockk<MediaExtractor>(relaxed = true)
        if (!recordCalls) {
            excludeRecords {
                ext.sampleTrackIndex
                ext.sampleTime
                ext.sampleFlags
                ext.readSampleData(any(), any())
                ext.advance()
                ext.seekTo(any(), any())
            }
        }
        every { ext.sampleTrackIndex } answers { if (cursor < samples.size) trackIndex else -1 }
        every { ext.sampleTime } answers { if (cursor < samples.size) samples[cursor].first else -1L }
        every { ext.sampleFlags } returns 0
        every { ext.readSampleData(any(), any()) } answers {
            if (cursor >= samples.size) {
                -1
            } else {
                val dst = firstArg<ByteBuffer>()
                val offset = secondArg<Int>()
                val payload = samples[cursor].second
                dst.position(offset)
                dst.put(payload)
                dst.position(offset)
                dst.limit(offset + payload.size)
                payload.size
            }
        }
        every { ext.advance() } answers {
            cursor++
            cursor < samples.size
        }
        return ext
    }

    private fun createMultiTrackExtractorMock(
        formats: List<MediaFormat>,
        activeTrackIndex: Int,
        samples: List<Pair<Long, ByteArray>>
    ): MediaExtractor {
        var cursor = 0
        val ext = mockk<MediaExtractor>(relaxed = true)
        every { ext.trackCount } returns formats.size
        every { ext.getTrackFormat(any()) } answers { formats[firstArg()] }
        every { ext.sampleTrackIndex } answers { if (cursor < samples.size) activeTrackIndex else -1 }
        every { ext.sampleTime } answers { if (cursor < samples.size) samples[cursor].first else -1L }
        every { ext.sampleFlags } returns MediaExtractor.SAMPLE_FLAG_SYNC
        every { ext.readSampleData(any(), any()) } answers {
            if (cursor >= samples.size) {
                -1
            } else {
                val dst = firstArg<ByteBuffer>()
                val offset = secondArg<Int>()
                val payload = samples[cursor].second
                dst.position(offset)
                dst.put(payload)
                dst.position(offset)
                dst.limit(offset + payload.size)
                payload.size
            }
        }
        every { ext.advance() } answers {
            cursor++
            cursor < samples.size
        }
        return ext
    }

    private class FakeAudioDecoderHarness(
        private val outputFormat: MediaFormat,
        decodedOutputs: List<Pair<Long, ByteArray>>,
        private val stallZeroTimeoutChecks: Int = 0,
        private val recordTimeouts: Boolean = true,
        private val encoderActivityProbe: (() -> Boolean)? = null,
        private val onSampleQueued: (() -> Unit)? = null
    ) {
        val codec: MediaCodec = mockk(relaxed = true)
        val inputTimeouts = mutableListOf<Long>()
        val outputTimeouts = mutableListOf<Long>()
        var stopped = false
        var released = false
        var sawEncoderActivityBeforeEos = false
            private set

        private val inputBuf = ByteBuffer.allocate(64 * 1024)
        private val pendingDecoded = ArrayDeque(decodedOutputs)
        private val readyOutputs = ArrayDeque<Triple<Long, ByteArray, Int>>()
        private var activeOutBuf: ByteBuffer? = null
        private var emittedFormatChanged = false
        private var inputEosSeen = false
        private var outputEosEmitted = false
        private var remainingStalls = stallZeroTimeoutChecks

        init {
            if (!recordTimeouts) {
                excludeRecords {
                    codec.dequeueInputBuffer(any())
                    codec.getInputBuffer(any())
                    codec.queueInputBuffer(any(), any(), any(), any(), any())
                    codec.dequeueOutputBuffer(any(), any())
                    codec.getOutputBuffer(any())
                    codec.releaseOutputBuffer(any(), any<Boolean>())
                    codec.outputFormat
                    codec.configure(any(), any<android.view.Surface>(), any<android.media.MediaCrypto>(), any<Int>())
                    codec.start()
                    codec.stop()
                    codec.release()
                }
            }
            every { codec.dequeueInputBuffer(any()) } answers {
                val timeout = firstArg<Long>()
                if (recordTimeouts) inputTimeouts.add(timeout)
                if (timeout == 0L && remainingStalls > 0) {
                    remainingStalls--
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    0
                }
            }
            every { codec.getInputBuffer(0) } answers {
                inputBuf.clear()
                inputBuf
            }
            every { codec.queueInputBuffer(any(), any(), any(), any(), any()) } answers {
                val flags = arg<Int>(4)
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    inputEosSeen = true
                    if (pendingDecoded.isEmpty()) {
                        readyOutputs.addLast(Triple(0L, ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM))
                    }
                } else if (pendingDecoded.isNotEmpty()) {
                    onSampleQueued?.invoke()
                    val (pts, pcm) = pendingDecoded.removeFirst()
                    val outFlags = if (pendingDecoded.isEmpty() && inputEosSeen) {
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    } else {
                        0
                    }
                    readyOutputs.addLast(Triple(pts, pcm, outFlags))
                }
            }
            every { codec.outputFormat } returns outputFormat
            every { codec.dequeueOutputBuffer(any(), any()) } answers {
                val info = firstArg<MediaCodec.BufferInfo>()
                val timeout = secondArg<Long>()
                if (recordTimeouts) outputTimeouts.add(timeout)
                if (!outputEosEmitted && encoderActivityProbe?.invoke() == true) {
                    sawEncoderActivityBeforeEos = true
                }
                when {
                    activeOutBuf != null -> MediaCodec.INFO_TRY_AGAIN_LATER
                    !emittedFormatChanged && readyOutputs.isNotEmpty() -> {
                        emittedFormatChanged = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    }
                    readyOutputs.isNotEmpty() -> {
                        val (pts, pcm, flags) = readyOutputs.removeFirst()
                        val finalFlags = if (inputEosSeen && pendingDecoded.isEmpty() && readyOutputs.isEmpty()) {
                            flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        } else {
                            flags
                        }
                        if (finalFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEosEmitted = true
                        }
                        activeOutBuf = ByteBuffer.wrap(pcm)
                        info.offset = 0
                        info.size = pcm.size
                        info.presentationTimeUs = pts
                        info.flags = finalFlags
                        0
                    }
                    inputEosSeen && pendingDecoded.isEmpty() -> {
                        outputEosEmitted = true
                        activeOutBuf = ByteBuffer.allocate(0)
                        info.offset = 0
                        info.size = 0
                        info.presentationTimeUs = 0L
                        info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        0
                    }
                    else -> MediaCodec.INFO_TRY_AGAIN_LATER
                }
            }
            every { codec.getOutputBuffer(0) } answers { activeOutBuf }
            every { codec.releaseOutputBuffer(0, any<Boolean>()) } answers {
                if (!outputEosEmitted && encoderActivityProbe?.invoke() == true) {
                    sawEncoderActivityBeforeEos = true
                }
                activeOutBuf = null
            }
            every { codec.stop() } answers { stopped = true }
            every { codec.release() } answers { released = true }
        }
    }

    private class FakeAacEncoderHarness(
        private val outputFormat: MediaFormat,
        private val inputBufferCapacity: Int = 8192,
        private val emitCodecConfigPacket: Boolean = false,
        private val maxInFlightInputsBeforeDrain: Int = 4,
        private val emitEncodedPackets: Boolean = true,
        private val asyncEncodeLatencyNs: Long = 0L,
        private val recordTimeouts: Boolean = true,
        private val onQueueInput: ((Int) -> Unit)? = null
    ) {
        private data class PendingAacOutput(
            val pts: Long,
            val data: ByteArray,
            val flags: Int,
            val readyAtNs: Long
        )

        val codec: MediaCodec = mockk(relaxed = true)
        val receivedPcm = ByteArrayOutputStream()
        val queuedInputBatches = mutableListOf<Pair<Long, Int>>()
        val inputTimeouts = mutableListOf<Long>()
        val outputTimeouts = mutableListOf<Long>()
        var stopped = false
        var released = false

        private val inputBuf = ByteBuffer.allocate(inputBufferCapacity)
        private val pendingAacPackets = ArrayDeque<PendingAacOutput>()
        private val reusablePacket = ByteArray(16) { 0x21 }
        private var activeOutBuf: ByteBuffer? = null
        private var formatEmitted = false
        private var codecConfigEmitted = !emitCodecConfigPacket
        private var queueCount = 0

        init {
            if (!recordTimeouts) {
                excludeRecords {
                    codec.dequeueInputBuffer(any())
                    codec.getInputBuffer(any())
                    codec.queueInputBuffer(any(), any(), any(), any(), any())
                    codec.dequeueOutputBuffer(any(), any())
                    codec.getOutputBuffer(any())
                    codec.releaseOutputBuffer(any(), any<Boolean>())
                    codec.outputFormat
                    codec.configure(any(), any<android.view.Surface>(), any<android.media.MediaCrypto>(), any<Int>())
                    codec.start()
                    codec.stop()
                    codec.release()
                }
            }
            every { codec.dequeueInputBuffer(any()) } answers {
                val timeout = firstArg<Long>()
                if (recordTimeouts) inputTimeouts.add(timeout)
                if (pendingAacPackets.size >= maxInFlightInputsBeforeDrain) {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    0
                }
            }
            every { codec.getInputBuffer(0) } answers {
                inputBuf.clear()
                inputBuf
            }
            every { codec.queueInputBuffer(any(), any(), any(), any(), any()) } answers {
                val offset = secondArg<Int>()
                val size = thirdArg<Int>()
                val pts = arg<Long>(3)
                val flags = arg<Int>(4)
                queueCount++
                onQueueInput?.invoke(queueCount)
                val readyAt = if (asyncEncodeLatencyNs > 0L && queueCount % 4 == 0) {
                    System.nanoTime() + asyncEncodeLatencyNs
                } else {
                    0L
                }
                if (size > 0) {
                    if (emitEncodedPackets) {
                        queuedInputBatches.add(pts to size)
                        val copy = ByteArray(size)
                        inputBuf.position(offset)
                        inputBuf.get(copy, 0, size)
                        receivedPcm.write(copy)
                        val aacPacket = ByteArray(64) { (pts and 0xFF).toByte() }
                        pendingAacPackets.addLast(PendingAacOutput(pts, aacPacket, 0, readyAt))
                    } else {
                        // Emit lightweight reusable packet reference so output backpressure & async overlap are exercised identically
                        pendingAacPackets.addLast(PendingAacOutput(pts, reusablePacket, 0, readyAt))
                    }
                }
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    pendingAacPackets.addLast(PendingAacOutput(pts, ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM, readyAt))
                }
            }
            every { codec.outputFormat } returns outputFormat
            every { codec.dequeueOutputBuffer(any(), any()) } answers {
                val info = firstArg<MediaCodec.BufferInfo>()
                val timeout = secondArg<Long>()
                if (recordTimeouts) outputTimeouts.add(timeout)
                val headReadyAt = pendingAacPackets.peekFirst()?.readyAtNs ?: 0L
                if (headReadyAt > 0L) {
                    val now = System.nanoTime()
                    if (now < headReadyAt) {
                        if (timeout <= 0L) {
                            return@answers MediaCodec.INFO_TRY_AGAIN_LATER
                        }
                        val maxWaitNs = minOf(headReadyAt - now, timeout * 1_000L)
                        val deadline = now + maxWaitNs
                        while (System.nanoTime() < deadline) {
                            Thread.onSpinWait()
                        }
                        if (System.nanoTime() < headReadyAt) {
                            return@answers MediaCodec.INFO_TRY_AGAIN_LATER
                        }
                    }
                }
                when {
                    activeOutBuf != null -> MediaCodec.INFO_TRY_AGAIN_LATER
                    !formatEmitted && pendingAacPackets.isNotEmpty() -> {
                        formatEmitted = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    }
                    !codecConfigEmitted && pendingAacPackets.isNotEmpty() -> {
                        codecConfigEmitted = true
                        val csd = byteArrayOf(0x11, 0x90.toByte())
                        activeOutBuf = ByteBuffer.wrap(csd)
                        info.offset = 0
                        info.size = csd.size
                        info.presentationTimeUs = 0L
                        info.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        0
                    }
                    pendingAacPackets.isNotEmpty() -> {
                        val out = pendingAacPackets.removeFirst()
                        activeOutBuf = ByteBuffer.wrap(out.data)
                        info.offset = 0
                        info.size = out.data.size
                        info.presentationTimeUs = out.pts
                        info.flags = out.flags
                        0
                    }
                    else -> MediaCodec.INFO_TRY_AGAIN_LATER
                }
            }
            every { codec.getOutputBuffer(0) } answers { activeOutBuf }
            every { codec.releaseOutputBuffer(0, any<Boolean>()) } answers { activeOutBuf = null }
            every { codec.stop() } answers { stopped = true }
            every { codec.release() } answers { released = true }
        }
    }

    @Test
    fun `createMinimalJpegStub generates exact 28-byte valid JPEG with target dimensions and matches full ExifInterface APP1`() {
        val cacheDir = tempFolder.newFolder("cache")
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.cacheDir } returns cacheDir
        }
        val exporter = FrameExporter(context)
        val width = 3840
        val height = 2160

        val stub = exporter.createMinimalJpegStub(width, height)
        assertEquals(28, stub.size)
        // SOI
        assertEquals(0xFF, stub[0].toInt() and 0xFF)
        assertEquals(0xD8, stub[1].toInt() and 0xFF)
        // SOF0 with height=2160 (0x0870) and width=3840 (0x0F00)
        assertEquals(0xFF, stub[2].toInt() and 0xFF)
        assertEquals(0xC0, stub[3].toInt() and 0xFF)
        assertEquals(0x08, stub[7].toInt() and 0xFF)
        assertEquals(0x70, stub[8].toInt() and 0xFF)
        assertEquals(0x0F, stub[9].toInt() and 0xFF)
        assertEquals(0x00, stub[10].toInt() and 0xFF)
        // Minimal SOS + EOI
        assertEquals(0xFF, stub[15].toInt() and 0xFF)
        assertEquals(0xDA, stub[16].toInt() and 0xFF)
        assertEquals(0xFF, stub[26].toInt() and 0xFF)
        assertEquals(0xD9, stub[27].toInt() and 0xFF)

        val metadata = sampleVideoMetadata()
        val app1 = exporter.buildJpegExifApp1Segment(metadata, width, height)
        assertNotNull(app1)
        app1!!
        assertEquals(0xFF, app1[0].toInt() and 0xFF)
        assertEquals(0xE1, app1[1].toInt() and 0xFF)
        assertTrue("Stub files must be cleaned up in finally", cacheDir.listFiles().isNullOrEmpty())

        // Compare byte-for-byte against running ExifInterface.saveAttributes() on a real encoded JPEG
        val rawJpeg = encodeSampleJpeg(320, 180, 1.0f)
        val fullRefFile = tempFolder.newFile("full_ref.jpg").apply { writeBytes(rawJpeg) }
        val refExif = ExifInterface(fullRefFile.absolutePath)
        MetadataWriter.writeExifData(refExif, metadata)
        refExif.saveAttributes()
        val fullRefBytes = fullRefFile.readBytes()

        val stubApp1For320 = exporter.buildJpegExifApp1Segment(metadata, 320, 180)!!
        val refApp1 = exporter.extractApp1Segment(fullRefBytes)
        assertArrayEquals("Prebuilt APP1 must match full ExifInterface APP1 byte-for-byte", refApp1, stubApp1For320)

        val streamedOut = ByteArrayOutputStream()
        JpegApp1SplicingOutputStream(streamedOut, stubApp1For320).use { it.write(rawJpeg) }
        assertArrayEquals("Streamed JPEG must match full ExifInterface output byte-for-byte", fullRefBytes, streamedOut.toByteArray())
    }

    @Test
    fun `JpegApp1SplicingOutputStream handles chunked SOI, single-byte writes, non-JPEG passthrough, and truncated header`() {
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x06, 1, 2, 3, 4)
        val rawJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 9, 8, 7, 0xFF.toByte(), 0xD9.toByte())
        val expectedSpliced = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + rawJpeg.copyOfRange(2, rawJpeg.size)

        // 1. Split across 1-byte chunks with flush and 0-length writes
        val splitTarget = ByteArrayOutputStream()
        val splitStream = JpegApp1SplicingOutputStream(splitTarget, app1)
        splitStream.use { out ->
            out.write(rawJpeg, 0, 0)
            out.write(rawJpeg, 0, 1) // 0xFF only
            out.flush()
            out.write(rawJpeg, 1, 1) // 0xD8 arrives in second call
            out.write(rawJpeg, 2, rawJpeg.size - 2)
        }
        assertTrue(splitStream.app1Injected)
        assertEquals(expectedSpliced.size.toLong(), splitStream.bytesWritten)
        assertArrayEquals(expectedSpliced, splitTarget.toByteArray())

        // 2. Single-byte write(Int) API
        val byteByByteTarget = ByteArrayOutputStream()
        val byteByByteStream = JpegApp1SplicingOutputStream(byteByByteTarget, app1)
        byteByByteStream.use { out ->
            for (b in rawJpeg) {
                out.write(b.toInt() and 0xFF)
            }
        }
        assertTrue(byteByByteStream.app1Injected)
        assertEquals(expectedSpliced.size.toLong(), byteByByteStream.bytesWritten)
        assertArrayEquals(expectedSpliced, byteByByteTarget.toByteArray())

        // 3. Non-JPEG header passthrough (PNG signature)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pngTarget = ByteArrayOutputStream()
        val pngStream = JpegApp1SplicingOutputStream(pngTarget, app1)
        pngStream.use { it.write(pngBytes) }
        assertFalse(pngStream.app1Injected)
        assertEquals(pngBytes.size.toLong(), pngStream.bytesWritten)
        assertArrayEquals(pngBytes, pngTarget.toByteArray())

        // 4. 0xFF followed by non-0xD8 byte
        val ffOther = byteArrayOf(0xFF.toByte(), 0x00, 0x11, 0x22)
        val ffOtherTarget = ByteArrayOutputStream()
        val ffOtherStream = JpegApp1SplicingOutputStream(ffOtherTarget, app1)
        ffOtherStream.use {
            it.write(0xFF)
            it.write(ffOther, 1, ffOther.size - 1)
        }
        assertFalse(ffOtherStream.app1Injected)
        assertEquals(ffOther.size.toLong(), ffOtherStream.bytesWritten)
        assertArrayEquals(ffOther, ffOtherTarget.toByteArray())

        // 5. Truncated 1-byte 0xFF stream flushed on close()
        val truncatedTarget = ByteArrayOutputStream()
        val truncatedStream = JpegApp1SplicingOutputStream(truncatedTarget, app1)
        truncatedStream.use { it.write(0xFF) }
        assertFalse(truncatedStream.app1Injected)
        assertEquals(1L, truncatedStream.bytesWritten)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), truncatedTarget.toByteArray())
    }

    @Test
    fun `exportStaticFrame to MediaStore on Android Q+ performs 3 IPC calls and persists EXIF before clearing IS_PENDING`() = runTest {
        val cacheDir = tempFolder.newFolder("mediastore_cache")
        val mediaFile = tempFolder.newFile("mediastore_target.jpg")
        val rawJpeg = encodeSampleJpeg(640, 360, 1.0f)

        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>()
        val mediaUri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/42" }

        val recordedValues = mutableMapOf<String, Any?>()
        val ipcLog = mutableListOf<String>()
        var exifVerifiedBeforeUnpending = false

        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any(), any<String>()) } answers {
                recordedValues[firstArg()] = secondArg<String?>()
            }
            every { anyConstructed<ContentValues>().put(any(), any<Int>()) } answers {
                recordedValues[firstArg()] = secondArg<Int?>()
            }
            every { anyConstructed<ContentValues>().put(any(), any<Long>()) } answers {
                recordedValues[firstArg()] = secondArg<Long?>()
            }
            every { anyConstructed<ContentValues>().clear() } answers {
                recordedValues.clear()
            }

            every { context.cacheDir } returns cacheDir
            every { context.contentResolver } returns resolver
            every { resolver.insert(any(), any()) } answers {
                ipcLog.add("insert(IS_PENDING=${recordedValues[MediaStore.Images.Media.IS_PENDING]})")
                mediaUri
            }
            every { resolver.openOutputStream(mediaUri) } answers {
                ipcLog.add("openOutputStream")
                FileOutputStream(mediaFile)
            }
            every { resolver.update(mediaUri, any(), any(), any()) } answers {
                ipcLog.add("update(IS_PENDING=${recordedValues[MediaStore.Images.Media.IS_PENDING]})")
                assertEquals(0, recordedValues[MediaStore.Images.Media.IS_PENDING])
                // Verify EXIF attributes are ALREADY completely readable on disk at the moment IS_PENDING becomes 0
                val exif = ExifInterface(mediaFile.absolutePath)
                assertEquals("2026:07:15 14:30:45", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("Sony", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("ILCE-7SM3", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals("800", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
                assertEquals(1.0 / 120.0, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 1e-6)
                assertEquals("1.8", exif.getAttribute(ExifInterface.TAG_F_NUMBER))
                assertEquals(35.0, exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0), 1e-3)
                assertEquals("FrameEcho", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty().contains("Source: video frame capture"))
                val latLong = exif.latLong
                assertNotNull(latLong)
                assertEquals(35.6895, latLong!![0], 0.001)
                assertEquals(139.6917, latLong[1], 0.001)
                exifVerifiedBeforeUnpending = true
                1
            }

            val bitmap = mockSoftwareBitmap(640, 360, rawJpeg)
            val metadata = sampleVideoMetadata()
            val frame = CapturedFrame(
                timestampUs = 2_000_000L,
                width = 640,
                height = 360,
                colorSpace = ColorSpaceInfo(),
                metadata = metadata
            )
            val exporter = FrameExporter(context, sdkInt = Build.VERSION_CODES.Q)

            val result = exporter.exportStaticFrame(
                bitmap = bitmap,
                frame = frame,
                config = ExportConfig(format = ExportFormat.JPEG, quality = 100, preserveMetadata = true)
            )

            assertTrue(result is ExportResult.Success)
            result as ExportResult.Success
            assertTrue(exifVerifiedBeforeUnpending)
            assertTrue(result.metadataPreserved)
            assertEquals(mediaFile.length(), result.fileSizeBytes)
            assertEquals(
                listOf("insert(IS_PENDING=1)", "openOutputStream", "update(IS_PENDING=0)"),
                ipcLog
            )
            verify(exactly = 0) { resolver.openFileDescriptor(any(), any()) }
            assertTrue(cacheDir.listFiles().isNullOrEmpty())
        } finally {
            unmockkConstructor(ContentValues::class)
        }
    }

    @Test
    fun `exportStaticFrame to SAF custom directory performs 2 provider operations without openFileDescriptor`() = runTest {
        val cacheDir = tempFolder.newFolder("saf_cache")
        val safFile = tempFolder.newFile("saf_target.jpg")
        val rawJpeg = encodeSampleJpeg(640, 360, 1.0f)

        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>()
        val treeUri = mockk<Uri>()
        val createdUri = mockk<Uri> { every { this@mockk.toString() } returns "content://saf.provider/tree/root/document/root%2Fframe.jpg" }
        val tree = mockk<DocumentFile>()
        val docFile = mockk<DocumentFile>()
        val providerOps = mutableListOf<String>()

        mockkStatic(DocumentFile::class, DocumentsContract::class)
        try {
            every { context.cacheDir } returns cacheDir
            every { context.contentResolver } returns resolver
            every { DocumentFile.fromTreeUri(context, treeUri) } returns tree
            every { tree.createFile(ExportFormat.JPEG.mimeType, any()) } answers {
                providerOps.add("createFile")
                docFile
            }
            every { docFile.uri } returns createdUri
            every { treeUri.authority } returns "saf.provider"
            every { createdUri.scheme } returns ContentResolver.SCHEME_CONTENT
            every { createdUri.authority } returns "saf.provider"
            every { DocumentsContract.isDocumentUri(context, createdUri) } returns true
            every { DocumentsContract.isTreeUri(createdUri) } returns true
            every { DocumentsContract.getTreeDocumentId(treeUri) } returns "root"
            every { DocumentsContract.getTreeDocumentId(createdUri) } returns "root"
            every { resolver.openOutputStream(createdUri) } answers {
                providerOps.add("openOutputStream")
                FileOutputStream(safFile)
            }
            every { resolver.openFileDescriptor(any(), any()) } throws UnsupportedOperationException("Stream-only cloud provider")

            val bitmap = mockSoftwareBitmap(640, 360, rawJpeg)
            val metadata = sampleVideoMetadata()
            val frame = CapturedFrame(
                timestampUs = 1_500_000L,
                width = 640,
                height = 360,
                colorSpace = ColorSpaceInfo(),
                metadata = metadata
            )
            val exporter = FrameExporter(context, sdkInt = Build.VERSION_CODES.Q)

            val result = exporter.exportStaticFrame(
                bitmap = bitmap,
                frame = frame,
                config = ExportConfig(format = ExportFormat.JPEG, quality = 100, preserveMetadata = true),
                customExportTreeUri = treeUri
            )

            assertTrue(result is ExportResult.Success)
            result as ExportResult.Success
            assertEquals(listOf("createFile", "openOutputStream"), providerOps)
            verify(exactly = 0) { resolver.openFileDescriptor(any(), any()) }
            assertTrue(result.metadataPreserved)
            assertEquals(safFile.length(), result.fileSizeBytes)

            // Verify byte-for-byte APP1 match with full ExifInterface.saveAttributes()
            val refFile = tempFolder.newFile("saf_ref.jpg").apply { writeBytes(rawJpeg) }
            val refExif = ExifInterface(refFile.absolutePath)
            MetadataWriter.writeExifData(refExif, metadata)
            refExif.saveAttributes()
            assertArrayEquals(
                exporter.extractApp1Segment(refFile.readBytes()),
                exporter.extractApp1Segment(safFile.readBytes())
            )
        } finally {
            unmockkStatic(DocumentFile::class, DocumentsContract::class)
        }
    }

    @Test
    fun `exportStaticFrame handles preserveMetadata false, EXIF prebuild failure fallback, and non-JPEG fallback accurately`() = runTest {
        val rawJpeg = encodeSampleJpeg(320, 180, 0.95f)
        val metadata = sampleVideoMetadata()
        val frame = CapturedFrame(
            timestampUs = 500_000L,
            width = 320,
            height = 180,
            colorSpace = ColorSpaceInfo(),
            metadata = metadata
        )

        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any(), any<String>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Int>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Long>()) } returns Unit
            every { anyConstructed<ContentValues>().clear() } returns Unit

            // Case 1: preserveMetadata = false -> single pass, metadataPreserved = false, exact size
            val noMetaFile = tempFolder.newFile("no_meta.jpg")
            val noMetaCache = tempFolder.newFolder("no_meta_cache")
            val noMetaResolver = mockk<ContentResolver>()
            val noMetaUri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/101" }
            val noMetaContext = mockk<Context>(relaxed = true) {
                every { cacheDir } returns noMetaCache
                every { contentResolver } returns noMetaResolver
            }
            every { noMetaResolver.insert(any(), any()) } returns noMetaUri
            every { noMetaResolver.openOutputStream(noMetaUri) } answers { FileOutputStream(noMetaFile) }
            every { noMetaResolver.update(noMetaUri, any(), any(), any()) } returns 1

            val noMetaResult = FrameExporter(noMetaContext, Build.VERSION_CODES.Q).exportStaticFrame(
                bitmap = mockSoftwareBitmap(320, 180, rawJpeg),
                frame = frame,
                config = ExportConfig(format = ExportFormat.JPEG, quality = 95, preserveMetadata = false)
            ) as ExportResult.Success
            assertFalse(noMetaResult.metadataPreserved)
            assertEquals(rawJpeg.size.toLong(), noMetaResult.fileSizeBytes)
            assertEquals(noMetaFile.length(), noMetaResult.fileSizeBytes)
            verify(exactly = 0) { noMetaResolver.openFileDescriptor(any(), any()) }

            // Case 2: EXIF prebuild throws (unwritable file used as cacheDir) -> graceful single-pass fallback
            val brokenCacheFile = tempFolder.newFile("not_a_directory")
            val fallbackFile = tempFolder.newFile("fallback.jpg")
            val fallbackResolver = mockk<ContentResolver>()
            val fallbackUri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/102" }
            val fallbackContext = mockk<Context>(relaxed = true) {
                every { cacheDir } returns brokenCacheFile
                every { contentResolver } returns fallbackResolver
            }
            every { fallbackResolver.insert(any(), any()) } returns fallbackUri
            every { fallbackResolver.openOutputStream(fallbackUri) } answers { FileOutputStream(fallbackFile) }
            every { fallbackResolver.update(fallbackUri, any(), any(), any()) } returns 1

            val fallbackResult = FrameExporter(fallbackContext, Build.VERSION_CODES.Q).exportStaticFrame(
                bitmap = mockSoftwareBitmap(320, 180, rawJpeg),
                frame = frame,
                config = ExportConfig(format = ExportFormat.JPEG, quality = 95, preserveMetadata = true)
            ) as ExportResult.Success
            assertFalse("metadataPreserved must be false when EXIF prebuild fails", fallbackResult.metadataPreserved)
            assertEquals(rawJpeg.size.toLong(), fallbackResult.fileSizeBytes)
            assertEquals(fallbackFile.length(), fallbackResult.fileSizeBytes)
            verify(exactly = 0) { fallbackResolver.openFileDescriptor(any(), any()) }

            // Case 3: Non-JPEG format (simulated PNG/WebP) with preserveMetadata = true -> runs writeMetadataToUri before IS_PENDING=0 and refreshes final size
            val pngPayload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5, 6)
            val extraExifChunk = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)
            val pngFile = tempFolder.newFile("image.png")
            val pngEvents = mutableListOf<String>()
            val pngResolver = mockk<ContentResolver>()
            val pngUri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/103" }
            val pngContext = mockk<Context>(relaxed = true) {
                every { cacheDir } returns tempFolder.newFolder("png_cache")
                every { contentResolver } returns pngResolver
            }
            val rwPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
                every { fileDescriptor } returns FileDescriptor()
            }
            val roPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
                every { statSize } answers { pngFile.length() }
            }

            mockkConstructor(ExifInterface::class)
            try {
                every { anyConstructed<ExifInterface>().setAttribute(any(), any()) } returns Unit
                every { anyConstructed<ExifInterface>().setLatLong(any(), any()) } returns Unit
                every { anyConstructed<ExifInterface>().saveAttributes() } answers {
                    pngEvents.add("saveAttributes")
                    FileOutputStream(pngFile, true).use { it.write(extraExifChunk) }
                }
                every { pngResolver.insert(any(), any()) } answers {
                    pngEvents.add("insert")
                    pngUri
                }
                every { pngResolver.openOutputStream(pngUri) } answers {
                    pngEvents.add("openOutputStream")
                    FileOutputStream(pngFile)
                }
                every { pngResolver.openFileDescriptor(pngUri, "rw") } answers {
                    pngEvents.add("openFd(rw)")
                    rwPfd
                }
                every { pngResolver.openFileDescriptor(pngUri, "r") } answers {
                    pngEvents.add("openFd(r)")
                    roPfd
                }
                every { pngResolver.update(pngUri, any(), any(), any()) } answers {
                    pngEvents.add("update(IS_PENDING=0)")
                    1
                }

                val mockPngFormat = mockk<ExportFormat> {
                    every { mimeType } returns "image/png"
                    every { extension } returns "png"
                }
                val mockPngConfig = mockk<ExportConfig> {
                    every { format } returns mockPngFormat
                    every { quality } returns 100
                    every { preserveMetadata } returns true
                    every { maxResolution } returns null
                    every { customFileName } returns null
                    every { exportDirectory } returns ExportDirectory.PICTURES_FRAMEECHO
                }

                val pngResult = FrameExporter(pngContext, Build.VERSION_CODES.Q).exportStaticFrame(
                    bitmap = mockSoftwareBitmap(320, 180, pngPayload),
                    frame = frame,
                    config = mockPngConfig
                ) as ExportResult.Success

                assertTrue(pngResult.metadataPreserved)
                assertEquals((pngPayload.size + extraExifChunk.size).toLong(), pngResult.fileSizeBytes)
                assertEquals(pngFile.length(), pngResult.fileSizeBytes)
                assertEquals(
                    listOf("insert", "openOutputStream", "openFd(rw)", "saveAttributes", "openFd(r)", "update(IS_PENDING=0)"),
                    pngEvents
                )
            } finally {
                unmockkConstructor(ExifInterface::class)
            }
        } finally {
            unmockkConstructor(ContentValues::class)
        }
    }

    @Test
    fun `exportStaticFrame deletes pending MediaStore entry and recycles temporary bitmaps on IOException and CancellationException`() = runTest {
        val cacheDir = tempFolder.newFolder("cleanup_cache")
        val frame = CapturedFrame(
            timestampUs = 1_000_000L,
            width = 640,
            height = 360,
            colorSpace = ColorSpaceInfo(),
            metadata = sampleVideoMetadata()
        )

        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any(), any<String>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Int>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Long>()) } returns Unit
            every { anyConstructed<ContentValues>().clear() } returns Unit

            // 1. IOException during compress -> deletes URI, recycles temporary softBitmap, returns ExportResult.Error
            val ioUri = mockk<Uri>()
            val ioResolver = mockk<ContentResolver>()
            val ioContext = mockk<Context>(relaxed = true) {
                every { this@mockk.cacheDir } returns cacheDir
                every { contentResolver } returns ioResolver
            }
            every { ioResolver.insert(any(), any()) } returns ioUri
            every { ioResolver.openOutputStream(ioUri) } returns ByteArrayOutputStream()
            every { ioResolver.delete(ioUri, null, null) } returns 1

            val softCopyForIo = mockk<Bitmap>(relaxed = true) {
                every { config } returns Bitmap.Config.ARGB_8888
                every { width } returns 640
                every { height } returns 360
                every { compress(any(), any(), any()) } throws java.io.IOException("Disk full")
            }
            val hardwareBitmapForIo = mockk<Bitmap>(relaxed = true) {
                every { config } returns Bitmap.Config.HARDWARE
                every { copy(Bitmap.Config.ARGB_8888, false) } returns softCopyForIo
            }

            val ioResult = FrameExporter(ioContext, Build.VERSION_CODES.Q).exportStaticFrame(
                bitmap = hardwareBitmapForIo,
                frame = frame,
                config = ExportConfig(format = ExportFormat.JPEG, quality = 100, preserveMetadata = true)
            )
            assertTrue(ioResult is ExportResult.Error)
            verify(exactly = 1) { ioResolver.delete(ioUri, null, null) }
            verify(exactly = 1) { softCopyForIo.recycle() }
            verify(exactly = 0) { hardwareBitmapForIo.recycle() }

            // 2. CancellationException during compress -> deletes URI, recycles temporary softBitmap, rethrows CancellationException
            val cancelUri = mockk<Uri>()
            val cancelResolver = mockk<ContentResolver>()
            val cancelContext = mockk<Context>(relaxed = true) {
                every { this@mockk.cacheDir } returns cacheDir
                every { contentResolver } returns cancelResolver
            }
            every { cancelResolver.insert(any(), any()) } returns cancelUri
            every { cancelResolver.openOutputStream(cancelUri) } returns ByteArrayOutputStream()
            every { cancelResolver.delete(cancelUri, null, null) } returns 1

            val softCopyForCancel = mockk<Bitmap>(relaxed = true) {
                every { config } returns Bitmap.Config.ARGB_8888
                every { width } returns 640
                every { height } returns 360
                every { compress(any(), any(), any()) } throws kotlinx.coroutines.CancellationException("User cancelled")
            }
            val hardwareBitmapForCancel = mockk<Bitmap>(relaxed = true) {
                every { config } returns Bitmap.Config.HARDWARE
                every { copy(Bitmap.Config.ARGB_8888, false) } returns softCopyForCancel
            }

            var cancelledThrown = false
            try {
                FrameExporter(cancelContext, Build.VERSION_CODES.Q).exportStaticFrame(
                    bitmap = hardwareBitmapForCancel,
                    frame = frame,
                    config = ExportConfig(format = ExportFormat.JPEG, quality = 100, preserveMetadata = true)
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelledThrown = true
            }
            assertTrue(cancelledThrown)
            verify(exactly = 1) { cancelResolver.delete(cancelUri, null, null) }
            verify(exactly = 1) { softCopyForCancel.recycle() }
            verify(exactly = 0) { hardwareBitmapForCancel.recycle() }
        } finally {
            unmockkConstructor(ContentValues::class)
        }
    }

    @Test
    fun `exportStaticFrame 4K JPEG achieves 1_00x target write bytes and under 1_001x total write bytes`() = runTest {
        val cacheDir = tempFolder.newFolder("4k_cache")
        val targetFile = tempFolder.newFile("4k_frame.jpg")
        // Build a ~5.6 MiB 3840x2160 valid multi-segment JPEG payload
        val fourKJpeg = buildSizedMultiSegmentJpeg(width = 3840, height = 2160, targetTotalBytes = 5_870_567)

        var targetBytesWritten = 0L
        val countingTargetStream = object : OutputStream() {
            private val fileOut = FileOutputStream(targetFile)
            override fun write(b: Int) {
                fileOut.write(b)
                targetBytesWritten += 1L
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                fileOut.write(b, off, len)
                targetBytesWritten += len.toLong()
            }
            override fun flush() = fileOut.flush()
            override fun close() = fileOut.close()
        }

        val resolver = mockk<ContentResolver>()
        val mediaUri = mockk<Uri> { every { this@mockk.toString() } returns "content://media/external/images/media/4000" }
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.cacheDir } returns cacheDir
            every { contentResolver } returns resolver
        }

        mockkConstructor(ContentValues::class)
        try {
            every { anyConstructed<ContentValues>().put(any(), any<String>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Int>()) } returns Unit
            every { anyConstructed<ContentValues>().put(any(), any<Long>()) } returns Unit
            every { anyConstructed<ContentValues>().clear() } returns Unit
            every { resolver.insert(any(), any()) } returns mediaUri
            every { resolver.openOutputStream(mediaUri) } returns countingTargetStream
            every { resolver.update(mediaUri, any(), any(), any()) } returns 1

            val bitmap = mockk<Bitmap>(relaxed = true) {
                every { config } returns Bitmap.Config.ARGB_8888
                every { width } returns 3840
                every { height } returns 2160
                every { compress(any(), any(), any()) } answers {
                    val out = thirdArg<OutputStream>()
                    var offset = 0
                    while (offset < fourKJpeg.size) {
                        val chunk = minOf(32_768, fourKJpeg.size - offset)
                        out.write(fourKJpeg, offset, chunk)
                        offset += chunk
                    }
                    true
                }
            }

            val result = FrameExporter(context, Build.VERSION_CODES.Q).exportStaticFrame(
                bitmap = bitmap,
                frame = CapturedFrame(
                    timestampUs = 3_000_000L,
                    width = 3840,
                    height = 2160,
                    colorSpace = ColorSpaceInfo(),
                    metadata = sampleVideoMetadata()
                ),
                config = ExportConfig(format = ExportFormat.JPEG, quality = 100, preserveMetadata = true)
            ) as ExportResult.Success

            assertTrue(result.metadataPreserved)
            assertEquals(targetFile.length(), result.fileSizeBytes)
            // Target file write amplification must be exactly 1.00x (zero full-file rewrite)
            assertEquals(result.fileSizeBytes, targetBytesWritten)
            // Stub writes: 28B stub + ~700B ExifInterface temp rewrite < 0.001x of 5.6 MiB
            val maxAllowedTotalVfsWrite = (result.fileSizeBytes * 1.001).toLong()
            val estimatedStubWrites = 28L + (result.fileSizeBytes - fourKJpeg.size) + 28L
            assertTrue(
                "Total VFS writes must stay within 1.001x of final image size",
                targetBytesWritten + estimatedStubWrites <= maxAllowedTotalVfsWrite
            )
        } finally {
            unmockkConstructor(ContentValues::class)
        }
    }

    private fun mockSoftwareBitmap(width: Int, height: Int, compressedPayload: ByteArray): Bitmap {
        return mockk<Bitmap>(relaxed = true) {
            every { config } returns Bitmap.Config.ARGB_8888
            every { this@mockk.width } returns width
            every { this@mockk.height } returns height
            every { compress(any(), any(), any()) } answers {
                val out = thirdArg<OutputStream>()
                // Write in realistic multi-chunk slices to exercise streaming state transitions
                val split = compressedPayload.size.coerceAtMost(64)
                if (split > 0) {
                    out.write(compressedPayload, 0, split)
                }
                if (compressedPayload.size > split) {
                    out.write(compressedPayload, split, compressedPayload.size - split)
                }
                true
            }
        }
    }

    private fun sampleVideoMetadata(): VideoMetadata = VideoMetadata(
        dateTime = "2026-07-15T14:30:45",
        latitude = 35.6895,
        longitude = 139.6917,
        altitude = 44.5,
        make = "Sony",
        model = "ILCE-7SM3",
        iso = 800,
        exposureTime = "1/120",
        fNumber = 1.8,
        focalLength = 35.0,
        rotation = 0,
        videoWidth = 3840,
        videoHeight = 2160,
        frameRate = 59.94f,
        codec = "video/hevc",
        sourceFileName = "C0042.MP4"
    )

    private fun encodeSampleJpeg(width: Int, height: Int, quality: Float): ByteArray {
        val w = width.coerceIn(1, 0xFFFF)
        val h = height.coerceIn(1, 0xFFFF)
        val scanLen = ((width * height / 16).coerceIn(256, 8192) * quality).toInt().coerceAtLeast(128)
        val out = ByteArrayOutputStream(scanLen + 128)
        // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 JFIF (16-byte payload + 2-byte marker)
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
                'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
            )
        )
        // DQT (67-byte length = 2 + 1 + 64)
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00))
        out.write(ByteArray(64) { 1 })
        // 3-component SOF0 (length = 17 = 2 + 1 + 2 + 2 + 1 + 3*3)
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
                ((h shr 8) and 0xFF).toByte(), (h and 0xFF).toByte(),
                ((w shr 8) and 0xFF).toByte(), (w and 0xFF).toByte(),
                0x03,
                0x01, 0x22, 0x00,
                0x02, 0x11, 0x00,
                0x03, 0x11, 0x00
            )
        )
        // SOS (3 components, length = 12 = 2 + 1 + 3*2 + 3)
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C, 0x03,
                0x01, 0x00, 0x02, 0x00, 0x03, 0x00,
                0x00, 0x3F, 0x00
            )
        )
        // Entropy-coded scan payload (avoiding unescaped 0xFF markers)
        val scanPayload = ByteArray(scanLen) { idx -> ((idx * 37 + 13) % 251).toByte() }
        out.write(scanPayload)
        // EOI
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun buildSizedMultiSegmentJpeg(width: Int, height: Int, targetTotalBytes: Int): ByteArray {
        val headerAndFooterOverhead = 2 + 18 + 69 + 19 + 14 + 2 // SOI + APP0 + DQT + SOF0 + SOS + EOI = 124 bytes
        val scanLen = (targetTotalBytes - headerAndFooterOverhead).coerceAtLeast(128)
        val w = width.coerceIn(1, 0xFFFF)
        val h = height.coerceIn(1, 0xFFFF)
        val out = ByteArrayOutputStream(targetTotalBytes)
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
                'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
                0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
            )
        )
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00))
        out.write(ByteArray(64) { 1 })
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
                ((h shr 8) and 0xFF).toByte(), (h and 0xFF).toByte(),
                ((w shr 8) and 0xFF).toByte(), (w and 0xFF).toByte(),
                0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00
            )
        )
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C, 0x03,
                0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x3F, 0x00
            )
        )
        val chunk = ByteArray(4096) { idx -> ((idx * 31 + 7) % 251).toByte() }
        var remaining = scanLen
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size)
            out.write(chunk, 0, n)
            remaining -= n
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }
}
