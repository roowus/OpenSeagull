package com.roowus.openseagull

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SessionChannel
import java.util.Collections

/**
 * The process-wide object, and the seam that lets a hosted OpenPigeon game read its own board.
 *
 * ## Why the interception happens here and not in their class
 *
 * Their `GameSessionIPC` binds `.IGameSession` at a **hardcoded** `com.openbubbles.openpigeon` —
 * that package id is a `const-string` in their dex, disassembled in `GameSessionBindProbe`, with no
 * hook around it. Left alone the bind leaves our process, succeeds, and then answers every session
 * we opened with an empty Bundle and no error (also measured). A hosted board opens blank.
 *
 * Four other ways in were considered and all four are dead, because the read is **synchronous**:
 * every one of their six `GameSessionIPC` sites — Pool, Golf, Crazy8, WordHunt, Knockout, Godot —
 * assigns the handle and calls `getCurrentMessage(sessionId)` in the next statement. There is no
 * window after `onServiceConnected` in which a field could be swapped. WordHunt's `ipcReady@` label
 * looks like a deferral and is not; it exists so early `return@ipcReady`s can leave the lambda.
 *
 * What is left is the bind itself. All six sites pass `applicationContext` to the `GameSessionIPC`
 * constructor, in **our** process, so that is this object — and [bindService] is an ordinary
 * overridable method on `ContextWrapper`. The call never reaches the framework, so the hardcoded
 * package id stops mattering. Nothing here names one of their types, so the content-free gate over
 * `src/main` is untouched.
 *
 * It also fixes the re-entry problem for free: their `initGameSession` runs from `onCreate` *and*
 * `onNewIntent` and builds a fresh `GameSessionIPC` each time, so any per-activity injection would
 * have to be repeated. Every construction re-enters this method.
 *
 * ## Why the callback is posted rather than called inline
 *
 * The framework delivers `onServiceConnected` on the main thread, after the caller returns.
 * Answering inline instead would run their callback — which calls `finish()`, `lockMsgHandle`, and
 * a full board load — from inside their `onCreate`, part-way through construction of the very
 * `GameSessionIPC` the callback is handed. Posting keeps the ordering their code was written
 * against.
 */
class SeagullApplication : Application() {

    /**
     * Built once per process, on first bind, and reused.
     *
     * Reuse matters beyond cost: their `asInterface` hands their code whatever object comes back,
     * and a fresh proxy per bind would make two handles to the same session unequal.
     */
    private val channel: SessionChannel? by lazy {
        val pigeon = InstalledOpenPigeon.find(this) ?: run {
            Log.w(TAG, "session bind intercepted but OpenPigeon is not installed")
            return@lazy null
        }
        SessionChannel.of(pigeon)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Connections we answered ourselves.
     *
     * Kept by identity because a `ServiceConnection` is an anonymous object with no `equals`, and
     * because [unbindService] has to know which ones the framework has never heard of — unbinding
     * one of those throws `IllegalArgumentException: Service not registered`.
     */
    private val intercepted: MutableSet<ServiceConnection> =
        Collections.synchronizedSet(Collections.newSetFromMap(java.util.IdentityHashMap()))

    /**
     * Answer their session bind ourselves; let everything else through.
     *
     * The package on the intent is deliberately **not** checked. Their installed build addresses it
     * at their own package id, a future build might use `context.packageName`, and either way the
     * only bind for this action inside our process is one of theirs. Matching on the action alone
     * keeps this working across both.
     */
    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
    ): Boolean {
        if (service.action != SessionAction) return super.bindService(service, conn, flags)

        val channel = this.channel ?: run {
            // Falling through is the honest failure: the bind goes where it was addressed and
            // behaves exactly as it does today — badly, but in a way their code already handles.
            Log.w(TAG, "no session channel — letting the bind go to ${service.`package`}")
            return super.bindService(service, conn, flags)
        }

        Log.i(TAG, "answering $SessionAction locally instead of ${service.`package`}")
        intercepted += conn
        mainHandler.post {
            conn.onServiceConnected(ComponentName(packageName, LocalSessionService), channel.binder)
        }
        return true
    }

    /**
     * Undo an interception, or hand a real one back to the framework.
     *
     * Nothing in their tree calls this today — it was greped for and is absent — but a future build
     * that added it would otherwise crash on a connection the framework never registered.
     */
    override fun unbindService(conn: ServiceConnection) {
        if (intercepted.remove(conn)) return
        super.unbindService(conn)
    }

    private companion object {
        const val TAG = "SEAGULL"

        /**
         * The action their `Intent(".IGameSession")` carries.
         *
         * A relative-looking string is unusual for an action but it is what their code passes, and
         * an action is matched as an opaque string, so it is used verbatim.
         */
        const val SessionAction = ".IGameSession"

        /**
         * Cosmetic. `onServiceConnected` wants a [ComponentName]; their code ignores it, and naming
         * a class we do not have would be a lie in any log that printed it.
         */
        const val LocalSessionService = "com.roowus.openseagull.host.SessionChannel"
    }
}
