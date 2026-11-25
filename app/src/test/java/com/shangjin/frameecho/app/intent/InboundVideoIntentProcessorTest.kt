package com.shangjin.frameecho.app.intent

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboundVideoIntentProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var applicationInfo: ApplicationInfo
    private lateinit var privateDataDir: File

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        privateDataDir = tempFolder.newFolder("app_private_data")
        applicationInfo = ApplicationInfo().apply {
            dataDir = privateDataDir.absolutePath
            flags = 0
        }

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.applicationInfo } returns applicationInfo
        every { context.contentResolver } returns resolver
        every { context.dataDir } returns privateDataDir
        every { context.filesDir } returns File(privateDataDir, "files")
        every { context.cacheDir } returns File(privateDataDir, "cache")
        every { context.packageName } returns "com.shangjin.frameecho"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `ignores null intent, ACTION_MAIN, unsupported actions, and history launches`() {
        val processor = InboundVideoIntentProcessor()

        assertEquals(InboundVideoResult.Ignored, processor.process(context, null))

        val mainIntent = createIntent(action = Intent.ACTION_MAIN)
        assertEquals(InboundVideoResult.Ignored, processor.process(context, mainIntent))

        val sendMultipleIntent = createIntent(action = Intent.ACTION_SEND_MULTIPLE)
        assertEquals(InboundVideoResult.Ignored, processor.process(context, sendMultipleIntent))

        val historyIntent = createIntent(
            action = Intent.ACTION_VIEW,
            data = createContentUri(),
            flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        )
        assertEquals(InboundVideoResult.Ignored, processor.process(context, historyIntent))
    }

    @Test
    fun `accepts valid ACTION_VIEW content video URI, closes descriptor, and never persists URI permission`() {
        val uri = createContentUri(path = "/external/video/media/101")
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val afd = mockValidDescriptor(uri, pfd)
        every { resolver.getType(uri) } returns "video/mp4"

        val externalFile = tempFolder.newFile("camera_clip.mp4")
        val processor = InboundVideoIntentProcessor(
            fdPathResolver = { externalFile.absolutePath }
        )
        val intent = createIntent(action = Intent.ACTION_VIEW, data = uri, type = "video/*")

        val result = processor.process(context, intent)

        assertTrue(result is InboundVideoResult.Valid)
        assertSame(uri, (result as InboundVideoResult.Valid).uri)
        verify(exactly = 1) {
            intent.putExtra(InboundVideoIntentProcessor.EXTRA_INBOUND_VIDEO_CONSUMED, true)
        }
        verify(exactly = 1) { afd.close() }
        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
    }

    @Test
    fun `accepts ACTION_VIEW falling back to clipData when intent data is null`() {
        val uri = createContentUri()
        mockValidDescriptor(uri)
        every { resolver.getType(uri) } returns "video/quicktime"

        val clipData = createClipDataWithUri(uri)
        val intent = createIntent(action = Intent.ACTION_VIEW, data = null, clipData = clipData)
        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })

        val result = processor.process(context, intent)

        assertEquals(InboundVideoResult.Valid(uri), result)
    }

    @Test
    fun `accepts ACTION_SEND via EXTRA_STREAM and falls back to clipData when EXTRA_STREAM is absent`() {
        val streamUri = createContentUri(path = "/share/1")
        val clipUri = createContentUri(path = "/share/2")
        mockValidDescriptor(streamUri)
        mockValidDescriptor(clipUri)
        every { resolver.getType(streamUri) } returns "video/mp4"
        every { resolver.getType(clipUri) } returns "video/webm"

        val processor = InboundVideoIntentProcessor(fdPathResolver = { "pipe:[98765]" })

        val streamIntent = createIntent(
            action = Intent.ACTION_SEND,
            streamExtra = streamUri,
            type = "video/mp4"
        )
        assertEquals(InboundVideoResult.Valid(streamUri), processor.process(context, streamIntent))

        val clipFallbackIntent = createIntent(
            action = Intent.ACTION_SEND,
            streamExtra = null,
            clipData = createClipDataWithUri(clipUri),
            type = "video/*"
        )
        assertEquals(InboundVideoResult.Valid(clipUri), processor.process(context, clipFallbackIntent))
    }

    @Test
    fun `accepts uppercase CONTENT scheme case-insensitively`() {
        val uri = createContentUri(scheme = "CONTENT")
        mockValidDescriptor(uri)
        every { resolver.getType(uri) } returns "video/mp4"

        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })
        val intent = createIntent(action = Intent.ACTION_VIEW, data = uri)

        assertEquals(InboundVideoResult.Valid(uri), processor.process(context, intent))
    }

    @Test
    fun `enforces single consumption across same Intent instance and parcel-copied consumed flag`() {
        val uri = createContentUri()
        mockValidDescriptor(uri)
        every { resolver.getType(uri) } returns "video/mp4"

        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })
        val intent = createIntent(action = Intent.ACTION_VIEW, data = uri)

        // First delivery consumes and returns Valid
        assertEquals(InboundVideoResult.Valid(uri), processor.process(context, intent))
        // Second delivery of same instance (e.g., configuration change) is ignored
        assertEquals(InboundVideoResult.Ignored, processor.process(context, intent))

        // Distinct parcel-copied Intent carrying EXTRA_INBOUND_VIDEO_CONSUMED = true is also ignored
        val parcelCopiedIntent = createIntent(
            action = Intent.ACTION_VIEW,
            data = uri,
            consumedFlag = true
        )
        assertEquals(InboundVideoResult.Ignored, processor.process(context, parcelCopiedIntent))
    }

    @Test
    fun `invalid intent is also marked consumed so rotation does not re-trigger error snackbar`() {
        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })
        val emptyViewIntent = createIntent(action = Intent.ACTION_VIEW, data = null)

        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, emptyViewIntent)
        )
        assertEquals(InboundVideoResult.Ignored, processor.process(context, emptyViewIntent))
    }

    @Test
    fun `rejects empty ACTION_VIEW and ACTION_SEND intents with ACCESS_FAILED`() {
        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })

        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_VIEW, data = null))
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_SEND, streamExtra = null))
        )
    }

    @Test
    fun `rejects file, http, https, android resource, and null schemes without touching ContentResolver`() {
        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })
        val disallowedSchemes = listOf("file", "http", "https", "android.resource", null)

        for (scheme in disallowedSchemes) {
            val uri = mockk<Uri>(relaxed = true) {
                every { this@mockk.scheme } returns scheme
                every { path } returns "/sdcard/Movies/clip.mp4"
            }
            val intent = createIntent(action = Intent.ACTION_VIEW, data = uri, type = "video/mp4")
            assertEquals(
                "Expected scheme=$scheme to be rejected",
                InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
                processor.process(context, intent)
            )
        }
        verify { resolver wasNot Called }
    }

    @Test
    fun `rejects non-video MIME types including spoofed intent type when ContentResolver reports image`() {
        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })

        val spoofedPngUri = createContentUri(path = "/media/1")
        every { resolver.getType(spoofedPngUri) } returns "image/png"
        val spoofedIntent = createIntent(
            action = Intent.ACTION_SEND,
            streamExtra = spoofedPngUri,
            type = "video/mp4"
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT),
            processor.process(context, spoofedIntent)
        )

        val unknownResolverImageUri = createContentUri(path = "/media/2")
        every { resolver.getType(unknownResolverImageUri) } returns null
        val imageDeclaredIntent = createIntent(
            action = Intent.ACTION_SEND,
            streamExtra = unknownResolverImageUri,
            type = "image/png"
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT),
            processor.process(context, imageDeclaredIntent)
        )
    }

    @Test
    fun `verifies video track when ContentResolver returns octet-stream for declared video intent`() {
        val validMkvUri = createContentUri(path = "/downloads/1")
        val fakeOctetUri = createContentUri(path = "/downloads/2")
        mockValidDescriptor(validMkvUri)
        mockValidDescriptor(fakeOctetUri)
        every { resolver.getType(validMkvUri) } returns "application/octet-stream"
        every { resolver.getType(fakeOctetUri) } returns "application/octet-stream"

        val processor = InboundVideoIntentProcessor(
            fdPathResolver = { null },
            videoTrackVerifier = { _, candidate -> candidate === validMkvUri }
        )

        assertEquals(
            InboundVideoResult.Valid(validMkvUri),
            processor.process(
                context,
                createIntent(action = Intent.ACTION_VIEW, data = validMkvUri, type = "video/x-matroska")
            )
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT),
            processor.process(
                context,
                createIntent(action = Intent.ACTION_VIEW, data = fakeOctetUri, type = "video/mp4")
            )
        )
    }

    @Test
    fun `blocks URI paths and traversal segments targeting private dataDir while allowing sibling package names`() {
        val directPrivateUri = createContentUri(
            path = "${privateDataDir.absolutePath}/datastore/player_preferences.preferences_pb"
        )
        val traversalPrivateUri = createContentUri(
            path = "/storage/emulated/0/Download/../../../../data/data/com.shangjin.frameecho/databases/secret.db"
        )
        val siblingAppFile = tempFolder.newFolder("com.shangjin.frameecho_other").let {
            File(it, "shared.mp4").apply { writeBytes(byteArrayOf(0, 1)) }
        }
        val siblingUri = createContentUri(path = "/external/video/999")
        mockValidDescriptor(siblingUri)
        every { resolver.getType(siblingUri) } returns "video/mp4"

        val processor = InboundVideoIntentProcessor(
            fdPathResolver = { siblingAppFile.absolutePath }
        )

        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_VIEW, data = directPrivateUri))
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_VIEW, data = traversalPrivateUri))
        )
        verify(exactly = 0) { resolver.openAssetFileDescriptor(directPrivateUri, any()) }
        verify(exactly = 0) { resolver.openAssetFileDescriptor(traversalPrivateUri, any()) }

        // Sibling package prefix must not falsely match com.shangjin.frameecho
        assertEquals(
            InboundVideoResult.Valid(siblingUri),
            processor.process(context, createIntent(action = Intent.ACTION_VIEW, data = siblingUri))
        )
    }

    @Test
    fun `blocks symlink descriptor pointing into private dataDir including deleted-fd kernel suffix`() {
        val secretFile = File(privateDataDir, "files/internal_token.bin").apply {
            parentFile?.mkdirs()
            writeText("top-secret")
        }
        val attackerDir = tempFolder.newFolder("attacker_provider")
        val symlinkToSecret = Files.createSymbolicLink(
            File(attackerDir, "innocent_video.mp4").toPath(),
            secretFile.toPath()
        ).toFile()
        val procFdSimulationLink = Files.createSymbolicLink(
            File(attackerDir, "fd_42").toPath(),
            symlinkToSecret.toPath()
        ).toFile()

        val resolvedRealPath = InboundVideoIntentProcessor.resolveProcFdLink(procFdSimulationLink.absolutePath)
        assertEquals(secretFile.canonicalPath, resolvedRealPath)

        val uri1 = createContentUri(path = "/public/innocent_video.mp4")
        val afd1 = mockValidDescriptor(uri1)
        every { resolver.getType(uri1) } returns "video/mp4"

        val processor = InboundVideoIntentProcessor(
            fdPathResolver = { resolvedRealPath }
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_SEND, streamExtra = uri1))
        )
        verify(exactly = 1) { afd1.close() }

        // Also block open-then-unlink kernel "(deleted)" fd targets inside dataDir
        val uri2 = createContentUri(path = "/public/unlinked_trick.mp4")
        mockValidDescriptor(uri2)
        every { resolver.getType(uri2) } returns "video/mp4"
        val deletedFdProcessor = InboundVideoIntentProcessor(
            fdPathResolver = { "${secretFile.canonicalPath} (deleted)" }
        )
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            deletedFdProcessor.process(context, createIntent(action = Intent.ACTION_VIEW, data = uri2))
        )
    }

    @Test
    fun `converts SecurityException, FileNotFoundException, and malformed extras into ACCESS_FAILED with sanitized logs in release`() {
        applicationInfo.flags = 0 // non-debuggable release build
        val revokedUri = createContentUri(path = "/media/revoked")
        val securityException = SecurityException("Permission Denial: content://secret/user_123/private.mp4")
        every { resolver.getType(revokedUri) } returns "video/mp4"
        every { resolver.openAssetFileDescriptor(revokedUri, "r") } throws securityException

        val processor = InboundVideoIntentProcessor(fdPathResolver = { null })
        val result = processor.process(
            context,
            createIntent(action = Intent.ACTION_VIEW, data = revokedUri)
        )

        assertEquals(InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED), result)
        // Release log must strip sensitive URI message and log only exception simple class name
        verify {
            Log.w(
                "InboundVideoIntent",
                "SecurityException opening inbound video URI: SecurityException"
            )
        }
        verify(exactly = 0) { Log.w("InboundVideoIntent", any(), securityException) }

        val missingUri = createContentUri(path = "/media/missing")
        every { resolver.getType(missingUri) } returns "video/mp4"
        every { resolver.openAssetFileDescriptor(missingUri, "r") } throws FileNotFoundException("Not found")
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, createIntent(action = Intent.ACTION_VIEW, data = missingUri))
        )

        // Malformed Bundle unparcel failure
        val malformedIntent = mockk<Intent>(relaxed = true) {
            every { action } returns Intent.ACTION_SEND
            every { flags } returns 0
            every {
                getBooleanExtra(InboundVideoIntentProcessor.EXTRA_INBOUND_VIDEO_CONSUMED, false)
            } throws RuntimeException("BadParcelableException: ClassNotFoundException")
        }
        assertEquals(
            InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED),
            processor.process(context, malformedIntent)
        )
    }

    private fun createContentUri(
        scheme: String = ContentResolver.SCHEME_CONTENT,
        authority: String = "com.android.providers.media.documents",
        path: String = "/document/video:42"
    ): Uri {
        return mockk(relaxed = true) {
            every { this@mockk.scheme } returns scheme
            every { this@mockk.authority } returns authority
            every { this@mockk.path } returns path
        }
    }

    private fun mockValidDescriptor(
        uri: Uri,
        pfd: ParcelFileDescriptor = mockk(relaxed = true)
    ): AssetFileDescriptor {
        val afd = mockk<AssetFileDescriptor>(relaxed = true) {
            every { parcelFileDescriptor } returns pfd
        }
        every { resolver.openAssetFileDescriptor(uri, "r") } returns afd
        return afd
    }

    private fun createClipDataWithUri(uri: Uri): ClipData {
        val item = mockk<ClipData.Item>(relaxed = true) {
            every { this@mockk.uri } returns uri
        }
        return mockk(relaxed = true) {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
        }
    }

    @Suppress("DEPRECATION")
    private fun createIntent(
        action: String?,
        data: Uri? = null,
        streamExtra: Uri? = null,
        clipData: ClipData? = null,
        type: String? = null,
        flags: Int = 0,
        consumedFlag: Boolean = false
    ): Intent {
        return mockk(relaxed = true) {
            every { this@mockk.action } returns action
            every { this@mockk.data } returns data
            every { this@mockk.clipData } returns clipData
            every { this@mockk.type } returns type
            every { this@mockk.flags } returns flags
            every {
                getBooleanExtra(InboundVideoIntentProcessor.EXTRA_INBOUND_VIDEO_CONSUMED, false)
            } returns consumedFlag
            every { getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) } returns streamExtra
            every { getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) } returns streamExtra
        }
    }
}
