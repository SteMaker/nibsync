package de.nibsync.background.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.nibsync.model.BackgroundKind
import de.nibsync.model.BackgroundPattern
import de.nibsync.model.BackgroundSpec

/** Test tags used by UI tests. */
object BackgroundTags {
    fun kind(kind: BackgroundKind) = "background-${kind.name}"
    fun spacing(mm: Float) = "background-spacing-${mm.toInt()}"
}

/** Choice of paper pattern and line spacing. Picking a kind resets the spacing to its usual one. */
@Composable
fun BackgroundPicker(value: BackgroundSpec, onChange: (BackgroundSpec) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.background_paper))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in BackgroundKind.entries) {
                FilterChip(
                    selected = value.kind == kind,
                    onClick = { if (value.kind != kind) onChange(BackgroundSpec.of(kind)) },
                    label = { Text(stringResource(kindLabel(kind))) },
                    leadingIcon = { BackgroundSwatch(BackgroundSpec.of(kind), Modifier.size(18.dp)) },
                    modifier = Modifier.testTag(BackgroundTags.kind(kind)),
                )
            }
        }
        if (value.kind != BackgroundKind.BLANK) {
            Text(stringResource(R.string.background_spacing), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (mm in BackgroundSpec.SPACINGS_MM) {
                    FilterChip(
                        selected = value.spacingMm == mm,
                        onClick = { onChange(value.copy(spacingMm = mm)) },
                        label = { Text(stringResource(R.string.background_spacing_mm, mm.toInt().toString())) },
                        modifier = Modifier.testTag(BackgroundTags.spacing(mm)),
                    )
                }
            }
        }
    }
}

/** Small preview of a pattern, showing [mmPerSwatch] millimetres of paper across. */
@Composable
fun BackgroundSwatch(spec: BackgroundSpec, modifier: Modifier = Modifier, mmPerSwatch: Float = 20f) {
    Canvas(modifier) {
        drawRect(Color.White)
        val scale = size.width / mmPerSwatch
        val rows = BackgroundPattern.rows(spec, 0f, size.height / scale)
        val columns = BackgroundPattern.columns(spec, mmPerSwatch)
        if (BackgroundPattern.isDotted(spec)) {
            for (y in rows) for (x in columns) drawCircle(PATTERN, radius = 1.2f, center = Offset(x * scale, y * scale))
        } else {
            for (y in rows) drawLine(PATTERN, Offset(0f, y * scale), Offset(size.width, y * scale), strokeWidth = 1.5f)
            for (x in columns) drawLine(PATTERN, Offset(x * scale, 0f), Offset(x * scale, size.height), strokeWidth = 1.5f)
        }
        drawRect(Color.Gray, style = Stroke(1f))
    }
}

private val PATTERN = Color(0xff7d9cc8)

private fun kindLabel(kind: BackgroundKind) = when (kind) {
    BackgroundKind.BLANK -> R.string.background_blank
    BackgroundKind.LINED -> R.string.background_lined
    BackgroundKind.GRID -> R.string.background_grid
    BackgroundKind.DOTTED -> R.string.background_dotted
}
