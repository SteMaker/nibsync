package com.stemaker.nibsync.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
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
import com.stemaker.nibsync.library.LibraryNode
import com.stemaker.nibsync.library.LibraryTree
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.sync.client.AccountId
import com.stemaker.nibsync.sync.client.SyncAccount
import com.stemaker.nibsync.sync.client.displayName

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
    const val SETTINGS = "library-settings"
    const val NEW_BOOK = "library-new-book"
    fun row(title: String) = "library-row-$title"
    fun bookMenu(title: String) = "library-book-menu-$title"
    fun assign(account: String) = "library-assign-$account"
}

/** Which account each book syncs with, and moving a local book to an account. */
class BookAccountsUi(
    val accounts: List<SyncAccount>,
    val accountOf: (NodeId) -> AccountId?,
    val onAssign: (NodeId, AccountId) -> Unit,
)

/** The library as a tree: click folders to open them, documents to show them. */
@Composable
fun LibraryPane(
    tree: LibraryTree,
    current: NodeId?,
    strings: Strings,
    onOpen: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown below the tree, e.g. the sync status. */
    footer: @Composable () -> Unit = {},
    /** Opens the settings; null hides the gear button. */
    onSettings: (() -> Unit)? = null,
    /** Starts creating a book; null hides the button. */
    onNewBook: (() -> Unit)? = null,
    bookAccounts: BookAccountsUi? = null,
) {
    var expanded by remember { mutableStateOf(current?.let { c -> tree.pathTo(c).dropLast(1).map { it.id }.toSet() } ?: emptySet()) }
    Column(modifier.fillMaxHeight().width(280.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.library, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onNewBook != null) {
                IconButton(onClick = onNewBook, modifier = Modifier.testTag(LibraryTags.NEW_BOOK)) {
                    Icon(Icons.Filled.Add, contentDescription = strings.newBook)
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.testTag(LibraryTags.SETTINGS)) {
                    Icon(Icons.Filled.Settings, contentDescription = strings.settings)
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
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
                    Text(node.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (node.parent == null && bookAccounts != null) {
                        Spacer(Modifier.weight(1f))
                        BookAccountLabel(node, bookAccounts, strings)
                    }
                }
            }
        }
        footer()
    }
}

/** The book's account (small), or for a book only on this PC a menu to sync it with an account. */
@Composable
private fun BookAccountLabel(book: LibraryNode, ui: BookAccountsUi, strings: Strings) {
    val account = ui.accountOf(book.id)?.let { id -> ui.accounts.firstOrNull { it.id == id } }
    if (account != null) {
        Text(account.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(enabled = ui.accounts.isNotEmpty()) { open = true }.testTag(LibraryTags.bookMenu(book.title)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Computer, contentDescription = strings.localOnly, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(strings.syncBookWith, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (a in ui.accounts) {
                DropdownMenuItem(
                    text = { Text("${a.name} · ${a.config.service.displayName}") },
                    onClick = { open = false; ui.onAssign(book.id, a.id) },
                    modifier = Modifier.testTag(LibraryTags.assign(a.name)),
                )
            }
        }
    }
}

/** Name and account of a new book; the account choice starts with [initial]. */
@Composable
fun NewBookDialog(strings: Strings, accounts: List<SyncAccount>, initial: AccountId?, onDismiss: () -> Unit, onCreate: (String, AccountId?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(initial?.takeIf { id -> accounts.any { it.id == id } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newBook) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(strings.bookName) },
                    modifier = Modifier.fillMaxWidth().testTag(NewBookTags.NAME),
                )
                Text(strings.bookAccount, style = MaterialTheme.typography.labelLarge)
                @Composable
                fun choice(id: AccountId?, label: String) = Row(
                    Modifier.fillMaxWidth().clickable { account = id }.testTag(NewBookTags.choice(id?.let { a -> accounts.first { it.id == a }.name })),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = account == id, onClick = { account = id })
                    Text(label)
                }
                for (a in accounts) choice(a.id, "${a.name} · ${a.config.service.displayName}")
                choice(null, strings.localOnly)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), account) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(NewBookTags.CREATE)) { Text(strings.create) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

object NewBookTags {
    const val NAME = "new-book-name"
    const val CREATE = "new-book-create"
    /** [account] null: only on this PC. */
    fun choice(account: String?) = "new-book-account-${account ?: "local"}"
}
