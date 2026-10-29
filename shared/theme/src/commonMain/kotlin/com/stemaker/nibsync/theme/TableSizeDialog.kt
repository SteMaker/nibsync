package com.stemaker.nibsync.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tags of [TableSizeDialog]. */
object TableSizeTags {
    const val DIALOG = "table-size"
    fun cell(rows: Int, columns: Int) = "table-size-$rows-$columns"
    const val HEADER = "table-size-header"
    const val CONFIRM = "table-size-confirm"
}

/**
 * Choosing the size of a new table on a grid: a tap on a square picks that many rows and
 * columns (shown highlighted, "rows × columns"), then confirm. Labels come from the app.
 */
@Composable
fun TableSizeDialog(
    title: String,
    headerLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (rows: Int, columns: Int, header: Boolean) -> Unit,
) {
    var rows by remember { mutableIntStateOf(3) }
    var columns by remember { mutableIntStateOf(3) }
    var header by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(TableSizeTags.DIALOG)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 1..MAX) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 1..MAX) {
                                val inside = r <= rows && c <= columns
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .background(if (inside) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(4.dp))
                                        .border(1.dp, if (inside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                                        .clickable { rows = r; columns = c }
                                        .testTag(TableSizeTags.cell(r, c)),
                                )
                            }
                        }
                    }
                }
                Text("$rows × $columns", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { header = !header }) {
                    Checkbox(checked = header, onCheckedChange = { header = it }, modifier = Modifier.testTag(TableSizeTags.HEADER))
                    Text(headerLabel)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(rows, columns, header) }, modifier = Modifier.testTag(TableSizeTags.CONFIRM)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

private const val MAX = 8
