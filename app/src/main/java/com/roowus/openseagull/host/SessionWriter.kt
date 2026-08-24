package com.roowus.openseagull.host

import android.util.Log
import com.bluebubbles.messaging.ITaskCompleteCallback
import java.util.concurrent.Executors

/**
 * The half of OpenPigeon's `GameSession` that puts a move back into the conversation.
 *
 * ## What was missing
 *
 * [SessionRegistry.update] has always merged a game's move into memory. That is enough for the
 * running game — it reads the board back through [SessionChannel] and sees its own move — and it is
 * not enough for anything else: on exit the merged map is dropped, the balloon still shows the
 * board as it was, and the opponent is never told. Their `GameSession.updateSession` is what closes
 * that, and this is its analogue.
 *
 * Their version needs three things we did not have. The handle is now held
 * ([SessionRegistry.Session.handle]); the ciphertext turns out to need no new machinery, because
 * their `buildGameMessage` calls `Cryption.encrypt` itself and [ForeignGame.buildMessage]
 * already goes through it; and the thread is here.
 *
 * ## Why a background thread is not optional
 *
 * `updateMessage` is **not** `oneway` in `IMessageViewHandle.aidl`, so the call blocks until
 * OpenBubbles has taken the message. The call arrives on their game's own thread — [SessionChannel]
 * is invoked directly rather than through a binder transaction, so there is no pool thread
 * absorbing it — and blocking there would stall the game's UI for as long as the host takes.
 *
 * A single thread rather than a pool: two moves in one conversation must reach the host in the
 * order they were made, and a pool would let the second overtake the first.
 *
 * ## Where this diverges from theirs, deliberately
 *
 * Theirs assigns `currentMessage = modifiedUpdated` **inside** `complete()`, so a host that never
 * calls back leaves the session reading a board without the move in it. Ours has already merged by
 * the time this runs ([SessionChannel] updates the registry before calling here), so the running
 * game keeps reading a board that agrees with what it just did even if the host is slow or silent.
 * The move is then either persisted or logged as lost — never quietly half-applied.
 */
internal object SessionWriter {

    private const val TAG = "SEAGULL"

    /**
     * Serialises write-back, and outlives any one session.
     *
     * A daemon thread: this must not be the reason our process stays alive, and there is nothing
     * here worth finishing after everything else has stopped.
     */
    private val writes = Executors.newSingleThreadExecutor { r ->
        Thread(r, "seagull-writeback").apply { isDaemon = true }
    }

    /**
     * Put [id]'s current board into the conversation, off the calling thread.
     *
     * Returns immediately. Everything that can fail after that point is logged rather than
     * propagated: the game's `onFinished` has already been called by the time this runs, so its
     * move is accepted from its point of view and there is nobody left to throw at.
     *
     * Four ways this ends without a write, each said out loud, because a lost move that logs
     * nothing is indistinguishable from a game that never made one:
     *
     * - the session is gone (closed between the move and the write)
     * - no handle (the host has never addressed this balloon to us — see
     *   [SessionRegistry.Session.handle])
     * - the game is not in the installed catalog
     * - their `buildGameMessage` declined
     */
    fun write(pigeon: InstalledOpenPigeon, id: String) {
        writes.execute {
            try {
                writeNow(pigeon, id)
            } catch (t: Throwable) {
                // An uncaught throwable here would kill the executor's only thread and silently
                // end write-back for the rest of the process.
                Log.e(TAG, "write-back failed for ${id.take(8)}…", t)
            }
        }
    }

