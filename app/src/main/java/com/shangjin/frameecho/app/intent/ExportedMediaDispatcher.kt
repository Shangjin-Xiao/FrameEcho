package com.shangjin.frameecho.app.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.shangjin.frameecho.core.media.utils.LogUtils
import java.io.File

/**
 * Outcome of dispatching an exported media file to an external viewer or the Android Sharesheet.
 */
enum class ExportedMediaActionResult {
    SUCCESS,
    FILE_UNAVAILABLE,
    NO_APP_AVAILABLE
}

/**
 * Unified dispatcher for sharing (`Intent.ACTION_SEND`) and externally viewing (`Intent.ACTION_VIEW`)
 * exported frames and motion photos.
 *
 * Security & UX invariants:
 * - Only readable `content://` URIs outside the app's private data directories are dispatched.
 * - Both `ACTION_SEND` and `ACTION_VIEW` are strictly wrapped in `Intent.createChooser` to prevent
 *   implicit intent hijacking (`.jules/sentinel.md`).
 * - Sharesheet intents attach `Intent.EXTRA_STREAM`, a MIME-typed `ClipData` item for top rich
 *   thumbnail preview rendering, and `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
 */
class ExportedMediaDispatcher(
    private val uriParser: (String) -> Uri? = { raw ->
        try {
            raw.toUri()
        } catch (_: Exception) {
            null
        }
    },
    private val fdPathResolver: (ParcelFileDescriptor) -> String? =
        InboundVideoIntentProcessor::defaultResolveFdPath,
    private val intentFactory: (String) -> Intent = { action -> Intent(action) },
    private val clipDataFactory: (CharSequence, Array<String>, Uri) -> ClipData = { label, mimeTypes, uri ->
        ClipData(ClipDescription(label, mimeTypes), ClipData.Item(uri))
    },
    private val chooserFactory: (Intent, CharSequence?) -> Intent = { target, title ->
        Intent.createChooser(target, title)
    }
) {

    companion object {
        private const val TAG = "ExportedMediaDispatcher"
        private const val DEFAULT_IMAGE_MIME_TYPE = "image/jpeg"

        val Default = ExportedMediaDispatcher()
    }

    /**
     * Launch the Android system Sharesheet (`Intent.ACTION_SEND`) for an exported media URI.
     */
    fun share(
        context: Context,
        outputPath: String,
        mimeType: String,
        chooserTitle: String? = null
    ): ExportedMediaActionResult {
        val uri = validateReadableExportUri(context, outputPath)
            ?: return ExportedMediaActionResult.FILE_UNAVAILABLE
        val resolvedMimeType = mimeType.trim().ifEmpty { DEFAULT_IMAGE_MIME_TYPE }

        return try {
            val mediaClipData = buildMediaClipData(
                label = chooserTitle,
                mimeType = resolvedMimeType,
                uri = uri
            )
            val sendIntent = intentFactory(Intent.ACTION_SEND).apply {
                type = resolvedMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = mediaClipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchWithChooser(
                context = context,
                targetIntent = sendIntent,
                clipData = mediaClipData,
                chooserTitle = chooserTitle
            )
        } catch (e: ActivityNotFoundException) {
            LogUtils.w(context, TAG, "No activity available to share exported media", e)
            ExportedMediaActionResult.NO_APP_AVAILABLE
        } catch (e: SecurityException) {
            LogUtils.w(context, TAG, "SecurityException sharing exported media", e)
            ExportedMediaActionResult.FILE_UNAVAILABLE
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "Failed to share exported media", e)
            ExportedMediaActionResult.NO_APP_AVAILABLE
        }
    }

    /**
     * Launch an external gallery/viewer (`Intent.ACTION_VIEW`) wrapped in `Intent.createChooser`.
     */
    fun view(
        context: Context,
        outputPath: String,
        mimeType: String,
        chooserTitle: String? = null
    ): ExportedMediaActionResult {
        val uri = validateReadableExportUri(context, outputPath)
            ?: return ExportedMediaActionResult.FILE_UNAVAILABLE
        val resolvedMimeType = mimeType.trim().ifEmpty { DEFAULT_IMAGE_MIME_TYPE }

        return try {
            val mediaClipData = buildMediaClipData(
                label = chooserTitle,
                mimeType = resolvedMimeType,
                uri = uri
            )
            val viewIntent = intentFactory(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMimeType)
                clipData = mediaClipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchWithChooser(
                context = context,
                targetIntent = viewIntent,
                clipData = mediaClipData,
                chooserTitle = chooserTitle
            )
        } catch (e: ActivityNotFoundException) {
            LogUtils.w(context, TAG, "No activity available to view exported media", e)
            ExportedMediaActionResult.NO_APP_AVAILABLE
        } catch (e: SecurityException) {
            LogUtils.w(context, TAG, "SecurityException viewing exported media", e)
            ExportedMediaActionResult.FILE_UNAVAILABLE
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "Failed to view exported media", e)
            ExportedMediaActionResult.NO_APP_AVAILABLE
        }
    }

    private fun launchWithChooser(
        context: Context,
        targetIntent: Intent,
        clipData: ClipData,
        chooserTitle: String?
    ): ExportedMediaActionResult {
        val chooserIntent = chooserFactory(targetIntent, chooserTitle).apply {
            if (this.clipData == null) {
                this.clipData = clipData
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooserIntent)
        return ExportedMediaActionResult.SUCCESS
    }

    private fun buildMediaClipData(
        label: String?,
        mimeType: String,
        uri: Uri
    ): ClipData {
        return clipDataFactory(label ?: "", arrayOf(mimeType), uri)
    }

    private fun validateReadableExportUri(
        context: Context,
        outputPath: String
    ): Uri? {
        val trimmed = outputPath.trim()
        if (trimmed.isEmpty()) return null

        val uri = uriParser(trimmed) ?: return null
        val scheme = uri.scheme
        if (scheme == null || !scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            LogUtils.w(context, TAG, "Rejected outbound media URI with non-content scheme")
            return null
        }

        val protectedDirs = collectProtectedDirs(context)
        val rawPath = uri.path
        if (!rawPath.isNullOrBlank() &&
            InboundVideoIntentProcessor.isPathInsideProtectedDirs(rawPath, protectedDirs)
        ) {
            LogUtils.w(context, TAG, "Blocked outbound media URI targeting private app directory")
            return null
        }

        val resolver = context.contentResolver
        val afd = try {
            resolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (afd != null) {
            val pointsToPrivateDir = try {
                val pfd = afd.parcelFileDescriptor
                val resolvedPath = if (pfd != null) {
                    try { fdPathResolver(pfd) } catch (_: Exception) { null }
                } else {
                    null
                }
                resolvedPath != null &&
                    resolvedPath.startsWith("/") &&
                    InboundVideoIntentProcessor.isPathInsideProtectedDirs(resolvedPath, protectedDirs)
            } finally {
                try { afd.close() } catch (_: Exception) { }
            }
            if (pointsToPrivateDir) {
                LogUtils.w(context, TAG, "Blocked outbound descriptor pointing to private app directory")
                return null
            }
            return uri
        }

        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "Exported media URI is no longer readable", e)
            return null
        } ?: return null

        val pointsToPrivateDir = try {
            val resolvedPath = try { fdPathResolver(pfd) } catch (_: Exception) { null }
            resolvedPath != null &&
                resolvedPath.startsWith("/") &&
                InboundVideoIntentProcessor.isPathInsideProtectedDirs(resolvedPath, protectedDirs)
        } finally {
            try { pfd.close() } catch (_: Exception) { }
        }
        if (pointsToPrivateDir) {
            LogUtils.w(context, TAG, "Blocked outbound file descriptor pointing to private app directory")
            return null
        }

        return uri
    }

    @android.annotation.SuppressLint("SdCardPath")
    private fun collectProtectedDirs(context: Context): Set<String> {
        val rawDirs = buildList {
            try { context.applicationInfo?.dataDir } catch (_: Exception) { null }?.let { add(it) }
            try { context.applicationInfo?.deviceProtectedDataDir } catch (_: Exception) { null }?.let { add(it) }
            try { context.dataDir?.path } catch (_: Exception) { null }?.let { add(it) }
            try { context.filesDir?.parentFile?.path } catch (_: Exception) { null }?.let { add(it) }
            try { context.cacheDir?.parentFile?.path } catch (_: Exception) { null }?.let { add(it) }
            try { context.packageName } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() }
                ?.let { pkg ->
                    add("/data/data/$pkg")
                    add("/data/user/0/$pkg")
                }
        }
        return buildSet {
            for (rawDir in rawDirs) {
                if (rawDir.isBlank()) continue
                val lexical = InboundVideoIntentProcessor.normalizeLexicalPath(rawDir)
                    .trimEnd('/')
                    .ifEmpty { "/" }
                if (lexical != "/") {
                    add(lexical)
                }
                try { File(rawDir).canonicalPath } catch (_: Exception) { null }
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() && it != "/" }
                    ?.let { add(it) }
            }
        }
    }
}
