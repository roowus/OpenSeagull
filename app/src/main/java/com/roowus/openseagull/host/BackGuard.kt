package com.roowus.openseagull.host

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import android.widget.Toast
import com.roowus.openseagull.R

/**
 * A stray back gesture no longer ends a hosted game on the first press.
 *
 * ## The behaviour
 *
 * Their activities do not handle back — `onBackPressed` is overridden in exactly one of them
 * (WordHunt, and it just finishes), so the system default applies to every game: one back
 * gesture, one `finish()`, board gone. On a phone where back is a *swipe* from the screen edge,
 * that gesture is easy to fire by accident — reaching for a ball near the cushion, or swiping at
 * what turns out to be an overlay menu rather than a dialog (a real dialog consumes back itself;
 * an overlay state does not).
 *
 * The guard makes leaving a two-step decision: the first back within [BackExitWindowMs] shows
 * "Press back again to leave the game" and is consumed; a second inside the window passes
 * through and does what it always did. Games keep their state in the session, so a deliberate
 * exit-and-return loses nothing — and now an accidental swipe loses nothing either.
 *
 * ## Why here, and why it took two attempts to land here
 *
 * The obvious shape — one wrapper subclass per game activity, overriding back in `onCreate` —
 ** cannot exist in this project. A subclass names its superclass at compile time, and this
 * project compiles against none of OpenPigeon's code: everything of theirs loads at runtime
 * through [ForeignCode], which is what keeps the APK content-free and the fork problem solved.
 * The wrappers were written, failed to compile (`Unresolved reference 'openbubbles'`), and were
 * deleted rather than worked around.
 *
 * What survives needs no knowledge of any specific activity:
 *
 * 1. [Application.ActivityLifecycleCallbacks] sees every activity built in this process,
 *    including `:godot`, including games added to their registry after this file was written.
 * 2. For each one whose class is theirs, the activity's [Window.Callback] is wrapped. The
 *    wrapper delegates everything, and intercepts exactly one event: `KEYCODE_BACK` on action
 *    up, uncanceled.
 *
 * Both hooks are public platform API available since well below minSdk 26, and neither touches
 * androidx — deliberately, because their activities extend plain `android.app.Activity`, so
 * `OnBackPressedDispatcher` (a `ComponentActivity` feature) was never applicable to them either.
 *
 * ## Ordering that makes the wrap safe with their UI stacks
 *
 * `onActivityCreated` fires after the activity's `onCreate` has returned, so anything the
 * activity installed — most importantly the dispatcher Compose's `setContent` puts on the window
 * — is already the innermost link when the guard wraps around it. Delegation reaches their code
 * unchanged; the guard is one shell on the outside, never a replacement.
 *
 * A dialog consumes back inside its own window and never reaches this callback, which matches
 * the intent: the guard exists for gestures aimed at the game, not at something floating over
 * it.
 *
 * ## Why key events and not `onBackPressedDispatcher` or predictive back
 *
 * The app does not opt into `android:enableOnBackInvokedCallback`, so on every API level the
 * system delivers back to the activity as a key event — gesture navigation included. Intercepting
 * `dispatchKeyEvent` therefore covers API 26 through current with one code path. If the app ever
 * opts into predictive back, back stops arriving as keys and this file needs an
 * `OnBackInvokedCallback` registered alongside — the failure mode of forgetting is the old
 * instant-exit behaviour returning, not a crash.
 *
 * ## Scope, stated precisely
 *
 * Every activity whose class name starts with `com.openbubbles.openpigeon.` gets the guard,
 * whether or not this project has an `<activity>` entry for it yet. Activities of ours
 * (diagnostics, the balloon trampoline, the unsupported-game dialog) keep their normal back
 * behaviour: they are transient screens where instant exit is correct.
 */
object BackGuard {

    /** Registered once from [com.roowus.openseagull.SeagullApplication.onCreate], every process. */
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(Lifecycle())
    }

    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        private fun guarded(activity: Activity) =
            activity.javaClass.name.startsWith(TheirPackagePrefix)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (!guarded(activity)) return
            // The callback can be null on a window that has none yet; installing nothing there is
            // right — there is no back event for a window that never receives one.
            val inner = activity.window.callback ?: return
            activity.window.callback = Guarded(inner, activity)
        }

        // The remaining seven are required by the interface and genuinely empty: teardown needs no
        // mirror of the wrap because a destroyed activity takes its window with it.
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * One shell around whatever the window already had. Everything delegates; only back-up
     * is examined.
     */
    private class Guarded(
        private val inner: Window.Callback,
        private val activity: Activity,
    ) : Window.Callback by inner {

        private var lastBack = 0L

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.repeatCount == 0 &&
                event.action == KeyEvent.ACTION_UP &&
                !event.isCanceled
            if (!isBackUp) return inner.dispatchKeyEvent(event)

            val now = SystemClock.elapsedRealtime()
            if (now - lastBack >= BackExitWindowMs) {
                lastBack = now
                Toast.makeText(activity, R.string.back_again_to_leave, Toast.LENGTH_SHORT).show()
                return true
            }
            // Second press inside the window: behave exactly as the unguarded window would.
            lastBack = 0L
            return inner.dispatchKeyEvent(event)
        }
    }

    private const val TheirPackagePrefix = "com.openbubbles.openpigeon."

    /** First back arms, second inside this window exits. Long enough to read a toast; short enough that exits stay deliberate. */
    private const val BackExitWindowMs = 2_000L
}
