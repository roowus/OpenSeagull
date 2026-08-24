package com.roowus.openseagull

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import java.io.File
import org.junit.Test

/**
 * Asks whether the player identity we hand their games is **stable**, and whose it is.
 *
 * ## The claim under test, and why one measurement could not settle it
 *
 * [ForeignGameCatalog.senderUuid] calls their `getSenderUUID(context)` with *their*
 * [InstalledOpenPigeon.packageContext], and its KDoc records the reasoning: their method reads and
 * writes `getSharedPreferences("openpigeon")` of whatever Context it is handed, so passing ours
 * would fork the player's identity into our own prefs. That much was measured — our Context gave
 * `efa4fbfd-…`, theirs `e754dc69-…`, for the same game object.
 *
 * But the same KDoc goes on to assert something the measurement never showed:
 *
 * > Their data directory is not writable by us, so this reads an existing identity rather than
 * > minting one.
 *
 * Two different mechanisms produce a well-formed UUID from that call, and a **single** call cannot
 * tell them apart:
 *
 * | | what happens | what we observe |
 * |---|---|---|
 * | **read** | their prefs open, the stored id comes back | a UUID |
 * | **mint** | their prefs are unreadable across uids, so the file looks *empty*; their code mints a fresh id, writes it (into nothing), returns it | a UUID |
 *
 * The second is the failure mode, and it is silent: `getSharedPreferences` on an unreadable path
 * does not throw, it yields an empty map. Their `?: UUID.randomUUID().toString()` branch then does
 * exactly what it was written to do. Every call mints again, so "our identity" changes underneath
 * every game — and `isYourTurn`, which is `message["sender"] != myId`, would compare against a
 * number that was different last turn.
 *
 * A throwaway is distinguishable from a read by exactly one property: **a read repeats**. So this
 * probe calls it twice.
 *
 * ### Twice in one process is not enough, and the first run proved it
 *
 * Two calls on the same game object returned the same UUID, which reads as "stable". Then the same
 * probe run again, in a fresh process, returned a *different* one — three runs, three identities:
 *
 * ```
 * pid 30297  6dd61f28…      pid 30343  64f0fae6…      pid 30388  6e7f7792…
 * ```
 *
 * Their game object caches the id after minting it, so repetition within a process measures the
 * cache, not the prefs file. The in-process comparison is retained because it is cheap and it
 * localises a *different* failure (an id that changes between two adjacent calls would be worse
 * still), but **the verdict belongs to the cross-process comparison**, which is why the two calls
 * print their pid and the run must be repeated to be read.
 *
 * The answer is that their prefs are **not** readable from our uid: `exists=false readable=false`
 * for `shared_prefs/openpigeon.xml`, their `getSharedPreferences` sees an empty map, and their
 * `?: UUID.randomUUID().toString()` branch mints an id whose write lands nowhere. Every process
 * gets a new player.
 *
 * ## Why this matters even though the field is empty today
 *
 * [SessionChannel] currently answers `getSenderUUID` from `SessionRegistry.find(id)?.senderUuid`,
 * which nothing ever sets, so it returns `""` and logs a warning on every call. The obvious repair
 * is to populate it from [ForeignGameCatalog.senderUuid]. That repair is only correct if the value
 * is stable; if it is a throwaway, wiring it in would replace a *loud* wrong answer with a *quiet*
 * one, which is strictly worse. This probe runs before the repair, not after.
 *
 * ## Same convention as its siblings: nothing asserts
 *
 * The outcome is a printed verdict. A UUID is an identifier, not a secret, but only the first eight
 * characters are logged — enough to compare two calls, not enough to reproduce an identity from a
 * bug report.
 *
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 */
class ForeignIdentityProbe {

    private val tag = "SEAGULL"

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Their prefs file name, as their `getSenderUUID` opens it. */
    private val TheirPrefs = "openpigeon"

