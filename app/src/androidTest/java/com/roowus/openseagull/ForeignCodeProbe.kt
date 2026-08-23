package com.roowus.openseagull

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.InstalledOpenPigeon
import org.junit.Test

/**
 * Exercises **[ForeignCode] itself**, not the mechanics it was built from.
 *
 * `GameplayFeasibilityProbe` already proved that their dex can be appended to our loader and that
 * their native library loads after one preload. This is a different question and it is worth
 * asking separately: [ForeignCode] is a *rewrite* of those measurements, and it added logic the
 * probe never had — ABI chosen by reading their archive instead of assuming ours, idempotent
 * caching, a [ForeignCode.Result] type in place of free-text strings, and a dependency loop that
 * terminates rather than a hand-run sequence.
 *
 * None of that is covered by the earlier probe. A faithful-looking transcription that quietly
 * changed behaviour would compile, and the old probe would still pass, because the old probe tests
 * its own private copies. This file closes that gap by driving the production entry points.
 *
 * Same conventions as its siblings: **nothing asserts**. A blocked host is a finding to be printed,
 * not a red test — that distinction is why these are probes and not unit tests.
 *
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 */
class ForeignCodeProbe {

    private val tag = "SEAGULL"

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pigeonOrSkip(): InstalledOpenPigeon? {
        val p = InstalledOpenPigeon.find(ctx())
        if (p == null) Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
        return p
    }

    /**
     * Does [InstalledOpenPigeon.sourceDir] name an archive we can actually open?
     *
     * The accessor is new and every other check here depends on it. Reading four bytes is enough:
     * `PK` is the zip local-header magic, and a path that yields it is a path the
     * linker can mmap out of. A path that resolves but is unreadable would otherwise surface much
     * later as an unexplained "library not found".
     */
    @Test
    fun theirApkPathIsReadable() {
        val p = pigeonOrSkip() ?: return
        val path = p.sourceDir
        Log.i(tag, "sourceDir = $path")
        if (path == null) {
            Log.i(tag, "VERDICT: no APK path — ForeignCode cannot work on this device")
            return
        }
        val file = java.io.File(path)
        val magic = runCatching {
            file.inputStream().use { stream ->
                val head = ByteArray(4)
                val read = stream.read(head)
                if (read == 4) head.joinToString("") { "%02x".format(it) } else "short read ($read)"
            }
        }
        Log.i(
            tag,
            "exists=${file.exists()} canRead=${file.canRead()} length=${file.length()} " +
                "magic=${magic.getOrNull() ?: magic.exceptionOrNull()?.javaClass?.simpleName}",
        )
        Log.i(
            tag,
            if (magic.getOrNull() == "504b0304") {
                "VERDICT: their APK is readable by our uid — in-place loading is possible"
            } else {
                "VERDICT: their APK is NOT readable as a zip — everything below will fail"
            },
        )
    }

    /**
     * Does [ForeignCode.installDex] make one of their classes resolvable by **our** loader?
     *
     * The check that matters is `Class.forName(name, false, ourLoader)`, not
     * [InstalledOpenPigeon.loadClassOrNull] — the latter goes through *their* loader and has always
     * worked, so using it here would pass whether or not the injection did anything. That is the
     * vacuous-assertion trap, and this comment exists so nobody "simplifies" the call back into it.
     *
     * `initialize = false` because resolving the name is the whole question; running their static
     * initialiser is a separate risk with no bearing on it.
     *
     * The second call measures idempotency: their `initGameSession` runs from both `onCreate` and
     * `onNewIntent`, so a repeat is the normal case rather than an edge one, and it must report
     * `alreadyDone` rather than append their APK a second time.
     */
    @Test
    fun installDexMakesTheirClassResolvableByOurLoader() {
        val p = pigeonOrSkip() ?: return
        val ours = ForeignCodeProbe::class.java.classLoader!!
        val theirClass = "com.openbubbles.openpigeon.MadridExtension"

        val before = runCatching { Class.forName(theirClass, false, ours) }
        Log.i(
            tag,
            "before injection: our loader -> " +
                (before.getOrNull()?.name ?: before.exceptionOrNull()!!.javaClass.simpleName),
        )

        val first = ForeignCode.installDex(p)
        Log.i(tag, "installDex (1st) -> $first")
        val second = ForeignCode.installDex(p)
        Log.i(tag, "installDex (2nd) -> $second")

        val after = runCatching { Class.forName(theirClass, false, ours) }
        Log.i(
            tag,
            "after injection: our loader -> " +
                (after.getOrNull()?.name ?: after.exceptionOrNull()!!.javaClass.simpleName),
        )

        val idempotent = second is ForeignCode.Result.Ok && second.alreadyDone
        Log.i(
            tag,
            when {
                before.isSuccess ->
                    "VERDICT: their class was ALREADY resolvable before injection — this probe " +
                        "cannot tell whether installDex did anything (did an earlier test run first?)"
                after.isSuccess && idempotent ->
                    "VERDICT: installDex works and is idempotent — their Activity can be named in " +
                        "an Intent our process can resolve"
                after.isSuccess ->
                    "VERDICT: installDex works but the repeat call did NOT report alreadyDone — " +
                        "the guard is not holding, and dexElements will grow per call"
                else ->
                    "VERDICT: installDex did not make their class resolvable — hosting is blocked"
            },
        )
    }

    /**
     * Does [ForeignCode.loadLibrary] load their shared native library, and what did it preload?
     *
     * `openbubblesextension` is the one behind Pool, Golf, Knockout and Shuffle, so this single
     * result covers all four native games. The interesting half is the detail string: a bare
     * `loaded` would mean the dependency chain never fired, which would contradict the earlier
     * measurement and imply something else already pulled `libc++_shared.so` into the namespace.
     *
     * A second call is made for the same reason as above — a hosted game may be opened repeatedly,
     * and `System.loadLibrary` on an already-loaded library is a no-op that must not be reported as
     * a fresh success with an empty preload list.
     */
    @Test
    fun theirSharedNativeLibraryLoadsThroughForeignCode() {
        val p = pigeonOrSkip() ?: return

        val path = ForeignCode.installNativePath(p)
        Log.i(tag, "installNativePath -> $path")

        val first = ForeignCode.loadLibrary(p, "openbubblesextension")
        Log.i(tag, "loadLibrary (1st) -> $first")
        val second = ForeignCode.loadLibrary(p, "openbubblesextension")
        Log.i(tag, "loadLibrary (2nd) -> $second")

        val idempotent = second is ForeignCode.Result.Ok && second.alreadyDone
        Log.i(
            tag,
            when {
                first is ForeignCode.Result.Ok &&
                    first.detail.contains("after preloading") && idempotent ->
                    "VERDICT: their native library loads from inside their APK, at the cost of an " +
                        "ordered preload and no file copy — the four native games are hostable"
                first is ForeignCode.Result.Ok && idempotent ->
                    "VERDICT: loaded with NO preload — unexpected against the earlier measurement; " +
                        "check whether something already pulled its dependency into the namespace"
                first is ForeignCode.Result.Ok ->
                    "VERDICT: loaded, but the repeat call did NOT report alreadyDone — the load is " +
                        "a no-op underneath, so this is a reporting bug rather than a broken load, " +
                        "and it still misleads every caller that reads alreadyDone"
                else ->
                    "VERDICT: their native library did not load — the four native games are blocked"
            },
        )
    }
}
