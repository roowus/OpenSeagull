package com.roowus.openseagull

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Says out loud that a tapped balloon cannot be opened, instead of dropping it in logcat.
 *
 * ## Why this is ours rather than theirs
 *
 * OpenPigeon has an activity for exactly this — `GameNotFound` — and hosting it the way we host
 * their game activities was the obvious move. Reading it ruled that out on two counts, one of which
 * matters far more than the other.
 *
 * The one that matters: its `onCreate` builds `MixpanelAPI.getInstance(…)` against a hardcoded
 * project token and fires a `missing_game` event. Hosting it would send analytics out of *our*
 * process, to *their* project, every time a user taps a balloon we cannot open — on a project whose
 * whole premise is that it ships and does nothing of theirs beyond what the user's own installed
 * copy already does. That is an outward-facing side effect nobody asked for, and it is not the kind
 * of thing to inherit by accident.
 *
 * The lesser one: it builds its dialog with `MaterialAlertDialogBuilder` under
 * `ThemeOverlay_Material3_MaterialAlertDialog`. That needs a Material theme; ours is
 * `Theme.AppCompat.DayNight.NoActionBar` and we carry no Material dependency. Solvable, but only
 * worth solving if the first problem were not there.
 *
 * So this is a dozen lines of our own instead. It also gets to be *more* useful than theirs, because
 * the two ways a tap dead-ends here have different causes and different fixes, and we know which one
 * happened — see [REASON].
 */
class UnsupportedGameActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Their own naming for the same two extras, so a reader moving between the two code bases is
        // not translating. DISPLAY_GAME is the human name off the payload ("8 Ball", "Word Hunt");
        // it can be absent on a malformed balloon, hence the fallback.
        val name = intent.getStringExtra("DISPLAY_GAME")?.takeIf { it.isNotBlank() } ?: "this game"

        AlertDialog.Builder(this)
            .setTitle("Can't open $name")
            .setMessage(intent.getStringExtra(REASON) ?: REASON_UNKNOWN)
            .setPositiveButton("OK") { _, _ -> finishAndRemoveTask() }
            .setOnCancelListener { finishAndRemoveTask() }
            .show()
    }

    companion object {
        /**
         * Extra carrying the already-worded explanation.
         *
         * A string rather than an enum because the caller knows things this activity cannot see —
         * which class was missing, what the installed OpenPigeon said — and the alternative is a
         * `when` here that has to be kept in step with the call sites by hand.
         */
        const val REASON = "com.roowus.openseagull.REASON"

        /** The installed OpenPigeon understands the game but refuses this particular payload. */
        fun payloadRefused(name: String): String =
            "Your installed OpenPigeon has $name, but doesn't recognise this version of it. " +
                "The game was probably sent from a newer build than the one on this phone."

        /**
         * No `<activity>` of ours can host what the game asked to open.
         *
         * Deliberately does not promise that OpenPigeon itself plays it. The one case known to
         * reach here is their `WordGames`, which is a chooser rather than a game — its
         * `getNewGameData` returns null and its `gameClass` returns their `GameNotFound`, because
         * opening it directly is not a thing that happens. Wording this as "OpenPigeon can, we
         * can't" would be wrong there, and wrong in the confident direction.
         */
        fun notHosted(name: String): String =
            "OpenSeagull can't open $name. Each game has to be declared up front, and there's no " +
                "screen registered for this one."

        private const val REASON_UNKNOWN = "OpenSeagull couldn't open this one."
    }
}
