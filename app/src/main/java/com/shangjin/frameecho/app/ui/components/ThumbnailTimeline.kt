package com.shangjin.frameecho.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lazy-loaded thumbnail timeline strip using LazyRow.
 *
 * Each thumbnail is loaded on demand when it becomes visible.
 * Resolution is downscaled (120px wide) to ensure silky-smooth scrolling.
 * Thumbnails span the full video duration for stability.
 * Auto-scroll smoothly follows the playback position.
 */
@Composable
fun ThumbnailTimeline(
    thumbnailCount: Int,
    currentPositionFraction: Float,
    getThumbnail: (index: Int) -> Bitmap?,
    requestThumbnail: (index: Int) -> Unit,
    onThumbnailClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val selectedIndex = if (thumbnailCount > 0) {
        ((thumbnailCount - 1) * currentPositionFraction).roundToInt().coerceIn(0, thumbnailCount - 1)
    } else {
        0
    }

    // Use rememberUpdatedState to ensure the latest lambdas are used
    // without triggering recomposition of the items block.
    val currentRequestThumbnail by rememberUpdatedState(requestThumbnail)
    val currentOnThumbnailClick by rememberUpdatedState(onThumbnailClick)

    BoxWithConstraints(modifier = modifier) {

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(thumbnailCount) { index ->
                // Wrap the lambdas in remember to provide stable instances to ThumbnailItem.
                // These will only be recreated if the index changes (which it doesn't for a given item).
                val requestLoad = remember(index) {
                    { currentRequestThumbnail(index) }
                }
                val onClick = remember(index) {
                    { currentOnThumbnailClick(index) }
                }

                ThumbnailItem(
                    index = index,
                    bitmap = getThumbnail(index),
                    isSelected = index == selectedIndex,
                    requestLoad = requestLoad,
                    onClick = onClick
                )
            }
        }

        // Auto-scroll to follow playback position smoothly.
        // Uses animateScrollToItem for natural visual movement.
        LaunchedEffect(selectedIndex, thumbnailCount) {
            if (!listState.isScrollInProgress && thumbnailCount > 0) {
                val targetIndex = selectedIndex
                // Center the target in the viewport by computing an offset
                val viewportWidth = listState.layoutInfo.viewportSize.width
                val itemWidth = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
                val offset = if (itemWidth > 0) {
                    ((viewportWidth - itemWidth) / 2).coerceAtLeast(0)
                } else 0
                val currentIndex = listState.firstVisibleItemIndex
                if (abs(targetIndex - currentIndex) <= 1) {
                    listState.scrollToItem(targetIndex.coerceAtLeast(0), -offset)
                } else {
                    listState.animateScrollToItem(targetIndex.coerceAtLeast(0), -offset)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    index: Int,
    bitmap: Bitmap?,
    isSelected: Boolean,
    requestLoad: () -> Unit,
    onClick: () -> Unit
) {
    // Request loading when this item becomes visible
    LaunchedEffect(index) {
        requestLoad()
    }

    Box(
        modifier = Modifier
            .width(60.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(R.string.capture_frame),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(60.dp)
                    .height(48.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}
