package com.roowus.openseagull

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.InstalledOpenPigeon
import org.junit.Test

/**
 * Asks the one question standing between [ForeignCode] and a playable board: **is a resolvable
 * class enough to start an Activity, or does our manifest have to declare it too?**
 *
 * `ForeignCodeProbe` proved their classes become resolvable by our loader after
 * [ForeignCode.installDex]. That answers `ClassNotFoundException` and nothing else. Starting an
 * Activity crosses a second gate that has never been measured here: the framework resolves the
 * component through **PackageManager**, against the manifest of the installed APK, before our
 * process ever loads the class. A name our loader can resolve but our manifest never declared is
 * two different failures wearing one intent.
 *
 * The distinction is worth measuring rather than assuming, because the two call for opposite work:
 *
 * - If resolution is the obstacle, the fix is a manifest entry naming *their* class — cheap, and
 *   the class need not exist at build time because `android:name` is only a string.
 * - If resolution succeeds and the launch still fails, the obstacle is inside their code, and the
 *   likely cause is resources: their activities are declared with
 *   `android:theme="@style/Theme.OpenBubblesSampleExtension"`, which lives in *their* table, and
 *   `setContentView(R.layout.activity_knockout)` reads an id that means nothing against ours.
 *
 * Guessing wrong here means writing `addAssetPath` merge code for a problem that was actually a
 * missing four-line manifest entry, or the reverse.
 *
 * Knockout is the subject rather than Godot deliberately: it is one of the four native games behind
 * the single `openbubblesextension` library, it runs in our own process rather than a `:godot` one,
 * and it is the family that fails *silently* on an empty session — so a launch that "works" here
 * proves the framework let it through rather than that the game is playable.
 *
 * Same convention as its siblings: **nothing asserts**. The outcome is a printed verdict.
 *
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 */
class HostedActivityProbe {

    private val tag = "SEAGULL"

    private val theirKnockout = "com.openbubbles.openpigeon.knockout.KnockoutActivity"

    /**
     * How long to stay alive after `startActivity` so the launch it queued can actually happen.
     *
     * Generous on purpose. The work behind it is a dex mmap of a 500 MB archive plus a `dlopen`,
     * measured at 619 ms when it ran on a cold start, and this probe is not timing anything — it
     * is keeping the process from being torn down. Too short and a slow launch reads as a clean
     * one, which is the failure this probe already had once.
     */
    private val LaunchObservationWindowMs = 8_000L

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Does the framework let us name one of their Activities, and where exactly does it stop?
     *
     * Three measurements in ascending order of commitment, because a failure at any one of them
     * makes the next meaningless:
     *
     * 1. **Class resolution through our loader** — the gate [ForeignCode.installDex] exists to open.
     * 2. **Component resolution through PackageManager** — `resolveActivity` against an explicit
     *    component answers "is this declared and enabled" without starting anything and without
     *    throwing. This is the gate this probe was written for.
     * 3. **An actual `startActivity`** — because resolution and launch are not the same check, and
     *    only the launch runs their `onCreate`.
     *
     * Step 3 is attempted even when step 2 says no, so the exception text is captured rather than
     * predicted. `NEW_TASK` is required because a test Context is not an Activity.
     */
    @Test
    fun theirActivityIsNameableByOurProcess() {
        val pigeon = InstalledOpenPigeon.find(ctx()) ?: run {
            Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
            return
        }

        val injected = ForeignCode.installDex(pigeon)
        Log.i(tag, "installDex -> $injected")

        val ours = HostedActivityProbe::class.java.classLoader!!
        val klass = runCatching { Class.forName(theirKnockout, false, ours) }
        Log.i(
            tag,
            "1. class via our loader -> " +
                (klass.getOrNull()?.name ?: klass.exceptionOrNull()!!.javaClass.simpleName),
        )
        if (klass.isFailure) {
            Log.i(tag, "VERDICT: dex injection did not take — the later gates cannot be measured")
            return
        }

        val intent = Intent(ctx(), klass.getOrThrow()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = ctx().packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        Log.i(
            tag,
            "2. PackageManager.resolveActivity -> " +
                (resolved?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "null"),
        )

        val launch = runCatching { ctx().startActivity(intent) }
        Log.i(
            tag,
            "3. startActivity -> " + (
                launch.exceptionOrNull()
                    ?.let { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
                    ?: "no exception"
                ),
        )

        // startActivity only queues the launch. Returning here ends the test, and instrumentation
        // force-stops the process on its way out — which kills the launch before the framework has
        // instantiated anything, so every interesting outcome (a <clinit> that throws, a board that
        // renders, a blank one) is destroyed before it happens. The first run of this probe looked
        // clean for exactly that reason.
        //
        // Waiting is not a substitute for observing: nothing here can see their Activity, because
        // their class is loaded by a loader this test has no handle on and would be a different
        // type even if it did. The wait exists to keep the process alive long enough for the
        // framework to reach the `<clinit>`, and the verdict below says where to look for what it
        // did — logcat's own frames, not an assertion in here.
        Thread.sleep(LaunchObservationWindowMs)
        Log.i(tag, "4. waited ${LaunchObservationWindowMs}ms — the launch has had time to run")

        Log.i(
            tag,
            when {
                resolved == null && launch.isFailure ->
                    "VERDICT: our manifest must DECLARE their Activity — a resolvable class is not " +
                        "enough. Next step is an <activity android:name=\"$theirKnockout\"> entry, " +
                        "not resource merging"
                resolved == null ->
                    "VERDICT: PackageManager did not resolve it and yet startActivity threw " +
                        "nothing — the launch failed asynchronously, so check logcat for the " +
                        "framework's own complaint before concluding anything"
                launch.isFailure ->
                    "VERDICT: the component IS declared and still would not start — the obstacle is " +
                        "past resolution, so read the exception above rather than adding manifest"
                else ->
                    "VERDICT: their Activity resolved and started from our process. Both gates " +
                        "are open — the <activity> declaration answers PackageManager and " +
                        "installDex answers our loader. Whether it RENDERED is a separate " +
                        "question, and startActivity is asynchronous so it cannot answer it: " +
                        "Knockout opens a blank new game on an empty session without " +
                        "complaining, and a <clinit> that fails to load a native library " +
                        "crashes AFTER this returns. Read the crash buffer, then the screen"
            },
        )
    }
}
