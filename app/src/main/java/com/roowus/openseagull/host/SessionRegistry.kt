package com.roowus.openseagull.host

import android.util.Log
import com.bluebubbles.messaging.IMessageViewHandle
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
 * conversation. That is *write-back*, and it needed three things: the `IMessageViewHandle` the host
 * addressed this balloon with, their `Cryption.encrypt` to build the outbound payload, and a thread
 * that is not the main one — `updateMessage` is not `oneway` in the AIDL, so it blocks until the
 * host has taken the message.
 *
 * The first of those is now here ([Session.handle], set through [attachHandle]). The other two are
 * not, so nothing calls `updateMessage` and [update] still ends in memory.
 *
 * Reading the other direction is built: [open] is called from `MadridExtension.didTapTemplate` when
 * a balloon is tapped and again from `messageUpdated` when the other player moves, so a hosted game
 * reads a real board. It is only the move *out* that stops here. A move made in a hosted game is
 * therefore visible to that game and lost on exit, and the log line in [update] says so rather than
 * letting it pass for working.
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
         * Who *we* are in this game.
         *
         * Read from [SeagullIdentity] at construction rather than from their `getSenderUUID`,
         * because theirs is not an identity: their prefs are unreadable across uids, so their
         * fallback mints a fresh UUID in every process. That KDoc is on [SeagullIdentity]; the
         * consequence here is that this field would have changed between one turn and the next.
         *
         * A per-session copy rather than a call through to [SeagullIdentity] on every read, so that
         * a game which opened under one id keeps reading that id for as long as it is open — the
         * property their `isYourTurn = message["sender"] != myId` actually depends on.
         *
         * Still `""` if [SeagullIdentity.attach] never ran, which on Android it always does. That
         * case keeps [SessionChannel]'s existing warning rather than substituting a throwaway.
         */
        @Volatile
        internal var senderUuid: String = SeagullIdentity.senderUuid()

        /**
         * Their `IMessageUpdatedCallback`, held opaquely.
         *
         * It arrives from their ClassLoader, so it cannot be typed here — see [InstalledOpenPigeon]
         * on per-loader class identity. [SessionChannel] is what knows how to call it.
         */
        @Volatile
        internal var listener: Any? = null

        /**
         * The host's way back to this balloon, or `null` if we have not been handed one.
         *
         * Typed, unlike [listener]: `IMessageViewHandle` is generated from *our* AIDL and loaded by
         * our own ClassLoader, so it is an ordinary reference here. It is the opposite direction —
         * OpenBubbles gave it to us, rather than OpenPigeon's code passing us one of theirs.
         *
         * Nullable and mutable because the host does not promise one. It arrives with a tap
         * (`MadridExtension.didTapTemplate`) or with a render (`MadridExtension.getLiveView`),
         * whichever happens first, and OpenBubbles hands out a *fresh* handle on a rebind — theirs
         * has an `updateHandle` for exactly that, and dropping the new one would leave write-back
         * addressed to a balloon the host has already forgotten.
         *
         * Held rather than used: nothing calls `updateMessage` yet. This is the piece that was
         * missing, not the write-back itself.
         */
        @Volatile
        internal var handle: IMessageViewHandle? = null
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
        // The *payload* is replaced; the handle is carried across. A handle is not part of the
        // balloon's state — it is the host's address for this balloon, and it stays valid until
        // OpenBubbles issues a new one. Two ordinary sequences would otherwise lose it: a render
        // hands us a handle before the tap that opens the session, and `messageUpdated` re-opens a
        // session that is already open every time the opponent moves — which would strip the handle
        // from a game in progress, at exactly the moment write-back is about to need it.
        session.handle = sessions[id]?.handle
        sessions[id] = session
        Log.i(
            TAG,
            "session opened id=${id.take(8)}… game=$game keys=${message.size} " +
                "as=${session.senderUuid.take(8).ifEmpty { "<none>" }}… " +
                "handle=${if (session.handle != null) "kept" else "none"}",
        )
        return session
    }

    /**
     * Record the host's way back to this balloon, replacing any previous one.
     *
     * Their `getSessionFor` calls `updateHandle(handle)` on every render of a session it already
     * knows, so a fresh handle always wins — this does the same. Silently does nothing for a
     * session we have not opened: the host renders balloons we have never been tapped into, and
     * that is not an error, just a handle with nowhere to go yet.
     */
    fun attachHandle(id: String, handle: IMessageViewHandle?) {
        if (handle == null) return
        val session = sessions[id] ?: return
        val replacing = session.handle != null && session.handle !== handle
        session.handle = handle
        if (replacing) Log.i(TAG, "handle replaced id=${id.take(8)}…")
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
