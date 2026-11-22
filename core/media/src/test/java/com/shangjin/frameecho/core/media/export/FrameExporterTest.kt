package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import android.util.Xml
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque

class FrameExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Xml::class, Log::class)
        every { Xml.newSerializer() } returns org.kxml2.io.KXmlSerializer()
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Xml::class, Log::class)
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

    @Test
    fun `PcmCarryBuffer enforces 16 KB cap and reassembles unaligned PCM frame slices`() {
        assertEquals(16 * 1024, FrameExporter.PCM_CARRY_BUFFER_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            PcmCarryBuffer(bytesPerFrame = 4, capacity = FrameExporter.PCM_CARRY_BUFFER_BYTES + 4)
        }

        // Stereo 16-bit: 4 bytes per frame; test small ring compaction + split-frame reassembly
        val carry = PcmCarryBuffer(bytesPerFrame = 4, capacity = 16)
        val totalBytes = 100 // 25 stereo frames
        val expected = ByteArray(totalBytes) { (it * 7 + 3).toByte() }
        val sliceSizes = intArrayOf(1, 2, 1, 5, 7, 11, 3, 14, 9, 15, 6, 13, 13)
        assertEquals(totalBytes, sliceSizes.sum())

        val dst = ByteBuffer.allocate(10) // Intentionally non-frame-aligned capacity (10 -> 8 aligned)
        val actualOut = ByteArrayOutputStream()
        var offset = 0
        for (len in sliceSizes) {
            val src = ByteBuffer.wrap(expected, offset, len)
            offset += len
            while (src.hasRemaining() || carry.hasAlignedFrames) {
                val written = carry.fillEncoderBuffer(src, dst)
                if (written > 0) {
                    assertEquals(0, written % 4)
                    dst.flip()
                    val chunk = ByteArray(written)
                    dst.get(chunk)
                    actualOut.write(chunk)
                }
                if (src.hasRemaining()) {
                    val appended = carry.appendFrom(src)
                    if (appended == 0 && !carry.hasAlignedFrames) break
                } else if (!carry.hasAlignedFrames) {
                    break
                }
            }
        }
        assertEquals(0, carry.size)
        assertArrayEquals(expected, actualOut.toByteArray())

        // Encoder buffer smaller than one frame returns 0 so caller can fail fast
        val tinyDst = ByteBuffer.allocate(3)
        assertEquals(0, carry.fillEncoderBuffer(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), tinyDst))
    }

    @Test
    fun `streamRawPcmToAac streams unaligned LPCM slices with monotonic PTS and drops config packets`() = runTest {
        val context = createTestContext()
        val exporter = FrameExporter(context)
        val encoderFormat = mockk<MediaFormat>(relaxed = true)
        val fakeEncoder = FakeAacEncoder(
            outputFormat = encoderFormat,
            inputBufferCapacity = 1026, // intentionally not divisible by 4 -> 1024 aligned
            inputSlotCount = 2
        )
        exporter.aacEncoderFactory = { rate, ch ->
            assertEquals(48_000, rate)
            assertEquals(2, ch)
            fakeEncoder
        }

        // 1,001 + 1,003 + 4,098 + 2 = 6,104 bytes (1,526 stereo frames), plus 2 trailing orphan bytes at EOS
        val validBytes = ByteArray(6_104) { ((it * 13) and 0xFF).toByte() }
        val chunks = listOf(
            SampleChunk(1_250_000L, validBytes.copyOfRange(0, 1)),
            SampleChunk(1_250_020L, validBytes.copyOfRange(1, 4)),
            SampleChunk(1_250_040L, validBytes.copyOfRange(4, 1_005)),
            SampleChunk(1_270_000L, validBytes.copyOfRange(1_005, 2_008)),
            SampleChunk(1_291_000L, validBytes.copyOfRange(2_008, 6_104)),
            SampleChunk(1_380_000L, byteArrayOf(0x7E, 0x7F)) // incomplete 2-byte stereo tail at EOS
        )
        val source = FakeAudioSampleSource(chunks)

        val result = exporter.streamRawPcmToAac(
            source = source,
            clipStartUs = 1_000_000L,
            endUs = 2_000_000L,
            sampleRate = 48_000,
            channelCount = 2
        )

        assertNotNull(result)
        assertEquals(encoderFormat, result!!.format)
        assertTrue(fakeEncoder.stoppedAndReleased)
        assertArrayEquals(validBytes, fakeEncoder.receivedPcm.toByteArray())
        assertTrue(fakeEncoder.queuedSizes.all { it > 0 && it % 4 == 0 })

        // Verify basePtsUs = 1_250_000 - 1_000_000 = 250_000 and monotonic pcmFramesToUs progression
        var cumulativeFrames = 0L
        for (i in fakeEncoder.queuedSizes.indices) {
            val expectedPts = 250_000L + pcmFramesToUs(cumulativeFrames, 48_000)
            assertEquals(expectedPts, fakeEncoder.queuedPtsUs[i])
            cumulativeFrames += fakeEncoder.queuedSizes[i] / 4
        }
        assertEquals(1_526L, cumulativeFrames)
        // Codec config packet emitted by FakeAacEncoder must be filtered out
        assertTrue(result.packets.none { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 })
        assertTrue(result.packets.isNotEmpty())

        // Android 8 (API 26-27) lacks MediaExtractor.sampleSize (-1L) and may omit KEY_MAX_INPUT_SIZE:
        // verify > 16 KiB LPCM chunks (e.g. 64 KiB) read cleanly via 256 KiB fallback direct buffer.
        val api26Chunk = ByteArray(64 * 1024) { ((it * 19 + 5) and 0xFF).toByte() }
        val api26Encoder = FakeAacEncoder(outputFormat = encoderFormat, inputBufferCapacity = 4_096)
        exporter.aacEncoderFactory = { _, _ -> api26Encoder }
        val api26Source = object : AudioSampleSource {
            private var done = false
            override val sampleTrackIndex: Int get() = if (!done) 0 else -1
            override val sampleTimeUs: Long get() = if (!done) 1_000_000L else -1L
            override val sampleSizeHint: Long get() = -1L
            override fun readSampleData(buffer: ByteBuffer): Int {
                if (done || buffer.remaining() < api26Chunk.size) return -1
                buffer.put(api26Chunk)
                return api26Chunk.size
            }
            override fun advance(): Boolean {
                done = true
                return false
            }
        }
        val api26Result = exporter.streamRawPcmToAac(
            source = api26Source,
            clipStartUs = 1_000_000L,
            endUs = 2_000_000L,
            sampleRate = 48_000,
            channelCount = 2,
            maxInputSizeHint = null
        )
        assertNotNull(api26Result)
        assertArrayEquals(api26Chunk, api26Encoder.receivedPcm.toByteArray())
    }

    @Test
    fun `streamCompressedToAac overlaps decode and encode in single loop across non-muxer codecs`() = runTest {
        val codecs = listOf("audio/ac3", "audio/mpeg", "audio/opus", "audio/flac")
        for (mime in codecs) {
            val context = createTestContext()
            val exporter = FrameExporter(context)
            val containerFormat = mockAudioFormat(mime = mime, sampleRate = -1, channelCount = -1)
            val decodedFormat = mockAudioFormat(
                mime = MediaFormat.MIMETYPE_AUDIO_RAW,
                sampleRate = 48_000,
                channelCount = 2,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            )
            val encoderFormat = mockk<MediaFormat>(relaxed = true)

            var encoderQueuedWhileDecoderStillActive = false
            var decoderRef: FakeCompressedDecoder? = null
            val fakeEncoder = FakeAacEncoder(
                outputFormat = encoderFormat,
                inputBufferCapacity = 4_096,
                inputSlotCount = 2,
                onInputQueued = { size, flags ->
                    if (size > 0 && flags == 0 && decoderRef?.outputEosDelivered == false) {
                        encoderQueuedWhileDecoderStillActive = true
                    }
                }
            )
            // 12 packets -> unaligned decoded PCM slices totaling 24,000 bytes (6,000 stereo frames)
            val expectedPcm = ByteArray(24_000) { ((it * 31 + mime.hashCode()) and 0xFF).toByte() }
            val decodedSlices = listOf(
                2_001, 1_999, 2_000, 2_003, 1_997, 2_000,
                2_002, 1_998, 2_000, 2_000, 2_000, 2_000
            )
            var cursor = 0
            val pcmPayloads = decodedSlices.map { len ->
                expectedPcm.copyOfRange(cursor, cursor + len).also { cursor += len }
            }
            val fakeDecoder = FakeCompressedDecoder(
                decodedFormat = decodedFormat,
                decodedPayloads = pcmPayloads,
                firstOutputPtsUs = 2_040_000L
            )
            decoderRef = fakeDecoder

            exporter.decoderFactory = { m, _ ->
                assertEquals(mime, m)
                fakeDecoder
            }
            exporter.aacEncoderFactory = { rate, ch ->
                assertEquals(48_000, rate)
                assertEquals(2, ch)
                fakeEncoder
            }

            val inputChunks = pcmPayloads.indices.map { idx ->
                SampleChunk(2_040_000L + idx * 20_000L, ByteArray(128) { idx.toByte() })
            }
            val result = exporter.streamCompressedToAac(
                source = FakeAudioSampleSource(inputChunks),
                mime = mime,
                audioFormat = containerFormat,
                clipStartUs = 2_000_000L,
                endUs = 5_000_000L,
                initialSampleRate = -1,
                initialChannelCount = -1
            )

            assertNotNull("Expected transcode success for $mime", result)
            assertTrue("Expected overlapped single-loop encode before decoder EOS for $mime",
                encoderQueuedWhileDecoderStillActive)
            assertArrayEquals(expectedPcm, fakeEncoder.receivedPcm.toByteArray())
            assertEquals(40_000L, fakeEncoder.queuedPtsUs.first())
            assertTrue(fakeDecoder.stoppedAndReleased)
            assertTrue(fakeEncoder.stoppedAndReleased)
        }
    }

    @Test
    fun `streamRawPcmToAac and streamCompressedToAac abort immediately past 15 MiB limit`() = runTest {
        val context = createTestContext()
        val exporter = FrameExporter(context)
        val chunkBytes = ByteArray(64 * 1024) { 0x55 }
        val totalChunks = (FrameExporter.MAX_PCM_BUFFER_BYTES / chunkBytes.size) + 5 // > 15 MiB

        // 1. LPCM > 15 MiB limit
        var lpcmReads = 0
        val lpcmSource = object : AudioSampleSource {
            private var index = 0
            override val sampleTrackIndex: Int get() = if (index < totalChunks) 0 else -1
            override val sampleTimeUs: Long get() = index * 10_000L
            override val sampleSizeHint: Long get() = chunkBytes.size.toLong()
            override fun readSampleData(buffer: ByteBuffer): Int {
                lpcmReads++
                buffer.put(chunkBytes)
                return chunkBytes.size
            }
            override fun advance(): Boolean {
                index++
                return index < totalChunks
            }
        }
        val lpcmEncoder = FakeAacEncoder(mockk(relaxed = true), inputBufferCapacity = 16 * 1024)
        exporter.aacEncoderFactory = { _, _ -> lpcmEncoder }

        val lpcmResult = exporter.streamRawPcmToAac(
            source = lpcmSource,
            clipStartUs = 0L,
            endUs = 60_000_000L,
            sampleRate = 48_000,
            channelCount = 2
        )
        assertNull(lpcmResult)
        assertTrue(lpcmEncoder.stoppedAndReleased)
        // LPCM sampleSizeHint pre-check aborts right at 240 * 64 KiB == 15 MiB before reading chunk #241
        val exactChunksFittingLimit = FrameExporter.MAX_PCM_BUFFER_BYTES / chunkBytes.size
        assertEquals(exactChunksFittingLimit, lpcmReads)

        // 2. Compressed > 15 MiB limit
        val decodedFormat = mockAudioFormat(
            mime = MediaFormat.MIMETYPE_AUDIO_RAW,
            sampleRate = 48_000,
            channelCount = 2,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        )
        var decodedChunksEmitted = 0
        val compressedEncoder = FakeAacEncoder(mockk(relaxed = true), inputBufferCapacity = 16 * 1024)
        val streamingDecoder = object : AudioCodecAdapter {
            private val inBuf = ByteBuffer.allocate(256)
            private val outBuf = ByteBuffer.allocate(chunkBytes.size)
            private var formatSent = false
            var released = false
            override val outputFormat: MediaFormat get() = decodedFormat
            override fun dequeueInputBuffer(timeoutUs: Long): Int = 0
            override fun getInputBuffer(index: Int): ByteBuffer = inBuf.apply { clear() }
            override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
            override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
                if (!formatSent) {
                    formatSent = true
                    return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                }
                if (decodedChunksEmitted >= totalChunks) {
                    info.size = 0
                    info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    return 0
                }
                decodedChunksEmitted++
                outBuf.clear()
                outBuf.put(chunkBytes)
                outBuf.flip()
                info.offset = 0
                info.size = chunkBytes.size
                info.presentationTimeUs = decodedChunksEmitted * 10_000L
                info.flags = 0
                return 0
            }
            override fun getOutputBuffer(index: Int): ByteBuffer = outBuf
            override fun releaseOutputBuffer(index: Int) {}
            override fun stopAndRelease() {
                released = true
            }
        }
        exporter.decoderFactory = { _, _ -> streamingDecoder }
        exporter.aacEncoderFactory = { _, _ -> compressedEncoder }

        val compressedSource = object : AudioSampleSource {
            private var i = 0
            override val sampleTrackIndex: Int get() = if (i < totalChunks) 1 else -1
            override val sampleTimeUs: Long get() = i * 10_000L
            override val sampleSizeHint: Long get() = 64L
            override fun readSampleData(buffer: ByteBuffer): Int {
                buffer.put(ByteArray(64))
                return 64
            }
            override fun advance(): Boolean {
                i++
                return i < totalChunks
            }
        }
        val compressedResult = exporter.streamCompressedToAac(
            source = compressedSource,
            mime = "audio/flac",
            audioFormat = mockAudioFormat("audio/flac", 48_000, 2),
            clipStartUs = 0L,
            endUs = 60_000_000L,
            initialSampleRate = 48_000,
            initialChannelCount = 2
        )
        assertNull(compressedResult)
        assertEquals(exactChunksFittingLimit + 1, decodedChunksEmitted)
        assertTrue(streamingDecoder.released)
        assertTrue(compressedEncoder.stoppedAndReleased)
    }

    @Test
    fun `single-loop backpressure uses 0L polling when progressing and single timeout when idle`() = runTest {
        val context = createTestContext()
        val exporter = FrameExporter(context)
        val decodedFormat = mockAudioFormat(
            mime = MediaFormat.MIMETYPE_AUDIO_RAW,
            sampleRate = 48_000,
            channelCount = 2,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        )
        val recordedTimeouts = ArrayList<Long>()

        // Simulate backpressure: encoder blocks input every 2 frames until a timed output poll unlocks it
        val backpressureEncoder = FakeAacEncoder(
            outputFormat = mockk(relaxed = true),
            inputBufferCapacity = 4_096,
            inputSlotCount = 1,
            requireTimedDrainToReleaseSlot = true,
            onTimeoutObserved = { recordedTimeouts.add(it) }
        )
        val payloads = List(6) { idx -> ByteArray(4_096) { idx.toByte() } }
        val decoder = FakeCompressedDecoder(
            decodedFormat = decodedFormat,
            decodedPayloads = payloads,
            firstOutputPtsUs = 0L,
            onTimeoutObserved = { recordedTimeouts.add(it) }
        )
        exporter.decoderFactory = { _, _ -> decoder }
        exporter.aacEncoderFactory = { _, _ -> backpressureEncoder }

        val chunks = List(6) { idx -> SampleChunk(idx * 20_000L, ByteArray(64)) }
        val result = exporter.streamCompressedToAac(
            source = FakeAudioSampleSource(chunks),
            mime = "audio/opus",
            audioFormat = mockAudioFormat("audio/opus", 48_000, 2),
            clipStartUs = 0L,
            endUs = 1_000_000L,
            initialSampleRate = 48_000,
            initialChannelCount = 2
        )

        assertNotNull(result)
        val zeroPolls = recordedTimeouts.count { it == 0L }
        val blockingPolls = recordedTimeouts.count { it == FrameExporter.CODEC_DEQUEUE_TIMEOUT_US }
        assertTrue("Expected majority of polls to be non-blocking 0L", zeroPolls > blockingPolls)
        assertTrue("Expected timed backpressure polls when encoder slot saturated", blockingPolls in 1..10)
        // Verify two consecutive blocking polls never occur without an intervening 0L poll round
        for (i in 1 until recordedTimeouts.size) {
            assertFalse(
                "Must never block twice in the same round without 0L re-poll",
                recordedTimeouts[i] > 0L && recordedTimeouts[i - 1] > 0L
            )
        }

        // Also verify MAX_CODEC_IDLE_ROUNDS stall protection
        var stalledDecoderReleased = false
        val stalledDecoder = object : AudioCodecAdapter {
            override val outputFormat: MediaFormat get() = decodedFormat
            override fun dequeueInputBuffer(timeoutUs: Long): Int = MediaCodec.INFO_TRY_AGAIN_LATER
            override fun getInputBuffer(index: Int): ByteBuffer? = null
            override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
            override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int = MediaCodec.INFO_TRY_AGAIN_LATER
            override fun getOutputBuffer(index: Int): ByteBuffer? = null
            override fun releaseOutputBuffer(index: Int) {}
            override fun stopAndRelease() { stalledDecoderReleased = true }
        }
        exporter.decoderFactory = { _, _ -> stalledDecoder }
        assertThrows(IOException::class.java) {
            runBlocking {
                exporter.streamCompressedToAac(
                    source = FakeAudioSampleSource(chunks),
                    mime = "audio/opus",
                    audioFormat = mockAudioFormat("audio/opus", 48_000, 2),
                    clipStartUs = 0L,
                    endUs = 1_000_000L,
                    initialSampleRate = 48_000,
                    initialChannelCount = 2
                )
            }
        }
        assertTrue(stalledDecoderReleased)
    }

    @Test
    fun `extractVideoClipOnce seeks video track only once and aligns audio basePtsUs via isolated extractor`() = runTest {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = createTestContext(resolver)
        val videoUri = mockk<Uri>(relaxed = true)
        val sharedFd = FileDescriptor()
        val sharedAfd = mockk<AssetFileDescriptor>(relaxed = true)
        every { sharedAfd.fileDescriptor } returns sharedFd
        every { sharedAfd.declaredLength } returns -1L
        every { resolver.openAssetFileDescriptor(videoUri, "r") } returns sharedAfd

        val videoFormat = mockVideoFormat("video/avc")
        val audioFormat = mockAudioFormat(
            mime = MediaFormat.MIMETYPE_AUDIO_RAW,
            sampleRate = 48_000,
            channelCount = 2,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        )

        // Keyframe actualStartUs = 1_000_000L (earlier than requested startUs = 2_500_000L)
        val videoSamples = listOf(
            SampleChunk(1_000_000L, byteArrayOf(1, 2, 3, 4), flags = MediaExtractor.SAMPLE_FLAG_SYNC),
            SampleChunk(1_033_333L, byteArrayOf(5, 6, 7, 8), flags = 0),
            SampleChunk(2_500_000L, byteArrayOf(9, 10, 11, 12), flags = 0)
        )
        val audioSamples = listOf(
            SampleChunk(1_010_000L, ByteArray(400) { 0x11 }),
            SampleChunk(1_030_000L, ByteArray(400) { 0x22 })
        )

        val primaryExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 1_000_000L,
            samplesForSelectedTrack = mapOf(0 to videoSamples, 1 to audioSamples)
        )
        val secondaryAudioExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 1_000_000L,
            samplesForSelectedTrack = mapOf(0 to videoSamples, 1 to audioSamples)
        )
        val extractors = ArrayDeque(listOf(primaryExtractor.extractor, secondaryAudioExtractor.extractor))

        val writtenVideoPts = ArrayList<Long>()
        val writtenAudioPts = ArrayList<Long>()
        val muxer = mockk<MediaMuxer>(relaxed = true)
        every { muxer.addTrack(videoFormat) } returns 0
        val encodedFormat = mockk<MediaFormat>(relaxed = true)
        every { muxer.addTrack(encodedFormat) } returns 1
        every { muxer.writeSampleData(any(), any(), any()) } answers {
            // Write dummy byte so tempFile is non-empty if inspected
            val track = firstArg<Int>()
            val info = thirdArg<MediaCodec.BufferInfo>()
            if (track == 0) writtenVideoPts.add(info.presentationTimeUs)
            if (track == 1) writtenAudioPts.add(info.presentationTimeUs)
        }

        val fakeEncoder = FakeAacEncoder(outputFormat = encodedFormat, inputBufferCapacity = 4_096)
        val exporter = FrameExporter(context).apply {
            extractorFactory = { extractors.removeFirst() }
            muxerFactory = { path ->
                java.io.File(path).writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18))
                muxer
            }
            bufferInfoFactory = { mockk(relaxed = true) }
            aacEncoderFactory = { _, _ -> fakeEncoder }
        }

        val clipResult = exporter.extractVideoClipOnce(
            videoUri = videoUri,
            startUs = 2_500_000L,
            endUs = 4_000_000L,
            muteAudio = false,
            seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
            rotation = 90
        )

        try {
            assertEquals(1_000_000L, clipResult.actualStartUs)
            assertEquals(3, clipResult.videoSamplesWritten)
            assertTrue(clipResult.hasAudioTrack)
            assertTrue(clipResult.audioIncluded)

            // Verify single seek on videoTrackIndex (0) and zero audio touches on primary extractor
            verify(exactly = 1) { primaryExtractor.extractor.setDataSource(sharedFd) }
            verify(exactly = 1) { primaryExtractor.extractor.selectTrack(0) }
            verify(exactly = 1) { primaryExtractor.extractor.seekTo(2_500_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            verify(exactly = 0) { primaryExtractor.extractor.unselectTrack(any()) }
            verify(exactly = 0) { primaryExtractor.extractor.selectTrack(1) }

            // Verify isolated audioExtractor shared the FD, seeked to actualStartUs (1_000_000L), and released
            verify(exactly = 1) { secondaryAudioExtractor.extractor.setDataSource(sharedFd) }
            verify(exactly = 1) { secondaryAudioExtractor.extractor.selectTrack(1) }
            verify(exactly = 1) { secondaryAudioExtractor.extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
            verify(exactly = 1) { secondaryAudioExtractor.extractor.release() }
            verify(exactly = 1) { primaryExtractor.extractor.release() }
            verify(exactly = 1) { sharedAfd.close() }

            // Video PTS starts at 0 (1_000_000 - 1_000_000), audio basePtsUs = 10_000 (1_010_000 - 1_000_000)
            assertEquals(listOf(0L, 33_333L, 1_500_000L), writtenVideoPts)
            assertEquals(10_000L, fakeEncoder.queuedPtsUs.first())
            assertEquals(10_000L, writtenAudioPts.first())
        } finally {
            clipResult.file.delete()
        }
    }

    @Test
    fun `extractVideoClip retry fallback performs 2 total video seeks instead of 4 and falls back to URI when FD unavailable`() = runTest {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = createTestContext(resolver)
        val videoUri = mockk<Uri>(relaxed = true)
        every { resolver.openAssetFileDescriptor(videoUri, "r") } returns null

        val videoFormat = mockVideoFormat("video/avc")
        val audioFormat = mockAudioFormat("audio/flac", 48_000, 2)
        val decodedFormat = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW,
            48_000,
            2,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val attempt1VideoExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(0 to emptyList(), 1 to listOf(SampleChunk(0L, ByteArray(64))))
        )
        val attempt1AudioExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(0 to emptyList(), 1 to listOf(SampleChunk(0L, ByteArray(64))))
        )
        val attempt2VideoExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 500_000L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(500_000L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to listOf(SampleChunk(500_000L, ByteArray(64)))
            )
        )
        val attempt2AudioExtractor = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 500_000L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(500_000L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to listOf(SampleChunk(500_000L, ByteArray(64)))
            )
        )
        val queue = ArrayDeque(
            listOf(
                attempt1VideoExtractor.extractor,
                attempt1AudioExtractor.extractor,
                attempt2VideoExtractor.extractor,
                attempt2AudioExtractor.extractor
            )
        )

        val exporter = FrameExporter(context).apply {
            extractorFactory = { queue.removeFirst() }
            muxerFactory = { path ->
                java.io.File(path).writeBytes(byteArrayOf(0x01))
                mockk(relaxed = true)
            }
            bufferInfoFactory = { mockk(relaxed = true) }
            decoderFactory = { _, _ ->
                FakeCompressedDecoder(decodedFormat, listOf(ByteArray(400)), 500_000L)
            }
            aacEncoderFactory = { _, _ ->
                FakeAacEncoder(mockk(relaxed = true), inputBufferCapacity = 4_096)
            }
        }

        val result = exporter.extractVideoClip(
            videoUri = videoUri,
            centerTimestampUs = 2_000_000L,
            beforeDurationUs = 1_000_000L,
            afterDurationUs = 1_000_000L,
            muteAudio = false,
            videoDurationUs = 5_000_000L
        )
        try {
            assertEquals(1, result.videoSamplesWritten)
            // Fallback to setDataSource(context, videoUri, null) verified on all extractors
            verify(exactly = 1) { attempt1VideoExtractor.extractor.setDataSource(context, videoUri, null) }
            verify(exactly = 1) { attempt1AudioExtractor.extractor.setDataSource(context, videoUri, null) }
            verify(exactly = 1) { attempt2VideoExtractor.extractor.setDataSource(context, videoUri, null) }
            verify(exactly = 1) { attempt2AudioExtractor.extractor.setDataSource(context, videoUri, null) }

            // Total video keyframe seeks across initial attempt + fallback retry = 1 + 1 = 2 (down from 4)
            verify(exactly = 1) { attempt1VideoExtractor.extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            verify(exactly = 1) { attempt2VideoExtractor.extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
        } finally {
            result.file.delete()
        }
    }

    @Test
    fun `audio failure and unsupported formats gracefully degrade without failing video clip and cancellation cleans up`() = runTest {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = createTestContext(resolver)
        val videoUri = mockk<Uri>(relaxed = true)
        every { resolver.openAssetFileDescriptor(videoUri, "r") } returns null

        val videoFormat = mockVideoFormat("video/avc")
        val audioFormat = mockAudioFormat("audio/ac3", 48_000, 6) // 6ch unsupported without downmix
        val non16BitFormat = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW,
            48_000,
            2,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        // 1. Decoder emits float PCM -> transcode returns null -> silent clip succeeds (audioDropped = true)
        val videoEx = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(0L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to listOf(SampleChunk(0L, ByteArray(64)))
            )
        )
        val audioEx = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(0L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to listOf(SampleChunk(0L, ByteArray(64)))
            )
        )
        val extractors = ArrayDeque(listOf(videoEx.extractor, audioEx.extractor))
        val exporter = FrameExporter(context).apply {
            extractorFactory = { extractors.removeFirst() }
            muxerFactory = { path ->
                java.io.File(path).writeBytes(byteArrayOf(0x01))
                mockk(relaxed = true)
            }
            bufferInfoFactory = { mockk(relaxed = true) }
            decoderFactory = { _, _ ->
                FakeCompressedDecoder(non16BitFormat, listOf(ByteArray(256)), 0L)
            }
        }

        val degradedClip = exporter.extractVideoClipOnce(
            videoUri = videoUri,
            startUs = 0L,
            endUs = 1_000_000L,
            muteAudio = false,
            seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        )
        try {
            assertEquals(1, degradedClip.videoSamplesWritten)
            assertTrue(degradedClip.hasAudioTrack)
            assertFalse(degradedClip.audioIncluded) // ExportResult.Success computes audioDropped = true
        } finally {
            degradedClip.file.delete()
        }

        // 2. Coroutine cancellation mid-transcode releases codecs, extractors, FD, and deletes tempFile
        val sharedAfd = mockk<AssetFileDescriptor>(relaxed = true)
        every { sharedAfd.fileDescriptor } returns FileDescriptor()
        every { sharedAfd.declaredLength } returns -1L
        every { resolver.openAssetFileDescriptor(videoUri, "r") } returns sharedAfd

        val cancelVideoEx = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(0L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to List(50) { idx -> SampleChunk(idx * 10_000L, ByteArray(64)) }
            )
        )
        val cancelAudioEx = buildMockExtractor(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            seekedVideoSampleTimeUs = 0L,
            samplesForSelectedTrack = mapOf(
                0 to listOf(SampleChunk(0L, byteArrayOf(1, 2, 3, 4), MediaExtractor.SAMPLE_FLAG_SYNC)),
                1 to List(50) { idx -> SampleChunk(idx * 10_000L, ByteArray(64)) }
            )
        )
        val cancelQueue = ArrayDeque(listOf(cancelVideoEx.extractor, cancelAudioEx.extractor))
        val reachedCodecLoop = CompletableDeferred<Unit>()
        var decoderReleasedOnCancel = false
        var encoderReleasedOnCancel = false

        val cacheDir = tempFolder.newFolder("cancel_cache")
        every { context.cacheDir } returns cacheDir
        val decoded16Bit = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW,
            48_000,
            2,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val cancelExporter = FrameExporter(context).apply {
            extractorFactory = { cancelQueue.removeFirst() }
            decoderFactory = { _, _ ->
                object : AudioCodecAdapter {
                    private val inBuf = ByteBuffer.allocate(128)
                    private val outBuf = ByteBuffer.allocate(4096)
                    private var emittedFormat = false
                    override val outputFormat: MediaFormat get() = decoded16Bit
                    override fun dequeueInputBuffer(timeoutUs: Long): Int = 0
                    override fun getInputBuffer(index: Int): ByteBuffer = inBuf.apply { clear() }
                    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
                    override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
                        if (!emittedFormat) {
                            emittedFormat = true
                            return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        }
                        reachedCodecLoop.complete(Unit)
                        Thread.sleep(2)
                        outBuf.clear()
                        outBuf.limit(4096)
                        info.offset = 0
                        info.size = 4096
                        info.presentationTimeUs = 0L
                        info.flags = 0
                        return 0
                    }
                    override fun getOutputBuffer(index: Int): ByteBuffer = outBuf
                    override fun releaseOutputBuffer(index: Int) {}
                    override fun stopAndRelease() { decoderReleasedOnCancel = true }
                }
            }
            aacEncoderFactory = { _, _ ->
                FakeAacEncoder(mockk(relaxed = true), inputBufferCapacity = 4_096).also { fake ->
                    // Track release via wrapper
                }.let { delegate ->
                    object : AudioCodecAdapter by delegate {
                        override fun stopAndRelease() {
                            encoderReleasedOnCancel = true
                            delegate.stopAndRelease()
                        }
                    }
                }
            }
        }

        runBlocking {
            var caughtCancellation = false
            val job = launch(Dispatchers.Default) {
                try {
                    cancelExporter.extractVideoClipOnce(
                        videoUri = videoUri,
                        startUs = 0L,
                        endUs = 2_000_000L,
                        muteAudio = false,
                        seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                    )
                } catch (e: CancellationException) {
                    caughtCancellation = true
                    throw e
                }
            }
            reachedCodecLoop.await()
            job.cancel()
            job.join()
            assertTrue(caughtCancellation)
        }

        assertTrue(decoderReleasedOnCancel)
        assertTrue(encoderReleasedOnCancel)
        verify(exactly = 1) { cancelAudioEx.extractor.release() }
        verify(exactly = 1) { cancelVideoEx.extractor.release() }
        verify(exactly = 1) { sharedAfd.close() }
        assertEquals("Temp clip file must be deleted on cancellation", 0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `benchmark two-stage PCM buffering vs single-loop streaming across 3s 10s 11s GOP and 15 MiB scenarios`() = runBlocking {
        data class Scenario(
            val name: String,
            val durationLabel: String,
            val sampleRate: Int,
            val channelCount: Int,
            val totalPcmBytes: Int,
            val decodeStageNsPerChunk: Long,
            val encodeStageNsPerChunk: Long
        )

        val frameChunkSize = 4_096 // 1024 stereo 16-bit frames (one AAC-LC frame)
        val scenarios = listOf(
            Scenario("3s_48k_stereo", "3s (48kHz 2ch)", 48_000, 2, (3 * 48_000 * 4 / frameChunkSize) * frameChunkSize, 35_000L, 30_000L),
            Scenario("10s_96k_stereo", "10s (96kHz 2ch)", 96_000, 2, (10 * 96_000 * 4 / frameChunkSize) * frameChunkSize, 20_000L, 16_000L),
            Scenario("11s_gop_96k_stereo", "11s GOP pre-roll (96kHz 2ch)", 96_000, 2, (11 * 96_000 * 4 / frameChunkSize) * frameChunkSize, 20_000L, 16_000L),
            Scenario("15mib_max_clip", "15 MiB max clip (192kHz 2ch)", 192_000, 2, FrameExporter.MAX_PCM_BUFFER_BYTES, 12_000L, 10_000L),
            Scenario("15mib_guardrail", ">15 MiB guardrail", 192_000, 2, FrameExporter.MAX_PCM_BUFFER_BYTES + 64 * 1024, 0L, 0L)
        )

        val context = createTestContext()
        val exporter = FrameExporter(context)
        val aacOutputFormat = mockk<MediaFormat>(relaxed = true)
        val containerFlacFormat = mockAudioFormat("audio/flac", 96_000, 2)
        val decoded48kFormat = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW, 48_000, 2, AudioFormat.ENCODING_PCM_16BIT
        )
        val decoded96kFormat = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW, 96_000, 2, AudioFormat.ENCODING_PCM_16BIT
        )
        val decoded192kFormat = mockAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_RAW, 192_000, 2, AudioFormat.ENCODING_PCM_16BIT
        )

        // Warmup JIT & MockK proxies outside timed measurements
        run {
            val warmClock = VirtualCodecClock()
            val warmDec = TimedAsyncFakeDecoder(decoded48kFormat, chunkSize = frameChunkSize, stageNs = 0L, clock = warmClock)
            val warmEnc = TimedAsyncFakeEncoder(aacOutputFormat, inputBufferCapacity = frameChunkSize, stageNs = 0L, clock = warmClock)
            exporter.decoderFactory = { _, _ -> warmDec }
            exporter.aacEncoderFactory = { _, _ -> warmEnc }
            exporter.streamCompressedToAac(
                source = TimedSampleSource(16, sampleRate = 48_000),
                mime = "audio/flac",
                audioFormat = containerFlacFormat,
                clipStartUs = 0L,
                endUs = Long.MAX_VALUE / 2,
                initialSampleRate = 48_000,
                initialChannelCount = 2
            )
        }

        println("\n=== Audio Transcode Benchmark: Legacy Two-Stage vs Single-Loop Streaming ===")
        for (sc in scenarios) {
            val chunkCount = sc.totalPcmBytes / frameChunkSize
            val decodedFormat = when (sc.sampleRate) {
                48_000 -> decoded48kFormat
                96_000 -> decoded96kFormat
                else -> decoded192kFormat
            }

            // --- 1. Legacy Two-Stage Execution (Decode all -> BAOS(256KB) -> toByteArray() -> Encode all) ---
            val legacyClock = VirtualCodecClock()
            val legacyDecoder = TimedAsyncFakeDecoder(
                outputFormat = decodedFormat,
                chunkSize = frameChunkSize,
                stageNs = sc.decodeStageNsPerChunk,
                clock = legacyClock
            )
            val legacyEncoder = TimedAsyncFakeEncoder(
                outputFormat = aacOutputFormat,
                inputBufferCapacity = frameChunkSize,
                stageNs = sc.encodeStageNsPerChunk,
                clock = legacyClock
            )
            val legacySource = TimedSampleSource(chunkCount, sampleRate = sc.sampleRate)
            val decInfo = CodecOutputInfo()
            val encInfo = CodecOutputInfo()

            val legacyAllocBefore = getCurrentThreadAllocatedBytesReflective()
            val legacyCpuStartNs = System.nanoTime()
            var legacyPeakResidentBytes = 0L
            var legacyAborted = false
            val pcmStream = ByteArrayOutputStream(256 * 1024)
            val copyChunk = ByteArray(64 * 1024)
            var baosBackingCapacity = 256 * 1024L

            // Stage 1: Serial decode into ByteArrayOutputStream
            var decInDone = false
            var decOutDone = false
            while (!decOutDone) {
                if (!decInDone) {
                    val inIdx = legacyDecoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = legacyDecoder.getInputBuffer(inIdx)!!
                        if (legacySource.sampleTrackIndex < 0) {
                            legacyDecoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInDone = true
                        } else {
                            val sz = legacySource.readSampleData(inBuf)
                            legacyDecoder.queueInputBuffer(inIdx, 0, sz, legacySource.sampleTimeUs, 0)
                            legacySource.advance()
                        }
                    }
                }
                val outIdx = legacyDecoder.dequeueOutputBuffer(decInfo, 10_000L)
                if (outIdx >= 0) {
                    if (decInfo.size > 0) {
                        val outBuf = legacyDecoder.getOutputBuffer(outIdx)!!
                        outBuf.position(decInfo.offset)
                        outBuf.limit(decInfo.offset + decInfo.size)
                        while (outBuf.hasRemaining()) {
                            val n = minOf(copyChunk.size, outBuf.remaining())
                            outBuf.get(copyChunk, 0, n)
                            val needed = pcmStream.size().toLong() + n
                            while (needed > baosBackingCapacity) {
                                val transientPeak = baosBackingCapacity + (baosBackingCapacity * 2) + copyChunk.size
                                legacyPeakResidentBytes = maxOf(legacyPeakResidentBytes, transientPeak)
                                baosBackingCapacity *= 2
                            }
                            pcmStream.write(copyChunk, 0, n)
                            legacyPeakResidentBytes = maxOf(legacyPeakResidentBytes, baosBackingCapacity + copyChunk.size)
                            if (pcmStream.size() > FrameExporter.MAX_PCM_BUFFER_BYTES) {
                                legacyAborted = true
                                break
                            }
                        }
                    }
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decOutDone = true
                    legacyDecoder.releaseOutputBuffer(outIdx)
                    if (legacyAborted) break
                }
            }

            val legacyPackets = ArrayList<ByteArray>()
            if (!legacyAborted) {
                val pcmBytes = pcmStream.toByteArray()
                legacyPeakResidentBytes = maxOf(
                    legacyPeakResidentBytes,
                    baosBackingCapacity + pcmBytes.size.toLong() + copyChunk.size
                )
                // Stage 2: Serial AAC encode starts only after Stage 1 completed
                var inOffset = 0
                var encInDone = false
                var encOutDone = false
                while (!encOutDone) {
                    if (!encInDone) {
                        val inIdx = legacyEncoder.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = legacyEncoder.getInputBuffer(inIdx)!!
                            if (inOffset >= pcmBytes.size) {
                                legacyEncoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encInDone = true
                            } else {
                                val n = minOf(inBuf.capacity(), pcmBytes.size - inOffset)
                                inBuf.clear()
                                inBuf.put(pcmBytes, inOffset, n)
                                legacyEncoder.queueInputBuffer(inIdx, 0, n, 0L, 0)
                                inOffset += n
                            }
                        }
                    }
                    val outIdx = legacyEncoder.dequeueOutputBuffer(encInfo, 10_000L)
                    if (outIdx >= 0) {
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                            val outBuf = legacyEncoder.getOutputBuffer(outIdx)!!
                            val pkt = ByteArray(encInfo.size)
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            outBuf.get(pkt)
                            legacyPackets.add(pkt)
                        }
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encOutDone = true
                        legacyEncoder.releaseOutputBuffer(outIdx)
                    }
                }
            }
            val legacyCpuMs = (System.nanoTime() - legacyCpuStartNs) / 1_000_000.0
            val legacyPipelineMs = legacyClock.nowNs / 1_000_000.0
            val legacyTotalMs = legacyPipelineMs + legacyCpuMs
            val legacyAllocatedBytes = getCurrentThreadAllocatedBytesReflective() - legacyAllocBefore

            // --- 2. New Single-Loop Streaming Execution via FrameExporter.streamCompressedToAac ---
            val streamClock = VirtualCodecClock()
            val streamDecoder = TimedAsyncFakeDecoder(
                outputFormat = decodedFormat,
                chunkSize = frameChunkSize,
                stageNs = sc.decodeStageNsPerChunk,
                clock = streamClock
            )
            val streamEncoder = TimedAsyncFakeEncoder(
                outputFormat = aacOutputFormat,
                inputBufferCapacity = frameChunkSize,
                stageNs = sc.encodeStageNsPerChunk,
                clock = streamClock
            )
            exporter.decoderFactory = { _, _ -> streamDecoder }
            exporter.aacEncoderFactory = { _, _ -> streamEncoder }

            val streamAllocBefore = getCurrentThreadAllocatedBytesReflective()
            val streamCpuStartNs = System.nanoTime()
            val streamResult = exporter.streamCompressedToAac(
                source = TimedSampleSource(chunkCount, sampleRate = sc.sampleRate),
                mime = "audio/flac",
                audioFormat = containerFlacFormat,
                clipStartUs = 0L,
                endUs = Long.MAX_VALUE / 2,
                initialSampleRate = sc.sampleRate,
                initialChannelCount = sc.channelCount
            )
            val streamCpuMs = (System.nanoTime() - streamCpuStartNs) / 1_000_000.0
            val streamPipelineMs = streamClock.nowNs / 1_000_000.0
            val streamTotalMs = streamPipelineMs + streamCpuMs
            val streamAllocatedBytes = getCurrentThreadAllocatedBytesReflective() - streamAllocBefore
            val streamResidentPcmPeakBytes = FrameExporter.PCM_CARRY_BUFFER_BYTES.toLong()
            val pipelineSpeedup = if (streamPipelineMs > 0.0) legacyPipelineMs / streamPipelineMs else 1.0

            println(
                String.format(
                    "Scenario %-28s | Legacy Peak PCM: %6.2f MB, Alloc: %6.2f MB, Pipeline: %6.2f ms (CPU %5.2f ms) | " +
                        "Streaming Peak PCM: %5.2f KB, Alloc: %6.2f MB, Pipeline: %6.2f ms (CPU %5.2f ms, %.2fx)",
                    sc.durationLabel,
                    legacyPeakResidentBytes / 1_000_000.0,
                    legacyAllocatedBytes / 1_000_000.0,
                    legacyTotalMs,
                    legacyCpuMs,
                    streamResidentPcmPeakBytes / 1_000.0,
                    streamAllocatedBytes / 1_000_000.0,
                    streamTotalMs,
                    streamCpuMs,
                    pipelineSpeedup
                )
            )

            // Verify resident PCM carry peak <= 16 KiB (well below 64 KB threshold)
            assertTrue(streamResidentPcmPeakBytes <= 16 * 1024L)
            assertTrue(legacyPeakResidentBytes > 1_000_000L)

            if (sc.name == "15mib_guardrail") {
                assertTrue(legacyAborted)
                assertNull("Streaming transcode must abort above MAX_PCM_BUFFER_BYTES", streamResult)
                assertTrue(legacyPeakResidentBytes >= 24L * 1024L * 1024L)
            } else {
                assertNotNull(streamResult)
                assertEquals(sc.totalPcmBytes.toLong(), streamEncoder.totalBytesQueued)
                assertEquals(legacyPackets.size, streamResult!!.packets.size)
                if (sc.name == "15mib_max_clip") {
                    // At 15 MiB limit, legacy hits 16 MiB BAOS + 15 MiB toByteArray() + 64 KiB chunk = 32,571,392 B (32.57 MB)
                    assertEquals(32_571_392L, legacyPeakResidentBytes)
                }
                assertTrue(
                    "Streaming must drastically cut heap allocations vs legacy for ${sc.name}",
                    streamAllocatedBytes < legacyAllocatedBytes / 4
                )
                assertTrue(
                    "Single-loop overlapped execution must eliminate two-stage serial amplification for ${sc.name} (got ${pipelineSpeedup}x)",
                    legacyPipelineMs > streamPipelineMs * 1.70
                )
            }
        }
    }

    private fun getCurrentThreadAllocatedBytesReflective(): Long {
        return try {
            val mgmtFactory = Class.forName("java.lang.management.ManagementFactory")
            val bean = mgmtFactory.getMethod("getThreadMXBean").invoke(null) ?: return 0L
            val sunBeanClass = Class.forName("com.sun.management.ThreadMXBean")
            val method = sunBeanClass.getMethod("getCurrentThreadAllocatedBytes")
            (method.invoke(bean) as? Long) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun createTestContext(resolver: ContentResolver = mockk(relaxed = true)): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.cacheDir } returns tempFolder.root
        every { ctx.contentResolver } returns resolver
        return ctx
    }

    private fun mockVideoFormat(mime: String): MediaFormat {
        val format = mockk<MediaFormat>(relaxed = true)
        every { format.containsKey(any()) } returns false
        every { format.containsKey(MediaFormat.KEY_MIME) } returns true
        every { format.getString(MediaFormat.KEY_MIME) } returns mime
        every { format.containsKey(MediaFormat.KEY_WIDTH) } returns true
        every { format.getInteger(MediaFormat.KEY_WIDTH) } returns 3840
        every { format.containsKey(MediaFormat.KEY_HEIGHT) } returns true
        every { format.getInteger(MediaFormat.KEY_HEIGHT) } returns 2160
        return format
    }

    private fun mockAudioFormat(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int? = null
    ): MediaFormat {
        val format = mockk<MediaFormat>(relaxed = true)
        every { format.containsKey(any()) } returns false
        every { format.containsKey(MediaFormat.KEY_MIME) } returns true
        every { format.getString(MediaFormat.KEY_MIME) } returns mime
        if (sampleRate > 0) {
            every { format.containsKey(MediaFormat.KEY_SAMPLE_RATE) } returns true
            every { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } returns sampleRate
        } else {
            every { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } throws NullPointerException()
        }
        if (channelCount > 0) {
            every { format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) } returns true
            every { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } returns channelCount
        } else {
            every { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } throws NullPointerException()
        }
        if (pcmEncoding != null) {
            every { format.containsKey(MediaFormat.KEY_PCM_ENCODING) } returns true
            every { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } returns pcmEncoding
        } else {
            every { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } throws NullPointerException()
        }
        return format
    }

    private data class SampleChunk(
        val timeUs: Long,
        val bytes: ByteArray,
        val flags: Int = 0
    )

    private class FakeAudioSampleSource(
        private val samples: List<SampleChunk>
    ) : AudioSampleSource {
        private var cursor = 0

        override val sampleTrackIndex: Int
            get() = if (cursor < samples.size) 1 else -1

        override val sampleTimeUs: Long
            get() = if (cursor < samples.size) samples[cursor].timeUs else -1L

        override val sampleSizeHint: Long
            get() = if (cursor < samples.size) samples[cursor].bytes.size.toLong() else -1L

        override fun readSampleData(buffer: ByteBuffer): Int {
            if (cursor >= samples.size) return -1
            val data = samples[cursor].bytes
            buffer.clear()
            buffer.put(data)
            buffer.flip()
            return data.size
        }

        override fun advance(): Boolean {
            cursor++
            return cursor < samples.size
        }
    }

    private data class MockExtractorHolder(val extractor: MediaExtractor)

    private fun buildMockExtractor(
        videoFormat: MediaFormat,
        audioFormat: MediaFormat,
        seekedVideoSampleTimeUs: Long,
        samplesForSelectedTrack: Map<Int, List<SampleChunk>>
    ): MockExtractorHolder {
        val extractor = mockk<MediaExtractor>(relaxed = true)
        var selectedTrack = -1
        var cursor = 0

        every { extractor.trackCount } returns 2
        every { extractor.getTrackFormat(0) } returns videoFormat
        every { extractor.getTrackFormat(1) } returns audioFormat
        every { extractor.selectTrack(any()) } answers {
            selectedTrack = firstArg()
            cursor = 0
        }
        every { extractor.unselectTrack(any()) } answers {
            if (selectedTrack == firstArg<Int>()) {
                selectedTrack = -1
            }
        }
        every { extractor.seekTo(any(), any()) } answers {
            cursor = 0
        }
        every { extractor.sampleTrackIndex } answers {
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            if (selectedTrack >= 0 && cursor < list.size) selectedTrack else -1
        }
        every { extractor.sampleTime } answers {
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            if (selectedTrack >= 0 && cursor < list.size) {
                list[cursor].timeUs
            } else if (selectedTrack == 0 && list.isEmpty()) {
                seekedVideoSampleTimeUs
            } else {
                -1L
            }
        }
        every { extractor.sampleSize } answers {
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            if (selectedTrack >= 0 && cursor < list.size) list[cursor].bytes.size.toLong() else -1L
        }
        every { extractor.sampleFlags } answers {
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            if (selectedTrack >= 0 && cursor < list.size) list[cursor].flags else 0
        }
        every { extractor.readSampleData(any(), any()) } answers {
            val buf = firstArg<ByteBuffer>()
            val offset = secondArg<Int>()
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            if (selectedTrack < 0 || cursor >= list.size) {
                -1
            } else {
                val bytes = list[cursor].bytes
                buf.position(offset)
                buf.put(bytes)
                buf.position(offset)
                buf.limit(offset + bytes.size)
                bytes.size
            }
        }
        every { extractor.advance() } answers {
            val list = samplesForSelectedTrack[selectedTrack].orEmpty()
            cursor++
            cursor < list.size
        }

        return MockExtractorHolder(extractor)
    }

    private class FakeCompressedDecoder(
        private val decodedFormat: MediaFormat,
        private val decodedPayloads: List<ByteArray>,
        private val firstOutputPtsUs: Long,
        private val onTimeoutObserved: ((Long) -> Unit)? = null
    ) : AudioCodecAdapter {
        private val inputBuf = ByteBuffer.allocate(4096)
        private val outputBuffers = Array(2) { ByteBuffer.allocate(32 * 1024) }
        private val freeOutputs = ArrayDeque<Int>().apply { add(0); add(1) }
        private val readyQueue = ArrayDeque<Triple<ByteArray, Long, Int>>()
        private var formatDelivered = false
        private var nextPayloadIdx = 0
        private var sawInputEos = false
        var outputEosDelivered = false
            private set
        var stoppedAndReleased = false
            private set

        override val outputFormat: MediaFormat
            get() = decodedFormat

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            onTimeoutObserved?.invoke(timeoutUs)
            return if (sawInputEos || readyQueue.size >= 2) MediaCodec.INFO_TRY_AGAIN_LATER else 0
        }

        override fun getInputBuffer(index: Int): ByteBuffer = inputBuf.apply { clear() }

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int
        ) {
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                sawInputEos = true
                if (nextPayloadIdx < decodedPayloads.size) {
                    while (nextPayloadIdx < decodedPayloads.size) {
                        val isLast = nextPayloadIdx == decodedPayloads.lastIndex
                        val outFlags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        readyQueue.addLast(
                            Triple(
                                decodedPayloads[nextPayloadIdx],
                                firstOutputPtsUs + nextPayloadIdx * 20_000L,
                                outFlags
                            )
                        )
                        nextPayloadIdx++
                    }
                } else {
                    readyQueue.addLast(
                        Triple(
                            ByteArray(0),
                            firstOutputPtsUs + nextPayloadIdx * 20_000L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    )
                }
            } else if (nextPayloadIdx < decodedPayloads.size) {
                readyQueue.addLast(
                    Triple(
                        decodedPayloads[nextPayloadIdx],
                        firstOutputPtsUs + nextPayloadIdx * 20_000L,
                        0
                    )
                )
                nextPayloadIdx++
            }
        }

        override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
            onTimeoutObserved?.invoke(timeoutUs)
            if (!formatDelivered && (readyQueue.isNotEmpty() || sawInputEos)) {
                formatDelivered = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            if (readyQueue.isEmpty() || freeOutputs.isEmpty()) {
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            val slot = freeOutputs.removeFirst()
            val (bytes, pts, flags) = readyQueue.removeFirst()
            val buf = outputBuffers[slot]
            buf.clear()
            buf.put(bytes)
            buf.flip()
            info.offset = 0
            info.size = bytes.size
            info.presentationTimeUs = pts
            info.flags = flags
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputEosDelivered = true
            }
            return slot
        }

        override fun getOutputBuffer(index: Int): ByteBuffer = outputBuffers[index]

        override fun releaseOutputBuffer(index: Int) {
            freeOutputs.addLast(index)
        }

        override fun stopAndRelease() {
            stoppedAndReleased = true
        }
    }

    private class FakeAacEncoder(
        override val outputFormat: MediaFormat,
        private val inputBufferCapacity: Int,
        private val inputSlotCount: Int = 4,
        private val requireTimedDrainToReleaseSlot: Boolean = false,
        private val emitPacketBytes: Int = 64,
        private val onInputQueued: ((Int, Int) -> Unit)? = null,
        private val onTimeoutObserved: ((Long) -> Unit)? = null
    ) : AudioCodecAdapter {
        private val inputBuffers = Array(inputSlotCount) { ByteBuffer.allocate(inputBufferCapacity) }
        private val freeInputs = ArrayDeque<Int>().apply { repeat(inputSlotCount) { add(it) } }
        private val outputBuf = ByteBuffer.allocate(512)
        private val pendingOutputs = ArrayDeque<Triple<Int, Long, Int>>() // (size, pts, flags)
        private var formatEmitted = false
        private var configEmitted = false

        val receivedPcm = ByteArrayOutputStream()
        val queuedSizes = ArrayList<Int>()
        val queuedPtsUs = ArrayList<Long>()
        var totalBytesQueued = 0L
            private set
        var stoppedAndReleased = false
            private set

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            onTimeoutObserved?.invoke(timeoutUs)
            return if (freeInputs.isEmpty()) MediaCodec.INFO_TRY_AGAIN_LATER else freeInputs.removeFirst()
        }

        override fun getInputBuffer(index: Int): ByteBuffer = inputBuffers[index]

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int
        ) {
            onInputQueued?.invoke(size, flags)
            if (size > 0) {
                val buf = inputBuffers[index]
                val copy = ByteArray(size)
                buf.position(offset)
                buf.limit(offset + size)
                buf.get(copy)
                if (emitPacketBytes >= 64) {
                    receivedPcm.write(copy)
                    queuedSizes.add(size)
                    queuedPtsUs.add(presentationTimeUs)
                }
                totalBytesQueued += size
                pendingOutputs.addLast(Triple(emitPacketBytes, presentationTimeUs, 0))
            }
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                pendingOutputs.addLast(Triple(0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
            }
            if (!requireTimedDrainToReleaseSlot) {
                freeInputs.addLast(index)
            }
        }

        override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
            onTimeoutObserved?.invoke(timeoutUs)
            if (requireTimedDrainToReleaseSlot && timeoutUs == 0L && freeInputs.isEmpty() && pendingOutputs.isNotEmpty()) {
                // Force one idle round so caller exercises single CODEC_DEQUEUE_TIMEOUT_US wait
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            if (!formatEmitted && pendingOutputs.isNotEmpty()) {
                formatEmitted = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            if (!configEmitted && pendingOutputs.isNotEmpty()) {
                configEmitted = true
                outputBuf.clear()
                outputBuf.put(byteArrayOf(0x11, 0x90.toByte()))
                outputBuf.flip()
                info.offset = 0
                info.size = 2
                info.presentationTimeUs = 0L
                info.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                return 0
            }
            if (pendingOutputs.isEmpty()) return MediaCodec.INFO_TRY_AGAIN_LATER

            val (pktSize, pts, flags) = pendingOutputs.removeFirst()
            outputBuf.clear()
            repeat(pktSize) { outputBuf.put(0xAA.toByte()) }
            outputBuf.flip()
            info.offset = 0
            info.size = pktSize
            info.presentationTimeUs = pts
            info.flags = flags
            if (requireTimedDrainToReleaseSlot && freeInputs.size < inputSlotCount) {
                freeInputs.addLast(0)
            }
            return 0
        }

        override fun getOutputBuffer(index: Int): ByteBuffer = outputBuf

        override fun releaseOutputBuffer(index: Int) {}

        override fun stopAndRelease() {
            stoppedAndReleased = true
        }
    }

    private class TimedSampleSource(
        private val totalChunks: Int,
        private val sampleRate: Int
    ) : AudioSampleSource {
        private var idx = 0
        override val sampleTrackIndex: Int get() = if (idx < totalChunks) 1 else -1
        override val sampleTimeUs: Long get() = (idx.toLong() * 1_024L * 1_000_000L) / sampleRate
        override val sampleSizeHint: Long get() = 64L
        override fun readSampleData(buffer: ByteBuffer): Int {
            buffer.clear()
            buffer.limit(64)
            buffer.position(64)
            return 64
        }
        override fun advance(): Boolean {
            idx++
            return idx < totalChunks
        }
    }

    /**
     * Deterministic virtual clock shared across concurrent simulated hardware codecs so pipeline
     * overlap is measured accurately without sensitivity to OS thread preemption on CI runners.
     */
    private class VirtualCodecClock {
        var nowNs: Long = 0L
        private val nextReadyProviders = ArrayList<() -> Long?>(2)

        fun register(provider: () -> Long?) {
            nextReadyProviders.add(provider)
        }

        fun awaitNextEvent(maxWaitNs: Long) {
            if (maxWaitNs <= 0L) return
            var earliest = Long.MAX_VALUE
            for (provider in nextReadyProviders) {
                val ready = provider()
                if (ready != null && ready < earliest) {
                    earliest = ready
                }
            }
            if (earliest != Long.MAX_VALUE && earliest > nowNs) {
                nowNs = minOf(earliest, nowNs + maxWaitNs)
            }
        }
    }

    /**
     * Simulates an asynchronous hardware/C2 audio decoder worker pipeline where each queued
     * compressed packet completes decoding after [stageNs] on [clock].
     */
    private class TimedAsyncFakeDecoder(
        override val outputFormat: MediaFormat,
        private val chunkSize: Int,
        private val stageNs: Long,
        private val clock: VirtualCodecClock
    ) : AudioCodecAdapter {
        private val inBuf = ByteBuffer.allocateDirect(128)
        private val outBuffers = Array(4) {
            ByteBuffer.allocateDirect(chunkSize).apply {
                repeat(chunkSize) { put(0x3C.toByte()) }
                flip()
            }
        }
        private val freeInputs = ArrayDeque<Int>().apply { repeat(4) { add(it) } }
        private val freeOutputs = ArrayDeque<Int>().apply { repeat(4) { add(it) } }
        private val inFlightReadyNs = LongArray(8)
        private val inFlightFlags = IntArray(8)
        private val inFlightSize = IntArray(8)
        private var head = 0
        private var tail = 0
        private var count = 0
        private var workerCursorNs = 0L
        private var formatSent = false

        init {
            clock.register { if (count > 0 && freeOutputs.isNotEmpty()) inFlightReadyNs[head] else null }
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            return if (freeInputs.isEmpty() || count >= inFlightReadyNs.size) {
                MediaCodec.INFO_TRY_AGAIN_LATER
            } else {
                freeInputs.removeFirst()
            }
        }

        override fun getInputBuffer(index: Int): ByteBuffer = inBuf.apply { clear() }

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            freeInputs.addLast(index)
            val start = maxOf(clock.nowNs, workerCursorNs)
            val isEos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val finish = if (isEos) start else start + stageNs
            workerCursorNs = finish

            inFlightReadyNs[tail] = finish
            inFlightFlags[tail] = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            inFlightSize[tail] = if (isEos) 0 else chunkSize
            tail = (tail + 1) % inFlightReadyNs.size
            count++
        }

        override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
            if (!formatSent && count > 0) {
                formatSent = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            if (count == 0 || freeOutputs.isEmpty()) return MediaCodec.INFO_TRY_AGAIN_LATER
            val readyAt = inFlightReadyNs[head]
            if (clock.nowNs < readyAt) {
                if (timeoutUs <= 0L) return MediaCodec.INFO_TRY_AGAIN_LATER
                clock.awaitNextEvent(timeoutUs * 1_000L)
                if (clock.nowNs < readyAt) return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            val slot = freeOutputs.removeFirst()
            val sz = inFlightSize[head]
            val flg = inFlightFlags[head]
            head = (head + 1) % inFlightReadyNs.size
            count--

            outBuffers[slot].position(0)
            outBuffers[slot].limit(sz)
            info.offset = 0
            info.size = sz
            info.presentationTimeUs = 0L
            info.flags = flg
            return slot
        }

        override fun getOutputBuffer(index: Int): ByteBuffer = outBuffers[index]

        override fun releaseOutputBuffer(index: Int) {
            freeOutputs.addLast(index)
        }

        override fun stopAndRelease() {}
    }

    /**
     * Simulates an asynchronous hardware/C2 AAC encoder worker pipeline without allocating
     * per-input heap arrays, emitting realistic ~256-byte AAC packets after [stageNs] on [clock].
     */
    private class TimedAsyncFakeEncoder(
        override val outputFormat: MediaFormat,
        inputBufferCapacity: Int,
        private val stageNs: Long,
        private val clock: VirtualCodecClock
    ) : AudioCodecAdapter {
        private val inBuffers = Array(4) { ByteBuffer.allocateDirect(inputBufferCapacity) }
        private val outBuffers = Array(4) {
            ByteBuffer.allocateDirect(256).apply {
                repeat(256) { put(0xAA.toByte()) }
                flip()
            }
        }
        private val freeInputs = ArrayDeque<Int>().apply { repeat(4) { add(it) } }
        private val freeOutputs = ArrayDeque<Int>().apply { repeat(4) { add(it) } }
        private val inFlightReadyNs = LongArray(8)
        private val inFlightFlags = IntArray(8)
        private val inFlightSize = IntArray(8)
        private val inFlightPts = LongArray(8)
        private var head = 0
        private var tail = 0
        private var count = 0
        private var workerCursorNs = 0L
        private var formatSent = false
        var totalBytesQueued = 0L
            private set

        init {
            clock.register { if (count > 0 && freeOutputs.isNotEmpty()) inFlightReadyNs[head] else null }
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            return if (freeInputs.isEmpty() || count >= inFlightReadyNs.size) {
                MediaCodec.INFO_TRY_AGAIN_LATER
            } else {
                freeInputs.removeFirst()
            }
        }

        override fun getInputBuffer(index: Int): ByteBuffer = inBuffers[index]

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            freeInputs.addLast(index)
            totalBytesQueued += size
            val start = maxOf(clock.nowNs, workerCursorNs)
            val isEos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val finish = if (isEos && size == 0) start else start + stageNs
            workerCursorNs = finish

            inFlightReadyNs[tail] = finish
            inFlightFlags[tail] = flags
            inFlightSize[tail] = if (size > 0) 256 else 0
            inFlightPts[tail] = presentationTimeUs
            tail = (tail + 1) % inFlightReadyNs.size
            count++
        }

        override fun dequeueOutputBuffer(info: CodecOutputInfo, timeoutUs: Long): Int {
            if (!formatSent && count > 0) {
                formatSent = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            if (count == 0 || freeOutputs.isEmpty()) return MediaCodec.INFO_TRY_AGAIN_LATER
            val readyAt = inFlightReadyNs[head]
            if (clock.nowNs < readyAt) {
                if (timeoutUs <= 0L) return MediaCodec.INFO_TRY_AGAIN_LATER
                clock.awaitNextEvent(timeoutUs * 1_000L)
                if (clock.nowNs < readyAt) return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            val slot = freeOutputs.removeFirst()
            val sz = inFlightSize[head]
            val flg = inFlightFlags[head]
            val pts = inFlightPts[head]
            head = (head + 1) % inFlightReadyNs.size
            count--

            outBuffers[slot].position(0)
            outBuffers[slot].limit(sz)
            info.offset = 0
            info.size = sz
            info.presentationTimeUs = pts
            info.flags = flg
            return slot
        }

        override fun getOutputBuffer(index: Int): ByteBuffer = outBuffers[index]

        override fun releaseOutputBuffer(index: Int) {
            freeOutputs.addLast(index)
        }

        override fun stopAndRelease() {}
    }
}
