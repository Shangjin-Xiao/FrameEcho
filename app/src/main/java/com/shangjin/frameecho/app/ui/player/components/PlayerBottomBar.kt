package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper

/**
 * Bottom app bar for the player screen.
 *
 * Contains quick toggle buttons (mute, motion photo, metadata, format cycle)
 * and the main capture FAB.
 */
@Composable
fun PlayerBottomBar(
    isMuted: Boolean,
    motionPhotoEnabled: Boolean,
    preserveMetadata: Boolean,
    formatExtension: String,
    isCapturing: Boolean,
    isExporting: Boolean,
    onToggleMute: () -> Unit,
    onToggleMotionPhoto: () -> Unit,
    onToggleMetadata: () -> Unit,
    onCycleFormat: () -> Unit,
    onCapture: () -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        actions = {
            // Mute toggle
            TooltipWrapper(
                label = stringResource(if (isMuted) R.string.unmute else R.string.mute)
            ) {
                val muteDesc = stringResource(if (isMuted) R.string.unmute else R.string.mute)
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.semantics { contentDescription = muteDesc }
                ) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(
                            if (isMuted) R.string.unmute else R.string.mute
                        )
                    )
                }
            }
            // Motion photo toggle
            TooltipWrapper(
                label = stringResource(
                    if (motionPhotoEnabled) R.string.motion_photo_on else R.string.motion_photo_off
                )
            ) {
                val motionDesc = stringResource(if (motionPhotoEnabled) R.string.motion_photo_on else R.string.motion_photo_off)
                IconButton(
                    onClick = onToggleMotionPhoto,
                    modifier = Modifier.semantics { contentDescription = motionDesc },
                    colors = if (motionPhotoEnabled) {
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    }
                ) {
                    Icon(
                        if (motionPhotoEnabled) Icons.Filled.MotionPhotosOn
                        else Icons.Outlined.MotionPhotosOn,
                        contentDescription = stringResource(
                            if (motionPhotoEnabled) R.string.motion_photo_on
                            else R.string.motion_photo_off
                        )
                    )
                }
            }
            // Metadata toggle
            TooltipWrapper(
                label = stringResource(
                    if (preserveMetadata) R.string.metadata_on else R.string.metadata_off
                )
            ) {
                val metaDesc = stringResource(if (preserveMetadata) R.string.metadata_on else R.string.metadata_off)
                IconButton(
                    onClick = onToggleMetadata,
                    modifier = Modifier.semantics { contentDescription = metaDesc },
                    colors = if (preserveMetadata) {
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    }
                ) {
                    Icon(
                        if (preserveMetadata) Icons.Filled.Info
                        else Icons.Outlined.Info,
                        contentDescription = stringResource(
                            if (preserveMetadata) R.string.metadata_on
                            else R.string.metadata_off
                        )
                    )
                }
            }
            // Format quick-cycle
            val formatDesc = stringResource(R.string.format)
            TooltipWrapper(label = formatDesc) {
                FilledTonalIconButton(
                    onClick = onCycleFormat,
                    modifier = Modifier.semantics { contentDescription = formatDesc }
                ) {
                    Text(
                        text = formatExtension.uppercase(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        floatingActionButton = {
            TooltipWrapper(label = stringResource(R.string.capture_and_save)) {
                FloatingActionButton(
                    onClick = onCapture,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    if (isCapturing || isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.capture_and_save)
                        )
                    }
                }
            }
        }
    )
}
