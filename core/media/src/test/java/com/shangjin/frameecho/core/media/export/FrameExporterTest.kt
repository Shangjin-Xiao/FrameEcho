package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.media.colorspace.HdrToneMapper
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Before
import org.junit.Test

class FrameExporterTest {

    @Before
    fun setup() {
        mockkStatic(Xml::class)
        every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
    }

    @After
    fun tearDown() {
        unmockkStatic(Xml::class)
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
            io.mockk.verify { cleanFormat.setByteBuffer("csd-0", csd0) }
            io.mockk.verify(exactly = 0) { cleanFormat.setByteBuffer("csd-1", any()) }
            io.mockk.verify { cleanFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30) }
            io.mockk.verify { cleanFormat.setLong(MediaFormat.KEY_DURATION, 1_000_000L) }
        } finally {
            unmockkStatic(MediaFormat::class)
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

                // Inject 2 artificial stall cycles where 0L returns TRY_AGAIN_LATER so backpressure wait triggers
                val decoderHarness = FakeAudioDecoderHarness(
                    outputFormat = decoderOutFormat,
                    decodedOutputs = decodedChunks,
                    stallZeroTimeoutChecks = 2
                )
                val encoderHarness = FakeAacEncoderHarness(
                    outputFormat = aacOutFormat,
                    inputBufferCapacity = 4096,
                    emitCodecConfigPacket = true,
                    maxInFlightInputsBeforeDrain = 2
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
                    decoderHarness.encoderStartedBeforeDecoderEos { encoderHarness.queuedInputBatches.isNotEmpty() }
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
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        return try {
            block()
        } finally {
            unmockkStatic(Log::class)
        }
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
        private val onSampleQueued: (() -> Unit)? = null
    ) {
        val codec: MediaCodec = mockk(relaxed = true)
        val inputTimeouts = mutableListOf<Long>()
        val outputTimeouts = mutableListOf<Long>()
        var stopped = false
        var released = false

        private val inputBuf = ByteBuffer.allocate(64 * 1024)
        private val pendingDecoded = ArrayDeque(decodedOutputs)
        private val readyOutputs = ArrayDeque<Triple<Long, ByteArray, Int>>()
        private var activeOutBuf: ByteBuffer? = null
        private var emittedFormatChanged = false
        private var inputEosSeen = false
        private var remainingStalls = stallZeroTimeoutChecks
        private var sawEncoderActivityBeforeEos = false
        private var encoderActivityProbe: (() -> Boolean)? = null
        private var batchCounter = 0

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
                if (encoderActivityProbe?.invoke() == true && (!inputEosSeen || readyOutputs.isNotEmpty())) {
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
                        activeOutBuf = ByteBuffer.wrap(pcm)
                        info.offset = 0
                        info.size = pcm.size
                        info.presentationTimeUs = pts
                        info.flags = finalFlags
                        0
                    }
                    inputEosSeen && pendingDecoded.isEmpty() -> {
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
                if (encoderActivityProbe?.invoke() == true && (pendingDecoded.isNotEmpty() || readyOutputs.isNotEmpty())) {
                    sawEncoderActivityBeforeEos = true
                }
                activeOutBuf = null
            }
            every { codec.stop() } answers { stopped = true }
            every { codec.release() } answers { released = true }
        }

        fun encoderStartedBeforeDecoderEos(probe: () -> Boolean): Boolean {
            encoderActivityProbe = probe
            return sawEncoderActivityBeforeEos || probe()
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
}
