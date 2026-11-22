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
import android.provider.DocumentsContract
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.kxml2.io.KXmlSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer

@RunWith(Parameterized::class)
class FrameExporterSecurityTest(private val motionPhoto: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "motionPhoto={0}")
        fun exporters() = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val treeUri = mockk<Uri>()
    private val createdUri = mockk<Uri>()
    private val tree = mockk<DocumentFile>()
    private val document = mockk<DocumentFile>()
    private val bitmap = mockk<Bitmap>()
    private val jpegBytes = byteArrayOf(1, 2, 3)
    private val videoBytes = byteArrayOf(4, 5, 6)
    private lateinit var videoFile: File

    @Before
    fun setup() {
        mockkStatic(DocumentFile::class, DocumentsContract::class)
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
        every { DocumentsContract.deleteDocument(resolver, createdUri) } returns true
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(jpegBytes)
            true
        }
        videoFile = temporaryFolder.newFile("clip.mp4").apply { writeBytes(videoBytes) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reject file URI before opening or deleting it`() {
        every { createdUri.scheme } returns ContentResolver.SCHEME_FILE
        assertRejected()
    }

    @Test
    fun `reject other non-content schemes`() {
        every { createdUri.scheme } returns ContentResolver.SCHEME_ANDROID_RESOURCE
        assertRejected()
    }

    @Test
    fun `reject a different provider even with the same tree ID`() {
        every { createdUri.authority } returns "other.provider"
        assertRejected()
    }

    @Test
    fun `reject a different tree in the selected provider`() {
        every { DocumentsContract.getTreeDocumentId(createdUri) } returns "other-tree"
        assertRejected()
    }

    @Test
    fun `reject document URI without tree context`() {
        every { DocumentsContract.isTreeUri(createdUri) } returns false
        assertRejected()
    }

    @Test
    fun `reject tree root or malformed URI that is not a document`() {
        every { DocumentsContract.isDocumentUri(context, createdUri) } returns false
        assertRejected()
    }

    @Test
    fun `write and return the validated document URI`() {
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdUri) } returns output

        assertSame(createdUri, export())
        assertArrayEquals(if (motionPhoto) jpegBytes + videoBytes else jpegBytes, output.toByteArray())
        verify(exactly = 1) { resolver.openOutputStream(createdUri) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `clean up only the validated URI when opening fails`() {
        every { resolver.openOutputStream(createdUri) } returns null

        assertThrows(IOException::class.java) { export() }

        verify(exactly = 1) { DocumentsContract.deleteDocument(resolver, createdUri) }
        verify(exactly = 0) { document.delete() }
    }

    @Test
    fun `clean up only the validated URI when writing fails`() {
        val failure = IOException("Write failed")
        val output = mockk<OutputStream>(relaxed = true)
        every { output.write(any<ByteArray>()) } throws failure
        every { resolver.openOutputStream(createdUri) } returns output

        assertSame(failure, assertThrows(IOException::class.java) { export() })

        verify(exactly = 1) { DocumentsContract.deleteDocument(resolver, createdUri) }
        verify(exactly = 0) { document.delete() }
    }

    private fun assertRejected() {
        assertThrows(IOException::class.java) { export() }
        verify { resolver wasNot Called }
        verify(exactly = 0) { document.delete() }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    // Exercise both private writers without widening the exporter's public API.
    private fun export(): Uri {
        val exporter = FrameExporter(context)
        val method = if (motionPhoto) {
            FrameExporter::class.java.getDeclaredMethod(
                "saveMotionPhotoToCustomDirectory", ByteArray::class.java, File::class.java,
                String::class.java, Uri::class.java
            )
        } else {
            FrameExporter::class.java.getDeclaredMethod(
                "saveToCustomDirectory", Bitmap::class.java, String::class.java,
                ExportConfig::class.java, Uri::class.java
            )
        }.apply { isAccessible = true }
        return try {
            if (motionPhoto) {
                method.invoke(exporter, jpegBytes, videoFile, "photo.jpg", treeUri) as Uri
            } else {
                method.invoke(exporter, bitmap, "photo.jpg", ExportConfig(), treeUri) as Uri
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private data class FakeSample(
        val timeUs: Long,
        val sizeBytes: Int,
        val flags: Int = 0
    )

    private class RemuxHarness(
        val cacheDir: File,
        var totalMuxedBytes: Long = 0L,
        var totalMuxedSamples: Int = 0,
        var minPtsUs: Long = Long.MAX_VALUE,
        var maxPtsUs: Long = 0L,
        var muxerReleaseCount: Int = 0,
        var muxerStopCount: Int = 0,
        var allTempFilesExistedAtMuxerRelease: Boolean = true,
        var audioSamplesRead: Int = 0
    ) {
        val muxedSpanUs: Long
            get() = if (minPtsUs == Long.MAX_VALUE) 0L else (maxPtsUs - minPtsUs).coerceAtLeast(0L)
    }

    private fun prepareMotionPhotoMocks(
        videoSamples: List<FakeSample>,
        audioSamples: List<FakeSample> = emptyList(),
        audioMime: String = MediaFormat.MIMETYPE_AUDIO_AAC,
        onVideoSeek: (targetUs: Long, mode: Int) -> Int
    ): RemuxHarness {
        val cacheDir = temporaryFolder.newFolder()
        val harness = RemuxHarness(cacheDir = cacheDir)

        mockkStatic(Log::class, Xml::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Xml.newSerializer() } returns KXmlSerializer()

        every { context.cacheDir } returns cacheDir
        every { context.applicationInfo } throws RuntimeException("unit test")

        val validJpegWithSoi = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()
        )
        every { bitmap.config } returns Bitmap.Config.ARGB_8888
        every { bitmap.width } returns 64
        every { bitmap.height } returns 64
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(validJpegWithSoi)
            true
        }

        val videoFormat = mockk<MediaFormat>(relaxed = true)
        every { videoFormat.getString(MediaFormat.KEY_MIME) } returns "video/avc"
        every { videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } returns 64 * 1024

        val audioFormat = mockk<MediaFormat>(relaxed = true)
        every { audioFormat.getString(MediaFormat.KEY_MIME) } returns audioMime
        every { audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } returns 16 * 1024
        every { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } returns 48_000
        every { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } returns 1
        every { audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) } returns AudioFormat.ENCODING_PCM_16BIT

        mockkConstructor(MediaExtractor::class)
        mockkConstructor(MediaMuxer::class)
        mockkConstructor(MediaCodec.BufferInfo::class)

        var selectedTrack = -1
        var videoIndex = 0
        var audioIndex = 0

        every { anyConstructed<MediaExtractor>().setDataSource(any<Context>(), any<Uri>(), any()) } just Runs
        every { anyConstructed<MediaExtractor>().trackCount } returns if (audioSamples.isEmpty()) 1 else 2
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } returns videoFormat
        every { anyConstructed<MediaExtractor>().getTrackFormat(1) } returns audioFormat
        every { anyConstructed<MediaExtractor>().selectTrack(any()) } answers {
            selectedTrack = firstArg()
        }
        every { anyConstructed<MediaExtractor>().unselectTrack(any()) } answers {
            if (selectedTrack == firstArg<Int>()) {
                selectedTrack = -1
            }
        }
        every { anyConstructed<MediaExtractor>().seekTo(any(), any()) } answers {
            val targetUs = firstArg<Long>()
            val mode = secondArg<Int>()
            if (selectedTrack == 0) {
                videoIndex = onVideoSeek(targetUs, mode)
            } else if (selectedTrack == 1) {
                val idx = audioSamples.indexOfFirst { it.timeUs >= targetUs }
                audioIndex = if (idx >= 0) idx else audioSamples.size
            }
        }
        every { anyConstructed<MediaExtractor>().sampleTrackIndex } answers {
            when (selectedTrack) {
                0 -> if (videoIndex in videoSamples.indices) 0 else -1
                1 -> if (audioIndex in audioSamples.indices) 1 else -1
                else -> -1
            }
        }
        every { anyConstructed<MediaExtractor>().sampleTime } answers {
            when (selectedTrack) {
                0 -> videoSamples.getOrNull(videoIndex)?.timeUs ?: -1L
                1 -> audioSamples.getOrNull(audioIndex)?.timeUs ?: -1L
                else -> -1L
            }
        }
        every { anyConstructed<MediaExtractor>().sampleFlags } answers {
            when (selectedTrack) {
                0 -> videoSamples.getOrNull(videoIndex)?.flags ?: 0
                1 -> audioSamples.getOrNull(audioIndex)?.flags ?: 0
                else -> 0
            }
        }
        every { anyConstructed<MediaExtractor>().readSampleData(any(), any()) } answers {
            val dst = firstArg<ByteBuffer>()
            val sample = when (selectedTrack) {
                0 -> videoSamples.getOrNull(videoIndex)
                1 -> audioSamples.getOrNull(audioIndex)?.also { harness.audioSamplesRead++ }
                else -> null
            } ?: return@answers -1
            val toWrite = minOf(sample.sizeBytes, dst.remaining())
            dst.put(ByteArray(toWrite))
            sample.sizeBytes
        }
        every { anyConstructed<MediaExtractor>().advance() } answers {
            when (selectedTrack) {
                0 -> {
                    videoIndex++
                    videoIndex in videoSamples.indices
                }
                1 -> {
                    audioIndex++
                    audioIndex in audioSamples.indices
                }
                else -> false
            }
        }
        every { anyConstructed<MediaExtractor>().release() } just Runs

        var nextMuxerTrack = 0
        every { anyConstructed<MediaMuxer>().addTrack(any()) } answers { nextMuxerTrack++ }
        every { anyConstructed<MediaMuxer>().setOrientationHint(any()) } just Runs
        every { anyConstructed<MediaMuxer>().start() } just Runs
        every { anyConstructed<MediaMuxer>().writeSampleData(any(), any(), any()) } answers {
            val info = thirdArg<MediaCodec.BufferInfo>()
            harness.totalMuxedBytes += info.size.toLong()
            harness.totalMuxedSamples++
            if (info.presentationTimeUs < harness.minPtsUs) {
                harness.minPtsUs = info.presentationTimeUs
            }
            if (info.presentationTimeUs > harness.maxPtsUs) {
                harness.maxPtsUs = info.presentationTimeUs
            }
            cacheDir.listFiles()?.firstOrNull { it.name.startsWith("motion_clip_") }?.let { temp ->
                if (temp.length() == 0L) {
                    temp.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18))
                }
            }
        }
        every { anyConstructed<MediaMuxer>().stop() } answers {
            harness.muxerStopCount++
        }
        every { anyConstructed<MediaMuxer>().release() } answers {
            harness.muxerReleaseCount++
            val tempExists = cacheDir.listFiles()?.any { it.name.startsWith("motion_clip_") } == true
            if (!tempExists) {
                harness.allTempFilesExistedAtMuxerRelease = false
            }
        }

        return harness
    }

    private fun buildFrame(timestampUs: Long, durationMs: Long): CapturedFrame {
        return CapturedFrame(
            timestampUs = timestampUs,
            width = 64,
            height = 64,
            colorSpace = ColorSpaceInfo(),
            metadata = VideoMetadata(durationMs = durationMs)
        )
    }

    @Test
    fun `reject sparse keyframe lookback exceeding limit and clean up temp files`() = runBlocking {
        val totalDurationMs = 600_000L
        val captureTimestampUs = 599_000_000L
        val videoSamples = List(4_500) { idx ->
            FakeSample(
                timeUs = idx * 133_333L,
                sizeBytes = 40 * 1024,
                flags = if (idx == 0) MediaExtractor.SAMPLE_FLAG_SYNC else 0
            )
        }
        val audioSamples = List(3_000) { idx ->
            FakeSample(
                timeUs = idx * 200_000L,
                sizeBytes = 2 * 1024,
                flags = MediaExtractor.SAMPLE_FLAG_SYNC
            )
        }
        // Exercise both keyframe validation sites: native AAC path and raw LPCM pre-transcode path.
        val audioMime = if (motionPhoto) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_RAW
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples,
            audioSamples = audioSamples,
            audioMime = audioMime
        ) { _, _ ->
            0 // Both PREVIOUS_SYNC and CLOSEST_SYNC land back at 0 us (stss = [1])
        }

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Error but got $result", result is ExportResult.Error)
        assertEquals(0L, harness.totalMuxedBytes)
        assertEquals(0, harness.totalMuxedSamples)
        assertEquals(0, harness.audioSamplesRead)
        assertTrue(harness.muxedSpanUs <= 30_000_000L)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(
            "Temporary motion_clip_*.mp4 files must be deleted on failure",
            harness.cacheDir.listFiles().isNullOrEmpty()
        )
        verify { resolver wasNot Called }
    }

    @Test
    fun `allow fallback to SEEK_TO_CLOSEST_SYNC when nearby keyframe is within clip bounds`() = runBlocking {
        val totalDurationMs = 600_000L
        val captureTimestampUs = 599_000_000L
        val nearbyKeyframeUs = 598_000_000L
        val videoSamples = listOf(
            FakeSample(timeUs = 0L, sizeBytes = 32 * 1024, flags = MediaExtractor.SAMPLE_FLAG_SYNC),
            FakeSample(timeUs = nearbyKeyframeUs, sizeBytes = 32 * 1024, flags = MediaExtractor.SAMPLE_FLAG_SYNC),
            FakeSample(timeUs = 599_000_000L, sizeBytes = 16 * 1024, flags = 0)
        )
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples
        ) { _, mode ->
            if (mode == MediaExtractor.SEEK_TO_PREVIOUS_SYNC) 0 else 1
        }
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdUri) } returns output
        every { resolver.openFileDescriptor(createdUri, "r") } returns null

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Success but got $result", result is ExportResult.Success)
        assertEquals(48 * 1024L, harness.totalMuxedBytes)
        assertTrue(harness.muxedSpanUs <= 20_000_000L)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(harness.cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `reject cumulative muxed bytes exceeding budget within valid time window`() = runBlocking {
        val totalDurationMs = 3_000L
        val captureTimestampUs = 1_500_000L
        val oneMiB = 1024 * 1024
        // 140 samples of 1 MiB inside [0, 2.8s] simulates shared stco chunk amplification (140 MiB > 128 MiB).
        val videoSamples = List(140) { idx ->
            FakeSample(
                timeUs = idx * 20_000L,
                sizeBytes = oneMiB,
                flags = if (idx == 0) MediaExtractor.SAMPLE_FLAG_SYNC else 0
            )
        }
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples
        ) { _, _ -> 0 }

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Error but got $result", result is ExportResult.Error)
        assertEquals(FrameExporter.MAX_MUXED_CLIP_BYTES, harness.totalMuxedBytes)
        assertTrue(harness.muxerStopCount >= 1)
        assertTrue(harness.muxerReleaseCount >= 1)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(
            "Temporary motion_clip_*.mp4 files must be deleted on failure",
            harness.cacheDir.listFiles().isNullOrEmpty()
        )
        verify { resolver wasNot Called }
    }

    @Test
    fun `reject cumulative muxed sample count exceeding budget across video and native audio loops`() = runBlocking {
        val totalDurationMs = 3_000L
        val captureTimestampUs = 1_500_000L
        val videoSamples = List(4_000) { idx ->
            FakeSample(
                timeUs = idx * 500L,
                sizeBytes = 32,
                flags = if (idx == 0) MediaExtractor.SAMPLE_FLAG_SYNC else 0
            )
        }
        val audioSamples = List(3_000) { idx ->
            FakeSample(
                timeUs = idx * 800L,
                sizeBytes = 32,
                flags = MediaExtractor.SAMPLE_FLAG_SYNC
            )
        }
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples,
            audioSamples = audioSamples,
            audioMime = MediaFormat.MIMETYPE_AUDIO_AAC
        ) { _, _ -> 0 }

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Error but got $result", result is ExportResult.Error)
        assertEquals(FrameExporter.MAX_MUXED_SAMPLES, harness.totalMuxedSamples)
        assertTrue(harness.totalMuxedBytes <= FrameExporter.MAX_MUXED_CLIP_BYTES)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(
            "Temporary motion_clip_*.mp4 files must be deleted on failure",
            harness.cacheDir.listFiles().isNullOrEmpty()
        )
        verify { resolver wasNot Called }
    }

    @Test
    fun `reject cumulative remux budget exceeded in transcoded AAC audio loop`() = runBlocking {
        val totalDurationMs = 3_000L
        val captureTimestampUs = 1_500_000L
        val videoSamples = List(5_995) { idx ->
            FakeSample(
                timeUs = idx * 400L,
                sizeBytes = 32,
                flags = if (idx == 0) MediaExtractor.SAMPLE_FLAG_SYNC else 0
            )
        }
        val pcmSamples = listOf(
            FakeSample(timeUs = 0L, sizeBytes = 4096, flags = MediaExtractor.SAMPLE_FLAG_SYNC)
        )
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples,
            audioSamples = pcmSamples,
            audioMime = MediaFormat.MIMETYPE_AUDIO_RAW
        ) { _, _ -> 0 }

        // Mock AAC encoder to emit 10 packets so 5995 video + 10 AAC = 6005 > MAX_MUXED_SAMPLES (6000).
        mockkStatic(MediaFormat::class, MediaCodec::class)
        val encoderFormat = mockk<MediaFormat>(relaxed = true)
        every { MediaFormat.createAudioFormat(any(), any(), any()) } returns encoderFormat
        val encoder = mockk<MediaCodec>(relaxed = true)
        every { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) } returns encoder
        val encoderInputBuffer = ByteBuffer.allocate(8192)
        val encoderOutputBuffer = ByteBuffer.allocate(64)
        var fedInput = false
        var sentFormat = false
        var packetsEmitted = 0
        every { encoder.dequeueInputBuffer(any()) } answers { if (!fedInput) 0 else -1 }
        every { encoder.getInputBuffer(0) } answers {
            encoderInputBuffer.clear()
            encoderInputBuffer
        }
        every { encoder.queueInputBuffer(any(), any(), any(), any(), any()) } answers {
            if ((arg<Int>(4) and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                fedInput = true
            }
        }
        every { encoder.outputFormat } returns encoderFormat
        every { encoder.dequeueOutputBuffer(any(), any()) } answers {
            val info = firstArg<MediaCodec.BufferInfo>()
            if (!sentFormat) {
                sentFormat = true
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            } else if (packetsEmitted < 10) {
                packetsEmitted++
                info.offset = 0
                info.size = 32
                info.presentationTimeUs = packetsEmitted * 20_000L
                info.flags = if (packetsEmitted == 10) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                0
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        every { encoder.getOutputBuffer(0) } answers {
            encoderOutputBuffer.clear()
            encoderOutputBuffer.put(ByteArray(32))
            encoderOutputBuffer.flip()
            encoderOutputBuffer
        }

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Error but got $result", result is ExportResult.Error)
        assertEquals(FrameExporter.MAX_MUXED_SAMPLES, harness.totalMuxedSamples)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(
            "Temporary motion_clip_*.mp4 files must be deleted on failure",
            harness.cacheDir.listFiles().isNullOrEmpty()
        )
        verify { resolver wasNot Called }
    }

    @Test
    fun `reject keyframe seek jumping past endUs and clean up temp files`() = runBlocking {
        val totalDurationMs = 600_000L
        val captureTimestampUs = 500_000_000L // clip window [498.5s, 501.5s]
        val videoSamples = listOf(
            FakeSample(timeUs = 0L, sizeBytes = 16 * 1024, flags = MediaExtractor.SAMPLE_FLAG_SYNC),
            FakeSample(timeUs = 550_000_000L, sizeBytes = 16 * 1024, flags = MediaExtractor.SAMPLE_FLAG_SYNC)
        )
        val audioSamples = listOf(
            FakeSample(timeUs = 500_000_000L, sizeBytes = 2 * 1024, flags = MediaExtractor.SAMPLE_FLAG_SYNC)
        )
        val audioMime = if (motionPhoto) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_RAW
        val harness = prepareMotionPhotoMocks(
            videoSamples = videoSamples,
            audioSamples = audioSamples,
            audioMime = audioMime
        ) { _, mode ->
            // PREVIOUS_SYNC lands too far back (0 us); CLOSEST_SYNC jumps past endUs (550s > 501.5s).
            if (mode == MediaExtractor.SEEK_TO_PREVIOUS_SYNC) 0 else 1
        }

        val exporter = FrameExporter(context)
        val result = exporter.exportMotionPhoto(
            videoUri = mockk(relaxed = true),
            bitmap = bitmap,
            frame = buildFrame(timestampUs = captureTimestampUs, durationMs = totalDurationMs),
            config = ExportConfig(motionPhoto = true, preserveMetadata = false),
            customExportTreeUri = treeUri
        )

        assertTrue("Expected ExportResult.Error but got $result", result is ExportResult.Error)
        assertEquals(0L, harness.totalMuxedBytes)
        assertEquals(0, harness.audioSamplesRead)
        assertTrue(harness.allTempFilesExistedAtMuxerRelease)
        assertTrue(harness.cacheDir.listFiles().isNullOrEmpty())
        verify { resolver wasNot Called }
    }
}
