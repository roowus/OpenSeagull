package com.roowus.openseagull

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.ForeignResourcesReport
import com.roowus.openseagull.host.InstalledOpenPigeon

/**
 * Reports exactly what OpenSeagull can see of the installed OpenPigeon.
 *
 * This is the launcher entry point, and for now it is the whole user-facing app. It exists because
 * every failure mode of the runtime-host architecture is either invisible or misleading from the
 * outside: a missing OpenPigeon, a registry that could not be read, a resource id resolved against
 * the wrong table. A screen that states which of those is happening turns a silent wrong result
 * into a legible one.
 *
 * The UI is built in code rather than from a layout because it is a diagnostic readout, and one
 * file that shows both the query and its result is easier to keep honest than a layout plus a
 * binding.
 *
 * ## Structure
 *
 * Sections with headers, rather than one flat monospace dump. The dump was accurate but had to be
 * *read*; the questions a user arrives with — is OpenPigeon found, can we load its code, how many
 * games, which ones — are now answered by scanning four headers. Content within a section is still
 * monospace and still one line per underlying query, because alignment is doing real work there;
 * it is the grouping that changed, not the honesty of the lines.
 */
class DiagnosticsActivity : AppCompatActivity() {

    /** The scrollable column every helper below appends to. Set in [onCreate], before any use. */
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(column) })

        val pigeon = InstalledOpenPigeon.find(this)

        header("OPENSEAGULL")
        line("version        ${BuildConfig.VERSION_NAME}")
        line("applicationId  $packageName")

        if (pigeon == null) {
            section("OPENPIGEON") {
                line("NOT FOUND", bad = true)
                blank()
                prose(InstalledOpenPigeon.NotInstalledMessage)
                blank()
                prose("Looked for:")
                InstalledOpenPigeon.CANDIDATES.forEach { prose("  $it") }
            }
            return
        }

        section("OPENPIGEON") {
            line("package        ${pigeon.packageName}")
            line("version        ${pigeon.versionName ?: "unknown"}")
            line("dataDir them   ${pigeon.packageContext.applicationInfo.dataDir}")
            line("dataDir us     ${applicationInfo.dataDir}")
        }

        // Ahead of the catalog because it is the live blocker and because the catalog's own
        // isEmpty branch returns early — a reading placed after it would vanish exactly when
        // something else had already gone wrong.
        //
        // This runs here, in an ordinary launched Activity, rather than in an instrumented test,
        // because the question it asks is whether a greylisted call is permitted, and `am
        // instrument` can be told to permit it. See ForeignResourcesReport.
        section("RUNTIME ACCESS") {
            ForeignResourcesReport.of(this, pigeon).forEach { line(it) }
        }

        val catalog = ForeignGameCatalog.of(pigeon)
        section("GAMES") {
            line("count          ${catalog.games.size}")
            line("source         ${catalog.strategy}")
            if (catalog.isEmpty) {
                blank()
                prose(
                    "No games could be read. Their registry was unreadable and no known game " +
                        "class loaded — most likely an OpenPigeon version whose internals have moved.",
                )
                return@section
            }

            // The catalog's own order, not the picker's alphabetical one: this screen is
            // diagnostic evidence of what their registry said, and their declaration order is
            // part of that evidence. The picker sorts for the user; this preserves for us.
            catalog.games.forEach { g ->
                // Poster resolution is reported as ok/null because it is the check that catches
                // the wrong-Resources trap: a wrong-but-present drawable would show as ok here,
                // so this line is a smoke signal, not a proof. The instrumented probe is the
                // real check.
                val poster = if (g.poster != null) "poster ok" else "poster null"
                line("  ${g.name ?: "?"}  ${g.displayName ?: "?"}  v${g.version ?: "?"}  $poster")
            }
        }
    }

    /**
     * One titled group with trailing space. A lambda over [column] so a section's content can
     * `blank()` and early-return (`return@section`) without the caller threading a writer
     * through — the flat `buildString` version had to duplicate that control flow by hand.
     */
    private fun section(title: String, body: () -> Unit) {
        header(title)
        body()
        blank(tall = true)
    }

    private fun header(title: String) {
        column.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF1F6F6B.toInt())
            textSize = 13f
            letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.NORMAL)
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun line(text: String, bad: Boolean = false) {
        column.addView(TextView(this).apply {
            this.text = text
            setTextColor(if (bad) Color.rgb(0xB3, 0x26, 0x1E) else Color.rgb(0x33, 0x33, 0x33))
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
        })
    }

    /** Prose, not a query result: wraps, proportional type, one shade calmer than the data. */
    private fun prose(text: String) {
        column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(0x66, 0x66, 0x66))
            textSize = 13f
        })
    }

    private fun blank(tall: Boolean = false) {
        column.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(if (tall) 20 else 8),
            )
        })
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()
}
