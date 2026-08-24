package com.roowus.openseagull.host

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * The player id OpenSeagull sends games under — minted once, by us, in our own data directory.
 *
 * ## Why this is not read from OpenPigeon
 *
 * The obvious answer is to ask their code: `Game.getSenderUUID(context)` exists, we can call it
 * reflectively, and it returns a well-formed UUID. `ForeignIdentityProbe` measured what that UUID
 * actually is, and it is not an identity.
 *
 * Their method reads `getSharedPreferences("openpigeon")` and falls back to
 * `?: UUID.randomUUID().toString()`. Their prefs file reports `exists=false readable=false` from our
 * uid — **not-writable does not imply readable, and it is neither** — so `getSharedPreferences`
 * hands their code an empty map rather than throwing, the fallback branch fires, and the write lands
 * nowhere. Four runs, four players:
 *
 * ```
 * pid 30297  6dd61f28…   pid 30343  64f0fae6…   pid 30388  6e7f7792…   pid 30611  8cf305f2…
 * ```
 *
 * Two calls *within* one process do agree, because their game object caches what it minted. That
 * agreement is the trap: it measures a field in memory, not a file on disk.
 *
 * Wiring theirs in would therefore have been worse than leaving the field empty. An empty sender is
 * a **loud** wrong answer — [SessionChannel] logs every time it serves one. A per-process UUID is a
 * **quiet** one: their `isYourTurn` is `message["sender"] != myId`, so the board would compare
 * against an id that changed since last turn and be confidently wrong with nothing in the log.
 *
 * ## What this is instead, and what it costs
 *
 * A UUID of our own, in our own `shared_prefs`, minted on first use and never again. It is **not**
 * the id the user plays under in OpenPigeon proper, and it cannot be: their prefs are unreadable
 * across uids, which is the sandbox working as designed rather than an obstacle to route around.
 * That is the correct outcome for a separate install with its own data directory — the same way a
 * second copy of any app is a second account — and unlike theirs it survives a process restart,
 * which is the one property `isYourTurn` actually needs.
 *
 * The visible consequence: a game started in OpenPigeon and continued in OpenSeagull looks to the
 * board like a different player took the turn. Turn detection stays *self*-consistent, which is what
 * a hosted board reads.
 *
 * ## Two details that are load-bearing
 *
 * **`commit()`, not `apply()`.** `GodotGameActivity` runs in `:godot`, a second process, and reads
 * this through its own [SeagullIdentity] instance backed by the same file. An `apply()` returns
 * before the write reaches disk, so a game launched immediately after the first mint could read a
 * file that is still empty and mint a *second* id. Blocking once, ever, is the cheaper mistake.
 *
 * **The first-ever-run race is real and is not closed here.** Two processes minting concurrently
 * would write two ids and the last writer wins, silently. It is left open because it cannot happen
 * in the order things actually run: `:godot` is spawned *by* a game launch, and a game launch
 * requires the picker, which is in the main process and has already minted. Said out loud rather
 * than papered over with a lock that would imply a guarantee the storage does not give.
 */
object SeagullIdentity {

    private const val TAG = "SEAGULL"

    /**
     * Our own prefs file — deliberately **not** `openpigeon`.
     *
     * Their code, handed our Context, would write its own key into a file of that name in our
     * directory. Keeping ours under a name we own means the two can never be confused for one
     * another in a bug report, and a stray write of theirs stays visibly theirs.
     */
    private const val PrefsName = "seagull_identity"

    private const val KeySenderUuid = "sender_uuid"

    /**
     * The application Context, handed over by [com.roowus.openseagull.SeagullApplication] before
     * anything can ask for an id.
     *
     * Held rather than threaded through every call site because the consumer is
     * [SessionChannel.dispatch], which is invoked by *their* code through a reflective proxy and has
     * no Context to pass. `applicationContext` is a process-lifetime singleton, so holding it leaks
     * nothing.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Cached after the first read so the common path touches no storage.
     *
     * Per-process, which is safe here in a way it is not for OpenPigeon: this cache is populated
     * *from* a durable file rather than instead of one, so a second process reads the same value
     * rather than inventing its own.
     */
    @Volatile
    private var cached: String? = null

    /** Called from `Application.onCreate`, in every process, before any game can open. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Our sender id, minting one if this install has never had it.
     *
     * Returns `""` only if [attach] was never called, which means a process reached a session read
     * without running `Application.onCreate` — impossible on Android, and therefore a signal that
     * this object is being used from somewhere it was not designed for (a JVM unit test, most
     * likely). An empty string keeps the existing loud-warning behaviour in [SessionChannel] rather
     * than substituting a throwaway that would look like it worked.
     */
    fun senderUuid(): String {
        cached?.let { return it }
        val context = appContext ?: run {
            Log.w(TAG, "identity requested before attach() — no Context, so no persistent id")
            return ""
        }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val existing = prefs.getString(KeySenderUuid, null)
            if (existing != null) {
                cached = existing
                return existing
            }
            val minted = UUID.randomUUID().toString()
            // Blocking, once per install. See the class KDoc on why apply() is not enough.
            prefs.edit().putString(KeySenderUuid, minted).commit()
            cached = minted
            Log.i(TAG, "minted our sender identity ${minted.take(8)}… — persisted, unlike theirs")
            return minted
        }
    }

    /**
     * Forget the in-memory copy, for tests that need a cold read.
     *
     * Does not delete the stored id: a test that wiped the file would change what the *next* run
     * sees, and an identity that a test can silently rotate is the failure this class was written
     * to stop.
     */
    internal fun resetCacheForTest() {
        cached = null
    }
}
