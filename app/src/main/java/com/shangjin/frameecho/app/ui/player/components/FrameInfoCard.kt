package com.shangjin.frameecho.app.ui.player.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper
import com.shangjin.frameecho.core.model.CapturedFrame

/**
 * Persistent compact card displaying the most recently captured frame's thumbnail preview,
 * dimensions, HDR / Motion Photo badges, and direct system Share & external View actions.
 */
@Composable
fun FrameInfoCard(
    capturedFrame: CapturedFrame?,
    modifier: Modifier = Modifier,
    previewBitmap: Bitmap? = null,
    isMotionPhoto: Boolean = false,
    isExporting: Boolean = false,
    canOpenOrShare: Boolean = false,
    onShareClick: () -> Unit = {},
    onViewClick: () -> Unit = {}
) {
    // Evaluate localized accessibility & tooltip labels outside Modifier.semantics closures
    // per .jules/palette.md guidelines.
    val viewTooltipLabel = stringResource(R.string.snackbar_action_view)
    val shareTooltipLabel = stringResource(R.string.snackbar_action_share)
    val viewFrameDesc = stringResource(R.string.view_captured_frame)
    val shareFrameDesc = stringResource(R.string.share_captured_frame)
    val thumbnailDesc = stringResource(R.string.captured_frame_thumbnail)

    AnimatedVisibility(
        visible = capturedFrame != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        capturedFrame?.let { frame ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: rounded thumbnail preview (tappable to view externally once exported)
                    if (canOpenOrShare) {
                        TooltipWrapper(label = viewTooltipLabel) {
                            FrameThumbnailBox(
                                previewBitmap = previewBitmap,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = viewTooltipLabel,
                                        onClick = onViewClick
                                    )
                                    .semantics { contentDescription = thumbnailDesc }
                            )
                        }
                    } else {
                        FrameThumbnailBox(
                            previewBitmap = previewBitmap,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .semantics { contentDescription = thumbnailDesc }
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Middle: resolution + HDR / Motion Photo / exporting status in a compact 1-2 line block
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.captured_info, frame.width, frame.height),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isExporting || frame.colorSpace.isHdr || isMotionPhoto) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isExporting) {
                                    Text(
                                        text = stringResource(R.string.exporting),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (frame.colorSpace.isHdr) {
                                    Text(
                                        text = stringResource(
                                            R.string.hdr_info,
                                            frame.colorSpace.type.name
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isMotionPhoto) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.MotionPhotosOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.motion_photo_badge),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: independent View & Share action buttons
                    if (isExporting || canOpenOrShare) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TooltipWrapper(label = viewTooltipLabel) {
                                IconButton(
                                    onClick = onViewClick,
                                    enabled = canOpenOrShare,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .semantics { contentDescription = viewFrameDesc }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            TooltipWrapper(label = shareTooltipLabel) {
                                FilledTonalIconButton(
                                    onClick = onShareClick,
                                    enabled = canOpenOrShare,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .semantics { contentDescription = shareFrameDesc }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameThumbnailBox(
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            val imageBitmap = remember(previewBitmap) { previewBitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
