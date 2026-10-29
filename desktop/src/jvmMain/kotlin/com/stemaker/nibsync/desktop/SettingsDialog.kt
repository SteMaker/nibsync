package com.stemaker.nibsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.PenWidths
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncService

object SettingsTags {
    const val SCREEN = "settings"
    const val CLOSE = "settings-close"
    const val SHOW_TITLE = "settings-show-title"
    const val SHOW_CREATED = "settings-show-created"
    fun penWidth(family: BrushFamily, index: Int) = "settings-pen-width-${family.name}-$index"
    const val PEN_WIDTHS_RESET = "settings-pen-widths-reset"
}

/** Display choices of the settings dialog. */
class DisplaySettings(
    val showTitle: Boolean,
    val onShowTitle: (Boolean) -> Unit,
    val showCreated: Boolean,
    val onShowCreated: (Boolean) -> Unit,
    val penWidths: Map<BrushFamily, List<Float>> = PenWidths.DEFAULTS,
    val onPenWidths: (Map<BrushFamily, List<Float>>) -> Unit = {},
)

/** App settings, grouped in sections; new settings get their own section here. */
@Composable
fun SettingsDialog(
    sync: SyncController?,
    display: DisplaySettings,
    strings: Strings,
    openBrowser: (String) -> Boolean,
    cloudLogin: suspend (SyncService) -> com.stemaker.nibsync.sync.client.SyncConfig,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(min = 420.dp, max = 600.dp).testTag(SettingsTags.SCREEN)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.settings, style = MaterialTheme.typography.headlineSmall)
                if (sync != null) {
                    Text(strings.settingsSync, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    AccountSettings(sync, strings, openBrowser, cloudLogin)
                }
                Text(strings.settingsDisplay, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SwitchLine(strings.showTitle, display.showTitle, display.onShowTitle, SettingsTags.SHOW_TITLE)
                SwitchLine(strings.showCreated, display.showCreated, display.onShowCreated, SettingsTags.SHOW_CREATED)
                Text(strings.settingsPens, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                PenWidthSettings(strings, display.penWidths, display.onPenWidths)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag(SettingsTags.CLOSE)) { Text(strings.close) }
                }
            }
        }
    }
}

/** Three widths per pen; a valid entry applies at once, an invalid one is marked and ignored. */
@Composable
private fun PenWidthSettings(strings: Strings, widths: Map<BrushFamily, List<Float>>, onChange: (Map<BrushFamily, List<Float>>) -> Unit) {
    Text(strings.penWidthsHint, style = MaterialTheme.typography.bodyMedium)
    // Reset recreates the fields, so they show the defaults.
    var resets by remember { mutableIntStateOf(0) }
    key(resets) {
        for (family in BrushFamily.entries) {
            val current = widths[family] ?: PenWidths.DEFAULTS.getValue(family)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.penName(family), Modifier.weight(1f))
                for (index in 0 until PenWidths.COUNT) {
                    var text by remember(family, index) { mutableStateOf(PenWidths.format(current[index])) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { new ->
                            text = new
                            val parsed = PenWidths.parse(family, new) ?: return@OutlinedTextField
                            val list = (widths[family] ?: PenWidths.DEFAULTS.getValue(family)).toMutableList().also { it[index] = parsed }
                            onChange(widths + (family to list))
                        },
                        label = { Text(strings.penWidthNames[index]) },
                        suffix = { Text("mm") },
                        isError = PenWidths.parse(family, text) == null,
                        singleLine = true,
                        modifier = Modifier.width(100.dp).testTag(SettingsTags.penWidth(family, index)),
                    )
                }
                PenWidthPreview(family, widths[family] ?: PenWidths.DEFAULTS.getValue(family))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onChange(PenWidths.DEFAULTS); resets++ }, enabled = widths != PenWidths.DEFAULTS, modifier = Modifier.testTag(SettingsTags.PEN_WIDTHS_RESET)) {
            Text(strings.penWidthsReset)
        }
    }
}

/** The three widths as short strokes at about their real size on the screen. */
@Composable
private fun PenWidthPreview(family: BrushFamily, widths: List<Float>) {
    val ink = if (family == BrushFamily.HIGHLIGHTER) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(80.dp, 48.dp)) {
        // On the PC a dp is about 1/96 inch.
        val pxPerMm = density * 96f / 25.4f
        widths.forEachIndexed { i, mm ->
            val y = size.height * (i + 0.5f) / widths.size
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(4f, y)
                cubicTo(size.width * 0.35f, y - 5f, size.width * 0.65f, y + 5f, size.width - 4f, y)
            }
            drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = (mm * pxPerMm).coerceAtLeast(1f), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
