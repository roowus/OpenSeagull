package com.roowus.openseagull.ui

/**
 * How many games fit on a page, and which page we are on.
 *
 * Split out of [GamePicker] with no Android types in it, so the arithmetic can be tested on the
 * JVM. That matters more than it looks: every bug this class can have — an off-by-one page count,
 * a page index that survives a shrinking catalog, a division that yields zero rows — shows up
 * on-device as a page of games the user simply cannot reach. There is no crash and no log line to
 * follow, which makes it exactly the kind of thing worth pinning down before it reaches a phone.
 *
 * The row and item constants are OpenPigeon's, so a page of ours occupies the same space as a page
 * of theirs.
 */
internal class Pagination(
    private val itemCount: Int,
    keyboardHeightDp: Int = DefaultKeyboardHeightDp,
) {
    /**
     * Rows that fit in the height the host gave us, clamped to [MinRows]..[MaxRows].
     *
     * The clamp is what makes a hostile or unexpected height safe: a host reporting something
     * absurdly small would otherwise divide down to zero rows and produce an empty page forever.
     */
    val rowsPerPage: Int =
        ((keyboardHeightDp - HeaderHeightDp) / RowHeightDp).coerceIn(MinRows, MaxRows)

    val itemsPerPage: Int = rowsPerPage * ItemsPerRow

    /** At least 1, so an empty catalog still renders a page that can say so. */
    val pageCount: Int =
        if (itemCount <= 0) 1 else (itemCount + itemsPerPage - 1) / itemsPerPage

    var page: Int = 0
        private set

    /** Clamped on write, so a caller cannot leave the object describing a page that isn't there. */
    fun setPage(value: Int) {
        page = value.coerceIn(0, pageCount - 1)
    }

    /** Move by [delta] pages. Returns true if the page actually changed. */
    fun movePage(delta: Int): Boolean {
        val before = page
        setPage(page + delta)
        return page != before
    }

    /** Index range of the current page, empty if the catalog is. */
    fun pageRange(): IntRange {
        val start = page * itemsPerPage
        if (start >= itemCount) return IntRange.EMPTY
        return start until minOf(start + itemsPerPage, itemCount)
    }

    val hasPrevious: Boolean get() = page > 0
    val hasNext: Boolean get() = page < pageCount - 1

    companion object {
        /** All read from OpenPigeon's working picker rather than chosen here. */
        const val DefaultKeyboardHeightDp = 300
        const val ExpandedKeyboardHeightDp = 380
        const val HeaderHeightDp = 44
        const val RowHeightDp = 100
        const val MinRows = 2
        const val MaxRows = 3
        const val ItemsPerRow = 5
    }
}
