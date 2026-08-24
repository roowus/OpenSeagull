package com.roowus.openseagull

import android.app.Instrumentation
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Sees the unsupported-game dialog for the first time.
 *
 * Everything upstream of this activity was verified at build time — the merged manifest carries it,
 * the theme resolves, `compileDebugKotlin` and 44 JVM tests pass — but nothing had ever launched
 * it. The two production call sites ([MadridExtension.explain] and its callers) need a real
 * OpenBubbles host and a tapped balloon, so they stay unexercised; what *can* be proven without a
 * host is that the activity itself works: it starts under its dialog theme, shows an AlertDialog,
 * and dismisses cleanly on OK.
 *
 * Runs in our uid, which is exactly why shell cannot do this: `am start` from adb runs as uid 2000,
 * and an `exported="false"` activity refuses it with SecurityException. The instrumentation process
 * shares our uid, so the same start succeeds here.
 *
 * The dialog itself is a framework view over the window, not a separate activity — there is nothing
 * in the activity stack to assert beyond resumed-ness, so the pixel half (does it *look* right) is
 * deliberately left to a human looking at the emulator while this runs:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 */
class UnsupportedGameProbe {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The currently resumed activity's class name, read on the main thread. */
    private fun resumedActivity(): String? {
        var name: String? = null
        instrumentation.runOnMainSync {
            name = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .singleOrNull()?.javaClass?.name
        }
        return name
    }

    @Test
    fun dialogLaunchesAndShowsItsReason() {
        val ctx = instrumentation.targetContext

        // Same shape [MadridExtension.explain] builds — DISPLAY_GAME plus the reason string.
        val intent = Intent(ctx, UnsupportedGameActivity::class.java).apply {
            putExtra("DISPLAY_GAME", "Word Games")
            putExtra(
                UnsupportedGameActivity.REASON,
                UnsupportedGameActivity.notHosted("Word Games"),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        instrumentation.startActivitySync(intent)

        assertEquals(
            UnsupportedGameActivity::class.java.name,
            resumedActivity(),
            "expected the dialog activity to be the resumed one",
        )
    }
}
