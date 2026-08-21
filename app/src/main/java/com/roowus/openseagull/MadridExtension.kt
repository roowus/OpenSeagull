package com.roowus.openseagull

import android.content.Context
import android.widget.RemoteViews
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon

/**
 * OpenSeagull's implementation of the OpenBubbles extension contract.
 *
 * ## Scope of this version
 *
 * This is the **runtime-host skeleton**, not a finished extension. It implements all five methods
 * of [IMadridExtension] and renders a real status view built from the user's installed OpenPigeon
 * — which is what proves the architecture end to end inside OpenBubbles rather than only inside an
 * instrumented test. It does not yet render a playable keyboard or live game view.
 *
 * The unimplemented methods return a status view rather than a stub or an exception, so a bind
 * from OpenBubbles always produces something legible on screen. Where a method cannot yet do its
 * job, the view says so; it does not pretend to have succeeded.
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

    override fun keyboardOpened(
        callback: IViewUpdateCallback?,
        handle: IKeyboardHandle?,
        userCount: Int,
    ): RemoteViews {
        this.callback = callback
        return statusView()
    }

    override fun keyboardClosed() {
        callback = null
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
}
