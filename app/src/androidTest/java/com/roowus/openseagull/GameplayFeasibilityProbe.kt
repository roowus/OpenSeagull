package com.roowus.openseagull

import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.InstalledOpenPigeon
import dalvik.system.PathClassLoader
import org.junit.Test

/**
 * Measures what actually stands between OpenSeagull and running a game, rather than reasoning
 * about it.
 *
 * Delegation is already ruled out: every game activity in OpenPigeon's manifest is
 * `android:exported="false"`, which the framework enforces across packages regardless of signature.
 * So their `Activity` has to run in *our* process, from their classes. That is the "harder path",
 * and it has three known obstacles. Each test below measures exactly one of them, because the
 * design that follows depends on which are real and which are folklore.
 *
 * Nothing here asserts a preferred answer. These tests record findings and pass; a build decision
 * made on a guess is the thing being avoided.
 *
 * ## What they measured (API 36 emulator, OpenPigeon v1.1.0)
 *
 * 1. **Their classes load, and they link.** `KnockoutActivity` resolves through
 *    `AppCompatActivity <- FragmentActivity <- ComponentActivity <- Activity`, and
 *    `GodotGameActivity` through `GodotActivity`, from a loader parented to ours. The engine is
 *    there too (`org.godotengine.godot.Godot`). Walking the superclass chain forces linkage, so
 *    this is stronger than "the name resolved" — nothing they inherit from is missing here.
 * 2. **Package ids do collide, but `addAssetPath` is not blocked** (see that test's KDoc). This is
 *    the finding that shapes the design, and it is less damning than expected.
 * 3. **The catalog is mostly Godot: 18 Godot, 7 native, 1 sentinel.** So a native-only host would
 *    reach barely a quarter of the games, and Godot cannot be deferred as a later phase.
 *
 * Note the emulator reports 26 registry entries where the phone reports 25 — their installed builds
 * differ (`shuffle` is absent from the phone's dex). Both are correct for their device.
 *
 * Findings go to a file as well as logcat. The log buffer is not durable — an emulator that dies
 * after a green run takes every finding with it, which happened once and cost a whole run. The
 * report is written to the app's external files dir, readable afterwards with:
 *
 * ```
 * adb shell run-as com.roowus.openseagull cat files/feasibility.txt
 * ```
 */
class GameplayFeasibilityProbe {

    private companion object {
        /** Under `filesDir`, so `run-as` can read it without external-storage permissions. */
        const val ReportFile = "feasibility.txt"

        /**
         * Whether this process has already cleared the report. JUnit builds a fresh instance per
         * test, so per-instance state would truncate three times and leave only the last test's
         * findings — exactly the loss this file exists to prevent.
         */
        var truncated = false
    }

    private val tag = "SEAGULL"

    /**
     * Appends to `files/feasibility.txt` as well as logcat, so a finding outlives the log buffer
     * and the device. Failure to write is itself logged rather than thrown — a probe that cannot
     * save its report should still report.
     */
    private fun record(line: String) {
        Log.i(tag, line)
        try {
            val file = java.io.File(context().filesDir, ReportFile)
            // Truncate once per process, not once per test: three tests share one report, but a
            // second run must not read as a continuation of the first.
            if (!truncated) {
                file.writeText("")
                truncated = true
            }
            file.appendText(line + "\n")
        } catch (e: java.io.IOException) {
            Log.w(tag, "could not append to $ReportFile", e)
        }
    }

    /** Marks which test the following findings came from, since all three share one file. */
    private fun beginSection(name: String) {
        record("")
        record("=== $name ===")
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pigeonOrSkip(): InstalledOpenPigeon? {
        val p = InstalledOpenPigeon.find(context())
        if (p == null) record("SKIP: OpenPigeon is not installed on this device")
        return p
    }

    /**
     * Obstacle 1: can the framework instantiate their Activity at all?
     *
     * An `Activity` is constructed by the framework, which uses the app's *own* ClassLoader — not
     * whatever loader we happen to hold. So `createPackageContext(INCLUDE_CODE)` giving us a
     * working loader is necessary but not sufficient: unless their dex is reachable from our
     * loader, a manifest entry naming their class fails with ClassNotFoundException at launch.
     *
     * This checks whether their dex can be reached from a loader whose *parent chain* includes
     * ours, which is the shape any hosting scheme would need.
     */
    @Test
    fun theirActivityClassesAreLoadableFromOurSide() {
        beginSection("obstacle 1: are their Activity classes loadable from our side?")
        val pigeon = pigeonOrSkip() ?: return

        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo for ${pigeon.packageName} (${e.javaClass.simpleName})")
            return
        }

        record("their apk = ${info.sourceDir}")
        record("their nativeLibraryDir = ${info.nativeLibraryDir}")

        // A loader over their APK, parented to ours: their classes resolve, and anything they share
        // with us (androidx, kotlin stdlib) comes from our copy rather than being loaded twice.
        val merged = PathClassLoader(
            info.sourceDir,
            info.nativeLibraryDir,
            javaClass.classLoader,
        )

        val probes = listOf(
            "com.openbubbles.openpigeon.knockout.KnockoutActivity",
            "com.openbubbles.openpigeon.godot.GodotGameActivity",
            "com.openbubbles.openpigeon.MadridExtension",
        )
        probes.forEach { fqcn ->
            val result = try {
                val k = merged.loadClass(fqcn)
                // Walking the superclass chain forces linkage, which is where a class that "loads"
                // but references something absent in our process actually fails.
                generateSequence<Class<*>>(k) { it.superclass }.take(6).joinToString(" <- ") {
                    it.simpleName
                }
            } catch (e: ClassNotFoundException) {
                "ABSENT (${e.javaClass.simpleName})"
            } catch (e: LinkageError) {
                "UNLINKABLE (${e.javaClass.simpleName}: ${e.message})"
            }
            record("loadable $fqcn -> $result")
        }
    }

