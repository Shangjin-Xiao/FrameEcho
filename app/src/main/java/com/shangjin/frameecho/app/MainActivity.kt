package com.shangjin.frameecho.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shangjin.frameecho.app.ui.FrameEchoNavHost
import com.shangjin.frameecho.app.ui.theme.FrameEchoTheme

/**
 * Main entry point for FrameEcho.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FrameEchoTheme {
                FrameEchoNavHost()
            }
        }
    }
}
