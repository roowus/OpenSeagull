package com.roowus.openseagull

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SessionRegistry
import java.io.File
import org.junit.Test

/**
 * Opens every game the manifest hosts, one at a time, and reports which of them are actually
 * alive afterwards — the sweep behind the long-standing "which games open vs fail" question.
 *
 * ## Why a table written to disk, not log lines
 *
 * The development burner (Pixel 4 XL) does not retain app logcat across the instrumentation
 * lifecycle, so this probe's siblings' convention — print a verdict, read it with `logcat -d` —
 * produced nothing here. The verdicts go to `getExternalFilesDir(null)/game-open-report.txt`
 * instead, where `adb shell cat` reaches them regardless of the logging daemon's mood.
 *
 * ## Why each row is written the moment it is measured, and why that rule exists at all
 *
 * The first full run of this probe died mid-loop: `Crazy8Activity.onPause` threw NPE when HOME was
 * pressed (their code, missing session state — a hosted game without a real balloon has no session,
 * and their pause path dereferences it). The crash killed the process *after* five games had
 * already been measured, and because rows were buffered until the end, all five results died too —
 * the report on disk still said `SKIP` from a previous run. A survey that can be destroyed by its
 * own subject must persist evidence as it goes; buffering assumes survival, which is exactly what
 * this probe does not have.
 *
 * ## What "opened" means here, and what it cannot mean
 *
 * A game counts as OPEN when the system reports its class as the top resumed activity five
 * seconds after launch — past their `<clinit>` native load, their onCreate bind, and their first
 * frame. It cannot count as *playable*: no board is seeded beyond a minimal one, no conversation
 * exists on this device, and several games degrade to a blank or self-started board without
 * complaining. This sweep separates "the framework let it through and their code survived" from
 * "it crashed", which is the split that decides what needs fixing. Playability still ends with a
 * human and a real balloon.
 *
 * Between games the probe presses HOME through UiAutomation rather than back: BackGuard now
 * intercepts back by design.
 *
 * Same SKIP convention as its siblings when OpenPigeon is absent.
 */
class GameOpenProbe {

    private val tag = "SEAGULL"

    /** The eight hosted activities, mirroring AndroidManifest.xml. Kept in step by hand; the sweep fails loudly below if any stops resolving. */
    private val games = listOf(
        "com.openbubbles.openpigeon.knockout.KnockoutActivity",
        "com.openbubbles.openpigeon.pool.PoolActivity",
        "com.openbubbles.openpigeon.golf.GolfActivity",
        "com.openbubbles.openpigeon.shuffle.ShuffleActivity",
        "com.openbubbles.openpigeon.wordhunt.WordHuntActivity",
        "com.openbubbles.openpigeon.crazy8.Crazy8Activity",
        "com.openbubbles.openpigeon.questions.SecretWordActivity",
        "com.openbubbles.openpigeon.godot.GodotGameActivity",
    )

    /** Past <clinit>, bind, and first frame; see HostedSessionProbe for why 5 s is the floor. */
    private val SettleMs = 5_000L

    /**
     * Each activity's wire game name — the value their own launch path passes as the `GAME` extra
     * and the key a seeded session is registered under. Only the ones that differ from the obvious
     * lowercased class name are listed (crazy8's game is `crazy`).
     */
    private val WireNames = mapOf(
        // Read off their registry on 2026-08-25 (versionCode 26071701): the wire names are not
        // the class names lowercased — knockout is "knock", WordHunt is "hunt". A wrong name
        // degrades the seed to the 1-key fallback and their parser dies on it.
        "KnockoutActivity" to "knock",
        "PoolActivity" to "pool",
        "GolfActivity" to "golf",
        "ShuffleActivity" to "shuffle",
        "WordHuntActivity" to "hunt",
        "Crazy8Activity" to "crazy",
        "SecretWordActivity" to "questions",
        "GodotGameActivity" to "questions",
    )

