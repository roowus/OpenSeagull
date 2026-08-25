package com.roowus.openseagull.host

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import android.widget.Toast
import com.roowus.openseagull.R
import android.window.OnBackInvokedDispatcher

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
 * ## Why two delivery paths, measured rather than assumed
 *
 * Back reaches an activity through whichever channel its window has armed, and the channels split
 * at API 33:
 *
 * - Below 33, back arrives as a key event (`KEYCODE_BACK`), gesture navigation included — the
 *   [Guarded] shell intercepts it in [Guarded.dispatchKeyEvent].
 * - At 33 and above — **measured on-device**, not read off release notes — the system attaches an
 *   `OnBackInvokedCallbackInfo` to the hosted window regardless of anything this app opts into,
 *   and a back press takes the `OnBackInvokedCallback` path. The first shipped version of this
 *   guard intercepted only key events; on such a device the press never reached it, the system's
 *   default callback fired, and the activity was destroyed exactly as if no guard existed. The
 *   log evidence is unambiguous: `CoreBackPreview: … Setting back callback
 *   OnBackInvokedCallbackInfo{…}` followed by `KnockoutActivity … DESTROYED` one press later.
 *
 * So from 33 up the guard registers its own [android.window.OnBackInvokedCallback] at
 * [android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT], which displaces the default
 * finish-on-back behaviour for that window. The two paths share one state machine ([Armed]) so
 * the two-step timing behaves identically whichever channel delivers the press.
 *
 * A dialog consumes back inside its own window and never reaches either hook, which matches the
 * intent: the guard exists for gestures aimed at the game, not at something floating over it.
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

            // API 33+: the system has armed its own finish-on-back callback on this window (see
            // the class KDoc — measured, not assumed). Registering ours at PRIORITY_DEFAULT
            // displaces it; without this the key shell below never sees a press.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.window.onBackInvokedDispatcher
                    .registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        BackPress(activity, armed(activity)),
                    )
                return
            }

            // The callback can be null on a window that has none yet; installing nothing there is
            // right — there is no back event for a window that never receives one.
            val inner = activity.window.callback ?: return
            activity.window.callback = Guarded(inner, armed(activity))
        }

        /** One two-step state machine per activity, shared by whichever channel delivers. */
        private fun armed(activity: Activity) = Armed(activity)

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
     * The two-step exit state machine, shared by both delivery channels so their timing cannot
     * drift apart. First press arms and says so; a second inside the window hands back `true`
     * meaning "let it through"; anything else is consumed.
     */
    private class Armed(private val activity: Activity) {

        private var lastBack = 0L

        /**
         * One back press. Returns whether the press should proceed to the normal behaviour
         * (finish), after arming or re-arming as needed.
         */
        fun consume(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBack < BackExitWindowMs) {
                lastBack = 0L
                return true
            }
            lastBack = now
            Toast.makeText(activity, R.string.back_again_to_leave, Toast.LENGTH_SHORT).show()
            return false
        }
    }

    /** The predictive-back channel's view of one press: consume when armed, finish on the second. */
    private class BackPress(
        private val activity: Activity,
        private val armed: Armed,
    ) : android.window.OnBackInvokedCallback {
        override fun onBackInvoked() {
            if (armed.consume()) activity.finish()
        }
    }

    /**
     * One shell around whatever the window already had — the sub-33 key-event path only. The
     * predictive-back path (33+) never reaches here; see the class KDoc for why both exist.
     */
    private class Guarded(
        private val inner: Window.Callback,
        private val armed: Armed,
    ) : Window.Callback by inner {

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.repeatCount == 0 &&
                event.action == KeyEvent.ACTION_UP &&
                !event.isCanceled
            if (!isBackUp) return inner.dispatchKeyEvent(event)

            // Second press inside the window: behave exactly as the unguarded window would.
            if (armed.consume()) return inner.dispatchKeyEvent(event)
            return true
        }
    }

    private const val TheirPackagePrefix = "com.openbubbles.openpigeon."

    /** First back arms, second inside this window exits. Long enough to read a toast; short enough that exits stay deliberate. */
    private const val BackExitWindowMs = 2_000L
}
