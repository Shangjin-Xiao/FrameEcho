package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.model.ExportConfig
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException

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
}
