package com.roowus.openseagull.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import com.roowus.openseagull.PickerActionReceiver
import com.roowus.openseagull.R
import com.roowus.openseagull.host.ForeignGame
import com.roowus.openseagull.host.ForeignGameCatalog

/**
 * The game grid OpenBubbles shows when the user opens the OpenSeagull keyboard.
 *
 * ## Why this is [RemoteViews] and not Glance
 *
 * OpenPigeon builds this same panel with Glance, and the tempting read is that OpenSeagull should
 * too. It should not, and the reason is not taste.
 *
 * Glance's value here is `ImageProvider(resourceId)` and `actionRunCallback<…>`. Both work for
 * OpenPigeon because the resource ids are theirs and the RemoteViews carries their package name,
 * so the host resolves everything against their APK. OpenSeagull's posters come out of a *foreign*
 * resource table, so an id is meaningless once it crosses the binder — the pixels have to travel
 * (see [Posters]). Once the images are bitmaps, Glance is a compose-compiler dependency buying
 * `addView` and `setImageViewBitmap`, which plain RemoteViews already has.
 *
 * ## The metrics are not invented
 *
 * The header height, row height, poster height and 5-per-row grid are OpenPigeon's numbers, read
 * from their `MadridExtension`. Matching them means a page of ours occupies the same space as a
 * page of theirs, so the panel does not jump when the user switches which extension holds the
 * GamePigeon slot.
 */
