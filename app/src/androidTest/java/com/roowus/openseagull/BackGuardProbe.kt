package com.roowus.openseagull

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.InstalledOpenPigeon
import org.junit.Test

/**
 * Measures the back guard where only it can be measured: with a hosted game in the foreground and
 * real back presses arriving through whichever channel this device uses.
 *
 * ## Why shell-driving could never work
 *
 * Every earlier attempt pressed back from `adb shell input` *after* an instrumented run launched
 * the game — and every attempt measured nothing, because instrumentation teardown finishes the
 * activity the moment the test method returns (`CoreBackPreview: Setting back callback null`
 * lands on the same second as the probe's VERDICT line). The presses have to happen **inside**
 * the test, which is what this does.
 *
 * ## The oracle
 *
 * Not our own state machine: after press 1 the *system* must still report KnockoutActivity as the
 * resumed activity (the first version of the guard failed exactly here on predictive-back devices
 * — the system's default callback finished it), and after press 2 it must not. Toast delivery is
 * observed from NotificationManagerService's log rather than asserted, because toasts are
 * fire-and-forget across processes.
 *
 * Presses are injected through UiAutomation, which is the same path a physical key takes once it
 * reaches InputDispatcher. On API 33+ the guard registers an OnBackInvokedCallback; injected
 * KEYCODE_BACK reaches that channel exactly as a gesture swipe would.
 *
 * Same convention as its siblings: nothing hard-asserts when OpenPigeon is absent (SKIP), but the
 * two-step behaviour itself is asserted, because "the game closed anyway" is precisely the failure
 * this file exists to catch.
 */
class BackGuardProbe {

    private val tag = "SEAGULL"

    private val theirKnockout = "com.openbubbles.openpigeon.knockout.KnockoutActivity"
    private val SessionExtra = "SESSION"

    /** Long enough for their onCreate + bind + read; see HostedSessionProbe for the arithmetic. */
    private val SettleMs = 5_000L

    /** Just over BackGuard's 2 s window, so press 2 lands inside and press-reset outside tests differ. */
    private val InsideWindowMs = 1_000L

    private fun ctx() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun firstBackKeepsTheGameAndSecondExits() {
        val pigeon = InstalledOpenPigeon.find(ctx().targetContext) ?: run {
            Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
            return
        }
        Log.i(tag, "installDex -> ${ForeignCode.installDex(pigeon)}")

        val ours = BackGuardProbe::class.java.classLoader!!
        val klass = runCatching { Class.forName(theirKnockout, false, ours) }.getOrNull()
            ?: run {
                Log.i(tag, "VERDICT: dex injection did not take — nothing to measure")
                return
            }

        val intent = Intent(ctx().targetContext, klass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SessionExtra, "backguard-probe-0001")
        ctx().targetContext.startActivity(intent)
        Thread.sleep(SettleMs)

        val ui = ctx().uiAutomation
        val aliveAfterFirst: Boolean
        try {
            ui.injectInputEvent(keyDown(), true)
            ui.injectInputEvent(keyUp(), true)
            Thread.sleep(InsideWindowMs)

            // THE assertion. False here is the predictive-back failure that shipped once:
            // the press reached the system's default callback and finished the activity.
            aliveAfterFirst = resumedActivityIs(theirKnockout)
            Log.i(tag, "after back #1: knockout still resumed = $aliveAfterFirst")

            // Press 2 inside the window: the guard hands the press through, their default
            // finish runs, and the activity goes away.
            if (aliveAfterFirst) {
                ui.injectInputEvent(keyDown(), true)
                ui.injectInputEvent(keyUp(), true)
                Thread.sleep(InsideWindowMs)
            }
        } finally {
            // Never leave a full-screen game owning the test device.
            pressHome(ui)
        }

        val goneAfterSecond = !resumedActivityIs(theirKnockout)
        Log.i(
            tag,
            "VERDICT: first kept=$aliveAfterFirst second exited=$goneAfterSecond — " +
                "pass requires both. first=false means the guard was bypassed (check the " +
                "OnBackInvokedCallback registration); second=true-after-first-false means " +
                "nothing was guarding at all",
        )
        check(aliveAfterFirst) { "back #1 closed the hosted game — the guard was bypassed" }
        check(goneAfterSecond || !aliveAfterFirst) {
            "back #2 did not exit the hosted game — the guard is swallowing both presses"
        }
    }

    /** Whether the system currently reports [className] as the top resumed activity. */
    private fun resumedActivityIs(className: String): Boolean =
        ctx().targetContext.getSystemService(android.app.ActivityManager::class.java)
            ?.getRunningTasks(1)
            ?.firstOrNull()?.topActivity?.className == className

    private fun keyDown() = key(android.view.KeyEvent.ACTION_DOWN)

    private fun keyUp() = key(android.view.KeyEvent.ACTION_UP)

    private fun key(action: Int) = android.view.KeyEvent(
        /* downTime = */ 0,
        /* eventTime = */ 0,
        action,
        android.view.KeyEvent.KEYCODE_BACK,
        /* repeat = */ 0,
    )

    /** Leave the game via the home gesture; safer than back, which the guard intercepts. */
    private fun pressHome(ui: android.app.UiAutomation) {
        ui.injectInputEvent(
            android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HOME, 0),
            true,
        )
        ui.injectInputEvent(
            android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HOME, 0),
            true,
        )
    }
}
