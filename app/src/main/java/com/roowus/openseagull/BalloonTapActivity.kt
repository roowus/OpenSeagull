package com.roowus.openseagull

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.roowus.openseagull.host.ForeignCallException
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SessionRegistry

/**
 * Opens a tapped balloon, as the target of the balloon's own click PendingIntent.
 *
 * ## Why a PendingIntent, when the host has a tap callback
 *
 * `didTapTemplate` is the contract's tap path, and it stays wired — but measured against a real
 * OpenBubbles host it fired exactly once across a dozen taps. The rendered balloon is a native
 * `RemoteViews` platform view inside the host's Flutter UI, and a touch that lands on it dies
 * there: it never reaches the Flutter `InkWell` that would invoke `extension-template-tap`, so
 * the tap simply does nothing. There is no error anywhere — the log is silent, the user sees a
 * dead balloon.
 *
 * OpenPigeon does not have this problem because it does not rely on that callback either: its
 * balloon is Glance, and the board carries `clickable(onClick = actionStartActivity(intent))` —
 * a PendingIntent baked into the view, launched by Android itself when the view is tapped. This
 * is the same arrangement in plain `RemoteViews`: [com.roowus.openseagull.MadridExtension]'s
 * balloon builder calls `setOnClickPendingIntent(R.id.balloon_root, …)` pointing here.
 *
 * ## What this does that their PendingIntent doesn't
 *
 * Their tap starts their activity, which reads the board from *their* `GameSessionService`. A
 * hosted activity of ours reads it from [SessionRegistry] instead — via `SessionChannel`, the
 * bind their `GameSessionIPC` makes to what it thinks is its own service — so the session has to
 * be registered **before** their activity's `onCreate` binds for it. That registration, and the
 * write-back handle, are what this trampoline adds before handing off.
 *
 * The payload to decode is carried as an extra: the same `url` string the render decoded from,
 * handed back by the PendingIntent. The render-time `IMessageViewHandle` is already stashed in
 * `SessionRegistry.pendingHandles` and is promoted into the session by [SessionRegistry.open] —
 * that is the handle write-back will use, and it is *fresher* than a tap-delivered one, which
 * the host `markDead()`s the moment the tap callback returns.
 *
 * `Theme.NoDisplay`: nothing is drawn, the activity must `finish()` promptly, and it does — the
 * only work is one decode and one `startActivity`.
 */
class BalloonTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionId = intent.getStringExtra("SESSION")
        val url = intent.getStringExtra("URL")
        if (sessionId.isNullOrEmpty() || url == null) {
            Log.w("SEAGULL", "balloon tap arrived with no session or url — nothing to open")
            finish()
            return
        }

        val p = InstalledOpenPigeon.find(this)
        if (p == null) {
            Log.w("SEAGULL", "balloon tap but OpenPigeon is gone — cannot open ${sessionId.take(8)}…")
            finish()
            return
        }

        try {
            open(p, sessionId, url)
        } catch (e: ForeignCallException) {
            // Their decrypt or their game object threw on a payload we handed it — the same
            // catch didTapTemplate has, for the same reason: the failure is theirs, the decision
            // to show nothing rather than crash the host's tap is ours.
            Log.w("SEAGULL", "OpenPigeon threw while opening ${sessionId.take(8)}…", e)
            finish()
        }
    }

    private fun open(p: InstalledOpenPigeon, sessionId: String, url: String) {
        val (game, board, name) = MadridExtension.readBalloon(p, url, sessionId) ?: run {
            finish()
            return
        }
        val shown = board["game_name"]?.takeIf { it.isNotBlank() } ?: name
        if (!game.isSupported(board)) {
            Log.i("SEAGULL", "'$name' refuses this payload as unsupported — not opening")
            UnsupportedGameActivity.launch(this, shown, UnsupportedGameActivity.payloadRefused(shown))
            finish()
            return
        }
        val target = game.gameClass() ?: run {
            Log.w("SEAGULL", "'$name' reports no gameClass — nothing to launch")
            UnsupportedGameActivity.launch(this, shown, UnsupportedGameActivity.notHosted(shown))
            finish()
            return
        }

        // The session must exist before their activity binds for it. The handle this promotes is
        // the render-time one — see the class KDoc for why that is the right one, not a fallback.
        SessionRegistry.open(sessionId, name, board)

        val launch = Intent(this, target).apply {
            putExtra("SESSION", sessionId)
            putExtra("GAME", name)
            putExtra("DISPLAY_GAME", board["game_name"])
            // A distinct `data` per tap, as theirs does: without it a second tap on the same
            // balloon is an Intent equal to the first, which the framework delivers to the
            // existing task rather than re-running the open path.
            data = "data://${System.currentTimeMillis()}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        Log.i("SEAGULL", "opening '$name' (pendingIntent) session=${sessionId.take(8)}… keys=${board.size}")
        startActivity(launch)
        // CLEAR_TASK has already torn this task down by the time this returns; finish() is for
        // the case where the launch was refused and the activity is still here.
        finish()
    }
}
