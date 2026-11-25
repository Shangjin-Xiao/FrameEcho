package com.shangjin.frameecho.app.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R

/**
 * Confirmation dialog displayed when an external video Intent arrives while a capture/export
 * operation is still running, protecting in-flight exports from silent cancellation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingExternalVideoDialog(
    isExportStillRunning: Boolean,
    onSwitchNow: () -> Unit,
    onSwitchAfterExport: () -> Unit,
    onKeepCurrent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepCurrent,
        title = {
            Text(
                text = stringResource(R.string.external_video_export_busy_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = stringResource(R.string.external_video_export_busy_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isExportStillRunning) {
                    TextButton(onClick = onSwitchAfterExport) {
                        Text(stringResource(R.string.external_video_switch_after_export))
                    }
                }
                TextButton(onClick = onSwitchNow) {
                    Text(stringResource(R.string.external_video_switch_now))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepCurrent) {
                Text(stringResource(R.string.external_video_keep_current))
            }
        }
    )
}
