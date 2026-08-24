package com.roowus.openseagull

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SeagullIdentity
import com.roowus.openseagull.host.SessionChannel
import java.util.Collections

/**
 * The process-wide object, and the two seams hosting an OpenPigeon game depends on.
 *
 * Both exist here for the same underlying reason — this class *is* the `applicationContext` their
 * code runs against, and it is the first of our code to run in any process we own:
 *
 * - [onCreate] hands [SeagullIdentity] a Context, and — below API 28, where
 *   [com.roowus.openseagull.host.HostedComponentFactory] cannot run — makes their classes loadable
 *   so the framework can build their Activity at all.
 * - [bindService] answers their session bind locally, so the Activity it built can read its board.
 *
 * They are strictly ordered and both are required. Without the first, the launch dies in
 * `ActivityThread` with a `ClassNotFoundException`; without the second, it succeeds and the board
 * opens blank.
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
     * Put their dex on our ClassLoader before the framework can ask for one of their Activities —
     * on the API levels where nothing earlier is available.
     *
     * The manifest declares eight of their classes. `android:name` is resolved at launch, through
     * `getClassLoader().loadClass(name)` of the process doing the launching, which is ours — so
     * the declaration only means anything once [ForeignCode.installDex] has run. Miss the ordering
     * and the failure is a `ClassNotFoundException` from deep inside `ActivityThread`, thrown
     * before any code of ours gets to explain itself.
     *
     * ## Why this is a fallback and not the main path
     *
     * This runs in **every** process on **every** cold start, including the one that only answers
     * OpenBubbles and draws the picker and will never host anything. Measured at **619 ms**. On
     * API 28+ [com.roowus.openseagull.host.HostedComponentFactory] does the same work per
     * component, only for components about to be built, so that cost disappears — and it also gets
     * their native runtime up, which this cannot usefully do without knowing which game is opening.
     *
     * Below API 28 the framework ignores `android:appComponentFactory` entirely, and this is the
     * only hook that precedes `loadClass` in every process. A launch we initiate could inject
     * immediately before `startActivity`, but `GodotGameActivity` is `android:process=":godot"`:
     * that process is spawned by the framework *because* of the launch, and the first thing it
     * runs is this method. There is no call site of ours in it at all.
     *
     * Cost is an mmap, not a copy — nothing is extracted from their 500 MB archive — but the
     * elapsed time is logged rather than assumed. A failure is not fatal: everything reflective
     * still works through [InstalledOpenPigeon], and only hosting is lost.
     */
    override fun onCreate() {
        super.onCreate()

        // Before the SDK gate, deliberately. This is the only line in this method that has to run
        // in *every* process on *every* level: :godot has no call site of ours before this, and on
        // API 28+ the dex work below is HostedComponentFactory's job and this method returns two
        // lines from now. Handing over a Context is free — nothing is read or minted until the
        // first session opens.
        SeagullIdentity.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // HostedComponentFactory has it, and will do it later and more cheaply.
            return
        }
        val pigeon = InstalledOpenPigeon.find(this) ?: run {
            Log.i(TAG, "OpenPigeon is not installed — nothing to host")
            return
        }
        val started = SystemClock.elapsedRealtime()
        val result = ForeignCode.installDex(pigeon)
        Log.i(TAG, "installDex in ${SystemClock.elapsedRealtime() - started} ms -> $result")
    }

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
