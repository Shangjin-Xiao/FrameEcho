package com.shangjin.frameecho.app.intent

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.content.IntentCompat
import com.shangjin.frameecho.core.media.utils.LogUtils
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Categorized inbound external video import failures mapped to localized UI messages.
 */
enum class InboundVideoError {
    UNSUPPORTED_FORMAT,
    ACCESS_FAILED
}

/**
 * Outcome of parsing and validating an Activity [Intent] for external video playback.
 */
sealed interface InboundVideoResult {
    data object Ignored : InboundVideoResult
    data class Valid(val uri: Uri) : InboundVideoResult
    data class Invalid(val error: InboundVideoError) : InboundVideoResult
}

/**
 * One-shot inbound event delivered from [com.shangjin.frameecho.app.MainActivity] to the UI layer.
 */
sealed interface InboundVideoEvent {
    val id: Long

    data class OpenVideo(
        override val id: Long,
        val uri: Uri
    ) : InboundVideoEvent

    data class Error(
        override val id: Long,
        val error: InboundVideoError
    ) : InboundVideoEvent
}

/**
 * Intermediate claim produced synchronously on the main thread so duplicate configuration/history
 * deliveries are filtered immediately before performing I/O validation.
 */
sealed interface ClaimedInboundIntent {
    data object Ignored : ClaimedInboundIntent
    data class Candidate(val uri: Uri?, val declaredMimeType: String?) : ClaimedInboundIntent
    data class ExtractionError(val error: InboundVideoError) : ClaimedInboundIntent
}

/**
 * Parses, deduplicates, and validates external `ACTION_VIEW` and `ACTION_SEND` video intents.
 *
 * Security invariants:
 * - Only `content://` URIs (case-insensitive) are accepted; `file://`, `http://`, `https://`, etc. are rejected.
 * - Temporary `FLAG_GRANT_READ_URI_PERMISSION` grants are never upgraded via `takePersistableUriPermission`.
 * - Any URI path or resolved file descriptor (`/proc/self/fd/<fd>`) pointing inside the app's private
 *   data directories (`dataDir`, `deviceProtectedDataDir`, `/data/data/<pkg>`) is blocked to prevent
 *   symlink / bait-and-switch exfiltration of internal files.
 * - All warning/exception logs are sanitized through [LogUtils].
 */
