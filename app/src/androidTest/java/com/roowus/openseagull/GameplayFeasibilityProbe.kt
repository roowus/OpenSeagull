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
 * 2b. **The id collision was real, and is now fixed at the root by our build.** With both APKs in
 *    one `AssetManager` and both tables on the default `0x7f`, whichever is added last owns the id
 *    space: our `seagull_mark` came back as *their* `abc_switch_track_mtrl_alpha`. Reversing the
 *    order appeared to fix it — both sample ids resolved correctly — but that was one lucky id:
 *    `checkers` sits at index `0x0ac`, past the end of our smaller drawable type, so it fell
 *    through rather than winning. Sweeping their whole drawable type found **58 of 379 shadowed**,
 *    the first at index `0x000`, which is what killed the "just add their APK last" reading.
 *
 *    The answer was not to work around it per-side but to vacate `0x7f`: our table is now built at
 *    package id `0x80` (`--package-id 0x80 --allow-reserved-package-id`, see `app/build.gradle.kts`
 *    for the full reasoning). Re-running this same sweep against that build reports **0 of 379
 *    shadowed in both merge orders**, so a single merged `Resources` is usable and hosting does not
 *    have to hand each side its own table. Keep this test: it is the regression guard on that build
 *    flag, and if the flag is ever dropped the count goes back to 58 with no other symptom.
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

        /**
         * How far the native-dependency preload loop will follow the chain before stopping.
         *
         * Their `libopenbubblesextension.so` needs one library (`libc++_shared.so`) and that one
         * needs nothing further out of their APK, so 8 is slack rather than a real bound. It exists
         * because the loop's exit condition comes from parsing a linker message, and a message that
         * changed shape would otherwise spin forever instead of reporting a finding.
         */
        const val MaxDependencyChain = 8
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

        // The top byte of an id *is* the package id, so this is the whole hypothesis in one line:
        // if ours is not 0x7f there is no id space to fight over. Read from the built resource
        // rather than asserted, so the record says what this APK actually is, not what the build
        // file intends it to be.
        val ourPackageId = ourId ushr 24
        record(
            "our package id = 0x%02x (%s)".format(
                ourPackageId,
                if (ourPackageId == 0x7f) "default — collides with theirs" else "relocated off 0x7f",
            ),
        )

        // The inverse lookup: whichever package owns an id range in the merged table is the one
        // whose names come back. Both ids are asked, because "theirs resolves" is only meaningful
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
                    "VERDICT: both resolve — one merged Resources works"
                theirsWon ->
                    "VERDICT: theirs won — our own resources are the ones that break"
                oursWon ->
                    "VERDICT: ours won — their layouts would silently inflate as our drawables"
                else ->
                    "VERDICT: neither resolved — the merged table is not usable as built"
            },
        )

        // Built the other way round because sample ids resolving correctly is not the same claim as
        // the table being sound. If the two packages still shared 0x7f, this would separate "last
        // path added wins" — something a host controls by construction — from "theirs wins
        // regardless". With our table relocated it should simply not matter, and an order that
        // *did* matter would mean the relocation had not taken.
        val reversed = try {
            buildMergedResources(info.sourceDir, context().applicationInfo.sourceDir)
        } catch (e: ReflectiveOperationException) {
            record("could not build the reversed table: ${e.cause ?: e}")
            return
        }
        record("reversed order: their id -> ${nameOf(reversed, theirId)}")
        record("reversed order: our  id -> ${nameOf(reversed, ourId)}")

        // Two ids agreeing proves nothing, and reading it as proof would be the expensive mistake
        // in this whole file. When both tables sat on 0x7f, `checkers` at index 0x0ac was past the
        // end of our smaller drawable type, so it fell through to theirs — correct by accident.
        // Their drawables at indices our table *did* cover were still shadowed: 58 of 379, the
        // first at index 0x000. One id cannot tell "the collision is gone" from "this id got
        // lucky", so their whole drawable type is swept in both orders and the mismatches counted.
        //
        // This sweep is now the regression guard on the --package-id 0x80 build flag. Drop the flag
        // and the count returns to 58 with no other symptom anywhere — no exception, just wrong
        // pictures in a hosted Activity.
        fun sweep(res: Resources, label: String): Int {
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
                val got = nameOf(res, id)
                if (got != truth) {
                    wrong++
                    if (firstWrong == null) firstWrong = "0x%08x theirs=$truth merged=$got".format(id)
                }
            }
            record("$label: swept $checked of their drawables, $wrong resolve to the wrong entry")
            firstWrong?.let { record("  first mismatch: $it") }
            return wrong
        }
        val wrongOursFirst = sweep(merged, "ours first")
        val wrongTheirsFirst = sweep(reversed, "theirs first")
        record(
            when {
                wrongOursFirst == 0 && wrongTheirsFirst == 0 ->
                    "VERDICT: no id is shadowed in either order — a single merged Resources is " +
                        "usable, and hosting needs no per-side table"
                wrongTheirsFirst == 0 ->
                    "VERDICT: order-dependent — $wrongOursFirst shadowed with ours first, 0 with " +
                        "theirs first. Sound only while the host controls merge order."
                else ->
                    "VERDICT: shadowed in both orders ($wrongOursFirst ours-first, " +
                        "$wrongTheirsFirst theirs-first) — check the --package-id 0x80 flag " +
                        "survived into this build"
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

    /**
     * Obstacle 4: can their native library be loaded by *our* process?
     *
     * Four of their seven native games sit behind one `.so`. `KnockoutActivity.kt:3015`,
     * `PoolActivity.kt:4039`, `GolfNativePhysics.kt:36` and `ShuffleNativePhysics.kt:7` all call
     * `System.loadLibrary("openbubblesextension")`, and that call searches the **caller's**
     * `ClassLoader`'s native search path — not theirs. Obstacle 1b put their *dex* on our loader;
     * their `.so` is a separate list on the same `DexPathList` and is not carried along with it.
     *
     * What makes this worth measuring rather than assuming is how the file is packaged:
     * `unzip -l` on their `base.apk` shows `lib/arm64-v8a/libopenbubblesextension.so` at **624,608
     * bytes, `Stored`, 0%** — uncompressed and page-aligned, which is the condition under which the
     * linker can map a `.so` straight out of a ZIP. `run-as com.roowus.openseagull head -c 4` on
     * that archive returns `504b 0304`, so our uid really can read it.
     *
     * So the bytes are reachable. Whether they are *loadable* turns on a path syntax: an
     * un-extracted library is named to the linker as `<apk>!/lib/<abi>`, a single `DexPathList`
     * entry, and nothing guarantees a foreign APK appended that way satisfies `System.loadLibrary`.
     *
     * ## Measured (emulator-5554, API 36, OpenPigeon 1.1.0), and the answer is yes — in two steps
     *
     * ```
     * before injection: UnsatisfiedLinkError: dlopen failed:
     *   library "libopenbubblesextension.so" not found
     * append -> ok (2 + 3 elements, 3 dirs)
     * after injection:  UnsatisfiedLinkError: dlopen failed:
     *   library "libc++_shared.so" not found: needed by
     *   …/base.apk!/lib/arm64-v8a/libopenbubblesextension.so in namespace clns-9
     * preloading …/base.apk!/lib/arm64-v8a/libc++_shared.so -> loaded
     * retry: loaded
     * ```
     *
     * **The two failures are not the same failure**, and reading the second as a repeat of the
     * first is the mistake this probe made on its first run. The first says the file was not found.
     * The second names the file — it was found, mapped, and *linked against* — and blames a
     * `DT_NEEDED` entry. The append had already worked by then.
     *
     * The reason the dependency still misses is that the append is Java-side only.
     * `System.loadLibrary` asks the ClassLoader where the file is, and that now answers correctly;
     * but once `dlopen` has the file, its dependencies are resolved by **the linker namespace**
     * (`clns-9`), whose search path was fixed when our ClassLoader was created and which reflection
     * over `DexPathList` never touches. Their `libc++_shared.so` is in the same directory of the
     * same APK and is simply not on that list.
     *
     * What rescues it is that a library already loaded into the namespace is matched by soname. So
     * naming the dependency by explicit full in-APK path — `System.load("<apk>!/lib/<abi>/<so>")` —
     * puts it in the namespace under its soname, and the retry resolves. Hosting a native game
     * therefore costs **an ordered preload, not a file copy**: nothing of theirs is extracted, and
     * nothing of theirs is shipped.
     *
     * As in obstacle 1b, the baseline runs **first**. `libopenbubblesextension` is not a name we
     * ship, but "it loaded" is exactly the conclusion that would be wrong-and-comfortable if some
     * transitive dependency had already put it on the path, so the pre-injection attempt is what
     * makes the post-injection result mean anything.
     *
     * One thing the run corrected outright: `info.nativeLibraryDir` **exists on disk** on this
     * device (an earlier note here claimed otherwise from a `dumpsys`-reported
     * `legacyNativeLibraryDir`). It is empty — `ls` gives `total 0` — so the conclusion that
     * nothing was extracted still holds, but via the directory's contents rather than its absence.
     *
     * Nothing here asserts. A blocked result is a finding, not a failure.
     */
    @Test
    fun theirNativeLibraryCanBeLoadedFromOurProcess() {
        beginSection("obstacle 4: can we load their .so out of their APK?")
        val pigeon = pigeonOrSkip() ?: return
        val info = try {
            context().packageManager.getApplicationInfo(pigeon.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            record("SKIP: no ApplicationInfo (${e.javaClass.simpleName})")
            return
        }

        // Their build leaves this pointing at a directory that does not exist on disk. Recording it
        // alongside the ABI is what makes the "not extracted" claim above checkable per device
        // rather than a remembered fact.
        record("their nativeLibraryDir = ${info.nativeLibraryDir}")
        record("  exists on disk = ${java.io.File(info.nativeLibraryDir).isDirectory}")
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        record("our primary abi = $abi")

        val library = "openbubblesextension"

        // Baseline, before touching the search path. See obstacle 1b: a library that already
        // resolves would make every line below vacuous.
        val before = loadOutcome(library)
        record("before injection: System.loadLibrary(\"$library\") -> $before")
        if (before == "loaded") {
            record("NOTE: already loadable — injection result below proves nothing")
            return
        }

        val entry = "${info.sourceDir}!/lib/$abi"
        record("appending native path entry: $entry")
        val added = try {
            appendNativePathToLoader(context().classLoader, info.sourceDir, abi)
        } catch (e: ReflectiveOperationException) {
            "blocked: ${e.cause ?: e}"
        } catch (e: RuntimeException) {
            "blocked: $e"
        }
        record("append -> $added")

        var after = loadOutcome(library)
        record("after injection: System.loadLibrary(\"$library\") -> $after")

        // Measured on the first run: the append *worked* — their `.so` was found and mapped — and
        // the failure moved one level down, to `libc++_shared.so not found: needed by
        // …/base.apk!/lib/arm64-v8a/libopenbubblesextension.so`. That is a different problem
        // wearing the same exception type, and reading it as "the path did not take" would have
        // sent the design off to extract a file that was never the obstacle.
        //
        // The reason is that our search-path append is Java-side only. `System.loadLibrary` asks
        // the ClassLoader where the file is, and that part now answers correctly; but once dlopen
        // has the file, its `DT_NEEDED` entries are resolved by **the linker namespace**, whose
        // search path was fixed when our ClassLoader was created and which our reflection never
        // touched. Their `libc++_shared.so` sits in the same directory of the same APK and is
        // simply not on that list.
        //
        // A library already loaded into the namespace is matched by soname, though, so naming the
        // dependency explicitly by full in-APK path is enough to satisfy the next attempt. The
        // loop follows the chain rather than assuming its length: each failure names exactly one
        // missing soname, so parse it, load that, and retry.
        val preloaded = mutableListOf<String>()
        var guard = 0
        while (guard++ < MaxDependencyChain) {
            val missing = missingSonameIn(after) ?: break
            if (missing in preloaded) {
                record("dependency $missing was preloaded and is still reported missing — giving up")
                break
            }
            val path = "${info.sourceDir}!/lib/$abi/$missing"
            val outcome = try {
                System.load(path); "loaded"
            } catch (e: UnsatisfiedLinkError) {
                "UnsatisfiedLinkError: ${e.message?.take(200)}"
            } catch (e: LinkageError) {
                "${e.javaClass.simpleName}: ${e.message?.take(200)}"
            }
            record("preloading dependency $path -> $outcome")
            if (outcome != "loaded") break
            preloaded += missing
            after = loadOutcome(library)
            record("retry after preloading $missing: System.loadLibrary(\"$library\") -> $after")
        }

        record(
            when {
                after == "loaded" && preloaded.isEmpty() ->
                    "VERDICT: their .so loads straight out of their APK — the four native games " +
                        "need no file copying, and nothing of theirs has to be shipped"
                after == "loaded" ->
                    "VERDICT: their .so loads out of their APK once ${preloaded.joinToString()} " +
                        "is loaded first by explicit path — no file copying, but hosting a native " +
                        "game must preload that dependency chain in order"
                missingSonameIn(after) != null ->
                    "VERDICT: the file is found and mapped; what fails is a dependency " +
                        "(${missingSonameIn(after)}) that preloading did not fix — the linker " +
                        "namespace, not the search path, is the obstacle"
                after.contains("not found", ignoreCase = true) ->
                    "VERDICT: the in-APK path did not satisfy the loader — hosting a native game " +
                        "means extracting $library into our own nativeLibraryDir first"
                else ->
                    "VERDICT: found but rejected ($after) — a mapping/linkage problem, not a " +
                        "search-path one, and extraction would not fix it"
            },
        )
    }

    /**
     * The soname a `dlopen` failure blames, or null if the failure is not a missing dependency.
     *
     * The distinction this draws is the one the first run of this probe got wrong. Both of these
     * are an `UnsatisfiedLinkError` and they call for opposite fixes:
     *
     * ```
     * library "libopenbubblesextension.so" not found                       <- the file is missing
     * library "libc++_shared.so" not found: needed by …/libopenbubbles…so  <- a dependency is
     * ```
     *
     * Only the second has a `needed by` clause, and only the second is worth preloading. Keying on
     * that clause rather than on the quoted name is what keeps a genuine search-path miss from
     * being mistaken for a dependency chain.
     */
    private fun missingSonameIn(outcome: String): String? {
        if (!outcome.contains("needed by")) return null
        return Regex("""library "([^"]+)" not found""").find(outcome)?.groupValues?.get(1)
    }

    /**
     * Try to load [library], reporting the outcome instead of throwing.
     *
     * The three cases are kept apart deliberately: "not found" and "found but would not link" look
     * identical if both are reduced to a boolean, and they call for opposite fixes.
     */
    private fun loadOutcome(library: String): String = try {
        System.loadLibrary(library)
        "loaded"
    } catch (e: UnsatisfiedLinkError) {
        // The message carries which of the two it is — a missing file names the paths searched, a
        // failed dlopen names the symbol or relocation. Keeping it verbatim is the finding.
        "UnsatisfiedLinkError: ${e.message?.take(300)}"
    } catch (e: LinkageError) {
        "${e.javaClass.simpleName}: ${e.message?.take(300)}"
    } catch (e: SecurityException) {
        "SecurityException: ${e.message?.take(300)}"
    }

    /**
     * Add `<[apkPath]>!/lib/<[abi]>` to [loader]'s native search path.
     *
     * The companion to [appendApkToLoader], and deliberately not folded into it: the dex list and
     * the native list are separate fields on the same `DexPathList`, injected at different times for
     * different reasons, and a caller that wants classes does not necessarily want to perturb the
     * linker's search order.
     *
     * Two representations have to be kept in step and that is the whole difficulty:
     *
     * - `nativeLibraryDirectories`, a `List<File>` — what `DexPathList` re-derives from;
     * - `nativeLibraryPathElements`, an `Element[]` — what `findLibrary` actually walks.
     *
     * Writing only the first is the classic silent no-op: the field looks right under a debugger
     * and `findLibrary` never consults it. So the `Element` is built by handing a throwaway
     * [PathClassLoader] the same in-APK path and stealing the element it constructed — the same
     * version-tolerance trick [appendApkToLoader] uses, and for the same reason: `makePathElements`
     * has changed signature repeatedly across releases.
     *
     * Ours stay first. A hosted game asking for a library we also ship must keep getting ours.
     *
     * Returns a short description of what it did, so the caller can record it; throws only when
     * reflection is blocked outright, which is itself the answer.
     */
    @Throws(ReflectiveOperationException::class)
    private fun appendNativePathToLoader(
        loader: ClassLoader,
        apkPath: String,
        abi: String,
    ): String {
        val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
            .getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(loader)
        val listClass = pathList.javaClass

        val dirsField = listClass
            .getDeclaredField("nativeLibraryDirectories").apply { isAccessible = true }
        val elementsField = listClass
            .getDeclaredField("nativeLibraryPathElements").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val dirs = dirsField.get(pathList) as MutableList<java.io.File>
        val existing = elementsField.get(pathList) as Array<*>

        // An in-APK library is addressed by this exact syntax; the linker splits on '!' and mmaps
        // the entry out of the archive. It is a path, not a directory, which is why File() is the
        // right type despite nothing existing at that name on disk.
        val inApk = java.io.File("$apkPath!/lib/$abi")
        if (dirs.none { it.path == inApk.path }) dirs.add(inApk)

        // Steal a ready-made Element rather than call makePathElements. The donor is given the
        // in-APK path as its librarySearchPath, so the element it builds is exactly the one wanted.
        val donor = PathClassLoader("", inApk.path, null)
        val donorElements = elementsField.get(pathListField.get(donor)) as Array<*>
        if (donorElements.isEmpty()) {
            return "no native element was built for ${inApk.path} (dirs updated only)"
        }

        val merged = java.lang.reflect.Array.newInstance(
            existing.javaClass.componentType,
            existing.size + donorElements.size,
        )
        System.arraycopy(existing, 0, merged, 0, existing.size)
        System.arraycopy(donorElements, 0, merged, existing.size, donorElements.size)
        elementsField.set(pathList, merged)

        return "ok (${existing.size} + ${donorElements.size} elements, ${dirs.size} dirs)"
    }
}
