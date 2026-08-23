package com.roowus.openseagull.host

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenSeagull's analogue of OpenPigeon's `MadridExtension.activeSessions`.
 *
 * ## Why this has to exist at all
 *
 * Every game OpenPigeon ships — native and Godot alike — reads the message it is meant to display
 * through `GameSessionIPC.getCurrentMessage(sessionId)`, which lands on their
 * `GameSessionService`, which does `activeSessions[id]`. That map lives in **their** process and is
 * populated by **their** extension. A session opened by OpenSeagull is not in it.
 *
 * `GameSessionBindProbe` measured what that costs: the bind succeeds (their service is
 * `exported="true"`), and then `getCurrentMessage` on an id they have never seen returns a Bundle
 * of **0 keys** and throws nothing. So the whole failure is silent. This object is the map that
 * has to answer instead — see [SessionChannel] for how their call is redirected here.
 *
 * ## Shape is dictated, not chosen
 *
 * Their `GameSessionIPC.toStringMap()` builds its result with `getString(key)?.let { … }`, so any
 * extra that is not a String is dropped without comment in both directions. The session payload is
 * therefore a flat `String → String` map and nothing else; storing anything richer here would be a
 * fiction that silently evaporates at the boundary.
 *
 * ## What is deliberately not done yet
 *
 * [update] merges into memory and notifies, but does **not** persist the result back to the
 * conversation — that needs their `Cryption.encrypt` and a blocking `IMessageViewHandle
 * .updateMessage` off the main thread, which is the balloon-tap half of the project and is not
 * built. A move made in a hosted game will therefore be visible to that game and lost on exit. The
 * log line in [update] says so rather than letting it pass for working.
 */
object SessionRegistry {

    private const val TAG = "SEAGULL"

    /**
     * One open game.
     *
     * `lockDepth` rather than a boolean because their activities call `lockMsgHandle` on open and
     * `unlockMsgHandle` on destroy, and `onNewIntent` can re-run the open path against a session
     * that is already locked. A counter makes the pair idempotent; a boolean would let the second
     * unlock release a lock the first one still needs.
     */
    class Session internal constructor(
        val id: String,
        /** Wire name, e.g. `pool` — the value their code passes as the `GAME` extra. */
        val game: String,
    ) {
        @Volatile
        internal var message: Map<String, String> = emptyMap()

        @Volatile
        internal var suppressed: Boolean = false

        @Volatile
        internal var lockDepth: Int = 0

        /**
         * Who *we* are in this game, as their `getSenderUUID` reports it.
         *
         * Empty until the balloon-tap half mints one, and that is a known-wrong answer rather than
         * a neutral one: their `isYourTurn` is `message["sender"] != myId`, so an empty id compares
         * unequal to every sender and the board says "your turn" unconditionally. [SessionChannel]
         * logs when it serves an empty value for exactly that reason.
         */
        @Volatile
        internal var senderUuid: String = ""

        /**
         * Their `IMessageUpdatedCallback`, held opaquely.
         *
         * It arrives from their ClassLoader, so it cannot be typed here — see [InstalledOpenPigeon]
         * on per-loader class identity. [SessionChannel] is what knows how to call it.
         */
        @Volatile
        internal var listener: Any? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Register a session so their game can read it, replacing any previous entry for [id].
     *
     * Replacing rather than merging is deliberate: a re-open re-derives the message from the
     * balloon, and that is the authority. Keeping a stale in-memory move would show the player a
     * board that disagrees with the conversation.
     */
    fun open(id: String, game: String, message: Map<String, String>): Session {
        val session = Session(id, game)
        session.message = message
        sessions[id] = session
        Log.i(TAG, "session opened id=${id.take(8)}… game=$game keys=${message.size}")
        return session
    }

    fun find(id: String): Session? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)?.let { Log.i(TAG, "session closed id=${id.take(8)}…") }
    }

    /** Every open session, for diagnostics. */
    fun ids(): Set<String> = sessions.keys.toSet()

    /**
     * The payload for [id], or an empty map if we have never heard of it.
     *
     * Empty is the same answer their own service gives for an unknown id, and their games already
     * handle it: most log `"$id does not exist!"` and `finish()`. Fabricating a plausible-looking
     * map instead would turn a diagnosable miss into a game that opens with invented state.
     */
    fun message(id: String): Map<String, String> = sessions[id]?.message ?: emptyMap()

    fun setSuppressed(id: String, suppress: Boolean) {
        sessions[id]?.suppressed = suppress
    }

    fun lock(id: String) {
        sessions[id]?.let { it.lockDepth++ }
    }

    fun unlock(id: String) {
        sessions[id]?.let { if (it.lockDepth > 0) it.lockDepth-- }
    }

    /**
     * Apply a game's move to the in-memory session and return the merged payload.
     *
     * Merge rather than replace because their games send only the keys they changed — `updateSession`
     * is called with a delta, not a whole board.
     *
     * Returns `null` for an unknown session so the caller can tell "applied" from "dropped"; their
     * own service would silently `return` in that case, which is exactly the class of quiet failure
     * this project exists to stop repeating.
     */
    fun update(id: String, delta: Map<String, String>): Map<String, String>? {
        val session = sessions[id] ?: run {
            Log.w(TAG, "update for unknown session id=${id.take(8)}… — dropping ${delta.size} keys")
            return null
        }
        val merged = LinkedHashMap(session.message).apply { putAll(delta) }
        session.message = merged
        // The move is real to the running game and invisible to the conversation until the balloon
        // half is wired. Said out loud because a lost move with no log line is indistinguishable
        // from a game that never made one.
        Log.i(
            TAG,
            "session updated id=${id.take(8)}… +${delta.size} keys (not yet written back to the " +
                "balloon — updateMessage is unwired)",
        )
        return merged
    }

    /** Drop every session. Used when the extension is released, and by tests. */
    fun clear() {
        sessions.clear()
    }
}