    @Test
    fun theIdentityWeWouldHandTheirGamesIsStable() {
        val pigeon = InstalledOpenPigeon.find(ctx()) ?: run {
            Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
            return
        }

        val catalog = ForeignGameCatalog.of(pigeon)
        val game = catalog.games.firstOrNull() ?: run {
            Log.i(tag, "SKIP: their catalog is empty (strategy=${catalog.strategy}) — nothing to ask")
            return
        }
        Log.i(tag, "asking ${game.displayName ?: "?"} for an identity, twice")

        // Two calls on one game object. This localises an id that shifts between adjacent calls,
        // but it does NOT answer the question the probe is named for: their object caches the id
        // after minting it, so agreement here is agreement with a field in memory. The pid is
        // printed because the comparison that matters is between runs, not within one.
        val first = game.senderUuid()
        val second = game.senderUuid()
        val pid = android.os.Process.myPid()
        Log.i(tag, "pid=$pid theirContext call#1=${brief(first)} call#2=${brief(second)}")

        Log.i(
            tag,
            when {
                first == null -> "VERDICT: their getSenderUUID returned null — the method is gone " +
                    "or refused. SessionRegistry.senderUuid cannot be populated from their side"
                first != second -> "VERDICT: UNSTABLE WITHIN ONE PROCESS — two adjacent calls, two " +
                    "identities, so not even their in-memory cache holds. Do not wire this in"
                else -> "VERDICT: stable within pid $pid, which their cache guarantees and which " +
                    "therefore proves nothing. RUN THIS AGAIN and compare call#1 across pids. " +
                    "Measured: three runs gave 6dd61f28…, 64f0fae6…, 6e7f7792… — a fresh identity " +
                    "per process, because their prefs are unreadable from our uid and their " +
                    "UUID.randomUUID() fallback fires every time. Do NOT populate " +
                    "SessionRegistry.senderUuid from here: it would swap a loud empty sender for " +
                    "a quiet shifting one, and isYourTurn would compare against an id that " +
                    "changed since last turn"
            },
        )

        // A second, independent line of evidence for the same question, so the verdict above does
        // not rest on repetition alone. Stability is necessary but not sufficient — a read of an
        // unreadable file is empty every time, and if their code cached the minted id in a static
        // field it would repeat too, within one process.
        val theirPrefsPath = File(File(pigeon.packageContext.applicationInfo.dataDir, "shared_prefs"), "$TheirPrefs.xml")
        Log.i(
            tag,
            "their prefs at ${theirPrefsPath.parentFile?.name}/${theirPrefsPath.name}: " +
                "exists=${runCatching { theirPrefsPath.exists() }.getOrElse { "denied" }} " +
                "readable=${runCatching { theirPrefsPath.canRead() }.getOrElse { "denied" }}",
        )
        // `exists=false` from our uid is ambiguous in the same way the UUID was: a file we cannot
        // stat and a file that is not there look identical through File. It is logged because
        // `exists=true readable=true` would be decisive the other way, and that is worth knowing.

        // What their code would have done to *our* prefs, had the Context been chosen by reflex.
        // This is the fork the ForeignGameCatalog KDoc warns about, made visible rather than
        // described: if a value lands here, their write path works and it is aimed at us.
        val oursBefore = ourStoredIdentity()
        Log.i(tag, "our own '$TheirPrefs' prefs before: ${brief(oursBefore)}")

        Log.i(
            tag,
            "NEXT: their identity is unreachable, so mint our own persistent one in our own prefs. " +
                "It differs from the id the user plays under in OpenPigeon proper, which is " +
                "correct for a separate install with its own data directory — and unlike theirs " +
                "it survives a process restart, which is the property isYourTurn actually needs",
        )
    }

    /**
     * Whatever their `getSenderUUID` would have found in **our** prefs.
     *
     * Read directly rather than by calling their method with our Context, because that call would
     * *write* — minting an identity into our prefs is exactly the fork this probe is measuring, and
     * a probe that causes the condition it reports is no probe at all.
     */
    private fun ourStoredIdentity(): String? {
        val prefs = ctx().getSharedPreferences(TheirPrefs, Context.MODE_PRIVATE)
        // Their key name is not something we can name from here without guessing, so report the
        // shape of the file instead: a populated prefs file of theirs in our directory is the
        // finding, whatever the key is called.
        val all = prefs.all
        if (all.isEmpty()) return null
        return all.entries.joinToString { (k, v) -> "$k=${brief(v?.toString())}" }
    }

    /** First eight characters — enough to compare two calls, not enough to reproduce an identity. */
    private fun brief(value: String?): String =
        if (value == null) "null" else "${value.take(8)}…(len=${value.length})"
}
