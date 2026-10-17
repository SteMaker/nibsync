package de.nibsync.canvas

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nibsync.model.BrushFamily

/** Test tags used by UI tests. */
object NoteScreenTags {
    const val CANVAS = "note-canvas"
    const val TOOLBAR = "note-toolbar"
    fun pen(family: BrushFamily) = "pen-${family.name}"
    fun color(index: Int) = "color-$index"
    fun size(index: Int) = "size-$index"
    const val FINGER_TOGGLE = "finger-toggle"
}

@Composable
fun NoteScreen(viewModel: CanvasViewModel, modifier: Modifier = Modifier, onCanvasCreated: (NoteCanvasView) -> Unit = {}) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Toolbar(
            tools = tools,
            onPen = viewModel::selectPen,
            onColor = viewModel::selectColor,
            onSize = viewModel::selectSize,
            onFingerToggle = viewModel::setDrawWithFinger,
        )
        AndroidView(
            modifier = Modifier.fillMaxSize().clipToBounds().testTag(NoteScreenTags.CANVAS),
            factory = { context ->
                NoteCanvasView(context).also {
                    it.onStrokeDrafted = viewModel::commitStroke
                    it.onShapeRequested = viewModel::commitShape
                    onCanvasCreated(it)
                }
            },
            update = { view ->
                view.pageFormat = document.meta.format
                view.brush = tools.activeBrush
                view.drawWithFinger = tools.drawWithFinger
                view.setStrokes(document.strokes)
            },
        )
    }
}

@Composable
private fun Toolbar(
    tools: ToolState,
    onPen: (BrushFamily) -> Unit,
    onColor: (Int) -> Unit,
    onSize: (Float) -> Unit,
    onFingerToggle: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(NoteScreenTags.TOOLBAR)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (family in BrushFamily.entries) {
                val label = stringResource(penLabel(family))
                WithTooltip(label) {
                    FilledIconToggleButton(
                        checked = tools.activePen == family,
                        onCheckedChange = { onPen(family) },
                        modifier = Modifier.testTag(NoteScreenTags.pen(family)),
                    ) {
                        Icon(penIcon(family), contentDescription = label)
                    }
                }
            }
            Divider()
            val colors = PenPalette.colors(tools.activePen)
            colors.forEachIndexed { index, argb ->
                ColorSwatch(
                    argb = argb,
                    selected = tools.activeSettings.colorArgb == argb,
                    description = stringResource(R.string.color_n, index + 1),
                    modifier = Modifier.testTag(NoteScreenTags.color(index)),
                    onClick = { onColor(argb) },
                )
            }
            Divider()
            PenPalette.sizes(tools.activePen).forEachIndexed { index, mm ->
                SizeDot(
                    diameter = (8 + index * 6).dp,
                    selected = tools.activeSettings.sizeMm == mm,
                    description = stringResource(R.string.width_mm, mm),
                    modifier = Modifier.testTag(NoteScreenTags.size(index)),
                    onClick = { onSize(mm) },
                )
            }
            Divider()
            val fingerLabel = stringResource(R.string.draw_with_finger)
            WithTooltip(fingerLabel) {
                FilledIconToggleButton(
                    checked = tools.drawWithFinger,
                    onCheckedChange = onFingerToggle,
                    modifier = Modifier.testTag(NoteScreenTags.FINGER_TOGGLE),
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = fingerLabel)
                }
            }
        }
    }
}

/** Shows [text] on long press, so the meaning of an icon can be looked up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        content()
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.width(4.dp))
    VerticalDivider(Modifier.height(32.dp))
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, description: String, modifier: Modifier, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .size(40.dp)
            .border(3.dp, ring, CircleShape)
            .padding(6.dp)
            .background(Color(argb), CircleShape)
            .border(1.dp, Color(0x33000000), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    )
}

@Composable
private fun SizeDot(diameter: androidx.compose.ui.unit.Dp, selected: Boolean, description: String, modifier: Modifier, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .size(40.dp)
            .border(3.dp, ring, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(diameter).background(MaterialTheme.colorScheme.onSurface, CircleShape))
    }
}

private fun penIcon(family: BrushFamily): ImageVector = when (family) {
    BrushFamily.BALLPOINT -> Icons.Filled.Draw
    BrushFamily.FOUNTAIN_PEN -> Icons.Filled.HistoryEdu
    BrushFamily.HIGHLIGHTER -> Icons.Filled.BorderColor
}

private fun penLabel(family: BrushFamily): Int = when (family) {
    BrushFamily.BALLPOINT -> R.string.pen_ballpoint
    BrushFamily.FOUNTAIN_PEN -> R.string.pen_fountain
    BrushFamily.HIGHLIGHTER -> R.string.pen_highlighter
}
