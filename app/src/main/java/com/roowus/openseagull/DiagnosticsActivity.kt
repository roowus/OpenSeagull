package com.roowus.openseagull

import android.os.Bundle
import android.util.TypedValue
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
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(48, 48, 48, 48)
            typeface = android.graphics.Typeface.MONOSPACE
            text = report()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    private fun report(): String = buildString {
        appendLine("OpenSeagull ${BuildConfig.VERSION_NAME}")
        appendLine("applicationId  ${packageName}")
        appendLine()

        val pigeon = InstalledOpenPigeon.find(this@DiagnosticsActivity)
        if (pigeon == null) {
            appendLine("OpenPigeon:    NOT FOUND")
            appendLine()
            appendLine(InstalledOpenPigeon.NotInstalledMessage)
            appendLine()
            appendLine("Looked for:")
            InstalledOpenPigeon.CANDIDATES.forEach { appendLine("  $it") }
            return@buildString
        }

        appendLine("OpenPigeon:    ${pigeon.packageName}")
        appendLine("version:       ${pigeon.versionName ?: "unknown"}")
        appendLine("dataDir(them): ${pigeon.packageContext.applicationInfo.dataDir}")
        appendLine("dataDir(us):   ${applicationInfo.dataDir}")
        appendLine()

        // Ahead of the catalog because it is the live blocker and because the catalog's own
        // isEmpty branch returns early — a reading placed after it would vanish exactly when
        // something else had already gone wrong.
        //
        // This runs here, in an ordinary launched Activity, rather than in an instrumented test,
        // because the question it asks is whether a greylisted call is permitted, and `am
        // instrument` can be told to permit it. See ForeignResourcesReport.
        ForeignResourcesReport.of(this@DiagnosticsActivity, pigeon).forEach { appendLine(it) }
        appendLine()

        val catalog = ForeignGameCatalog.of(pigeon)
        appendLine("games:         ${catalog.games.size}  (via ${catalog.strategy})")
        if (catalog.isEmpty) {
            appendLine()
            appendLine("No games could be read. Their registry was unreadable and no known")
            appendLine("game class loaded — most likely an OpenPigeon version whose internals")
            appendLine("have moved.")
            return@buildString
        }

        appendLine()
        catalog.games.forEach { g ->
            // Poster resolution is reported as ok/null because it is the check that catches the
            // wrong-Resources trap: a wrong-but-present drawable would show as ok here, so this
            // line is a smoke signal, not a proof. The instrumented probe is the real check.
            val poster = if (g.poster != null) "poster ok" else "poster null"
            appendLine("  ${g.name ?: "?"}  ${g.displayName ?: "?"}  v${g.version ?: "?"}  $poster")
        }
    }
}
