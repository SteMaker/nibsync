package com.stemaker.nibsync.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Test tags of [AppearanceSettings]. */
object AppearanceTags {
    fun mode(mode: ThemeMode) = "theme-mode-${mode.name}"
    fun palette(palette: NibSyncPalette) = "theme-palette-${palette.name}"
}

/**
 * Light / dark / system, and the colour scheme as swatches, for the settings of both apps.
 * The labels come from the app's own string resources.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    palette: NibSyncPalette,
    onPalette: (NibSyncPalette) -> Unit,
    modeLabel: (ThemeMode) -> String,
    paletteLabel: (NibSyncPalette) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SingleChoiceSegmentedButtonRow {
            ThemeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    modifier = Modifier.testTag(AppearanceTags.mode(m)),
                ) { Text(modeLabel(m)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in NibSyncPalette.entries) {
                val selected = p == palette
                val light = p.scheme(dark = false)
                Column(
                    Modifier
                        .width(84.dp)
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onPalette(p) }
                        .padding(8.dp)
                        .semantics { this.selected = selected }
                        .testTag(AppearanceTags.palette(p)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The scheme's surface with its accent in the middle.
                    Box(Modifier.size(44.dp).background(light.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(24.dp).background(light.primary, CircleShape))
                    }
                    Text(
                        paletteLabel(p),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
