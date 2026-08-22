package com.roowus.openseagull

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.RemoteViews
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import com.roowus.openseagull.host.ForeignCallException
import com.roowus.openseagull.host.ForeignGame
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.ui.GamePicker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OpenSeagull's implementation of the OpenBubbles extension contract.
 *
 * ## Scope of this version
 *
 * The keyboard renders a real, paginated grid of the games found in the user's installed
 * OpenPigeon, drawn with their own poster art, and tapping a game **sends** it — which is what a
 * picker tap does in OpenPigeon too, rather than opening a board (see [launchGame]).
 *
 * What is still missing is the other half: tapping a balloon to *play*. [didTapTemplate] is a
 * no-op and [getLiveView] returns the status view rather than a board. Sending is the half that
 * needs no hosted Activity and no game engine, which is why it is the half that works first.
 *
 * Methods that cannot yet do their job return a status view rather than a stub or an exception, so
 * a bind from OpenBubbles always produces something legible on screen. Where a method is
 * incomplete, the view says so; it does not pretend to have succeeded.
 *
 * ## Why the extension owns an [InstalledOpenPigeon]
 *
 * OpenSeagull ships no OpenPigeon content. Everything it displays or plays is read out of the
 * user's own installed copy at runtime. That handle is resolved once, lazily, and shared: it
 * caches the foreign Context, ClassLoader, and Resources, and it is the only place the two apps
 * meet. See [InstalledOpenPigeon] for the three cross-package hazards it exists to contain.
 *
 * Resolution is lazy rather than eager because OpenPigeon can be installed or uninstalled while
 * this process is alive; a missing OpenPigeon is a first-class state, not a construction failure.
 */
class MadridExtension(val context: Context) : IMadridExtension.Stub() {

    /** Re-resolved on each access so install/uninstall while we are running is picked up. */
    private val pigeon: InstalledOpenPigeon?
        get() = InstalledOpenPigeon.find(context)

    private var callback: IViewUpdateCallback? = null

    /**
     * Kept across the whole keyboard session so paging survives a redraw.
     *
     * Rebuilt on each [keyboardOpened] rather than held for the life of the process: the user may
     * install or update OpenPigeon between openings, and a stale catalog would show a game list
     * that no longer matches what is on the device.
     */
    private var picker: GamePicker? = null

    /**
     * The host's send channel, and the only way a composed game reaches the conversation.
     *
     * Handed to us once per keyboard session and valid only for that session, so it is cleared in
     * [keyboardClosed] alongside everything else. Holding a stale handle would mean posting a
     * balloon into a conversation the user has already left.
     */
    private var keyboardHandle: IKeyboardHandle? = null

    /**
     * How many people are in this conversation, as the host reports it.
     *
     * Recorded because [ForeignGame.minPlayerRequirement] is checked against it — their own
     * `ChooseGameCallback` refuses to compose a game whose minimum exceeds this, and a picker that
     * skipped the check would post balloons OpenPigeon itself would not have sent.
     */
    private var userCount: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Where game composition runs.
     *
     * [launchGame] is reached from a `BroadcastReceiver`, i.e. on our main thread under the
     * receiver time limit, and composing is not cheap: their `buildGameMessage` runs the payload
     * through `Cryption.encrypt` and renders a JPEG poster — measured at 89172 base64 chars for
     * pool. Single-threaded so that two fast taps compose in the order they were tapped rather
     * than racing.
     */
    private val composer: ExecutorService = Executors.newSingleThreadExecutor()

    override fun keyboardOpened(
        callback: IViewUpdateCallback?,
        handle: IKeyboardHandle?,
        userCount: Int,
    ): RemoteViews {
        this.callback = callback
        this.keyboardHandle = handle
        this.userCount = userCount

        val p = pigeon ?: return statusView()
        val catalog = ForeignGameCatalog.of(p)
        if (catalog.isEmpty) return statusView()

        val picker = GamePicker(context, catalog).also { this.picker = it }
        picker.keyboardHeightDp = keyboardHeightDpForHost()
        return picker.build()
    }

    override fun keyboardClosed() {
        callback = null
        picker = null
        keyboardHandle = null
        userCount = 0
    }