    private fun writeNow(pigeon: InstalledOpenPigeon, id: String) {
        val session = SessionRegistry.find(id) ?: run {
            Log.w(TAG, "write-back for ${id.take(8)}… — session closed before the move was sent")
            return
        }
        val handle = session.handle ?: run {
            Log.w(
                TAG,
                "write-back for ${id.take(8)}… — no handle, so the move stays in memory. The host " +
                    "hands one out on a tap or a render; neither has happened for this balloon.",
            )
            return
        }
        val game = try {
            ForeignGameCatalog.of(pigeon).byName(session.game)
        } catch (e: ForeignCallException) {
            Log.w(TAG, "catalog threw resolving '${session.game}' for write-back", e)
            null
        } ?: run {
            Log.w(TAG, "write-back for ${id.take(8)}… — '${session.game}' is not installed")
            return
        }

        val board = SessionRegistry.update(id, enrich(game, session.game, session.message))
            ?: run {
                Log.w(TAG, "write-back for ${id.take(8)}… — session closed while enriching")
                return
            }

        val message = try {
            // Their `currentSession = mySession` is non-null here, which is what tells their
            // `buildGameMessage` this is a move in an existing game rather than an invitation —
            // the poster is only re-encoded into the balloon when the session is null.
            game.buildMessage(board, id)
        } catch (e: ForeignCallException) {
            Log.w(TAG, "buildGameMessage threw for '${session.game}'", e)
            null
        } ?: run {
            Log.w(TAG, "write-back for ${id.take(8)}… — their buildGameMessage returned nothing")
            return
        }

        Log.i(
            TAG,
            "writing back ${board.size} keys to ${id.take(8)}… game=${session.game} " +
                "url=${message.url.length}b art=${if (message.imageBase64 != null) "yes" else "none"}",
        )
        handle.updateMessage(
            message,
            object : ITaskCompleteCallback.Stub() {
                override fun complete() {
                    // Nothing is assigned here, unlike theirs — the board was merged before this
                    // ran. This exists because the AIDL requires a callback and because the host
                    // taking the message is the only positive confirmation the move left us.
                    Log.i(TAG, "host accepted the move for ${id.take(8)}…")
                }
            },
        )
    }

    /**
     * The two keys their `updateSession` adds to a board before sending it.
     *
     * A delta rather than a merged map, so the caller can put it through [SessionRegistry.update]
     * — these belong in the *stored* board as much as the game's own move does, because the next
     * `getCurrentMessage` must see the same map the balloon was built from.
     *
     * Runs on the writer thread with the rest of the write, not on the game's: both rules are
     * cheap, but the caption one calls into their code, and the whole point of this file is that
     * their thread does not wait on ours.
     */
    private fun enrich(
        game: ForeignGame,
        gameName: String,
        board: Map<String, String>,
    ): Map<String, String> {
        val extras = LinkedHashMap<String, String>()
        val myId = SeagullIdentity.senderUuid()

        // Theirs: `if (modifiedUpdated["player2"] != myUUID && !containsKey("player1"))`. This is
        // how the second player writes themselves into a game they did not start — whoever created
        // it filled `player2` with their own id, so whoever is *not* that claims player1 on their
        // first move. Skipped for an empty id, which would claim the slot for nobody.
        //
        // `SeagullIdentity`, not their `getSenderUUID`: theirs mints a fresh UUID per process from
        // our uid, so delegating would stamp a throwaway into the conversation permanently.
        if (myId.isNotEmpty() && board["player2"] != myId && !board.containsKey("player1")) {
            extras["player1"] = myId
        }

        // Theirs recomputes the caption on every move except in 20 Questions, where the player
        // types it and it is only filled in when they left it blank.
        if (gameName != "questions" || board["caption"].isNullOrBlank()) {
            // `getSubtitle`, not `getDisplaySubtitle`. This string is written *into the payload*
            // and read by the recipient, so it is phrased from their side — theirs returns
            // "I won!" where the display variant returns "You Won!". Delegated rather than
            // reimplemented in `BoardVerdict` because this one keys off `message["sender"]` rather
            // than `getSenderUUID`, so the identity fault that forced `BoardVerdict` to exist does
            // not reach it.
            //
            // Their body does `message["sender"]!!` inside its `winner` branch, so a board holding
            // `winner` without `sender` throws inside their code. Caught rather than guarded
            // against, because the call has other ways to fail and none should cost the move: a
            // stale caption is a line of text, a dropped write-back is a lost turn.
            val subtitle = try {
                game.subtitle(board)
            } catch (e: ForeignCallException) {
                Log.w(TAG, "getSubtitle threw for '$gameName' — keeping the caption as it was", e)
                null
            }
            if (subtitle != null) extras["caption"] = subtitle
        }

        return extras
    }
}
