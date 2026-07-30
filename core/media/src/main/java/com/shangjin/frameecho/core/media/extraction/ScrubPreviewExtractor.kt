@file:androidx.annotation.OptIn(UnstableApi::class)

package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.FrameExtractor as Media3FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Frame source for the seek-bar preview overlay.
 *
 * Seeking the playback decoder on every drag event is what makes scrubbing feel laggy:
 * a frame-accurate seek has to decode from the preceding keyframe up to the target, so
 * the preview falls further behind the finger the longer you drag. Players that scrub
 * smoothly don't do that at all — they show a separate preview image while the finger
 * moves and only seek the real player once it stops (YouTube storyboards, Google Play
 * Movies, NewPipe's seekbar thumbnails). This is that preview source.
 *
 * media3-inspector's FrameExtractor is used rather than [FrameExtractor]'s
 * MediaMetadataRetriever because it keeps one long-lived decoding session instead of
 * paying `setDataSource` on every call, converts colour on the GPU, and — via
 * [Presentation] — downscales the frame before it ever becomes a Bitmap, so a 4K source
 * doesn't allocate a 33MB bitmap per preview. Seeks are keyframe-accurate on purpose:
 * previews only have to keep up with a moving finger, and the exact frame comes from the
 * real player once the drag settles.
 *
 * Not thread-safe. Create, use and [release] from a single thread (the main thread).
 */
class ScrubPreviewExtractor(
    private val context: Context,
    private val previewShortSidePx: Int = DEFAULT_PREVIEW_SHORT_SIDE_PX
) {

    private var extractor: Media3FrameExtractor? = null
    private var preparedUri: Uri? = null

    companion object {
        /**
         * Short side of extracted previews. Big enough to judge the shot while dragging,
         * small enough that each bitmap is a few hundred KB rather than tens of MB.
         */
        const val DEFAULT_PREVIEW_SHORT_SIDE_PX = 480
    }

    /**
     * Open a decoding session for [videoUri]. Cheap to call repeatedly with the same URI;
     * switching URIs closes the previous session.
     */
    fun prepare(videoUri: Uri) {
        if (preparedUri == videoUri && extractor != null) return
        release()
        extractor = Media3FrameExtractor.Builder(context, MediaItem.fromUri(videoUri))
            .setEffects(listOf<Effect>(Presentation.createForShortSide(previewShortSidePx)))
            // Keyframe-accurate: a preview that keeps up beats a preview that is exact.
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
        preparedUri = videoUri
    }

    /**
     * Extract the preview frame nearest [positionMs].
     *
     * Suspends until the frame is ready, so callers can use the completion to pace
     * requests — one extraction in flight at a time, newest target wins — instead of
     * queueing work the decoder can't keep up with.
     *
     * @return the frame, or null if this extractor has no session or extraction failed.
     */
    suspend fun frameAt(positionMs: Long): Bitmap? {
        val current = extractor ?: return null
        return current.getFrame(positionMs).awaitOrNull()?.bitmap
    }

    /** Close the decoding session, freeing its decoder and GL context. */
    fun release() {
        extractor?.let { open ->
            try {
                open.close()
            } catch (_: Exception) {
                // Nothing useful to do — the session is being discarded either way.
            }
        }
        extractor = null
        preparedUri = null
    }
}

/**
 * Await a [ListenableFuture], mapping failures to null.
 *
 * Extraction failing (unsupported codec, DRM, a transient decoder error) must not take
 * the scrub gesture down with it — the caller falls back to seeking the player.
 */
private suspend fun <T> ListenableFuture<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resume(null)
                } catch (e: InterruptedException) {
                    continuation.resumeWithException(e)
                } catch (e: java.util.concurrent.CancellationException) {
                    continuation.cancel(e)
                }
            },
            Executor { it.run() }
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
