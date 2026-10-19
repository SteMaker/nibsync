package com.stemaker.nibsync.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.library.OpenTodo
import com.stemaker.nibsync.model.StrokeId
import java.io.File

object TodoListTags {
    const val LIST = "todo-list"
    const val EMPTY = "todo-list-empty"
    const val UNDO = "todo-list-undo"
    fun item(taskId: StrokeId) = "todo-item-${taskId.value}"
}

/** Pixels per millimetre the to-dos are shown at in the list. */
private const val LIST_PX_PER_MM = 4f

/** How long the "done – undo" note stays. */
private const val UNDO_MS = 5000L

/** A to-do ticked off in the list, for undoing it. */
class TickedTodo(val todo: OpenTodo)

/**
 * The generated page of all open to-dos, one below the other with the document they are in.
 * A click on the circle ticks one off (in its document); a click elsewhere opens the document.
 */
@Composable
fun TodoListPane(
    todos: List<OpenTodo>?,
    strings: Strings,
    onTick: (OpenTodo) -> Unit,
    onOpen: (OpenTodo) -> Unit,
    ticked: TickedTodo?,
    onUndo: (TickedTodo) -> Unit,
    onUndoGone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val painter = remember { PagePainter(DesktopTextLayout(), MediaCache(scope) { File("") }) }
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(strings.openTodos, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp))
            when {
                todos == null -> Unit
                todos.isEmpty() -> Text(strings.noOpenTodos, Modifier.padding(24.dp).testTag(TodoListTags.EMPTY))
                else -> LazyColumn(Modifier.fillMaxSize().testTag(TodoListTags.LIST), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    items(todos, key = { it.item.taskId.value }) { todo ->
                        val item = todo.item
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(todo.path.joinToString(" › "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val k = LIST_PX_PER_MM
                            Canvas(
                                Modifier
                                    .padding(top = 2.dp)
                                    .size((item.widthMm * k / density).dp, (item.heightMm * k / density).dp)
                                    .testTag(TodoListTags.item(item.taskId))
                                    .pointerInput(item) {
                                        detectTapGestures { p ->
                                            val x = p.x / k + item.x
                                            val y = p.y / k + item.y
                                            val c = item.checkBounds
                                            if (x <= c.right + 1.5f && y >= c.top - 1.5f && y <= c.bottom + 1.5f) onTick(todo) else onOpen(todo)
                                        }
                                    },
                            ) {
                                scale(k, k, pivot = Offset.Zero) {
                                    translate(-item.x, -item.y) { painter.drawTodo(this, item, done = false) }
                                }
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
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.todoDone, color = MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton(onClick = { onUndo(t) }, modifier = Modifier.testTag(TodoListTags.UNDO)) {
                        Text(strings.undoShort, color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}