    private fun ctx() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyHostedGameOpens() {
        // `-e only <comma-separated simple names>` reruns just those games — for after a crash
        // leaves the rest of the table already on disk.
        val only = InstrumentationRegistry.getArguments().getString("only")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val file = File(
            ctx().targetContext.getExternalFilesDir(null) ?: ctx().targetContext.filesDir,
            "game-open-report.txt",
        )
        val out = StringBuilder(file.takeIf { it.exists() }?.readText().orEmpty())

        fun row(vararg cells: String) {
            out.appendLine(cells.joinToString("  ") { it.padEnd(28) })
            write(file, out.toString())
        }

        if (out.isEmpty()) {
            row("GAME", "LAUNCH", "RESUMED@5s")
            row("----", "------", "---------")
        }

        val pigeon = InstalledOpenPigeon.find(ctx().targetContext)
        if (pigeon == null) {
            out.clear()
            write(file, "SKIP: OpenPigeon is not installed on this device")
            return
        }

        // Games whose row already exists in the table are not re-run unless named via -e only.
        // Matching is on simple name so the argument stays short on an adb command line.
        // MULTILINE: ^ must mean "start of a table row", not "start of the file". A non-empty
        // `only` means *exactly* those games — a game that crashes the process must be excludable,
        // not merely re-runnable.
        val done = Regex("^([A-Za-z0-9]+)  ", RegexOption.MULTILINE).findAll(out.toString())
            .map { it.groupValues[1] }
            .filterNot { it == "GAME" || it.startsWith("---") }
            .toSet()
        val simpleNames = games.associateBy { it.substringAfterLast('.') }
        val queue = if (only.isNotEmpty()) {
            only.mapNotNull { simpleNames[it] }
        } else {
            games.filter { it.substringAfterLast('.') !in done }
        }

        val injected = ForeignCode.installDex(pigeon)
        Log.i(tag, "installDex -> $injected")
        // Their registry's own names, logged once: WireNames above is maintained by hand against
        // this list, and a name that silently stops resolving degrades that game's seed to the
        // 1-key fallback — which their parser then dies on (WordHunt did, under "wordhunt").
        Log.i(
            tag,
            "registry names: " + ForeignGameCatalog.of(pigeon).games.mapNotNull { it.name }.joinToString(),
        )

        val ours = GameOpenProbe::class.java.classLoader!!
        val am = ctx().targetContext.getSystemService(ActivityManager::class.java)
        val ui = ctx().uiAutomation

        for (name in queue) {
            val klass = runCatching { Class.forName(name, false, ours) }.getOrNull()
            if (klass == null) {
                row(name.substringAfterLast('.'), "CLASS-NOT-FOUND", "-")
                continue
            }

            // A session is registered before every launch, for the same reason
            // HostedSessionProbe registers one: their games answer an unknown session by
            // finish()ing — which is designed behaviour, not a hosting failure — and without this
            // the sweep conflated "self-finished, no session" with "failed to open".
            //
            // The board is composed by *their* `getNewGameData` rather than hand-written: a
            // hand-written minimal map was measured killing Pool inside its own
            // `onServiceConnected`→`handleMessage` (NPE parsing keys pool's parser expects), and a
            // crash we caused by seeding wrong data is not a fact about the game. What their own
            // composer produces is exactly what a real balloon would have carried.
            val id = "open-sweep-${name.hashCode()}"
            val wireName = WireNames[name.substringAfterLast('.')] ?: name.substringAfterLast('.').lowercase()
            val composed = runCatching {
                ForeignGameCatalog.of(pigeon).byName(wireName)?.newGameData()
            }.getOrNull()
            val board = composed
                ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }
                ?: mapOf("game" to wireName)
            Log.i(tag, "seeded '$wireName' from ${if (composed != null) "their composer" else "fallback"}: ${board.size} keys")
            SessionRegistry.open(id, game = wireName, message = board)

            val intent = Intent(ctx().targetContext, klass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("SESSION", id)
                .putExtra("GAME", wireName)
            val launch = runCatching { ctx().targetContext.startActivity(intent) }
            val launchResult = launch.exceptionOrNull()
                ?.let { "THREW:${it.javaClass.simpleName}" }
                ?: "ok"

            // The row goes on disk *before* the settle, as PENDING, and is replaced by the final
            // verdict if the process is still alive to write it. A game can die inside the settle
            // window itself — Crazy8's onPause NPE did — and without the placeholder that death
            // would leave no row at all, which is the exact silence this probe exists to end.
            // A row stranded on PENDING is itself a verdict: launched, then took the process down.
            val simple = name.substringAfterLast('.')
            row(simple, launchResult, "PENDING")

            SystemClock.sleep(SettleMs)
            val resumed = am?.getRunningTasks(1)?.firstOrNull()?.topActivity?.className == name
            out.replaceLastRowFor(simple, "$launchResult  ${resumed.toString().padEnd(28)}")
            write(file, out.toString())
            Log.i(tag, "$launchResult $name resumed=$resumed")

            // HOME, not back: the guard consumes the first back press by design. A game may die
            // here anyway — which is precisely why each row persists before the next launch starts.
            pressHome(ui)
            SystemClock.sleep(1_000)
            SessionRegistry.close(id)
        }

        write(file, out.toString() + "\n(run complete)\n")
    }

    private fun pressHome(ui: UiAutomation) {
        ui.injectInputEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true)
        ui.injectInputEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true)
    }

    /** Swap [simple]'s most recent row (the PENDING one) for [cells], in place. */
    private fun StringBuilder.replaceLastRowFor(simple: String, cells: String) {
        val lines = this.split("\n").toMutableList()
        val idx = lines.indexOfLast { it.startsWith(simple.padEnd(28)) || it.startsWith("$simple  ") }
        if (idx >= 0) lines[idx] = simple.padEnd(28) + cells
        // Rebuild without a trailing empty element that split introduces.
        this.clear()
        this.append(lines.joinToString("\n"))
    }

    private fun write(file: File, text: String) {
        runCatching { file.writeText(text) }
            .onFailure { Log.e(tag, "could not write report to ${file.path}", it) }
        Log.i(tag, "report at ${file.path}")
    }
}
