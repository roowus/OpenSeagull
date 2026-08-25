package com.roowus.openseagull.host

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.CoreComponentFactory

/**
 * Prepares our process for one of *their* classes, in the instant before the framework builds it.
 *
 * ## Why this exists rather than a line in `SeagullApplication.onCreate`
 *
 * Hosting needs two preparations, and both must be complete before `Class.newInstance` runs:
 *
 * 1. Their dex on our ClassLoader, or the framework cannot resolve `android:name` at all.
 * 2. Their C++ runtime in the linker namespace — because their static initialiser loads a native
 *    library *during construction*, which is earlier than any hook inside the Activity.
 *
 * Measured, with the declarations in place but no native preparation:
 *
 * ```
 * java.lang.UnsatisfiedLinkError: dlopen failed: library "libopenbubblesextension.so" not found
 *     at java.lang.System.loadLibrary(System.java:1765)
 *     at com.openbubbles.openpigeon.knockout.KnockoutActivity.<clinit>(KnockoutActivity.kt:3015)
 *     at java.lang.Class.newInstance(Native Method)
 *     at android.app.AppComponentFactory.instantiateActivity(AppComponentFactory.java:97)
 * ```
 *
 * That `<clinit>` frame is the whole argument for this class. `onCreate` of the Activity never
 * runs; overriding anything on the Activity is too late by construction.
 *
 * Doing the work in [android.app.Application.onCreate] instead is correct but wasteful: it
 * measured **619 ms** on a cold start, and it was paid by every process including the one that
 * only answers OpenBubbles and draws the picker, which never hosts anything. This hook is called
 * once per component, only for components the framework is actually about to build, so a process
 * that opens no game pays nothing.
 *
 * ## Cost is still paid once, and it is paid on the main thread
 *
 * There is no way around that: the framework is mid-launch and blocked on this method returning.
 * The work is an mmap of an archive already on disk, not a copy, and it is the same work the
 * launch would otherwise fail without. It is logged with its elapsed time so a slow launch can be
 * attributed rather than guessed at.
 *
 * ## Failure is deliberately not swallowed here
 *
 * If preparation fails, [instantiateActivity] still delegates to super, and the framework throws
 * the same `ClassNotFoundException` or `UnsatisfiedLinkError` it would have thrown anyway. Turning
 * that into a silent no-show would hide the one diagnostic that explains it. Everything reflective
 * — the picker, the catalog, the send path — is untouched by a hosting failure and keeps working.
 */
@RequiresApi(Build.VERSION_CODES.P)
class HostedComponentFactory : CoreComponentFactory() {

    /**
     * Bring their code up if [className] is theirs, then let the framework build it as usual.
     *
     * The name is matched against their package prefix rather than a list of the eight declared
     * activities. A list would have to be kept in step with the manifest by hand, and the check it
     * would buy is one the framework already performs — an undeclared component never reaches this
     * method.
     *
     * Back-gesture interception is deliberately NOT done here: an `AppComponentFactory` sees the
     * class name but no instance, and the instance's window does not exist until `onCreate` has
     * run. That work lives in [BackGuard], registered process-wide from [SeagullApplication].
     */
    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ) = run {
        if (className.startsWith(TheirPackagePrefix)) prepareFor(className)
        super.instantiateActivity(cl, className, intent)
    }

    /**
     * Make their dex resolvable and their C++ runtime resident.
     *
     * `c++_shared` is loaded by name rather than as a side effect of loading the library the game
     * actually wants, for two reasons. It is the single dependency measured as missing — the
     * Java-side search-path append tells `System.loadLibrary` where the file is, but a
     * `DT_NEEDED` entry resolves through the linker namespace, which reflection over `DexPathList`
     * never touches. And a library already in the namespace is matched by **soname**, so bringing
     * it up once is enough for *their* `System.loadLibrary` call to then succeed on its own.
     *
     * That indirection is what keeps this general. We do not need a map from Activity to native
     * library, we do not need to know which library a future game of theirs loads, and Godot's
     * 71 MB engine library is never touched by a native game's launch.
     *
     * Both calls are idempotent and cached inside [ForeignCode], so the second Activity in a
     * process pays nothing.
     */
    private fun prepareFor(className: String) {
        val pigeon = InstalledOpenPigeon.find(this.let { appContext() } ?: return) ?: run {
            Log.w(TAG, "asked to build $className but OpenPigeon is not installed")
            return
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val dex = ForeignCode.installDex(pigeon)
        val runtime = ForeignCode.loadLibrary(pigeon, CppRuntime)
        // Last, and deliberately not through `pigeon` or any Context we hold: the table that has to
        // be merged into belongs to the Activity being built, which the framework created in
        // createBaseContextForActivity moments ago and did not hand to us. installResourcesEverywhere
        // finds it through ResourcesManager. Patching appContext()'s table instead would compile,
        // log success, and change nothing — an Activity's AssetManager is a different object.
        val resources = ForeignCode.installResourcesEverywhere(pigeon)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        Log.i(
            TAG,
            "prepared for $className in $elapsed ms — " +
                "dex=$dex runtime=$runtime resources=$resources",
        )
    }

    /**
     * A [android.content.Context] to look the installed package up through.
     *
     * An `AppComponentFactory` is constructed before the `Application` exists and is handed no
     * Context of its own, which is the one awkward consequence of hooking this early.
     * `ActivityThread.currentApplication()` is the documented-enough way back to one; it is
     * non-null by the time an Activity is being instantiated, because the framework creates the
     * Application first.
     *
     * `null` is handled rather than asserted: if that ordering ever changed, hosting would fail
     * with the framework's own error instead of a `NullPointerException` from in here.
     */
    private fun appContext(): android.content.Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "no Application context available (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val TAG = "SEAGULL"

        /** Their package. A string, not a type — nothing of theirs is referenced at build time. */
        const val TheirPackagePrefix = "com.openbubbles.openpigeon."

        /**
         * The bare name `System.loadLibrary` takes for `libc++_shared.so`.
         *
         * Their every native library links against it, which is why one preload covers all of
         * them.
         */
        const val CppRuntime = "c++_shared"
    }
}