    /**
     * How tall the host will make the panel, which decides whether we get two rows or three.
     *
     * OpenPigeon resolves this from the calling uid's package name and version, expanding to 380 dp
     * on newer OpenBubbles builds. That detection is worth replicating, but not worth guessing at:
     * until the mapping from host version to panel height has been *measured* on-device, assuming
     * the expanded height would render a third row that the host then clips.
     *
     * So this returns the conservative default. A wrong guess here is invisible in logs and shows
     * up only as a row the user cannot reach, which is exactly the kind of bug this project has
     * been avoiding by measuring first.
     */
    private fun keyboardHeightDpForHost(): Int = GamePicker.Metrics.DefaultKeyboardHeightDp

    /**
     * Advance the picker and push the new page to the host.
     *
     * Called from [PickerActionReceiver], i.e. from a tap that happened in the host's process.
     */
    fun changePage(delta: Int) {
        val picker = this.picker ?: run {
            Log.w(TAG, "page tap with no open picker — dropping")
            return
        }
        if (!picker.movePage(delta)) return
        pushView(picker.build())
    }

    /**
     * Send a new game into the conversation, which is what tapping a picker cell actually does.
     *
     * Reading OpenPigeon's own `ChooseGameCallback` settled something this project had backwards:
     * a picker tap does **not** open a game. It calls
     * `game.buildGameMessage(context, game.getNewGameData(context), null)` and hands the result to
     * `addMessage` — it composes a balloon and posts it. Opening a board is what happens when you
     * tap an *existing* balloon, which is [didTapTemplate] and still unwired.
     *
     * So this path needs no hosted Activity, no dex injection and no Godot engine. It needs one
     * thing nothing else here has done: carry an object built by **their** code into a binder call
     * made by **ours**, which is what [ParcelBridge] exists for.
     *
     * Everything after the lookup runs on [composer] rather than the receiver's thread, because
     * `buildGameMessage` encrypts a payload and renders a poster.
     */
    fun launchGame(name: String) {
        val handle = keyboardHandle ?: run {
            Log.w(TAG, "launch tap with no keyboard handle — dropping '$name'")
            return
        }
        val p = pigeon ?: run {
            Log.w(TAG, "launch tap but OpenPigeon is gone — dropping '$name'")
            return
        }
        val game = ForeignGameCatalog.of(p).byName(name) ?: run {
            Log.w(TAG, "launch tap for unknown game '$name' — dropping")
            return
        }

        // Their own callback refuses here rather than composing, so we do too. Measured across the
        // installed catalog only `crazy` (Crazy 8s) sets a minimum, at 3.
        val min = game.minPlayerRequirement()
        if (min > userCount) {
            Log.i(TAG, "'$name' needs $min players, conversation has $userCount — refusing")
            return
        }
        // 17 of 26 games have a configuration step we have not built. Sending the default game is
        // the honest partial behaviour — it produces a real, playable balloon — but it is not what
        // their picker would have done, so it is recorded rather than passed over in silence.
        if (game.isConfigurable()) {
            Log.i(TAG, "'$name' is configurable; sending defaults (setup UI not built)")
        }

        composer.execute { composeAndSend(game, handle, name) }
    }

    /**
     * Compose [game] and post it, off the main thread.
     *
     * [ForeignCallException] is caught by name because it is precisely the signal that *their* code
     * threw rather than ours: a failure inside `buildGameMessage` says the installed OpenPigeon
     * disagrees with what we expected of it, and that is a log line and a dropped tap, not a crash
     * in the host's keyboard.
     */
    private fun composeAndSend(game: ForeignGame, handle: IKeyboardHandle, name: String) {
        val message = try {
            val data = game.newGameData() ?: run {
                Log.w(TAG, "'$name' produced no new-game data — nothing to send")
                return
            }
            game.buildMessage(data, session = null) ?: run {
                Log.w(TAG, "'$name' built no message — nothing to send")
                return
            }
        } catch (e: ForeignCallException) {
            Log.w(TAG, "OpenPigeon threw while composing '$name'", e)
            return
        }

        // Sizes only: `url` is ciphertext from their Cryption and the poster is a base64 JPEG.
        Log.i(
            TAG,
            "sending '$name': url=${message.url?.length ?: 0} chars, " +
                "image=${message.imageBase64?.length ?: 0} chars",
        )
        try {
            handle.addMessage(message)
        } catch (e: RemoteException) {
            // `addMessage` is oneway, so this means the host process died rather than that it
            // rejected the message — there is no error channel that could tell us the latter.
            Log.w(TAG, "host went away during addMessage for '$name'", e)
        }
    }

