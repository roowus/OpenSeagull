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
 * and it has three known obstacles. Each test below measures exactly one of them (obstacles 1 and 2
 * in two parts each), because the design that follows depends on which are real and which are
 * folklore.
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
 * 1b. **And they load from the loader that actually matters.** Appending their APK to our own app
 *    ClassLoader's `DexPathList` is **not blocked** on API 36: before injection our loader could not
 *    resolve `KnockoutActivity`, after it resolves *and links*. That is the loader the framework
 *    passes to `Instrumentation.newActivity`, so the manifest-entry route is open — hosting does not
 *    need a hand-rolled proxy that drives the Activity lifecycle itself. This was the obstacle most
 *    likely to be fatal, and it is not.
 * 2. **Package ids do collide, but `addAssetPath` is not blocked** (see that test's KDoc). This is
 *    the finding that shapes the design, and it is less damning than expected.
 * 2b. **A merged table is not usable, and the near-miss is instructive.** With both APKs in one
 *    `AssetManager`, whichever is added last owns `0x7f`: our `seagull_mark` came back as *their*
 *    `abc_switch_track_mtrl_alpha`. Reversing the order appeared to fix it — both sample ids resolved
 *    correctly — but that was one lucky id: `checkers` sits at index `0x0ac`, past the end of our
 *    smaller drawable type, so it fell through rather than winning. Sweeping their whole drawable
 *    type found **58 of 379 shadowed**, the first at index `0x000`. So merge order is not a design
 *    knob, and each side must get a `Resources` containing only its own APK —
 *    `getResourcesForApplication` for theirs, the process table for ours.
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
     * Obstacle 1b: can their dex be reached from **our own** app ClassLoader?
     *
     * Obstacle 1 showed their classes load from a loader *we* construct. That is not the loader
     * that matters. When the framework instantiates an Activity it calls
     * `Instrumentation.newActivity(cl, className, intent)` with `cl` = the `LoadedApk` ClassLoader —
     * ours, fixed at process start. A loader we build and hold in a field is invisible to it, so
     * obstacle 1 passing does not mean a manifest entry naming their Activity would launch.
     *
     * The standard way to close that gap is to append their APK to our loader's `DexPathList`, the
     * mechanism every multidex and hotfix library has used for a decade. Whether it still works on
     * API 36 is the question — `dalvik.system.DexPathList` is a non-SDK class, and the reflective
     * access could be greylisted, blocked, or the field renamed out from under this.
     *
     * This does not assert success. Being blocked here is a legitimate finding: it would mean the
     * manifest-entry route is closed and hosting needs a different shape entirely (a proxy Activity
     * of ours that instantiates theirs by hand and drives its lifecycle, which is far more code).
     * Either way the answer should come from the device.
     */
    @Test
    fun theirDexCanBeAppendedToOurOwnClassLoader() {
        beginSection("obstacle 1b: is their dex reachable from OUR app ClassLoader?")
        val pigeon = pigeonOrSkip() ?: return
        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo (${e.javaClass.simpleName})")
            return
        }

        val target = "com.openbubbles.openpigeon.knockout.KnockoutActivity"
        val ours = context().classLoader
        record("our loader = ${ours.javaClass.name}")

        // Baseline. If this somehow already resolves, everything below is moot — and a false
        // "injection worked" would be the easiest wrong conclusion to draw here.
        val before = try {
            ours.loadClass(target); true
        } catch (e: ClassNotFoundException) {
            false
        }
        record("before injection: our loader resolves $target = $before")
        if (before) {
            record("NOTE: already reachable — injection result below proves nothing")
            return
        }

        val added = try {
            appendApkToLoader(ours, info.sourceDir)
            "ok"
        } catch (e: ReflectiveOperationException) {
            "blocked: ${e.cause ?: e}"
        } catch (e: RuntimeException) {
            "blocked: $e"
        }
        record("append their apk to our DexPathList -> $added")

        val after = try {
            val k = ours.loadClass(target)
            generateSequence<Class<*>>(k) { it.superclass }.take(4).joinToString(" <- ") {
                it.simpleName
            }
        } catch (e: ClassNotFoundException) {
            "still ABSENT"
        } catch (e: LinkageError) {
            "UNLINKABLE (${e.javaClass.simpleName})"
        }
        record("after injection: our loader resolves $target -> $after")
    }

    /**
     * Appends [apkPath] to [loader]'s `DexPathList`, the trick every multidex and hotfix library
     * has used for a decade.
     *
     * Rather than call `makePathElements` — whose signature has changed repeatedly across releases —
     * this builds a throwaway [PathClassLoader] over their APK and steals its already-constructed
     * elements. Far more version-tolerant, and shorter.
     *
     * Throws rather than reporting: the caller records the failure as a finding, because being
     * blocked here is itself the answer to the question this test asks.
     */
    @Throws(ReflectiveOperationException::class)
    private fun appendApkToLoader(loader: ClassLoader, apkPath: String) {
        val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
            .getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(loader)

        val dexElementsField = pathList.javaClass
            .getDeclaredField("dexElements").apply { isAccessible = true }
        val existing = dexElementsField.get(pathList) as Array<*>

        val donor = PathClassLoader(apkPath, null, null)
        val donorElements = dexElementsField.get(pathListField.get(donor)) as Array<*>

        // Ours first: anything both APKs define (androidx, kotlin stdlib) keeps resolving to our
        // copy, so appending their dex cannot change the meaning of code that already worked.
        val merged = java.lang.reflect.Array.newInstance(
            existing.javaClass.componentType,
            existing.size + donorElements.size,
        )
        System.arraycopy(existing, 0, merged, 0, existing.size)
        System.arraycopy(donorElements, 0, merged, existing.size, donorElements.size)
        dexElementsField.set(pathList, merged)
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
     * Obstacle 2b: in a merged table, does **their** numeric id still mean their resource?
     *
     * This is the question obstacle 2 raised and could not answer. `addAssetPath` accepting their
     * APK (cookie 14) only proves the paths coexist. It says nothing about what happens next, and
     * what happens next is the whole design:
     *
     * A hosted Activity of theirs does not call `getIdentifier("checkers", …)`. Their aapt2 baked
     * **integer constants** into their dex — `setContentView(0x7f0b0012)` — and those integers are
     * all that survives into the running code. So the only question that matters is whether the
     * merged table maps their integer back to their entry. Two packages both claiming `0x7f` cannot
     * both win.
     *
     * `getResourceName` is the probe because it inverts exactly the lookup the framework does: id
     * in, package/type/entry out. If their id comes back as their name, hosting can use one merged
     * `Resources` and no build-time change. If it comes back as *ours*, their layout silently
     * inflates as one of our drawables — the same silent-wrong-resource failure already measured
     * from the outside (`0x7f070157`), and the design must instead hand each hosted Activity a
     * `Resources` containing only their APK, or move our table off `0x7f` with aapt2.
     *
     * Deliberately built on a **fresh** AssetManager rather than the process one: mutating the real
     * table would leave every later test running against a merged table, and a probe should not
     * change the thing the next probe measures.
     */
    @Test
    fun theirNumericIdsSurviveAMergedTable() {
        beginSection("obstacle 2b: does their numeric id survive a merged table?")
        val pigeon = pigeonOrSkip() ?: return
        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo (${e.javaClass.simpleName})")
            return
        }

        // Looked up by name rather than hard-coded: their ids shift between their builds, and a
        // stale constant would make this test measure nothing while still passing.
        val theirId = pigeon.resources.getIdentifier("checkers", "drawable", pigeon.packageName)
        val ourId = context().resources
            .getIdentifier("seagull_mark", "drawable", context().packageName)
        record("their checkers = 0x%08x, our seagull_mark = 0x%08x".format(theirId, ourId))
        if (theirId == 0 || ourId == 0) {
            record("SKIP: need a sample id from each table")
            return
        }

        val merged = try {
            buildMergedResources(context().applicationInfo.sourceDir, info.sourceDir)
        } catch (e: ReflectiveOperationException) {
            record("could not build a merged AssetManager: ${e.cause ?: e}")
            return
        }

        // The inverse lookup: whichever package owns 0x7f in the merged table is the one whose
        // names come back. Both ids are asked, because "theirs resolves" is only meaningful
        // alongside what happened to ours.
        fun nameOf(res: Resources, id: Int) = try {
            res.getResourceName(id)
        } catch (e: Resources.NotFoundException) {
            "NOT FOUND"
        }
        val theirName = nameOf(merged, theirId)
        val ourName = nameOf(merged, ourId)
        record("merged table: their id -> $theirName")
        record("merged table: our  id -> $ourName")

        val theirsWon = theirName.startsWith(pigeon.packageName)
        val oursWon = ourName.startsWith(context().packageName)
        record(
            when {
                theirsWon && oursWon ->
                    "VERDICT: both resolve — ids do not actually collide, one merged Resources works"
                theirsWon ->
                    "VERDICT: theirs won 0x7f — our own resources are the ones that break"
                oursWon ->
                    "VERDICT: ours won 0x7f — their layouts would silently inflate as our drawables"
                else ->
                    "VERDICT: neither resolved — the merged table is not usable as built"
            },
        )

        // Is the winner something we choose, or something imposed on us? A table built the other
        // way round separates "last path added wins" — which a host controls by construction —
        // from "theirs wins regardless", which would mean the merge order is not a design knob at
        // all. The answer changes what the fix has to be, so it is worth one more AssetManager.
        val reversed = try {
            buildMergedResources(info.sourceDir, context().applicationInfo.sourceDir)
        } catch (e: ReflectiveOperationException) {
            record("could not build the reversed table: ${e.cause ?: e}")
            return
        }
        record("reversed order: their id -> ${nameOf(reversed, theirId)}")
        record("reversed order: our  id -> ${nameOf(reversed, ourId)}")

        // The reversed table looks like it fixed everything, and reading it that way would be the
        // expensive mistake in this whole file. `checkers` is at index 0x0ac; our drawable type is
        // smaller than that, so the lookup may simply be running off the end of our table and
        // falling through to theirs — correct by accident, for that one id.
        //
        // If that is the mechanism, their drawables at indices our table *does* cover are still
        // shadowed. One id cannot tell the difference between "ordering fixes it" and "this id got
        // lucky", so their whole drawable type is swept and the mismatches counted.
        var checked = 0
        var wrong = 0
        var firstWrong: String? = null
        for (index in 0 until 0x400) {
            val id = 0x7f070000 or index
            val truth = try {
                pigeon.resources.getResourceName(id)
            } catch (e: Resources.NotFoundException) {
                continue // not one of their drawables; nothing to get wrong
            }
            checked++
            val got = nameOf(reversed, id)
            if (got != truth) {
                wrong++
                if (firstWrong == null) firstWrong = "0x%08x theirs=$truth merged=$got".format(id)
            }
        }
        record("reversed order: swept $checked of their drawables, $wrong resolve to the wrong entry")
        firstWrong?.let { record("  first mismatch: $it") }
        record(
            if (wrong == 0) {
                "VERDICT: order alone fixes it — their APK first, ours second, no id is shadowed"
            } else {
                "VERDICT: order does NOT fix it — $wrong/$checked shadowed; the earlier pass was " +
                    "one lucky id. Hosting needs a single-package Resources per side."
            },
        )
    }

    /**
     * A [Resources] over a fresh [AssetManager] holding [apks] in order, via the hidden
     * `AssetManager()` constructor and `addAssetPath`.
     *
     * The display metrics and configuration are copied from the process `Resources` so the table
     * selects the same density and locale buckets a real Activity would get — a merged table that
     * silently resolved `mdpi` art would answer a different question than the one being asked.
     */
    @Throws(ReflectiveOperationException::class)
    private fun buildMergedResources(vararg apks: String): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        apks.forEach { apk ->
            val cookie = addAssetPath.invoke(assets, apk) as? Int
            record("  merged AssetManager += $apk -> cookie=$cookie")
        }
        val base = context().resources
        @Suppress("DEPRECATION")
        return Resources(assets, base.displayMetrics, base.configuration)
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
