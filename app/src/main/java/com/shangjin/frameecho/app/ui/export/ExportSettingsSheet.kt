package com.shangjin.frameecho.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.shangjin.frameecho.R
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.HdrToneMapStrategy

/**
 * M3 Modal Bottom Sheet for export settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSettingsSheet(
    config: ExportConfig,
    onConfigChange: (ExportConfig) -> Unit,
    rememberQuickSettings: Boolean,
    onRememberQuickSettingsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    customExportTreeUri: Uri? = null,
    onPickCustomFolder: (() -> Unit)? = null,
    onClearCustomFolder: (() -> Unit)? = null,
    isHdrContent: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.export_settings),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Format section ──
            SettingSectionHeader(
                icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                title = stringResource(R.string.format)
            )
            Spacer(modifier = Modifier.height(12.dp))
            FormatSelector(
                selected = config.format,
                onSelect = { onConfigChange(config.copy(format = it)) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (config.format) {
                    ExportFormat.JPEG -> stringResource(R.string.format_desc_jpeg)
                    ExportFormat.PNG -> stringResource(R.string.format_desc_png)
                    ExportFormat.WEBP -> stringResource(R.string.format_desc_webp)
                    ExportFormat.HEIF -> stringResource(R.string.format_desc_heif)
                    ExportFormat.AVIF -> stringResource(R.string.format_desc_avif)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionDivider()

            // ── File name section ──
            SettingSectionHeader(
                icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                title = stringResource(R.string.custom_file_name)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Show current effective name
            Text(
                text = stringResource(R.string.current_name_format,
                    config.customFileName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.default_name_example)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Preset naming patterns
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    null to stringResource(R.string.name_auto),
                    "FrameEcho" to "FrameEcho",
                    "IMG" to "IMG",
                    "Screenshot" to "Screenshot"
                )
                presets.forEach { (value, label) ->
                    val isSelected = (value == null && config.customFileName.isNullOrBlank()) ||
                        (value != null && config.customFileName == value)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onConfigChange(config.copy(customFileName = value)) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Custom input
            OutlinedTextField(
                value = config.customFileName.orEmpty(),
                onValueChange = { raw ->
                    // Keep UI updates within model constraints to avoid runtime exceptions.
                    onConfigChange(config.copy(customFileName = raw.take(80)))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.custom_file_name_hint)) },
                placeholder = { Text(stringResource(R.string.default_name_example)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.char_count_format, config.customFileName?.length ?: 0, 80),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            )

            SectionDivider()

            // ── Default export location ──
            SettingSectionHeader(
                icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                title = stringResource(R.string.default_export_location)
            )
            if (customExportTreeUri != null) {
                // Show custom folder is active
                Text(
                    text = stringResource(R.string.custom_folder_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            ExportLocationSelector(
                selected = config.exportDirectory,
                onSelect = {
                    onClearCustomFolder?.invoke()
                    onConfigChange(config.copy(exportDirectory = it))
                },
                isCustomActive = customExportTreeUri != null,
                onPickCustomFolder = onPickCustomFolder,
                onClearCustomFolder = onClearCustomFolder
            )

            SectionDivider()

            // ── Quality section ──
            SettingSectionHeader(
                icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
                title = if (config.format == ExportFormat.PNG)
                    stringResource(R.string.quality)
                else
                    "${stringResource(R.string.quality)}: ${config.quality}%"
            )
            if (config.format == ExportFormat.PNG) {
                Text(
                    text = stringResource(R.string.quality_not_applicable_png),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Slider(
                    value = config.quality.toFloat(),
                    onValueChange = { onConfigChange(config.copy(quality = it.roundToInt().coerceIn(1, 100))) },
                    valueRange = 1f..100f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = stringResource(R.string.quality_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Motion photo duration (only when enabled) ──
            if (config.motionPhoto) {
                SectionDivider()
                SettingSectionHeader(
                    icon = { Icon(Icons.Outlined.MotionPhotosOn, contentDescription = null) },
                    title = "${stringResource(R.string.motion_duration)}: ${"%.1f".format(config.totalMotionDurationS)}s"
                )
                Slider(
                    value = config.motionDurationBeforeS,
                    onValueChange = {
                        onConfigChange(config.copy(motionDurationBeforeS = it, motionDurationAfterS = it))
                    },
                    valueRange = 0.5f..5f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            SectionDivider()

            // ── Preserve metadata ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = config.preserveMetadata,
                        role = Role.Switch,
                        onValueChange = { onConfigChange(config.copy(preserveMetadata = it)) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = stringResource(R.string.preserve_metadata),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = config.preserveMetadata,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                )
            }

            SectionDivider()

            // ── Remember quick toggles ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = rememberQuickSettings,
                        role = Role.Switch,
                        onValueChange = onRememberQuickSettingsChange
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remember_quick_settings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.remember_quick_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rememberQuickSettings,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                )
            }

            // ── HDR handling (only for HDR content) ──
            if (isHdrContent) {
                SectionDivider()
                SettingSectionHeader(
                    icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
                    title = stringResource(R.string.hdr_handling)
                )
                Spacer(modifier = Modifier.height(12.dp))
                HdrStrategySelector(
                    selected = config.hdrToneMap,
                    onSelect = { onConfigChange(config.copy(hdrToneMap = it)) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (config.hdrToneMap) {
                        HdrToneMapStrategy.AUTO -> stringResource(R.string.hdr_desc_auto)
                        HdrToneMapStrategy.FORCE_SDR -> stringResource(R.string.hdr_desc_sdr)
                        HdrToneMapStrategy.PRESERVE_HDR -> stringResource(R.string.hdr_desc_hdr)
                        HdrToneMapStrategy.SYSTEM -> stringResource(R.string.hdr_desc_system)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportLocationSelector(
    selected: ExportDirectory,
    onSelect: (ExportDirectory) -> Unit,
    isCustomActive: Boolean = false,
    onPickCustomFolder: (() -> Unit)? = null,
    onClearCustomFolder: (() -> Unit)? = null
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Custom folder option
        if (onPickCustomFolder != null) {
            FilterChip(
                selected = isCustomActive,
                onClick = {
                    if (isCustomActive) {
                        onClearCustomFolder?.invoke()
                    } else {
                        onPickCustomFolder()
                    }
                },
                label = { Text(stringResource(R.string.location_custom)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
        ExportDirectory.entries.forEach { dir ->
            val label = when (dir) {
                ExportDirectory.PICTURES_FRAMEECHO -> stringResource(R.string.location_pictures)
                ExportDirectory.DCIM_FRAMEECHO -> stringResource(R.string.location_dcim)
                ExportDirectory.MOVIES_FRAMEECHO -> stringResource(R.string.location_movies)
                ExportDirectory.PICTURES -> stringResource(R.string.location_pictures_root)
                ExportDirectory.DCIM -> stringResource(R.string.location_dcim_root)
                ExportDirectory.MOVIES -> stringResource(R.string.location_movies_root)
            }
            FilterChip(
                selected = dir == selected && !isCustomActive,
                onClick = { onSelect(dir) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun SettingSectionHeader(
    icon: @Composable () -> Unit,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            icon()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatSelector(
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExportFormat.entries.forEach { format ->
            val isSelected = format == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(format) },
                label = { Text(format.extension.uppercase()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HdrStrategySelector(
    selected: HdrToneMapStrategy,
    onSelect: (HdrToneMapStrategy) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HdrToneMapStrategy.entries.forEach { strategy ->
            val isSelected = strategy == selected
            val label = when (strategy) {
                HdrToneMapStrategy.AUTO -> stringResource(R.string.hdr_auto)
                HdrToneMapStrategy.FORCE_SDR -> stringResource(R.string.hdr_sdr)
                HdrToneMapStrategy.PRESERVE_HDR -> stringResource(R.string.hdr_hdr)
                HdrToneMapStrategy.SYSTEM -> stringResource(R.string.hdr_system)
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(strategy) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            )
        }
    }
}