internal class GamePicker(
    private val context: Context,
    private val catalog: ForeignGameCatalog,
) {

    /**
     * Games actually offered, in catalog order.
     *
     * OpenPigeon hides three of its own games from its picker (`hunt`, `anagrams`, `wordbites`).
     * That is their editorial decision about their own product, not a technical constraint, so
     * OpenSeagull does not inherit it — a game their registry reports is a game we show. If one of
     * them turns out to be broken when launched, that is a gameplay problem to fix there.
     */
    val games: List<ForeignGame> get() = catalog.games

    /**
     * Paging state and the layout arithmetic behind it, kept outside the view so a tap can advance
     * the page without re-reading the catalog. Lives in [Pagination] so it is JVM-testable.
     */
    private var pagination = Pagination(games.size, Metrics.DefaultKeyboardHeightDp)

    /** Keyboard height the host gave us, in dp. Resets paging, since the page size changes. */
    var keyboardHeightDp: Int = Metrics.DefaultKeyboardHeightDp
        set(value) {
            if (field == value) return
            field = value
            pagination = Pagination(games.size, value)
        }

    /** Move by [delta] pages, clamped. Returns true if the page actually changed. */
    fun movePage(delta: Int): Boolean = pagination.movePage(delta)

    /**
     * Build the grid for the current page.
     *
     * Rows are assembled with `addView` because the row count depends on the host's keyboard
     * height, which is not known until the host has bound — there is no static layout that can
     * express "two or three rows depending".
     *
     * Short final pages are padded with empty cells rather than left ragged. Without the padding
     * the surviving cells would each take `1f` of a smaller total weight and stretch, so the last
     * page would render its games larger than every other page.
     */
    fun build(): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.picker_root)
        val pages = pagination.pageCount

        root.setTextViewText(R.id.picker_title, title(pages))
        wireArrows(root)

        root.removeAllViews(R.id.picker_rows)

        val range = pagination.pageRange()
        val pageGames = if (range.isEmpty()) emptyList() else games.slice(range)
        if (pageGames.isEmpty()) {
            Log.i(TAG, "picker page ${pagination.page} is empty (games=${games.size})")
            return root
        }

        var bytes = 0
        pageGames.chunked(Metrics.ItemsPerRow).forEach { rowGames ->
            val row = RemoteViews(context.packageName, R.layout.picker_row)
            rowGames.forEach { game -> bytes += addCell(row, game) }
            repeat(Metrics.ItemsPerRow - rowGames.size) { addSpacer(row) }
            root.addView(R.id.picker_rows, row)
        }

        // Logged because a page's cost is otherwise invisible: it is paid in the *host's* process,
        // where our logs are not. The KiB figure is bitmap heap, not transaction size — posters
        // above a few KB cross via ashmem, so the parcel stays tiny (measured: 5460 bytes against
        // 1387 KiB of pixels). It is the host's memory while the page is up that this bounds.
        Log.i(
            TAG,
            "picker page ${pagination.page + 1}/$pages — ${pageGames.size} games, " +
                "${pagination.rowsPerPage} rows, ~${bytes / 1024} KiB of posters",
        )
        return root
    }

    /** Adds one game cell; returns the bitmap bytes it contributed, for the budget log. */
    private fun addCell(row: RemoteViews, game: ForeignGame): Int {
        val cell = RemoteViews(context.packageName, R.layout.picker_cell)
        cell.setTextViewText(R.id.cell_label, game.displayName ?: game.name.orEmpty())

        var bytes = 0
        val drawable = try {
            game.poster
        } catch (e: RuntimeException) {
            // A foreign resource table can fail in ways ours cannot — a poster referencing a
            // density or attr that does not resolve here. One bad drawable should cost one cell
            // its art, not cost the user the whole picker.
            Log.w(TAG, "poster failed for ${game.name}", e)
            null
        }
        val bitmap = drawable?.let { Posters.rasterise(it) }
        if (bitmap != null) {
            cell.setImageViewBitmap(R.id.cell_poster, bitmap)
            bytes = Posters.approximateBytes(bitmap)
        } else {
            // Our own placeholder, so a cell with no resolvable art is still tappable and still
            // holds its place in the grid.
            cell.setImageViewResource(R.id.cell_poster, R.drawable.seagull_mark)
        }

        game.name?.let { cell.setOnClickPendingIntent(R.id.cell_root, launchGame(it)) }
        row.addView(R.id.picker_row_cells, cell)
        return bytes
    }

    /** An invisible cell that holds a column's width on a short final page. */
    private fun addSpacer(row: RemoteViews) {
        row.addView(R.id.picker_row_cells, RemoteViews(context.packageName, R.layout.picker_cell))
    }

    private fun title(pages: Int): String = when {
        games.isEmpty() -> "No games found"
        pages > 1 -> "Games — ${pagination.page + 1}/$pages"
        else -> "Games"
    }

    /**
     * Show an arrow only where there is somewhere to go.
     *
     * `setViewVisibility` to `INVISIBLE`, not `GONE`: the 38 dp slots either side are what keep the
     * title optically centred, and collapsing one would shift the title as the user paged.
     */
    private fun wireArrows(root: RemoteViews) {
        val showPrev = pagination.hasPrevious
        val showNext = pagination.hasNext
        root.setViewVisibility(
            R.id.picker_prev,
            if (showPrev) android.view.View.VISIBLE else android.view.View.INVISIBLE,
        )
        root.setViewVisibility(
            R.id.picker_next,
            if (showNext) android.view.View.VISIBLE else android.view.View.INVISIBLE,
        )
        if (showPrev) root.setOnClickPendingIntent(R.id.picker_prev, changePage(-1))
        if (showNext) root.setOnClickPendingIntent(R.id.picker_next, changePage(+1))
    }

    /**
     * A [PendingIntent] for a picker tap.
     *
     * `FLAG_IMMUTABLE` because nothing needs to fill these in and mutable PendingIntents handed to
     * another process are a standing hazard; it is also mandatory from API 31 up.
     *
     * The distinguishing detail is [Intent.setData]. `PendingIntent` equality ignores extras
     * entirely, so ten cells built with the same action and differing only in a game-name extra
     * would collapse into one PendingIntent, and every cell would launch whichever game was
     * created last. Putting the discriminator in the data [Uri] — which equality *does* consider —
     * is what keeps them distinct. This is a genuinely easy bug to ship, because it looks correct
     * and misbehaves only on the second cell onward.
     */
    private fun pendingIntent(action: String, discriminator: String, extras: Intent.() -> Unit) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PickerActionReceiver::class.java).apply {
                this.action = action
                data = Uri.fromParts(UriScheme, discriminator, null)
                extras()
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun launchGame(name: String) =
        pendingIntent(PickerActionReceiver.ActionLaunchGame, "game/$name") {
            putExtra(PickerActionReceiver.ExtraGameName, name)
        }

    private fun changePage(delta: Int) =
        pendingIntent(PickerActionReceiver.ActionChangePage, "page/$delta") {
            putExtra(PickerActionReceiver.ExtraPageDelta, delta)
        }

    /**
     * Layout constants, all read from OpenPigeon's working picker rather than chosen here.
     *
     * A typealias onto [Pagination]'s companion rather than a second copy: the numbers describe
     * both the arithmetic and the layout files, and two copies would drift. They are hard-coded
     * rather than reflected out of OpenPigeon's dex on purpose — these describe *our* layouts, and
     * a picker that silently reshaped itself when the user updated OpenPigeon would be worse than
     * one that is merely a version behind.
     */
    internal companion object Metrics {
        const val TAG = "SEAGULL"

        /** Scheme for the PendingIntent discriminator URIs; never resolved, only compared. */
        const val UriScheme = "openseagull"

        const val DefaultKeyboardHeightDp = Pagination.DefaultKeyboardHeightDp
        const val ExpandedKeyboardHeightDp = Pagination.ExpandedKeyboardHeightDp
        const val ItemsPerRow = Pagination.ItemsPerRow
    }
}