class InboundVideoIntentProcessor(
    private val fdPathResolver: (ParcelFileDescriptor) -> String? = ::defaultResolveFdPath,
    private val videoTrackVerifier: (Context, Uri) -> Boolean = ::defaultVerifyHasVideoTrack
) {

    private val consumedIntents: MutableSet<Intent> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    companion object {
        private const val TAG = "InboundVideoIntent"
        const val EXTRA_INBOUND_VIDEO_CONSUMED =
            "com.shangjin.frameecho.extra.INBOUND_VIDEO_CONSUMED"
        const val STATE_KEY_INITIAL_INTENT_HANDLED =
            "com.shangjin.frameecho.state.INITIAL_INTENT_HANDLED"
        private const val DELETED_FD_SUFFIX = " (deleted)"

        internal fun defaultResolveFdPath(pfd: ParcelFileDescriptor): String? {
            val fd = runCatching { pfd.fd }.getOrNull() ?: return null
            if (fd < 0) return null
            return resolveProcFdLink("/proc/self/fd/$fd")
        }

        internal fun resolveProcFdLink(fdLinkPath: String): String? {
            val osTarget = runCatching { Os.readlink(fdLinkPath) }.getOrNull()
            if (!osTarget.isNullOrBlank()) {
                return osTarget.removeSuffix(DELETED_FD_SUFFIX)
            }
            return runCatching {
                val linkFile = File(fdLinkPath)
                if (linkFile.exists()) {
                    linkFile.canonicalPath.removeSuffix(DELETED_FD_SUFFIX)
                } else {
                    null
                }
            }.getOrNull()
        }

        internal fun defaultVerifyHasVideoTrack(context: Context, uri: Uri): Boolean {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                hasVideo.equals("yes", ignoreCase = true) || hasVideo == "true"
            } finally {
                runCatching { retriever.release() }
            }
        }

        internal fun isVideoMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            val normalized = mimeType.trim().lowercase(Locale.ROOT)
            return normalized == "video/*" ||
                (normalized.startsWith("video/") && normalized.length > "video/".length)
        }

        internal fun isOctetStreamMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            return mimeType.trim().equals("application/octet-stream", ignoreCase = true)
        }

        internal fun normalizeLexicalPath(rawPath: String): String {
            val trimmed = rawPath.trim().removeSuffix(DELETED_FD_SUFFIX)
            if (trimmed.isEmpty()) return ""
            val isAbsolute = trimmed.startsWith("/")
            val segments = ArrayDeque<String>()
            for (part in trimmed.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(part)
                }
            }
            val joined = segments.joinToString("/")
            return if (isAbsolute) "/$joined" else joined
        }

        internal fun isPathInsideProtectedDirs(
            candidatePath: String,
            protectedDirs: Set<String>
        ): Boolean {
            if (protectedDirs.isEmpty()) return false
            val cleaned = candidatePath.trim().removeSuffix(DELETED_FD_SUFFIX)
            if (cleaned.isEmpty()) return false

            val candidates = buildSet {
                val lexical = normalizeLexicalPath(cleaned)
                if (lexical.isNotEmpty()) add(lexical)
                runCatching { File(cleaned).canonicalPath }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it.trimEnd('/').ifEmpty { "/" }) }
            }

            return candidates.any { candidate ->
                protectedDirs.any { dir ->
                    candidate == dir || candidate.startsWith("$dir/")
                }
            }
        }
    }

    /**
     * Synchronously inspects and claims an external video [Intent] on the caller thread,
     * marking it consumed so subsequent configuration changes or history launches ignore it.
     */
    fun claimIntent(context: Context, intent: Intent?): ClaimedInboundIntent {
        if (intent == null) return ClaimedInboundIntent.Ignored
        val action = intent.action ?: return ClaimedInboundIntent.Ignored
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) {
            return ClaimedInboundIntent.Ignored
        }
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return ClaimedInboundIntent.Ignored
        }

        val alreadyConsumedFlag = try {
            intent.getBooleanExtra(EXTRA_INBOUND_VIDEO_CONSUMED, false)
        } catch (e: RuntimeException) {
            LogUtils.w(context, TAG, "Malformed inbound intent extras", e)
            consumedIntents.add(intent)
            return ClaimedInboundIntent.ExtractionError(InboundVideoError.ACCESS_FAILED)
        }
        if (alreadyConsumedFlag || !consumedIntents.add(intent)) {
            return ClaimedInboundIntent.Ignored
        }

        try {
            intent.putExtra(EXTRA_INBOUND_VIDEO_CONSUMED, true)
        } catch (e: RuntimeException) {
            LogUtils.w(context, TAG, "Unable to mark inbound intent consumed", e)
        }

        return try {
            val uri = when (action) {
                Intent.ACTION_VIEW -> intent.data ?: extractFirstClipDataUri(intent)
                Intent.ACTION_SEND ->
                    extractStreamExtraUri(intent) ?: extractFirstClipDataUri(intent) ?: intent.data
                else -> null
            }
            ClaimedInboundIntent.Candidate(uri = uri, declaredMimeType = intent.type)
        } catch (e: RuntimeException) {
            LogUtils.w(context, TAG, "Failed to extract URI from inbound intent", e)
            ClaimedInboundIntent.ExtractionError(InboundVideoError.ACCESS_FAILED)
        }
    }

    /**
     * Validates a previously [claimIntent]-claimed candidate against URI scheme, private directory
     * confinement, MIME type, and `ContentResolver` readability.
     */
    fun validateClaimed(context: Context, claimed: ClaimedInboundIntent): InboundVideoResult {
        return when (claimed) {
            ClaimedInboundIntent.Ignored -> InboundVideoResult.Ignored
            is ClaimedInboundIntent.ExtractionError -> InboundVideoResult.Invalid(claimed.error)
            is ClaimedInboundIntent.Candidate -> validateCandidateUri(
                context = context,
                uri = claimed.uri,
                declaredMimeType = claimed.declaredMimeType
            )
        }
    }

    /**
     * Convenience entry point performing both single-consumption claiming and full validation.
     */
    fun process(context: Context, intent: Intent?): InboundVideoResult {
        val claimed = claimIntent(context, intent)
        return validateClaimed(context, claimed)
    }

    private fun validateCandidateUri(
        context: Context,
        uri: Uri?,
        declaredMimeType: String?
    ): InboundVideoResult {
        if (uri == null) {
            LogUtils.w(context, TAG, "Rejected inbound video intent with missing URI")
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        val scheme = uri.scheme
        if (scheme == null || !scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            LogUtils.w(context, TAG, "Rejected inbound URI with unsupported scheme")
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        val protectedDirs = collectProtectedAppDirs(context)
        val rawUriPath = uri.path
        if (!rawUriPath.isNullOrBlank() && isPathInsideProtectedDirs(rawUriPath, protectedDirs)) {
            LogUtils.w(context, TAG, "Blocked inbound URI path targeting private app directory")
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        val resolver = context.contentResolver
        val resolvedMimeType = try {
            resolver.getType(uri)
        } catch (e: SecurityException) {
            LogUtils.w(context, TAG, "SecurityException querying inbound URI MIME type", e)
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "Failed to query inbound URI MIME type", e)
            null
        }

        val requiresTrackProbe = when {
            resolvedMimeType != null -> {
                when {
                    isVideoMimeType(resolvedMimeType) -> false
                    isOctetStreamMimeType(resolvedMimeType) && isVideoMimeType(declaredMimeType) -> true
                    else -> {
                        LogUtils.w(context, TAG, "Rejected inbound URI with non-video MIME type")
                        return InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT)
                    }
                }
            }
            declaredMimeType != null -> {
                if (isVideoMimeType(declaredMimeType)) {
                    false
                } else {
                    LogUtils.w(context, TAG, "Rejected inbound intent with non-video declared MIME type")
                    return InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT)
                }
            }
            else -> true
        }

        val openedDescriptor = try {
            openReadOnlyDescriptor(resolver, uri)
        } catch (e: SecurityException) {
            LogUtils.w(context, TAG, "SecurityException opening inbound video URI", e)
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "Failed to open inbound video URI", e)
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        if (openedDescriptor == null) {
            LogUtils.w(context, TAG, "ContentResolver returned null descriptor for inbound URI")
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        val pointsToPrivateAppDir = try {
            val pfd = openedDescriptor.parcelFileDescriptor
            val resolvedPath = if (pfd != null) {
                runCatching { fdPathResolver(pfd) }.getOrNull()
            } else {
                null
            }
            resolvedPath != null &&
                resolvedPath.startsWith("/") &&
                isPathInsideProtectedDirs(resolvedPath, protectedDirs)
        } finally {
            openedDescriptor.closeQuietly()
        }

        if (pointsToPrivateAppDir) {
            LogUtils.w(context, TAG, "Blocked inbound URI descriptor pointing to private app directory")
            return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
        }

        if (requiresTrackProbe) {
            val hasVideoTrack = try {
                videoTrackVerifier(context, uri)
            } catch (e: SecurityException) {
                LogUtils.w(context, TAG, "SecurityException probing inbound video tracks", e)
                return InboundVideoResult.Invalid(InboundVideoError.ACCESS_FAILED)
            } catch (e: Exception) {
                LogUtils.w(context, TAG, "Failed to probe inbound video tracks", e)
                false
            }
            if (!hasVideoTrack) {
                LogUtils.w(context, TAG, "Rejected inbound URI without decodable video track")
                return InboundVideoResult.Invalid(InboundVideoError.UNSUPPORTED_FORMAT)
            }
        }

        return InboundVideoResult.Valid(uri)
    }

    private fun extractStreamExtraUri(intent: Intent): Uri? {
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun extractFirstClipDataUri(intent: Intent): Uri? {
        val clipData = intent.clipData ?: return null
        if (clipData.itemCount <= 0) return null
        return clipData.getItemAt(0)?.uri
    }

    @SuppressLint("SdCardPath")
    private fun collectProtectedAppDirs(context: Context): Set<String> {
        // Primary paths come from Context APIs (handling multi-user / work profiles),
        // supplemented with legacy /data/data and /data/user/0 aliases as defense-in-depth.
        val rawDirs = buildList {
            runCatching { context.applicationInfo?.dataDir }.getOrNull()?.let { add(it) }
            runCatching { context.applicationInfo?.deviceProtectedDataDir }.getOrNull()?.let { add(it) }
            runCatching { context.dataDir?.path }.getOrNull()?.let { add(it) }
            runCatching { context.filesDir?.parentFile?.path }.getOrNull()?.let { add(it) }
            runCatching { context.cacheDir?.parentFile?.path }.getOrNull()?.let { add(it) }
            runCatching { context.packageName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { pkg ->
                    add("/data/data/$pkg")
                    add("/data/user/0/$pkg")
                }
        }

        return buildSet {
            for (rawDir in rawDirs) {
                if (rawDir.isBlank()) continue
                val lexical = normalizeLexicalPath(rawDir).trimEnd('/').ifEmpty { "/" }
                if (lexical != "/") {
                    add(lexical)
                }
                runCatching { File(rawDir).canonicalPath }
                    .getOrNull()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() && it != "/" }
                    ?.let { add(it) }
            }
        }
    }

    private class OpenedDescriptor(
        val parcelFileDescriptor: ParcelFileDescriptor?,
        private val closeable: AutoCloseable
    ) {
        fun closeQuietly() {
            runCatching { closeable.close() }
        }
    }

    private fun openReadOnlyDescriptor(
        resolver: ContentResolver,
        uri: Uri
    ): OpenedDescriptor? {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
        if (afd != null) {
            return OpenedDescriptor(afd.parcelFileDescriptor, afd)
        }
        val pfd = resolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            return OpenedDescriptor(pfd, pfd)
        }
        return null
    }
}
