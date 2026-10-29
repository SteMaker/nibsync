package com.stemaker.nibsync.app

import com.stemaker.nibsync.theme.AppearanceSettings
import com.stemaker.nibsync.theme.NibSyncPalette
import com.stemaker.nibsync.theme.ThemeMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.PenWidths
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stemaker.nibsync.app.sync.AccountSettings
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncService

object SettingsTags {
    const val SCREEN = "settings"
    const val CLOSE = "settings-close"
    const val PAGE_BREAKS = "settings-page-breaks"
    const val DRAW_WITH_FINGER = "settings-draw-with-finger"
    const val SHOW_TITLE = "settings-show-title"
    const val SHOW_CREATED = "settings-show-created"
    fun penWidth(family: BrushFamily, index: Int) = "settings-pen-width-${family.name}-$index"
    const val PEN_WIDTHS_RESET = "settings-pen-widths-reset"
}

/** App settings, grouped in sections; new settings get their own section or line here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sync: SyncController,
    cloudLogin: suspend (SyncService) -> com.stemaker.nibsync.sync.client.SyncConfig,
    showPageBreaks: Boolean,
    onShowPageBreaks: (Boolean) -> Unit,
    drawWithFinger: Boolean,
    onDrawWithFinger: (Boolean) -> Unit,
    showTitle: Boolean,
    onShowTitle: (Boolean) -> Unit,
    showCreated: Boolean,
    onShowCreated: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    palette: NibSyncPalette,
    onPalette: (NibSyncPalette) -> Unit,
    penWidths: Map<BrushFamily, List<Float>>,
    onPenWidths: (Map<BrushFamily, List<Float>>) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag(SettingsTags.SCREEN),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Section(stringResource(R.string.settings_sync)) { AccountSettings(sync, cloudLogin) }
                    HorizontalDivider()
                    Section(stringResource(R.string.settings_appearance)) {
                        val modes = mapOf(
                            ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                            ThemeMode.LIGHT to stringResource(R.string.theme_light),
                            ThemeMode.DARK to stringResource(R.string.theme_dark),
                        )
                        val names = NibSyncPalette.entries.associateWith { stringResource(paletteName(it)) }
                        AppearanceSettings(themeMode, onThemeMode, palette, onPalette, { modes.getValue(it) }, { names.getValue(it) })
                    }
                    HorizontalDivider()
                    Section(stringResource(R.string.settings_display)) {
                        SwitchLine(stringResource(R.string.page_breaks), showPageBreaks, onShowPageBreaks, SettingsTags.PAGE_BREAKS)
                        SwitchLine(stringResource(R.string.draw_with_finger), drawWithFinger, onDrawWithFinger, SettingsTags.DRAW_WITH_FINGER)
                        SwitchLine(stringResource(R.string.show_title), showTitle, onShowTitle, SettingsTags.SHOW_TITLE)
                        SwitchLine(stringResource(R.string.show_created), showCreated, onShowCreated, SettingsTags.SHOW_CREATED)
                    }
                    HorizontalDivider()
                    Section(stringResource(R.string.settings_pens)) { PenWidthSettings(penWidths, onPenWidths) }
                }
            }
        }
    }
}

/** Three widths per pen; a valid entry applies at once, an invalid one is marked and ignored. */
@Composable
private fun PenWidthSettings(widths: Map<BrushFamily, List<Float>>, onChange: (Map<BrushFamily, List<Float>>) -> Unit) {
    Text(stringResource(R.string.pen_widths_hint), style = MaterialTheme.typography.bodyMedium)
    // Reset recreates the fields, so they show the defaults.
    var resets by remember { mutableIntStateOf(0) }
    key(resets) {
        for (family in BrushFamily.entries) {
            val label = when (family) {
                BrushFamily.BALLPOINT -> R.string.pen_widths_ballpoint
                BrushFamily.FOUNTAIN_PEN -> R.string.pen_widths_fountain
                BrushFamily.HIGHLIGHTER -> R.string.pen_widths_highlighter
            }
            val current = widths[family] ?: PenWidths.DEFAULTS.getValue(family)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), Modifier.weight(1f))
                val names = listOf(R.string.pen_width_fine, R.string.pen_width_medium, R.string.pen_width_broad)
                for (index in 0 until PenWidths.COUNT) {
                    var text by remember(family, index) { mutableStateOf(PenWidths.format(current[index])) }
                    val value = PenWidths.parse(family, text)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { new ->
                            text = new
                            val parsed = PenWidths.parse(family, new) ?: return@OutlinedTextField
                            val list = (widths[family] ?: PenWidths.DEFAULTS.getValue(family)).toMutableList().also { it[index] = parsed }
                            onChange(widths + (family to list))
                        },
                        label = { Text(stringResource(names[index])) },
                        suffix = { Text("mm") },
                        isError = value == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(110.dp).testTag(SettingsTags.penWidth(family, index)),
                    )
                }
                PenWidthPreview(family, widths[family] ?: PenWidths.DEFAULTS.getValue(family))
            }
        }
    }
    TextButton(onClick = { onChange(PenWidths.DEFAULTS); resets++ }, enabled = widths != PenWidths.DEFAULTS, modifier = Modifier.testTag(SettingsTags.PEN_WIDTHS_RESET)) {
        Text(stringResource(R.string.pen_widths_reset))
    }
}

private fun paletteName(p: NibSyncPalette) = when (p) {
    NibSyncPalette.GREY -> R.string.palette_grey
    NibSyncPalette.SLATE -> R.string.palette_slate
    NibSyncPalette.PETROL -> R.string.palette_petrol
    NibSyncPalette.SAGE -> R.string.palette_sage
    NibSyncPalette.WARM -> R.string.palette_warm
    NibSyncPalette.INK -> R.string.palette_ink
    NibSyncPalette.BLUE -> R.string.palette_blue
    NibSyncPalette.RED -> R.string.palette_red
}

/** The three widths as short strokes at their real size on the screen. */
@Composable
private fun PenWidthPreview(family: BrushFamily, widths: List<Float>) {
    val ink = if (family == BrushFamily.HIGHLIGHTER) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(96.dp, 48.dp)) {
        // 160 dp are one inch.
        val pxPerMm = density * 160f / 25.4f
        val rows = widths.size
        widths.forEachIndexed { i, mm ->
            val y = size.height * (i + 0.5f) / rows
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(4f, y)
                cubicTo(size.width * 0.35f, y - 6f, size.width * 0.65f, y + 6f, size.width - 4f, y)
            }
            drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = (mm * pxPerMm).coerceAtLeast(1f), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    )
}

