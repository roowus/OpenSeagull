package com.roowus.openseagull.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the picker's page arithmetic on the JVM.
 *
 * Worth the file because every way [Pagination] can be wrong looks identical on-device: no crash,
 * no log line, just a page of games the user cannot reach. The catalog sizes below are the real
 * ones — 25 games is what the installed OpenPigeon actually exposes through `THEIR_REGISTRY` — so
 * a regression here is a regression the user would see.
 */
class PaginationTest {

    /**
     * The default 300 dp panel: (300 - 44) / 100 = 2 rows, 10 games a page, 25 games over 3 pages
     * with a short final page of 5.
     */
    @Test
    fun defaultHeightGivesTwoRowsAndThreePagesFor25Games() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        assertEquals(2, p.rowsPerPage)
        assertEquals(10, p.itemsPerPage)
        assertEquals(3, p.pageCount)
    }

    /**
     * The expanded 380 dp panel newer OpenBubbles builds give: (380 - 44) / 100 = 3 rows, 15 a
     * page, so the same catalog collapses to 2 pages.
     */
    @Test
    fun expandedHeightGivesThreeRowsAndTwoPagesFor25Games() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.ExpandedKeyboardHeightDp)
        assertEquals(3, p.rowsPerPage)
        assertEquals(15, p.itemsPerPage)
        assertEquals(2, p.pageCount)
    }

    /**
     * A hostile or unexpected panel height must not divide down to zero rows — that would produce
     * an empty page forever, with nothing in the logs to say why.
     */
    @Test
    fun rowsPerPageIsClampedAtBothEnds() {
        assertEquals(Pagination.MinRows, Pagination(25, keyboardHeightDp = 0).rowsPerPage)
        assertEquals(Pagination.MinRows, Pagination(25, keyboardHeightDp = -1000).rowsPerPage)
        assertEquals(Pagination.MaxRows, Pagination(25, keyboardHeightDp = 10_000).rowsPerPage)
    }

    /** An exact multiple must not gain a trailing empty page from the ceiling division. */
    @Test
    fun exactMultipleDoesNotGainATrailingPage() {
        assertEquals(2, Pagination(20, Pagination.DefaultKeyboardHeightDp).pageCount)
        assertEquals(3, Pagination(21, Pagination.DefaultKeyboardHeightDp).pageCount)
    }

    /** An empty catalog still gets one page, so the picker has somewhere to say it is empty. */
    @Test
    fun emptyCatalogStillHasOnePageAndAnEmptyRange() {
        val p = Pagination(itemCount = 0)
        assertEquals(1, p.pageCount)
        assertTrue(p.pageRange().isEmpty())
        assertFalse(p.hasPrevious)
        assertFalse(p.hasNext)
    }

    @Test
    fun pageRangeCoversTheWholeCatalogExactlyOnce() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        assertEquals(0 until 10, p.pageRange())
        p.setPage(1)
        assertEquals(10 until 20, p.pageRange())
        p.setPage(2)
        // The short final page: 5 games, not a full 10, and it must not run past the catalog.
        assertEquals(20 until 25, p.pageRange())
    }

    @Test
    fun setPageClampsRatherThanDescribingAPageThatIsNotThere() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        p.setPage(99)
        assertEquals(2, p.page)
        p.setPage(-5)
        assertEquals(0, p.page)
    }

    /**
     * [Pagination.movePage] reports whether anything changed, which is what stops the extension
     * pushing an identical view over the binder every time the user taps a dead arrow.
     */
    @Test
    fun movePageReportsWhetherThePageActuallyChanged() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        assertFalse("already on the first page", p.movePage(-1))
        assertTrue(p.movePage(1))
        assertTrue(p.movePage(1))
        assertEquals(2, p.page)
        assertFalse("already on the last page", p.movePage(1))
        assertEquals(2, p.page)
    }

    @Test
    fun arrowsAreOffAtTheEndsAndOnInTheMiddle() {
        val p = Pagination(itemCount = 25, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        assertFalse(p.hasPrevious)
        assertTrue(p.hasNext)
        p.setPage(1)
        assertTrue(p.hasPrevious)
        assertTrue(p.hasNext)
        p.setPage(2)
        assertTrue(p.hasPrevious)
        assertFalse(p.hasNext)
    }

    /** A catalog that fits on one page must offer no paging at all. */
    @Test
    fun singlePageCatalogHasNoArrows() {
        val p = Pagination(itemCount = 4, keyboardHeightDp = Pagination.DefaultKeyboardHeightDp)
        assertEquals(1, p.pageCount)
        assertFalse(p.hasPrevious)
        assertFalse(p.hasNext)
        assertEquals(0 until 4, p.pageRange())
    }
}