    /**
     * Obstacle 2 — the one that decides the architecture: do our resource ids collide with theirs?
     *
     * An Activity resolves `R.layout.foo` against the `Resources` of the process it runs in. Both
     * APKs are ordinary apps, so both almost certainly got package id `0x7f` from aapt2, and a
     * merged AssetManager would have two tables claiming the same id — their layout id would
     * silently resolve to whichever table won.
     *
     * `PickerRenderProbe` already showed the effect from the outside (`0x7f070157` is a pool poster
     * in their table and absent from ours). This measures the cause: the actual package id each
     * table was built with.
     *
     * Measured on API 36: **both are `0x7f`** — ours `seagull_mark = 0x7f07006d`, theirs
     * `checkers = 0x7f0700ac`. So a merged table has two packages claiming one id. What is *not*
     * true is the pessimistic reading: `addAssetPath` is still reachable and accepted their APK
     * (cookie 14, non-zero). The collision is therefore about **name resolution**, not about
     * whether the paths can coexist — `getIdentifier` picks a winner per package name, and the
     * loser's ids are the ones that silently resolve wrong.
     *
     * That leaves the architecture question open rather than settled: either move ours off `0x7f`
     * at build time (aapt2 `--package-id`, which needs `--allow-reserved-package-id` below `0x7f`),
     * or give each hosted Activity a `Resources` built on an AssetManager holding **only** their
     * APK, so nothing of ours is in the table to collide. The second needs no build-time change and
     * is the cheaper thing to try first.
     */
    @Test
    fun resourcePackageIdsCollide() {
        beginSection("obstacle 2: do our resource package ids collide with theirs?")
        val pigeon = pigeonOrSkip() ?: return

        fun packageIdOf(res: Resources, pkg: String, sampleType: String, sampleName: String): String {
            val id = res.getIdentifier(sampleName, sampleType, pkg)
            if (id == 0) return "no sample found ($pkg:$sampleType/$sampleName)"
            return "0x%02x (from %s = 0x%08x)".format(id ushr 24, sampleName, id)
        }

        val ours = packageIdOf(context().resources, context().packageName, "drawable", "seagull_mark")
        val theirs = packageIdOf(pigeon.resources, pigeon.packageName, "drawable", "checkers")
        record("package id ours=$ours")
        record("package id theirs=$theirs")

        // Can one AssetManager hold both APKs at once? addAssetPath is hidden API, so whether it is
        // reachable is itself a finding — and it is the mechanism any merged-Resources plan needs.
        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo (${e.javaClass.simpleName})")
            return
        }

        val addPath = try {
            AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        } catch (e: NoSuchMethodException) {
            record("addAssetPath is not reachable: ${e.message}")
            null
        }
        if (addPath != null) {
            val cookie = try {
                addPath.invoke(context().assets, info.sourceDir) as? Int
            } catch (e: ReflectiveOperationException) {
                record("addAssetPath blocked: ${e.cause ?: e}")
                null
            }
            record("addAssetPath(their apk) cookie=$cookie (0 or null = refused)")
        }
    }

    /**
     * Obstacle 3: how much of the catalog is Godot, and therefore needs the engine as well as the
     * Activity?
     *
     * A Godot game is not just an Activity — it needs the engine's native libraries and its PCK
     * assets. Those live in their APK, under an ABI directory that has to match this device. A
     * native game and a Godot game are different amounts of work, so knowing the split decides
     * which to attempt first: if most of the catalog is native, a native-only host is already
     * worth shipping.
     */
    @Test
    fun reportGodotVersusNativeSplit() {
        beginSection("obstacle 3: how much of the catalog is Godot?")
        val pigeon = pigeonOrSkip() ?: return
        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo (${e.javaClass.simpleName})")
            return
        }

        val merged = PathClassLoader(info.sourceDir, info.nativeLibraryDir, javaClass.classLoader)
        val catalog = com.roowus.openseagull.host.ForeignGameCatalog.of(pigeon)
        if (catalog.isEmpty) {
            record("SKIP: catalog is empty")
            return
        }

        var godot = 0
        var native = 0
        var unknown = 0
        var sentinel = 0
        catalog.games.forEach { game ->
            // gameClass() is the Activity the game would open. Its name is enough to classify.
            val activity = try {
                val m = game.instance.javaClass.getMethod("gameClass")
                (m.invoke(game.instance) as? Class<*>)?.name
            } catch (e: ReflectiveOperationException) {
                Log.w(tag, "gameClass() failed for ${game.name}", e)
                null
            }
            when {
                activity == null -> unknown++
                activity.contains("Godot", ignoreCase = true) -> godot++
                // `GameNotFound` is their own placeholder for a registry entry with no Activity
                // (wordgames is a submenu, not a game). Counting it as native would overstate how
                // much of the catalog a native-only host could actually reach.
                activity.endsWith(".GameNotFound") -> sentinel++
                else -> native++
            }
            record("  ${game.name} -> ${activity ?: "unknown"}")
        }
        record(
            "activity split: godot=$godot native=$native " +
                "sentinel=$sentinel unknown=$unknown",
        )

        // The engine itself: present in their APK, and loadable?
        listOf("org.godotengine.godot.Godot", "org.godotengine.godot.GodotActivity").forEach {
            val found = try {
                merged.loadClass(it).name
            } catch (e: ClassNotFoundException) {
                "absent"
            } catch (e: LinkageError) {
                "unlinkable: ${e.javaClass.simpleName}"
            }
            record("engine class $it -> $found")
        }
    }
}
