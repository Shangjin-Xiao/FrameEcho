package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap

class FrameExporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Xml::class)
        every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
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

            // Scenario 1: Clamped 256 MiB KEY_MAX_INPUT_SIZE to 32 MiB on buffer AND on both muxer.addTrack formats
            val s1 = scenarios[0]
            assertTrue("Expected Success for ${s1.name}, got ${s1.result}", s1.result is ExportResult.Success)
            assertFalse((s1.result as ExportResult.Success).audioDropped)
            assertEquals(FrameExporter.MAX_SAMPLE_BUFFER_BYTES, s1.maxObservedBufferCapacity)
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

            // Scenario 3: AAC remux oversized first sample omitted audio track before muxer.start()
            val s3 = scenarios[2]
            assertTrue("Expected Success for ${s3.name}, got ${s3.result}", s3.result is ExportResult.Success)
            val s3Success = s3.result as ExportResult.Success
            assertTrue(s3Success.isMotionPhoto)
            assertTrue("Expected audioDropped=true for ${s3.name}", s3Success.audioDropped)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), s3.muxerAddedTrackMimes)
            assertEquals(0, s3.muxerAudioSamplesWritten)
            assertEquals(0, s3.audioReadSampleCount)

            // Scenario 4: LPCM oversized sample skipped before allocateDirect with audioDropped = true
            val s4 = scenarios[3]
            assertTrue("Expected Success for ${s4.name}, got ${s4.result}", s4.result is ExportResult.Success)
            val s4Success = s4.result as ExportResult.Success
            assertTrue(s4Success.isMotionPhoto)
            assertTrue("Expected audioDropped=true for ${s4.name}", s4Success.audioDropped)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), s4.muxerAddedTrackMimes)
            assertEquals(0, s4.audioReadSampleCount)
        }
    }

    @Test
    fun `exportMotionPhoto omits compatible audio track and avoids truncated audio when later sample exceeds limit`() {
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
            // Verify no audio track or truncated audio samples were added to MediaMuxer
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC), outcome.muxerAddedTrackMimes)
            assertEquals(0, outcome.muxerAudioSamplesWritten)
            assertEquals(0, outcome.audioReadSampleCount)
        }
    }

    @Test
    fun `exportMotionPhoto reuses bounded direct buffer across successively larger samples without exhausting direct memory`() {
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
            assertEquals(20 * oneMiB, outcome.maxObservedBufferCapacity)
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
        val muxerAddedTrackMimes: List<String?>,
        val muxerAddedTrackMaxInputSizes: List<Int?>,
        val muxerAudioSamplesWritten: Int,
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
        var peakDirectBytes = directMemoryUsedBytes()
        var maxObservedBufferCapacity = 0
        var maxObservedAudioBufferCapacity = 0
        var audioReadSampleCount = 0
        val capturedEncoderPcm = ByteArrayOutputStream()

        fun sampleDirectMemory() {
            peakDirectBytes = maxOf(peakDirectBytes, directMemoryUsedBytes())
        }

        val cacheDir = temporaryFolder.newFolder(name)
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
        mockkConstructor(MediaExtractor::class, MediaMuxer::class, MediaCodec.BufferInfo::class)

        if (mockAacEncoder) {
            mockkStatic(MediaFormat::class, MediaCodec::class)
            every {
                MediaFormat.createAudioFormat(any(), any(), any())
            } answers {
                buildMockFormat(
                    mime = firstArg(),
                    sampleRate = secondArg(),
                    channelCount = thirdArg()
                )
            }
            every { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) } answers {
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
                        val info = MediaCodec.BufferInfo().apply {
                            this.offset = 0
                            this.size = 8
                            this.presentationTimeUs = ptsUs
                            this.flags = 0
                        }
                        pendingOutputs.addLast(ByteArray(8) { 0x55 } to info)
                    }
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val eosInfo = MediaCodec.BufferInfo().apply {
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

        val cursors = Collections.synchronizedMap(IdentityHashMap<MediaExtractor, ExtractorCursor>())
        fun cursorFor(extractor: MediaExtractor): ExtractorCursor =
            cursors.getOrPut(extractor) { ExtractorCursor(tracks) }

        every { anyConstructed<MediaExtractor>().setDataSource(any<Context>(), any<Uri>(), any()) } answers {
            cursors[self as MediaExtractor] = ExtractorCursor(tracks)
        }
        every { anyConstructed<MediaExtractor>().trackCount } answers {
            cursorFor(self as MediaExtractor).tracks.size
        }
        every { anyConstructed<MediaExtractor>().getTrackFormat(any()) } answers {
            cursorFor(self as MediaExtractor).tracks[firstArg()].format
        }
        every { anyConstructed<MediaExtractor>().selectTrack(any()) } answers {
            val cursor = cursorFor(self as MediaExtractor)
            cursor.selectedTrack = firstArg()
            cursor.sampleIndex = 0
        }
        every { anyConstructed<MediaExtractor>().unselectTrack(any()) } answers {
            val cursor = cursorFor(self as MediaExtractor)
            if (cursor.selectedTrack == firstArg<Int>()) {
                cursor.selectedTrack = -1
            }
        }
        every { anyConstructed<MediaExtractor>().seekTo(any(), any()) } answers {
            cursorFor(self as MediaExtractor).sampleIndex = 0
        }
        every { anyConstructed<MediaExtractor>().sampleTrackIndex } answers {
            val cursor = cursorFor(self as MediaExtractor)
            if (cursor.currentSample() != null) cursor.selectedTrack else -1
        }
        every { anyConstructed<MediaExtractor>().sampleTime } answers {
            cursorFor(self as MediaExtractor).currentSample()?.timeUs ?: -1L
        }
        every { anyConstructed<MediaExtractor>().sampleSize } answers {
            sampleDirectMemory()
            cursorFor(self as MediaExtractor).currentSample()?.reportedSampleSize ?: -1L
        }
        every { anyConstructed<MediaExtractor>().sampleFlags } answers {
            cursorFor(self as MediaExtractor).currentSample()?.flags ?: 0
        }
        every { anyConstructed<MediaExtractor>().readSampleData(any(), any()) } answers {
            sampleDirectMemory()
            val buf = firstArg<ByteBuffer>()
            val offset = secondArg<Int>()
            val cursor = cursorFor(self as MediaExtractor)
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
        every { anyConstructed<MediaExtractor>().advance() } answers {
            val cursor = cursorFor(self as MediaExtractor)
            cursor.sampleIndex++
            cursor.currentSample() != null
        }
        every { anyConstructed<MediaExtractor>().release() } returns Unit

        val muxerStates = Collections.synchronizedMap(IdentityHashMap<MediaMuxer, MuxerState>())
        fun muxerStateFor(muxer: MediaMuxer): MuxerState =
            muxerStates.getOrPut(muxer) { MuxerState() }
        var lastStoppedMuxerState: MuxerState? = null

        every { anyConstructed<MediaMuxer>().addTrack(any()) } answers {
            val state = muxerStateFor(self as MediaMuxer)
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
        every { anyConstructed<MediaMuxer>().setOrientationHint(any()) } returns Unit
        every { anyConstructed<MediaMuxer>().start() } returns Unit
        every { anyConstructed<MediaMuxer>().writeSampleData(any(), any(), any()) } answers {
            sampleDirectMemory()
            val state = muxerStateFor(self as MediaMuxer)
            val trackId = firstArg<Int>()
            val buf = secondArg<ByteBuffer>()
            maxObservedBufferCapacity = maxOf(maxObservedBufferCapacity, buf.capacity())
            state.samplesWrittenByTrack[trackId] = (state.samplesWrittenByTrack[trackId] ?: 0) + 1
            cacheDir.listFiles()?.filter { it.name.startsWith("motion_clip_") }?.forEach { clipFile ->
                if (clipFile.length() == 0L) {
                    clipFile.writeBytes(byteArrayOf(0, 0, 0, 8, 0x66, 0x74, 0x79, 0x70))
                }
            }
        }
        every { anyConstructed<MediaMuxer>().stop() } answers {
            val state = muxerStateFor(self as MediaMuxer)
            val emptyTrack = state.samplesWrittenByTrack.entries.firstOrNull { it.value == 0 }
            if (emptyTrack != null) {
                throw IllegalStateException(
                    "MPEG4Writer: track ${emptyTrack.key} has 0 samples written"
                )
            }
            lastStoppedMuxerState = state
        }
        every { anyConstructed<MediaMuxer>().release() } returns Unit

        val exporter = FrameExporter(context)
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
            unmockkAll()
            mockkStatic(Xml::class)
            every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
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

        return ScenarioOutcome(
            name = name,
            result = exportResult,
            triggeredOom = triggeredOom,
            peakDirectBytes = peakDirectBytes,
            maxObservedBufferCapacity = maxObservedBufferCapacity,
            maxObservedAudioBufferCapacity = maxObservedAudioBufferCapacity,
            audioReadSampleCount = audioReadSampleCount,
            muxerAddedTrackMimes = finalMuxer?.addedTrackMimes?.toList() ?: emptyList(),
            muxerAddedTrackMaxInputSizes = finalMuxer?.addedTrackMaxInputSizes?.toList() ?: emptyList(),
            muxerAudioSamplesWritten = muxerAudioWritten,
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
}
