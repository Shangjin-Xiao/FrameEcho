package com.shangjin.frameecho.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.shangjin.frameecho.app.ui.player.PlayerViewModel
import com.shangjin.frameecho.app.ui.player.shouldRequestLegacyWritePermission
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

@OptIn(ExperimentalCoroutinesApi::class)
class DistributionAndPermissionPolicyTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scoped storage Android Q and above never requests legacy write permission`() {
        var permissionChecked = false
        for (sdk in listOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.TIRAMISU, 36)) {
            val requiresPermission = shouldRequestLegacyWritePermission(
                sdkInt = sdk,
                hasCustomExportTreeUri = false,
                hasWriteExternalStoragePermission = {
                    permissionChecked = true
                    false
                }
            )
            assertFalse("SDK $sdk must not require WRITE_EXTERNAL_STORAGE", requiresPermission)
        }
        assertFalse(permissionChecked)
    }

    @Test
    fun `legacy Android P and below only requests write permission for non-SAF gallery exports`() {
        var permissionChecked = false
        assertFalse(
            shouldRequestLegacyWritePermission(
                sdkInt = Build.VERSION_CODES.P,
                hasCustomExportTreeUri = true,
                hasWriteExternalStoragePermission = {
                    permissionChecked = true
                    false
                }
            )
        )
        assertFalse(permissionChecked)

        assertTrue(
            shouldRequestLegacyWritePermission(
                sdkInt = Build.VERSION_CODES.P,
                hasCustomExportTreeUri = false,
                hasWriteExternalStoragePermission = { false }
            )
        )
        assertFalse(
            shouldRequestLegacyWritePermission(
                sdkInt = Build.VERSION_CODES.O,
                hasCustomExportTreeUri = false,
                hasWriteExternalStoragePermission = { true }
            )
        )
    }

    @Test
    fun `play manifest strips unused media and internet permissions while preserving legacy write`() {
        val playManifest = resolveAppFile("src/play/AndroidManifest.xml")
        val mainManifest = resolveAppFile("src/main/AndroidManifest.xml")

        val playRemovals = parseUsesPermissionNodes(playManifest)
        val mainPermissions = parseUsesPermissionNodes(mainManifest)

        val expectedRemoved = setOf(
            "android.permission.INTERNET",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_EXTERNAL_STORAGE"
        )

        expectedRemoved.forEach { permission ->
            assertTrue("$permission should exist in main manifest", permission in mainPermissions)
            assertEquals(
                "$permission should be removed with tools:node=\"remove\" in play manifest",
                "remove",
                playRemovals[permission]
            )
        }

        // Android 8/9 MediaStore gallery fallback still relies on WRITE_EXTERNAL_STORAGE (maxSdkVersion=28)
        assertTrue("android.permission.WRITE_EXTERNAL_STORAGE" in mainPermissions)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in playRemovals)
    }

    @Test
    fun `video selection, frame capture, and gallery or SAF motion photo export succeed without media read permissions`() = runTest {
        val frameExtractor = mockk<FrameExtractor>(relaxed = true)
        val frameExporter = mockk<FrameExporter>(relaxed = true)
        val viewModel = PlayerViewModel()
        viewModel.setDependenciesForTesting(frameExtractor, frameExporter)

        val videoUri = mockk<Uri>()
        val safTreeUri = mockk<Uri>()
        val frameBitmap = mockk<Bitmap>(relaxed = true)
        val previewBitmap = mockk<Bitmap>(relaxed = true)
        val capturedFrame = mockk<CapturedFrame>(relaxed = true)

        val galleryStaticSuccess = ExportResult.Success(
            outputPath = "content://media/external/images/media/1001",
            width = 1920,
            height = 1080,
            fileSizeBytes = 320_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = false
        )
        val safMotionSuccess = ExportResult.Success(
            outputPath = "content://com.android.externalstorage.documents/tree/primary%3APictures/document/1002",
            width = 1920,
            height = 1080,
            fileSizeBytes = 2_400_000L,
            format = ExportFormat.JPEG,
            isMotionPhoto = true
        )

        coEvery { frameExtractor.extractFrameWithInfo(videoUri, any()) } returns (frameBitmap to capturedFrame)
        coEvery { frameExtractor.createPreviewThumbnail(frameBitmap, any()) } returns previewBitmap
        coEvery {
            frameExporter.exportStaticFrame(frameBitmap, capturedFrame, any(), null)
        } returns galleryStaticSuccess
        coEvery {
            frameExporter.exportMotionPhoto(videoUri, frameBitmap, capturedFrame, any(), safTreeUri)
        } returns safMotionSuccess

        // 1. Select video via picker / content:// URI and capture static frame to MediaStore gallery
        viewModel.setVideoUri(videoUri)
        assertSame(videoUri, viewModel.uiState.value.videoUri)
        viewModel.captureAndSave(motionPhoto = false)

        assertEquals(galleryStaticSuccess, viewModel.uiState.value.exportResult)
        assertEquals(galleryStaticSuccess, viewModel.uiState.value.lastExportSuccess)
        coVerify(exactly = 1) {
            frameExporter.exportStaticFrame(frameBitmap, capturedFrame, any(), null)
        }

        // 2. Switch to custom SAF export folder and capture Motion Photo
        viewModel.setCustomExportTreeUri(safTreeUri)
        viewModel.captureAndSave(motionPhoto = true)

        assertEquals(safMotionSuccess, viewModel.uiState.value.exportResult)
        assertEquals(safMotionSuccess, viewModel.uiState.value.lastExportSuccess)
        assertTrue(viewModel.uiState.value.isCapturedMotionPhoto)
        coVerify(exactly = 1) {
            frameExporter.exportMotionPhoto(videoUri, frameBitmap, capturedFrame, any(), safTreeUri)
        }
    }

    private fun resolveAppFile(relativeToApp: String): File =
        listOf(File(relativeToApp), File("app", relativeToApp))
            .firstOrNull { it.isFile }
            ?: throw AssertionError("Could not locate $relativeToApp from ${File(".").absolutePath}")

    private fun parseUsesPermissionNodes(manifestFile: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)

        val nodes = doc.getElementsByTagName("uses-permission")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            val toolsNode = element.getAttributeNS("http://schemas.android.com/tools", "node")
            if (name.isNotBlank()) {
                result[name] = toolsNode
            }
        }
        return result
    }
}
