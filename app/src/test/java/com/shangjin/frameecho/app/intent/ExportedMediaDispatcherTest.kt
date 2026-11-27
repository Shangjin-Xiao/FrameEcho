package com.shangjin.frameecho.app.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.io.FileNotFoundException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportedMediaDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var activityContext: Activity
    private lateinit var appContext: Context
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
        activityContext = mockk(relaxed = true) {
            every { this@mockk.applicationInfo } returns this@ExportedMediaDispatcherTest.applicationInfo
            every { contentResolver } returns resolver
            every { dataDir } returns privateDataDir
            every { filesDir } returns File(privateDataDir, "files")
            every { cacheDir } returns File(privateDataDir, "cache")
            every { packageName } returns "com.shangjin.frameecho"
        }
        appContext = mockk(relaxed = true) {
            every { this@mockk.applicationInfo } returns this@ExportedMediaDispatcherTest.applicationInfo
            every { contentResolver } returns resolver
            every { dataDir } returns privateDataDir
            every { filesDir } returns File(privateDataDir, "files")
            every { cacheDir } returns File(privateDataDir, "cache")
            every { packageName } returns "com.shangjin.frameecho"
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `share builds ACTION_SEND with EXTRA_STREAM, MIME ClipData, read grant, and launches via Intent createChooser`() {
        val rawUri = "content://media/external/images/media/2048"
        val uri = createContentUri(path = "/external/images/media/2048")
        val afd = mockReadableDescriptor(uri)
        val externalPhoto = tempFolder.newFile("IMG_20261127.jpg")

        val sendIntent = mockk<Intent>(relaxed = true)
        val chooserIntent = mockk<Intent>(relaxed = true) {
            every { clipData } returns null
        }
        val clipData = mockk<ClipData>(relaxed = true)
        var capturedAction: String? = null
        var capturedClipLabel: CharSequence? = null
        var capturedClipMimeTypes: Array<String>? = null
        var capturedClipUri: Uri? = null
        var chooserTarget: Intent? = null
        var chooserTitleSeen: CharSequence? = null

        val dispatcher = ExportedMediaDispatcher(
            uriParser = { if (it == rawUri) uri else null },
            fdPathResolver = { externalPhoto.absolutePath },
            intentFactory = { action ->
                capturedAction = action
                sendIntent
            },
            clipDataFactory = { label, mimeTypes, itemUri ->
                capturedClipLabel = label
                capturedClipMimeTypes = mimeTypes
                capturedClipUri = itemUri
                clipData
            },
            chooserFactory = { target, title ->
                chooserTarget = target
                chooserTitleSeen = title
                chooserIntent
            }
        )

        val outcome = dispatcher.share(
            context = activityContext,
            outputPath = rawUri,
            mimeType = "image/jpeg",
            chooserTitle = "分享截取画面"
        )

        assertEquals(ExportedMediaActionResult.SUCCESS, outcome)
        assertEquals(Intent.ACTION_SEND, capturedAction)
        assertEquals("分享截取画面", capturedClipLabel)
        assertArrayEquals(arrayOf("image/jpeg"), capturedClipMimeTypes)
        assertSame(uri, capturedClipUri)
        assertSame(sendIntent, chooserTarget)
        assertEquals("分享截取画面", chooserTitleSeen)

        verify(exactly = 1) { afd.close() }
        verify(exactly = 1) { sendIntent.type = "image/jpeg" }
        verify(exactly = 1) { sendIntent.putExtra(Intent.EXTRA_STREAM, uri) }
        verify(exactly = 1) { sendIntent.clipData = clipData }
        verify(exactly = 1) { sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        // Security guardrail: chooserIntent (never bare sendIntent) is passed to startActivity
        verify(exactly = 1) { chooserIntent.clipData = clipData }
        verify(exactly = 1) { chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify(exactly = 0) { chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { activityContext.startActivity(chooserIntent) }
        verify(exactly = 0) { activityContext.startActivity(sendIntent) }
    }

    @Test
    fun `view builds ACTION_VIEW with setDataAndType, ClipData, read grant, and wraps in chooser with NEW_TASK on non-Activity context`() {
        val rawUri = "content://com.android.externalstorage.documents/document/primary%3APictures%2FMVIMG_1.jpg"
        val uri = createContentUri(
            authority = "com.android.externalstorage.documents",
            path = "/document/primary:Pictures/MVIMG_1.jpg"
        )
        val afd = mockReadableDescriptor(uri)
        val externalPhoto = tempFolder.newFile("MVIMG_1.jpg")

        val viewIntent = mockk<Intent>(relaxed = true)
        val chooserIntent = mockk<Intent>(relaxed = true) {
            every { clipData } returns null
        }
        val clipData = mockk<ClipData>(relaxed = true)
        var capturedAction: String? = null
        var chooserTarget: Intent? = null

        val dispatcher = ExportedMediaDispatcher(
            uriParser = { if (it == rawUri) uri else null },
            fdPathResolver = { externalPhoto.absolutePath },
            intentFactory = { action ->
                capturedAction = action
                viewIntent
            },
            clipDataFactory = { _, _, _ -> clipData },
            chooserFactory = { target, _ ->
                chooserTarget = target
                chooserIntent
            }
        )

        val outcome = dispatcher.view(
            context = appContext,
            outputPath = rawUri,
            mimeType = "image/jpeg",
            chooserTitle = "查看截取画面"
        )

        assertEquals(ExportedMediaActionResult.SUCCESS, outcome)
        assertEquals(Intent.ACTION_VIEW, capturedAction)
        assertSame(viewIntent, chooserTarget)
        verify(exactly = 1) { afd.close() }
        verify(exactly = 1) { viewIntent.setDataAndType(uri, "image/jpeg") }
        verify(exactly = 1) { viewIntent.clipData = clipData }
        verify(exactly = 1) { viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify(exactly = 1) { chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify(exactly = 1) { chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { appContext.startActivity(chooserIntent) }
        verify(exactly = 0) { appContext.startActivity(viewIntent) }
    }

    @Test
    fun `returns FILE_UNAVAILABLE when exported file was deleted externally, non-content scheme, or points to private app dir`() {
        val deletedRawUri = "content://media/external/images/media/9999"
        val deletedUri = createContentUri(path = "/external/images/media/9999")
        every { resolver.openAssetFileDescriptor(deletedUri, "r") } throws FileNotFoundException("Deleted")
        every { resolver.openFileDescriptor(deletedUri, "r") } throws FileNotFoundException("Deleted")

        val fileRawUri = "file:///storage/emulated/0/DCIM/IMG_1.jpg"
        val fileSchemeUri = createContentUri(scheme = "file", path = "/storage/emulated/0/DCIM/IMG_1.jpg")

        val symlinkRawUri = "content://evil.provider/export/1"
        val symlinkUri = createContentUri(authority = "evil.provider", path = "/export/1")
        mockReadableDescriptor(symlinkUri)
        val secretInternalFile = File(privateDataDir, "datastore/preferences.preferences_pb").apply {
            parentFile?.mkdirs()
            writeText("secret")
        }

        val dispatcher = ExportedMediaDispatcher(
            uriParser = { raw ->
                when (raw) {
                    deletedRawUri -> deletedUri
                    fileRawUri -> fileSchemeUri
                    symlinkRawUri -> symlinkUri
                    else -> null
                }
            },
            fdPathResolver = { secretInternalFile.absolutePath },
            intentFactory = { mockk(relaxed = true) },
            clipDataFactory = { _, _, _ -> mockk(relaxed = true) },
            chooserFactory = { _, _ -> mockk(relaxed = true) }
        )

        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.share(activityContext, "", "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.share(activityContext, deletedRawUri, "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.view(activityContext, deletedRawUri, "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.share(activityContext, fileRawUri, "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.share(activityContext, symlinkRawUri, "image/jpeg")
        )

        verify(exactly = 0) { activityContext.startActivity(any()) }
    }

    @Test
    fun `maps ActivityNotFoundException to NO_APP_AVAILABLE and SecurityException to FILE_UNAVAILABLE`() {
        val rawUri = "content://media/external/images/media/300"
        val uri = createContentUri(path = "/external/images/media/300")
        mockReadableDescriptor(uri)
        val externalPhoto = tempFolder.newFile("IMG_300.jpg")
        val chooserIntent = mockk<Intent>(relaxed = true)

        val dispatcher = ExportedMediaDispatcher(
            uriParser = { uri },
            fdPathResolver = { externalPhoto.absolutePath },
            intentFactory = { mockk(relaxed = true) },
            clipDataFactory = { _, _, _ -> mockk(relaxed = true) },
            chooserFactory = { _, _ -> chooserIntent }
        )

        every { activityContext.startActivity(chooserIntent) } throws ActivityNotFoundException("No chooser")
        assertEquals(
            ExportedMediaActionResult.NO_APP_AVAILABLE,
            dispatcher.share(activityContext, rawUri, "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.NO_APP_AVAILABLE,
            dispatcher.view(activityContext, rawUri, "image/jpeg")
        )

        every { activityContext.startActivity(chooserIntent) } throws SecurityException("Grant revoked")
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.share(activityContext, rawUri, "image/jpeg")
        )
        assertEquals(
            ExportedMediaActionResult.FILE_UNAVAILABLE,
            dispatcher.view(activityContext, rawUri, "image/jpeg")
        )
    }

    @Test
    fun `all locale strings xml files contain complete non-blank share, view, thumbnail, and error resources`() {
        val requiredKeys = listOf(
            "snackbar_action_share",
            "snackbar_action_view",
            "share_captured_frame",
            "view_captured_frame",
            "captured_frame_thumbnail",
            "motion_photo_badge",
            "error_exported_file_unavailable",
            "error_no_app_to_open_media"
        )
        val localeDirs = listOf("values", "values-zh-rCN", "values-ja", "values-es")

        val resRoot = sequenceOf(
            File("src/main/res"),
            File("app/src/main/res")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Could not locate app/src/main/res directory", resRoot)

        val dbFactory = DocumentBuilderFactory.newInstance()
        for (localeDir in localeDirs) {
            val stringsFile = File(resRoot!!, "$localeDir/strings.xml")
            assertTrue("Missing strings.xml for $localeDir", stringsFile.isFile)
            val doc = dbFactory.newDocumentBuilder().parse(stringsFile)
            val nodes = doc.getElementsByTagName("string")
            val entries = HashMap<String, String>(nodes.length)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                if (name != null) {
                    entries[name] = node.textContent.orEmpty().trim()
                }
            }
            for (key in requiredKeys) {
                val value = entries[key]
                assertTrue(
                    "Missing or blank string '$key' in $localeDir/strings.xml",
                    !value.isNullOrBlank()
                )
            }
        }
    }

    private fun createContentUri(
        scheme: String = ContentResolver.SCHEME_CONTENT,
        authority: String = "media",
        path: String = "/external/images/media/1"
    ): Uri {
        return mockk(relaxed = true) {
            every { this@mockk.scheme } returns scheme
            every { this@mockk.authority } returns authority
            every { this@mockk.path } returns path
        }
    }

    private fun mockReadableDescriptor(
        uri: Uri,
        pfd: ParcelFileDescriptor = mockk(relaxed = true)
    ): AssetFileDescriptor {
        val afd = mockk<AssetFileDescriptor>(relaxed = true) {
            every { parcelFileDescriptor } returns pfd
        }
        every { resolver.openAssetFileDescriptor(uri, "r") } returns afd
        return afd
    }
}
