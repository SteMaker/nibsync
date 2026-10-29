package com.stemaker.nibsync.library

/** Colours a book can be given (ARGB); null is the default. Muted, so they suit the grey theme. */
object BookColors {
    val PALETTE: List<Int?> = listOf(
        null,
        0xffb04a4a.toInt(),
        0xffc07a2c.toInt(),
        0xffa8922e.toInt(),
        0xff4f8a4f.toInt(),
        0xff3f8a85.toInt(),
        0xff4a6fa5.toInt(),
        0xff7b5aa6.toInt(),
    )

    /** Colour of the book [node] is in (following its parents), or null for the default. */
    fun of(tree: LibraryTree, node: LibraryNode): Int? {
        var n: LibraryNode? = node
        while (n?.parent != null) n = tree[n.parent!!]
        return n?.colorArgb
    }
}