    /**
     * Send a new view to the host.
     *
     * Posted twice: once now, once after a short delay on the main thread. This mirrors what
     * OpenPigeon does, and the reason is that [IViewUpdateCallback.updateView] is `oneway` — it
     * returns immediately with no indication of whether the host was in a state to apply the
     * update. An update that arrives while the host is still laying the panel out can be dropped
     * silently. The second post costs one extra binder call and removes a class of "the tap did
     * nothing" reports that would otherwise be unreproducible.
     *
     * A [RemoteException] here means the host went away between the tap and the push, which is
     * ordinary — the callback is cleared and the next [keyboardOpened] will supply a fresh one.
     */
    private fun pushView(views: RemoteViews) {
        val target = callback ?: run {
            Log.w(TAG, "no host callback — cannot push view")
            return
        }
        try {
            target.updateView(views)
        } catch (e: RemoteException) {
            Log.w(TAG, "host went away during updateView", e)
            callback = null
            return
        }
        mainHandler.postDelayed({
            try {
                callback?.updateView(views)
            } catch (e: RemoteException) {
                Log.w(TAG, "host went away during delayed updateView", e)
                callback = null
            }
        }, RedrawSettleMs)
    }

    /**
     * Opening a game is not wired up yet. Deliberately a no-op: launching the wrong thing would be
     * worse than doing nothing, and the status view already says where the project stands.
     *
     * One design question is already settled, and against the convenient answer. OpenPigeon opens
     * a game with `Intent(context, game.gameClass())` — so the obvious shortcut is to aim that
     * same Intent at their package and let their process do the work. It cannot be done: every
     * game activity in their manifest is `android:exported="false"`, which the framework enforces
     * across packages regardless of signature. `GameSessionService` is exported, but it is the
     * session channel, not the UI.
     *
     * So gameplay has to run in *our* process, from their classes, through their ClassLoader —
     * the same reflective path everything else here uses. That is more work than delegation, but
     * it is also the only path consistent with not shipping their content.
     */
    override fun didTapTemplate(
        message: MadridMessage?,
        handle: IMessageViewHandle?,
        userCount: Int,
    ) = Unit

    override fun getLiveView(
        callback: IViewUpdateCallback?,
        message: MadridMessage?,
        handle: IMessageViewHandle?,
        userCount: Int,
    ): RemoteViews = statusView()

    override fun messageUpdated(message: MadridMessage?) {
        // No sessions are tracked yet, so there is nothing to update.
    }

    /**
     * Release the compose thread. Called when the service holding this extension goes away.
     *
     * `shutdown()` rather than `shutdownNow()`: a compose already in flight has either sent its
     * balloon or is about to, and interrupting it mid-`buildGameMessage` would abandon a tap the
     * user made. There is at most one, and it finishes in well under a second.
     */
    fun release() {
        composer.shutdown()
    }

    /**
     * A [RemoteViews] reporting what OpenSeagull can actually see of the installed OpenPigeon.
     *
     * [RemoteViews] rather than Glance/Compose because this must inflate in **OpenBubbles'**
     * process, not ours — that is the whole point of the contract. Keeping it to a plain layout
     * keeps the dependency surface at zero for now.
     */
    private fun statusView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.extension_status)
        val p = pigeon

        if (p == null) {
            views.setTextViewText(R.id.status_title, "OpenPigeon not found")
            views.setTextViewText(R.id.status_detail, InstalledOpenPigeon.NotInstalledMessage)
            return views
        }

        val catalog = ForeignGameCatalog.of(p)
        views.setTextViewText(
            R.id.status_title,
            "OpenSeagull — ${catalog.games.size} games loaded",
        )
        views.setTextViewText(
            R.id.status_detail,
            buildString {
                append("from ${p.packageName} ${p.versionName ?: "?"}")
                append(" via ${catalog.strategy}")
                val sample = catalog.games.mapNotNull { it.displayName }.take(4)
                if (sample.isNotEmpty()) append("\n${sample.joinToString(", ")}…")
            },
        )
        return views
    }

    private companion object {
        const val TAG = "SEAGULL"

        /** Delay before the second `updateView`, matching OpenPigeon's workaround. */
        const val RedrawSettleMs = 50L
    }
}
