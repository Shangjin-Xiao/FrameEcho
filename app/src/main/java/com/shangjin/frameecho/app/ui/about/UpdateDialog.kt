package com.shangjin.frameecho.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R

/**
 * Dialog shown when a new version is available.
 *
 * Offers three actions:
 * - **Go to Release**: Opens the GitHub release page in the browser.
 * - **Ignore This Time**: Dismisses the dialog for this session only.
 * - **Ignore Permanently**: Dismisses the dialog and adds this version to
 *   the permanently ignored list.
 *
 * @param releaseInfo The release information to display.
 * @param onGoToRelease Called when the user chooses to view the release.
 * @param onIgnoreThisTime Called when the user dismisses for this session.
 * @param onIgnorePermanently Called when the user wants to permanently ignore this version.
 */
@Composable
fun UpdateDialog(
    releaseInfo: ReleaseInfo,
    onGoToRelease: () -> Unit,
    onIgnoreThisTime: () -> Unit,
    onIgnorePermanently: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onIgnoreThisTime,
        title = {
            Text(
                text = stringResource(R.string.update_available),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.update_new_version, releaseInfo.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                if (releaseInfo.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_release_notes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = releaseInfo.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onGoToRelease) {
                Text(stringResource(R.string.update_go_to_release))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnorePermanently) {
                    Text(stringResource(R.string.update_ignore_permanently))
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onIgnoreThisTime) {
                    Text(stringResource(R.string.update_ignore_this_time))
                }
            }
        }
    )
}
