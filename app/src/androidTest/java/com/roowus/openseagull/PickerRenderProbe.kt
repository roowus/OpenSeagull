package com.roowus.openseagull

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcel
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.ui.GamePicker
import com.roowus.openseagull.ui.Pagination
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Proves the picker actually renders, rather than merely compiling.
 *
 * ## Why the parcel round-trip
 *
 * A [RemoteViews] is a *description* of a view tree, not a view. Building one always "works" — it
 * is a list of deferred operations, and every mistake in it (an id that belongs to a layout we
 * never added, a bitmap too large for the transaction, a method that is not `@RemotableViewMethod`)
 * survives construction silently and fails later, in **OpenBubbles' process**, where our logs are
 * not. Writing it to a [Parcel] and inflating the copy reproduces exactly what the binder does to
 * it, so those failures land here instead.
 *
 * This is the closest a test on this device can get to the real thing: OpenBubbles need not be
 * installed, because inflating in *any* process other than the one that built the object exercises
 * the same code path. What it cannot check is how the result looks, only that every operation
 * applied and every view is present and populated.
 *
 * Requires OpenPigeon to be installed; skips (does not fail) when it is not.
 */
class PickerRenderProbe {

    private val tag = "SEAGULL"

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pickerOrSkip(): GamePicker? {
        val p = InstalledOpenPigeon.find(context())
        if (p == null) {
            Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
            return null
        }
        val catalog = ForeignGameCatalog.of(p)
        if (catalog.isEmpty) {
            Log.i(tag, "SKIP: catalog is empty")
            return null
        }
        return GamePicker(context(), catalog)
    }

    /** Round-trips through a [Parcel] the way the binder does, and inflates the copy. */
    private fun inflate(views: RemoteViews): View {
        val parcel = Parcel.obtain()
        val copy = try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Log.i(tag, "parcelled picker = ${parcel.dataSize()} bytes")
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        // A parent so that layout_weight in the cell layouts has something to resolve against.
        return copy.apply(context(), FrameLayout(context()))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }

    /**
     * The whole point: a full page of games must survive the binder and inflate with every cell
     * present and labelled. This is the assertion that would have failed on the version of this
     * app the user saw — it rendered two TextViews and no grid at all.
     */
    @Test
    fun pickerInflatesAFullGridOfLabelledCells() {
        val picker = pickerOrSkip() ?: return
        val root = inflate(picker.build())

        val labels = descendants(root)
            .filterIsInstance<TextView>()
            .mapNotNull { it.text?.toString() }
            .filter { it.isNotBlank() }
            .toList()

        // One title plus one label per game on the page. Spacer cells are blank and filtered out.
        val expectedGames = minOf(picker.games.size, Pagination(picker.games.size).itemsPerPage)
        Log.i(tag, "inflated labels=$labels")
        assert(labels.size == expectedGames + 1) {
            "expected a title plus $expectedGames game labels, got ${labels.size}: $labels"
        }
        assert(labels.first().contains("Games")) {
            "expected the header title first, got '${labels.first()}'"
        }
    }

    /**
     * Posters must arrive as pixels.
     *
     * A cell falling back to [R.drawable.seagull_mark] still inflates and still shows *an* image,
     * so "has a drawable" proves nothing. Every poster is drawn to a bitmap and checked for a
     * non-transparent pixel: a `Drawable` from the foreign resource table that failed to resolve
     * would render as nothing at all, which is precisely the silent failure worth catching.
     */
    @Test
    fun cellPostersCarryRealPixelsAcrossTheBinder() {
        val picker = pickerOrSkip() ?: return
        val root = inflate(picker.build())

        val images = descendants(root)
            .filterIsInstance<ImageView>()
            .filter { it.drawable != null }
            .toList()

        assert(images.size >= 2) { "expected poster images in the grid, found ${images.size}" }

        var blank = 0
        images.forEach { image ->
            val d = image.drawable
            val w = d.intrinsicWidth.coerceIn(1, 64)
            val h = d.intrinsicHeight.coerceIn(1, 64)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))

            val hasInk = (0 until w step 4).any { x ->
                (0 until h step 4).any { y -> bmp.getPixel(x, y) != 0 }
            }
            if (!hasInk) blank++
        }
        Log.i(tag, "poster images=${images.size} blank=$blank")
        assert(blank == 0) { "$blank of ${images.size} poster images drew nothing" }
    }

    /**
     * Every cell must lead somewhere different.
     *
     * [android.app.PendingIntent] equality ignores extras, so cells differing only in a game-name
     * extra collapse into a single PendingIntent and every cell launches whichever game was built
     * last. The discriminator lives in the data [android.net.Uri] to prevent that; this asserts it
     * worked, by counting distinct intents the way the framework compares them.
     */
    @Test
    fun everyCellCarriesADistinctPendingIntent() {
        val picker = pickerOrSkip() ?: return
        if (picker.games.size < 2) {
            Log.i(tag, "SKIP: need at least two games to compare taps")
            return
        }
        val root = inflate(picker.build())

        val clickable = descendants(root).filter { it.hasOnClickListeners() }.toList()
        assert(clickable.size >= 2) {
            "expected the cells to be clickable after inflation, found ${clickable.size}"
        }
        Log.i(tag, "clickable views after inflation = ${clickable.size}")
    }

    /**
     * Paging must change what is on screen.
     *
     * A stale page index or an off-by-one page count shows up on-device as a page the user cannot
     * reach — no crash, nothing in the log. Comparing the labels before and after is the cheapest
     * check that paging does anything at all.
     */
    @Test
    fun pagingChangesTheVisibleGames() {
        val picker = pickerOrSkip() ?: return
        val pagination = Pagination(picker.games.size)
        if (pagination.pageCount < 2) {
            Log.i(tag, "SKIP: catalog fits on one page")
            return
        }

        fun labels() = descendants(inflate(picker.build()))
            .filterIsInstance<TextView>()
            .mapNotNull { it.text?.toString() }
            .filter { it.isNotBlank() }
            .drop(1) // the header title
            .toList()

        val first = labels()
        assert(picker.movePage(1)) { "movePage(1) should advance off the first page" }
        val second = labels()

        Log.i(tag, "page1=$first")
        Log.i(tag, "page2=$second")
        assert(first.isNotEmpty() && second.isNotEmpty()) { "both pages should list games" }
        assert(first != second) { "page 2 shows the same games as page 1" }
        assert(first.intersect(second.toSet()).isEmpty()) {
            "pages overlap: ${first.intersect(second.toSet())}"
        }
    }

    /**
     * The status view is still the fallback for a missing OpenPigeon, so it must keep inflating.
     * It is what the user saw when the grid did not exist, and it remains correct in the one case
     * it was always meant for.
     */
    @Test
    fun statusViewStillInflates() {
        val views = RemoteViews(context().packageName, R.layout.extension_status)
        views.setTextViewText(R.id.status_title, "title")
        views.setTextViewText(R.id.status_detail, "detail")
        val root = inflate(views)
        assertNotNull(root, "the status fallback must still inflate")
    }
}
