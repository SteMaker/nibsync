package de.nibsync.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.nibsync.library.LibraryNode
import de.nibsync.library.LibraryTree
import de.nibsync.library.NodeId

/** One visible line of the library tree. */
data class TreeRow(val node: LibraryNode, val depth: Int, val expanded: Boolean, val hasChildren: Boolean)

/** Lines to show: books and the content of every expanded folder, in tree order. */
fun treeRows(tree: LibraryTree, expanded: Set<NodeId>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun add(node: LibraryNode, depth: Int) {
        val children = if (node.isFolder) tree.childrenOf(node.id) else emptyList()
        val open = node.id in expanded
        out += TreeRow(node, depth, open, children.isNotEmpty())
        if (open) children.forEach { add(it, depth + 1) }
    }
    tree.books.forEach { add(it, 0) }
    return out
}

object LibraryTags {
    fun row(title: String) = "library-row-$title"
}

/** The library as a tree: click folders to open them, documents to show them. */
@Composable
fun LibraryPane(
    tree: LibraryTree,
    current: NodeId?,
    strings: Strings,
    onOpen: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(current?.let { c -> tree.pathTo(c).dropLast(1).map { it.id }.toSet() } ?: emptySet()) }
    Column(modifier.fillMaxHeight().width(280.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text(strings.library, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(treeRows(tree, expanded), key = { it.node.id.value }) { row ->
                val node = row.node
                val selected = node.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable {
                            if (node.isFolder) {
                                expanded = if (node.id in expanded) expanded - node.id else expanded + node.id
                            } else {
                                onOpen(node.id)
                            }
                        }
                        .padding(start = (12 + row.depth * 18).dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                        .testTag(LibraryTags.row(node.title)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (node.isFolder && row.hasChildren) {
                        Icon(if (row.expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                    } else {
                        Spacer(Modifier.width(18.dp))
                    }
                    val icon = when {
                        !node.isFolder -> Icons.Filled.Description
                        node.parent == null -> Icons.AutoMirrored.Filled.MenuBook
                        else -> Icons.Filled.Folder
                    }
                    Icon(icon, null, Modifier.size(18.dp))
                    Text(node.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
