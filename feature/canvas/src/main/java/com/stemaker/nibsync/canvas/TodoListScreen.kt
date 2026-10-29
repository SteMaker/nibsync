package com.stemaker.nibsync.canvas

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.library.OpenTodo
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.text.TextBoxRenderer

object TodoListTags {
    const val SCREEN = "todo-screen"
    const val LIST = "todo-list"
    const val EMPTY = "todo-list-empty"
    const val UNDO = "todo-list-undo"
    fun item(taskId: StrokeId) = "todo-item-${taskId.value}"
}

/** A to-do just ticked off in the list, for undoing it. */
class TickedTodo(val todo: OpenTodo)

/** Size of the to-dos in the list, in dp per millimetre. */
private const val LIST_DP_PER_MM = 3.2f

/** How long the "done – undo" note stays. */
private const val UNDO_MS = 5000L

/**
 * The generated page of all open to-dos, grouped by the document they are in, side by side as
 * far as the width allows. Tapping the circle ticks one off (in its document); tapping
 * elsewhere opens the document.
 */
@Composable
fun TodoListScreen(
    todos: List<OpenTodo>?,
    onMenu: () -> Unit,
    onTick: (OpenTodo) -> Unit,
    onOpen: (OpenTodo) -> Unit,
    ticked: TickedTodo?,
    onUndo: (TickedTodo) -> Unit,
    onUndoGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer = remember { TodoRenderer(TextBoxRenderer(context)) }
    val density = LocalDensity.current.density
    Box(modifier.fillMaxSize().testTag(TodoListTags.SCREEN)) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMenu, modifier = Modifier.testTag(NoteScreenTags.MENU)) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.library_menu))
                    }
                    Text(stringResource(R.string.open_todos), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                    if (!todos.isNullOrEmpty()) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(start = 12.dp)) {
                            Text(todos.size.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            when {
                todos == null -> Unit
                todos.isEmpty() -> Column(
                    Modifier.fillMaxSize().testTag(TodoListTags.EMPTY),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(72.dp))
                    Text(stringResource(R.string.all_done), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        stringResource(R.string.no_open_todos),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize().testTag(TodoListTags.LIST), contentPadding = PaddingValues(24.dp)) {
                    // One block per document, in library order.
                    val groups = todos.groupBy { it.document }.values.toList()
                    items(groups, key = { it.first().document.value }, contentType = { "document" }) { group ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(group.first().path.joinToString(" › "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(" · ${group.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            FlowRow(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (todo in group) TodoCard(todo, renderer, density, onTick, onOpen)
                            }
                        }
                    }
                }
            }
        }
        ticked?.let { t ->
            LaunchedEffect(t) {
                kotlinx.coroutines.delay(UNDO_MS)
                onUndoGone()
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.todo_done), color = MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton(onClick = { onUndo(t) }, modifier = Modifier.testTag(TodoListTags.UNDO)) {
                        Text(stringResource(R.string.undo), color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

/** One to-do as on its page, scaled to the list. */
@Composable
private fun TodoCard(todo: OpenTodo, renderer: TodoRenderer, density: Float, onTick: (OpenTodo) -> Unit, onOpen: (OpenTodo) -> Unit) {
    val item = todo.item
    val k = LIST_DP_PER_MM * density
    Canvas(
        Modifier
            .size((item.widthMm * LIST_DP_PER_MM).dp, (item.heightMm * LIST_DP_PER_MM).dp)
            .testTag(TodoListTags.item(item.taskId))
            .pointerInput(item) {
                detectTapGestures { p ->
                    val x = p.x / k + item.x
                    val y = p.y / k + item.y
                    val c = item.checkBounds
                    if (x <= c.right + 2f && y >= c.top - 2f && y <= c.bottom + 2f) onTick(todo) else onOpen(todo)
                }
            },
    ) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            val matrix = Matrix().apply {
                setScale(k, k)
                preTranslate(-item.x, -item.y)
            }
            native.concat(matrix)
            renderer.draw(native, item, done = false, documentToView = matrix)
            native.restoreToCount(save)
        }
    }
}
