package com.shangjin.frameecho.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.shangjin.frameecho.app.intent.ClaimedInboundIntent
import com.shangjin.frameecho.app.intent.InboundVideoEvent
import com.shangjin.frameecho.app.intent.InboundVideoIntentProcessor
import com.shangjin.frameecho.app.intent.InboundVideoResult
import com.shangjin.frameecho.app.ui.FrameEchoNavHost
import com.shangjin.frameecho.app.ui.theme.FrameEchoTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main entry point for FrameEcho.
 */
class MainActivity : ComponentActivity() {

    private val inboundProcessor = InboundVideoIntentProcessor()
    private val _inboundVideoEvent = MutableStateFlow<InboundVideoEvent?>(null)
    private val _isResolvingInboundVideo = MutableStateFlow(false)
    private val nextInboundEventId = AtomicLong(1L)
    private var latestValidationEventId: Long = 0L
    private var initialIntentHandled: Boolean = false
    private var activeValidationJob: Job? = null

    private val newIntentListener = Consumer<Intent> { newIntent ->
        setIntent(newIntent)
        dispatchInboundIntent(newIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        addOnNewIntentListener(newIntentListener)

        initialIntentHandled = savedInstanceState?.getBoolean(
            InboundVideoIntentProcessor.STATE_KEY_INITIAL_INTENT_HANDLED,
            false
        ) == true
        if (!initialIntentHandled) {
            initialIntentHandled = true
            dispatchInboundIntent(intent)
        }

        setContent {
            val inboundEvent by _inboundVideoEvent.collectAsStateWithLifecycle()
            val isResolvingInboundVideo by _isResolvingInboundVideo.collectAsStateWithLifecycle()
            FrameEchoTheme {
                FrameEchoNavHost(
                    inboundVideoEvent = inboundEvent,
                    isResolvingInboundVideo = isResolvingInboundVideo,
                    onInboundVideoEventConsumed = { consumedId ->
                        _inboundVideoEvent.update { current ->
                            if (current?.id == consumedId) null else current
                        }
                    },
                    onExitExternalSession = { finish() }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            InboundVideoIntentProcessor.STATE_KEY_INITIAL_INTENT_HANDLED,
            initialIntentHandled
        )
    }

    override fun onDestroy() {
        removeOnNewIntentListener(newIntentListener)
        super.onDestroy()
    }

    private fun dispatchInboundIntent(targetIntent: Intent?) {
        val claimed = inboundProcessor.claimIntent(applicationContext, targetIntent)
        if (claimed is ClaimedInboundIntent.Ignored) return

        activeValidationJob?.cancel()
        val eventId = nextInboundEventId.getAndIncrement()
        latestValidationEventId = eventId
        _isResolvingInboundVideo.value = true

        activeValidationJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    inboundProcessor.validateClaimed(applicationContext, claimed)
                }
                when (result) {
                    InboundVideoResult.Ignored -> Unit
                    is InboundVideoResult.Valid -> {
                        _inboundVideoEvent.value = InboundVideoEvent.OpenVideo(
                            id = eventId,
                            uri = result.uri
                        )
                    }
                    is InboundVideoResult.Invalid -> {
                        _inboundVideoEvent.value = InboundVideoEvent.Error(
                            id = eventId,
                            error = result.error
                        )
                    }
                }
            } finally {
                if (latestValidationEventId == eventId) {
                    _isResolvingInboundVideo.value = false
                }
            }
        }
    }
}
