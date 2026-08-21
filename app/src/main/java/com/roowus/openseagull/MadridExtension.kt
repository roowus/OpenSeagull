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
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.ui.GamePicker

/**
 * OpenSeagull's implementation of the OpenBubbles extension contract.
 *
 * ## Scope of this version
 *
 * The keyboard now renders a real, paginated grid of the games found in the user's installed
 * OpenPigeon, drawn with their own poster art. Tapping a game is recognised and logged but does
 * not yet start it — see [launchGame] — and the live view for a received game is still the status
 * view rather than a board.
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun keyboardOpened(
        callback: IViewUpdateCallback?,
        handle: IKeyboardHandle?,
        userCount: Int,
    ): RemoteViews {
        this.callback = callback

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
     * Start a game. Not yet implemented — see [didTapTemplate] for why this is the hard part.
     *
     * The tap is logged rather than silently ignored so that "the grid is wired" and "the grid is
     * decorative" are distinguishable from outside the process: `adb logcat -s SEAGULL:I`.
     */
    fun launchGame(name: String) {
        Log.i(TAG, "launch requested for '$name' — gameplay not wired up yet")
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
