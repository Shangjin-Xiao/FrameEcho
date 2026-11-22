package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.media.metadata.MetadataWriter
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

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
