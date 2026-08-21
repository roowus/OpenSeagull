package com.roowus.openseagull

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Where picker taps land.
 *
 * ## Why a receiver, and why the extension is reached through a static
 *
 * A [android.widget.RemoteViews] inflates in **OpenBubbles'** process, so a tap on it is dispatched
 * by their view hierarchy, not ours. The only handler RemoteViews can carry is a
 * [android.app.PendingIntent], and the only way back into our process is for that PendingIntent to
 * start one of our components. Glance hides this behind `actionRunCallback`, but the machinery
 * underneath is exactly this.
 *
 * That means the tap arrives on a fresh [BroadcastReceiver] with no reference to the live
 * [MadridExtension] holding the host's update callback. It is reached through
 * [MadridExtensionService.extension], the static the service already keeps for the same reason —
 * the host binds and unbinds freely, and state has to outlive any single bind.
 *
 * If that static is null the tap is dropped with a log line rather than reconstructing an
 * extension: without a bind there is no [com.bluebubbles.messaging.IViewUpdateCallback] to push a
 * redraw to, so a rebuilt extension would have nowhere to send its view. Silently doing nothing
 * would be indistinguishable from a broken PendingIntent, hence the log.
 *
 * The receiver is **not exported** (see the manifest): these PendingIntents are created by us and
 * merely *held* by the host, so no other app needs to send us this broadcast.
 */
class PickerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extension = MadridExtensionService.extension
        if (extension == null) {
            Log.w(TAG, "picker tap ${intent.action} with no live extension — dropping")
            return
        }

        when (intent.action) {
            ActionChangePage -> {
                val delta = intent.getIntExtra(ExtraPageDelta, 0)
                Log.i(TAG, "picker page delta $delta")
                extension.changePage(delta)
            }

            ActionLaunchGame -> {
                val name = intent.getStringExtra(ExtraGameName)
                if (name == null) {
                    Log.w(TAG, "launch tap with no game name — dropping")
                    return
                }
                Log.i(TAG, "picker launch $name")
                extension.launchGame(name)
            }

            else -> Log.w(TAG, "unrecognised picker action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "SEAGULL"

        const val ActionChangePage = "com.roowus.openseagull.action.CHANGE_PAGE"
        const val ActionLaunchGame = "com.roowus.openseagull.action.LAUNCH_GAME"

        const val ExtraPageDelta = "com.roowus.openseagull.extra.PAGE_DELTA"
        const val ExtraGameName = "com.roowus.openseagull.extra.GAME_NAME"
    }
}
