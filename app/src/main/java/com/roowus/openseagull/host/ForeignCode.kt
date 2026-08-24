package com.roowus.openseagull.host

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.ref.Reference
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.ZipFile

/**
 * Makes the installed OpenPigeon's **classes, native libraries and resources** reachable from our
 * own process, without copying a byte of any of them to disk.
 *
 * [InstalledOpenPigeon] gets us a [android.content.Context] whose *own* loader can see their code.
 * That is enough to call a game object reflectively, and everything up to the picker does exactly
 * that. It is **not** enough to *host* a game: an Activity is constructed by the framework through
 * `getClassLoader()` of the process it runs in — ours — so their Activity class has to be
 * resolvable by our loader, not merely by theirs. That is what this class arranges.
 *
 * ## Three separate injections, on purpose
 *
 * They are needed at different moments, for different reasons, and by different callers:
 *
 * - [installDex] appends their APK to `dexElements`, so `loadClass` finds their Activity.
 * - [installNativePath] appends `<their apk>!/lib/<abi>` to the native search path, so
 *   `System.loadLibrary` finds their `.so`.
 * - [installResources] appends their APK to an `AssetManager`, so an id baked into their dex
 *   resolves to the entry it was compiled against.
 *
 * The first two are not folded together because a caller that wants a class does not necessarily
 * want to perturb the linker's search order, and because the native half is only relevant to the
 * four native games (Pool, Golf, Knockout, Shuffle) — the Godot ones need it too, but for a
 * different library.
 *
 * The third stands apart for a stronger reason: it is the only one that is **not process-wide**.
 * `dexElements` and the native search path belong to a ClassLoader, of which our process has one.
 * An `AssetManager` belongs to a `Resources`, of which the framework builds many — measured, seven
 * live at once — and an Activity's is **not** the Application's. So [installResources] is
 * idempotent per object rather than once per process, and takes the object as an argument.
 *
 * ## Ours always comes first
 *
 * Both merges put our existing entries ahead of theirs. Both APKs ship androidx and the Kotlin
 * stdlib, and if their copy won, code of ours that already worked could change meaning after an
 * injection — the worst kind of regression, because nothing near the change would look wrong.
 * Appending can only *add* names that were previously unresolvable.
 *
 * ## Why the native load is two steps and not one
 *
 * Measured on-device (`GameplayFeasibilityProbe.theirNativeLibraryCanBeLoadedFromOurProcess`):
 * appending the search path really does let `System.loadLibrary("openbubblesextension")` find and
 * map their `.so` — and it *still* fails, blaming `libc++_shared.so` with a `needed by` clause.
 *
 * The two failures wear the same exception type and mean opposite things:
 *
 * ```
 * library "libopenbubblesextension.so" not found                        <- the file is missing
 * library "libc++_shared.so" not found: needed by …/libopenbubbles….so  <- a dependency is
 * ```
 *
 * The reason for the second is that the append is Java-side only. `System.loadLibrary` asks the
 * ClassLoader where the file is, which now answers correctly; but once `dlopen` holds the file, its
 * `DT_NEEDED` entries resolve through the **linker namespace** (`clns-9`), whose search path was
 * fixed when our ClassLoader was created and which reflection over `DexPathList` never touches.
 * Their `libc++_shared.so` sits in the same directory of the same APK and is simply not on that
 * list.
 *
 * What rescues it is that a library already loaded into the namespace is matched by **soname**. So
 * naming the dependency by its explicit full in-APK path once puts it in the namespace, and the
 * retry resolves. [loadLibrary] does that automatically by reading the missing soname out of the
 * linker's own message, so the chain is followed rather than hardcoded.
 *
 * Cost: **an ordered preload, not a file copy.** Nothing of theirs is extracted, nothing shipped.
 *
 * ## Idempotency is load-bearing, not tidiness
 *
 * Their `initGameSession` runs from both `onCreate` and `onNewIntent`, and a user can open several
 * balloons in one process lifetime. Appending the same APK twice would grow `dexElements` without
 * bound and put a second copy of every class behind the first. Every entry point here is guarded
 * and returns the *same* result on a second call.
 */
object ForeignCode {

    private const val TAG = "SEAGULL"

    /**
     * How far [loadLibrary] follows the dependency chain before giving up.
     *
     * Their `libopenbubblesextension.so` needs exactly one library and that one needs nothing
     * further out of their APK, so this is slack rather than a real bound. It exists because the
     * loop's exit condition comes from parsing a linker message, and a message that changed shape
     * would otherwise spin forever instead of reporting a failure.
     */
    private const val MaxDependencyChain = 8

    /**
     * An id from *their* table, used to prove a merge actually took.
     *
     * The exact layout id their `KnockoutActivity.onCreate` passes to `setContentView`, taken from
     * the `Resources$NotFoundException` that closing gate 3 revealed. A real id from a real failure
     * is worth more than a synthetic one: if this resolves, the launch that produced that stack
     * trace gets further.
     *
     * A canary, not a dependency. Nothing here needs that layout and no code of ours draws it, so
     * a build of theirs that renumbers it must not stop a game from being hosted — see [verify].
     */
    const val KnownTheirLayout = 0x7f0c001d

    /** What an injection attempt did. Never thrown — a blocked host has to degrade, not crash. */
    sealed interface Result {
        /** The injection is in place. [alreadyDone] distinguishes a repeat call from the first. */
        data class Ok(val detail: String, val alreadyDone: Boolean = false) : Result

        /** It did not happen, and [reason] says why in terms a diagnostic screen can print. */
        data class Failed(val reason: String) : Result
    }

    private val lock = Any()

    private var dexInstalled: Result? = null
    private var nativeInstalled: Result? = null

    /**
     * The libraries [loadLibrary] has already put in the linker namespace by explicit path.
     *
     * Tracked because `System.load` on an already-loaded path is cheap but not free, and because a
     * soname that reappears after being preloaded means the chain is not converging — that is a
     * real failure and must not become an infinite loop.
     */
    private val preloaded = mutableSetOf<String>()

    /**
     * Libraries [loadLibrary] has already brought up, by the bare name it was asked for.
     *
     * `System.loadLibrary` on an already-loaded library is a documented no-op, so a repeat call is
     * harmless — but without this it would still be reported as `alreadyDone = false`, which is not
     * what that field means. Measured: the second `loadLibrary("openbubblesextension")` returned a
     * fresh-looking `Ok`. Callers use `alreadyDone` to tell a no-op from work done, so a wrong
     * answer here is a wrong answer everywhere it is read.
     */
    private val loadedLibraries = mutableMapOf<String, Result>()

    /**
     * Make their classes resolvable by **our** ClassLoader.
     *
     * Required before an `Intent` naming one of their Activities can be started in our process:
     * the framework instantiates it via our loader, and until this runs that lookup fails with
     * `ClassNotFoundException` no matter that [InstalledOpenPigeon.classLoader] can see it fine.
     */
    fun installDex(pigeon: InstalledOpenPigeon): Result = synchronized(lock) {
        dexInstalled?.let { return it.repeat() }
        val result = try {
            val apk = pigeon.sourceDir
                ?: return@synchronized Result.Failed("their APK path is unknown").also {
                    dexInstalled = it
                }
            appendDex(ForeignCode::class.java.classLoader!!, apk)
        } catch (e: ReflectiveOperationException) {
            // The internals this depends on are not API. A future Android that hides them is a
            // supported outcome: hosting stops working, everything reflective keeps working.
            Result.Failed("DexPathList is not reachable (${e.javaClass.simpleName})")
        } catch (e: LinkageError) {
            Result.Failed("their dex would not link (${e.javaClass.simpleName})")
        }
        dexInstalled = result
        Log.i(TAG, "installDex -> $result")
        result
    }

    /**
     * Put `<their apk>!/lib/<abi>` on our native search path.
     *
     * Does not load anything — [loadLibrary] does that. Separated because the search path is a
     * process-wide change and the load is per-library, and because a failure here should be
     * reported against the path rather than blamed on the first library that happens to want it.
     */
    fun installNativePath(pigeon: InstalledOpenPigeon): Result = synchronized(lock) {
        nativeInstalled?.let { return it.repeat() }
        val result = try {
            val apk = pigeon.sourceDir
                ?: return@synchronized Result.Failed("their APK path is unknown").also {
                    nativeInstalled = it
                }
            val abi = abiIn(apk)
                ?: return@synchronized Result.Failed(
                    "their APK carries no library directory for any ABI this device supports " +
                        "(${Build.SUPPORTED_ABIS.joinToString()})",
                ).also { nativeInstalled = it }
            appendNativePath(ForeignCode::class.java.classLoader!!, apk, abi)
        } catch (e: ReflectiveOperationException) {
            Result.Failed("DexPathList is not reachable (${e.javaClass.simpleName})")
        }
        nativeInstalled = result
        Log.i(TAG, "installNativePath -> $result")
        result
    }

    /**
     * Load one of *their* native libraries by soname, preloading whatever it needs first.
     *
     * [name] is the bare name `System.loadLibrary` takes — `openbubblesextension`, not
     * `libopenbubblesextension.so`.
     *
     * Calls [installNativePath] itself rather than requiring the caller to sequence it, because
     * the two are only ever useful together and an unsequenced call fails in the confusing
     * direction (a "not found" that looks like a missing file).
     *
     * The dependency loop reads the missing soname out of the linker's message rather than
     * carrying a hardcoded list, so a build of theirs that adds a dependency is followed rather
     * than guessed at. See [missingSonameIn] for why the message — not the exception type — is
     * what distinguishes a dependency miss from a genuinely absent file.
     */
    fun loadLibrary(pigeon: InstalledOpenPigeon, name: String): Result {
        when (val path = installNativePath(pigeon)) {
            is Result.Failed -> return path
            is Result.Ok -> Unit
        }
        val apk = pigeon.sourceDir ?: return Result.Failed("their APK path is unknown")
        val abi = abiIn(apk) ?: return Result.Failed("no usable ABI in their APK")

        synchronized(lock) {
            loadedLibraries[name]?.let { return it.repeat() }
            var outcome = loadOutcome(name)
            var guard = 0
            while (outcome != null && guard++ < MaxDependencyChain) {
                val missing = missingSonameIn(outcome) ?: break
                if (!preloaded.add(missing)) {
                    return Result.Failed(
                        "$missing was preloaded and is still reported missing — the linker " +
                            "namespace is rejecting it rather than failing to find it",
                    )
                }
                val full = "$apk!/lib/$abi/$missing"
                try {
                    System.load(full)
                } catch (e: LinkageError) {
                    return Result.Failed(
                        "dependency $missing could not be preloaded from their APK " +
                            "(${e.javaClass.simpleName})",
                    )
                }
                Log.i(TAG, "preloaded $full for $name")
                outcome = loadOutcome(name)
            }
            return if (outcome == null) {
                Result.Ok(
                    if (preloaded.isEmpty()) "loaded $name"
                    else "loaded $name after preloading ${preloaded.joinToString()}",
                ).also { loadedLibraries[name] = it }
            } else {
                // Deliberately not cached. A dex or native-path injection either happened or is
                // impossible, but a library load can fail for reasons that do not persist — a
                // package mid-update, an ABI directory that was not readable at that instant — and
                // pinning the first failure forever would turn a transient miss into a permanent
                // one. Retrying costs a failed dlopen; not retrying costs the game.
                Result.Failed("could not load $name: $outcome")
            }
        }
    }

    /**
     * `AssetManager`s their APK has already been appended to.
     *
     * Weak and identity-keyed, both deliberately. Weak because these outlive nothing — a
     * `ResourcesImpl` is evicted when its `ResourcesKey` goes out of use, and a strong reference
     * here would pin every table an app ever built. Identity because `AssetManager` inherits
     * `equals` from `Object` anyway, but relying on that silently would be the kind of assumption
     * this codebase keeps getting bitten by; [Collections.newSetFromMap] over a [WeakHashMap] says
     * it out loud.
     *
     * Not guarded by [lock]: resource installation is per-object, so it does not contend with the
     * process-wide injections, and synchronizing on the shared lock from a launch path would put
     * an Activity construction behind an unrelated `dlopen`. The set has its own synchronization.
     */
    private val patchedAssets: MutableSet<AssetManager> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * Make an id baked into *their* dex resolve against *their* resource table.
     *
     * This is the last gate between a hosted Activity and a drawn board. Their `onCreate` calls
     * `setContentView(0x7f0c001d)` — an integer, compiled in, with no name left in it — and against
     * our table that id is nothing:
     *
     * ```
     * android.content.res.Resources$NotFoundException: Resource ID #0x7f0c001d
     *     at android.content.res.Resources.getLayout(Resources.java:1289)
     *     at com.openbubbles.openpigeon.knockout.KnockoutActivity.onCreate(KnockoutActivity.kt:196)
     * ```
     *
     * Because the id is an integer rather than a name, `getIdentifier` is beside the point: the
     * merged table has to map *their* integer back to *their* entry, which is what appending their
     * APK to the `AssetManager` does.
     *
     * ## Why appending is safe rather than reckless
     *
     * `0x7f` is the default app package id, and it is theirs. Ours is `0x80`, a reserved id this
     * APK opts into (`--package-id 0x80 --allow-reserved-package-id`) for exactly this reason.
     * Measured across both merge orders: **0 of 379 ids shadowed**, against 58 when we built at
     * `0x7f`. So appending their table can only add names that previously resolved to nothing. It
     * cannot change the meaning of an id of ours — which is what makes an in-place patch of a live,
     * already-in-use table something other than a gamble.
     *
     * ## Why this patches an existing table instead of building a better one
     *
     * The framework builds an Activity's `Resources` in `ActivityThread.createBaseContextForActivity`,
     * which runs **before** `mInstrumentation.newActivity(...)` — so by the time
     * [HostedComponentFactory] is asked to instantiate their class, the table that will fail
     * already exists and was built without us. An earlier reading of that ordering concluded the
     * hook was too late. It is not: it rules out *preventing* the problem, not *repairing* it. The
     * factory still runs before `attach`, therefore before `onCreate`, therefore before the
     * `setContentView` that throws.
     *
     * Two routes to influencing the table at construction time were measured and are not used.
     * `ApplicationInfo.sharedLibraryFiles` — the public-API shortcut — reported `not loaded`: the
     * appended entry was silently ignored, with no exception. And patching the Application's table
     * would never be seen, because an Activity's `AssetManager` is a different object (measured:
     * `@f492f0e` vs `@1537d2f`). See `ForeignResourcesReport`, which took all three readings in a
     * real launched Activity rather than under instrumentation — `am instrument
     * --no-hidden-api-checks` lifts precisely the greylist restriction being tested, so a probe's
     * pass proves nothing about production.
     *
     * ## Verified through `Resources`, not through the `AssetManager`
     *
     * [assets] is what gets mutated, but the read-back goes through [verifyWith] because
     * `ResourcesImpl` sits in between with its own caches. A patch the `AssetManager` accepted but
     * the owning `Resources` could not see would be a false pass, and the failure it caused would
     * surface far away from here.
     *
     * @param assets the table to merge into — an Activity's own, not the Application's.
     * @param verifyWith the `Resources` that owns [assets], read back through to confirm the merge
     *   actually took. Optional only because a caller reaching [assets] by reflection may not have
     *   the owner in hand; when it is omitted the result says the merge was not verified.
     */
    fun installResources(
        pigeon: InstalledOpenPigeon,
        assets: AssetManager,
        verifyWith: android.content.res.Resources? = null,
    ): Result {
        if (!patchedAssets.add(assets)) {
            return Result.Ok("their table is already on @${idOf(assets)}", alreadyDone = true)
        }
        val apk = pigeon.sourceDir ?: run {
            // Removed again so a later call can retry: unlike a missing DexPathList field, an
            // unreadable package path is a transient condition (a mid-update install), and pinning
            // this AssetManager as done-and-failed would outlive the cause.
            patchedAssets.remove(assets)
            return Result.Failed("their APK path is unknown")
        }

        val result = try {
            val addAssetPath = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPath.invoke(assets, apk) as? Int
            when {
                cookie == null || cookie == 0 ->
                    Result.Failed("addAssetPath on @${idOf(assets)} returned $cookie")
                verifyWith == null ->
                    Result.Ok("cookie $cookie on @${idOf(assets)}, unverified")
                else -> verify(verifyWith, cookie, assets)
            }
        } catch (e: ReflectiveOperationException) {
            // addAssetPath is greylisted, not public. A future Android that enforces the greylist
            // harder ends hosting and leaves everything reflective working, same as installDex.
            Result.Failed("addAssetPath is not reachable (${e.javaClass.simpleName})")
        } catch (e: LinkageError) {
            Result.Failed("their table would not load (${e.javaClass.simpleName})")
        }

        if (result is Result.Failed) patchedAssets.remove(assets)
        Log.i(TAG, "installResources -> $result")
        return result
    }

    /**
     * Confirm one of their ids resolves through [resources] after the merge.
     *
     * [KnownTheirLayout] is a canary, not a dependency: nothing here needs that specific layout,
     * and no code of ours ever draws it. It is checked because a merge that lands but resolves
     * nothing is indistinguishable from a merge that worked until an Activity tries to inflate
     * something, at which point the exception names a frame in *their* code and points nowhere near
     * the real cause.
     *
     * Failing the canary does **not** fail the install. Their build could rename or renumber that
     * layout, and refusing to host a game because a probe constant went stale would be the tail
     * wagging the dog. The result says the canary is missing, and the caller can decide.
     */
    private fun verify(
        resources: android.content.res.Resources,
        cookie: Int,
        assets: AssetManager,
    ): Result = try {
        val name = resources.getResourceName(KnownTheirLayout)
        Result.Ok("cookie $cookie on @${idOf(assets)}, 0x%08x = %s".format(KnownTheirLayout, name))
    } catch (e: android.content.res.Resources.NotFoundException) {
        Result.Ok(
            "cookie $cookie on @${idOf(assets)}, but 0x%08x still resolves to nothing — their "
                .format(KnownTheirLayout) +
                "build may have renumbered it, so treat the merge as unconfirmed",
        )
    }

    /**
     * Merge their table into every live `Resources` in this process.
     *
     * The awkward case this exists for: [HostedComponentFactory.instantiateActivity] is handed a
     * `ClassLoader`, a class name and an `Intent` — **no Context**. The table it has to patch was
     * built moments earlier by `createBaseContextForActivity` and is not addressable from anything
     * in that signature.
     *
     * `ResourcesManager` is the one object that knows about all of them. It is a process singleton
     * and keeps every `ResourcesImpl` it has built in `mResourceImpls`, a
     * `Map<ResourcesKey, WeakReference<ResourcesImpl>>`. Measured from a real Activity: seven live
     * entries, and that Activity's own `AssetManager` among them.
     *
     * ## Why every table and not just the newest
     *
     * There is no ordering guarantee in that map worth relying on — it is keyed by `ResourcesKey`,
     * not by age — so "the newest" would have to be inferred, and an inference that is wrong once
     * produces a game that launches blank on some path nobody can reproduce. Patching all of them
     * costs one `addAssetPath` per table on the first hosted launch and nothing thereafter, because
     * [patchedAssets] remembers each one. Tables built *later* are not covered by this call, which
     * is why the per-object guard matters more than the sweep does: the next hosted launch sweeps
     * again and picks them up.
     *
     * Returns a [Result] describing the sweep as a whole. A partial success is reported as success
     * with counts, because one unpatchable table among seven is not a reason to abandon a launch —
     * the one that matters is very likely among the rest, and the alternative to trying is a
     * guaranteed `NotFoundException`.
     */
    fun installResourcesEverywhere(pigeon: InstalledOpenPigeon): Result {
        val tables = try {
            liveResources()
        } catch (e: ReflectiveOperationException) {
            return Result.Failed("ResourcesManager is not reachable (${e.javaClass.simpleName})")
        } catch (e: LinkageError) {
            return Result.Failed("ResourcesManager is not reachable (${e.javaClass.simpleName})")
        }
        if (tables.isEmpty()) return Result.Failed("no live Resources found to merge into")

        var merged = 0
        var already = 0
        var refused = 0
        for (resources in tables) {
            when (val one = installResources(pigeon, resources.assets, resources)) {
                is Result.Ok -> if (one.alreadyDone) already++ else merged++
                is Result.Failed -> refused++
            }
        }
        val detail = "$merged merged, $already already, $refused refused of ${tables.size} tables"
        return if (refused == tables.size) {
            Result.Failed(detail)
        } else {
            Result.Ok(detail, alreadyDone = merged == 0)
        }
    }

    /**
     * Every `Resources` this process currently has alive.
     *
     * Reconstructed from `ResourcesManager.mResourceImpls` rather than from any Context, because
     * the caller that needs this has no Context. Each value is a `WeakReference<ResourcesImpl>`;
     * cleared ones are skipped rather than treated as an error, since an evicted table is exactly
     * the case this is supposed to tolerate.
     *
     * `ResourcesImpl` is not a `Resources` — it is the shared implementation several `Resources`
     * objects can point at — so a throwaway `Resources` is wrapped around each one purely to have
     * something with an `assets` property and a `getResourceName`. That wrapper is never handed to
     * their code; only the `AssetManager` inside it is shared, and that is the object being merged
     * into.
     */
    @Throws(ReflectiveOperationException::class)
    private fun liveResources(): List<android.content.res.Resources> {
        val rmClass = Class.forName("android.app.ResourcesManager")
        val manager = rmClass.getMethod("getInstance").invoke(null)
        val impls = rmClass.getDeclaredField("mResourceImpls")
            .apply { isAccessible = true }
            .get(manager) as? Map<*, *>
            ?: throw NoSuchFieldException("mResourceImpls is not a Map")

        val assetsField = Class.forName("android.content.res.ResourcesImpl")
            .getDeclaredField("mAssets").apply { isAccessible = true }

        val seen = mutableSetOf<AssetManager>()
        return impls.values.mapNotNull { ref ->
            val impl = (ref as? Reference<*>)?.get() ?: return@mapNotNull null
            val assets = assetsField.get(impl) as? AssetManager ?: return@mapNotNull null
            // Several ResourcesImpl can share one AssetManager. Merging twice is harmless — the
            // per-object guard catches it — but the counts in the sweep's result would lie.
            if (!seen.add(assets)) return@mapNotNull null
            @Suppress("DEPRECATION")
            android.content.res.Resources(assets, null, null)
        }
    }

    private fun idOf(any: Any) = Integer.toHexString(System.identityHashCode(any))

    /**
     * The soname a `dlopen` failure blames, or `null` if the failure is not a missing dependency.
     *
     * The distinction this draws is the one the probe got wrong on its first run. Both of these are
     * an `UnsatisfiedLinkError` and they call for opposite fixes:
     *
     * ```
     * library "libopenbubblesextension.so" not found                       <- the file is missing
     * library "libc++_shared.so" not found: needed by …/libopenbubbles…so  <- a dependency is
     * ```
     *
     * Only the second has a `needed by` clause, and only the second is worth preloading. Keying on
     * that clause rather than on the quoted name is what keeps a genuine search-path miss from
     * being mistaken for a dependency chain and sending the caller off to preload a file that was
     * never the obstacle.
     */
    private fun missingSonameIn(outcome: String): String? {
        if (!outcome.contains("needed by")) return null
        return Regex("""library "([^"]+)" not found""").find(outcome)?.groupValues?.get(1)
    }

    /** Attempt the load; `null` means it succeeded, otherwise the linker's own message. */
    private fun loadOutcome(name: String): String? = try {
        System.loadLibrary(name)
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message?.take(300) ?: "UnsatisfiedLinkError with no message"
    } catch (e: LinkageError) {
        "${e.javaClass.simpleName}: ${e.message?.take(300)}"
    }

    /**
     * Which ABI directory of their APK this device can actually run.
     *
     * Read out of their archive rather than assumed from [Build.SUPPORTED_ABIS] alone, because the
     * two lists are not the same question. A 64-bit device supports `arm64-v8a` *and* `armeabi-v7a`;
     * their APK may carry only one of them. Picking our preferred ABI and hoping produces a "not
     * found" that looks exactly like a broken search path.
     *
     * [Build.SUPPORTED_ABIS] is iterated in order — it is already sorted most-preferred first — and
     * the first one their APK has wins. Opening the archive is cheap: [ZipFile] reads only the
     * central directory, not the 500 MB of entries.
     */
    private fun abiIn(apkPath: String): String? {
        val present = try {
            ZipFile(File(apkPath)).use { zip ->
                zip.entries().asSequence()
                    .mapNotNull { entry ->
                        val parts = entry.name.split('/')
                        if (parts.size >= 3 && parts[0] == "lib") parts[1] else null
                    }
                    .toSet()
            }
        } catch (e: Exception) {
            // Unreadable archive is a real possibility (permissions, a mid-update package). Falling
            // back to our preferred ABI is a guess, and it is labelled as one in the log so a
            // subsequent "not found" is not mistaken for a search-path bug.
            Log.w(TAG, "could not read ABIs from $apkPath (${e.javaClass.simpleName}) — guessing")
            return Build.SUPPORTED_ABIS.firstOrNull()
        }
        return Build.SUPPORTED_ABIS.firstOrNull { it in present }
    }

    /**
     * Append [apkPath] to [loader]'s `dexElements`.
     *
     * Rather than call `makePathElements` — whose signature has changed repeatedly across releases —
     * this builds a throwaway [PathClassLoader] over their APK and steals its already-constructed
     * elements. Version-tolerant, and shorter.
     */
    @Throws(ReflectiveOperationException::class)
    private fun appendDex(loader: ClassLoader, apkPath: String): Result {
        val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
            .getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(loader)

        val dexElementsField = pathList.javaClass
            .getDeclaredField("dexElements").apply { isAccessible = true }
        val existing = dexElementsField.get(pathList) as Array<*>

        val donor = PathClassLoader(apkPath, null, null)
        val donorElements = dexElementsField.get(pathListField.get(donor)) as Array<*>
        if (donorElements.isEmpty()) return Result.Failed("their APK yielded no dex elements")

        val merged = java.lang.reflect.Array.newInstance(
            existing.javaClass.componentType,
            existing.size + donorElements.size,
        )
        System.arraycopy(existing, 0, merged, 0, existing.size)
        System.arraycopy(donorElements, 0, merged, existing.size, donorElements.size)
        dexElementsField.set(pathList, merged)

        return Result.Ok("${existing.size} of ours + ${donorElements.size} of theirs")
    }

    /**
     * Append `<[apkPath]>!/lib/<[abi]>` to [loader]'s native search path.
     *
     * Two representations have to be kept in step and that is the whole difficulty:
     *
     * - `nativeLibraryDirectories`, a `List<File>` — what `DexPathList` re-derives from;
     * - `nativeLibraryPathElements`, an `Element[]` — what `findLibrary` actually walks.
     *
     * Writing only the first is the classic silent no-op: the field looks correct under a debugger
     * and `findLibrary` never consults it.
     */
    @Throws(ReflectiveOperationException::class)
    private fun appendNativePath(loader: ClassLoader, apkPath: String, abi: String): Result {
        val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
            .getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(loader)
        val listClass = pathList.javaClass

        val dirsField = listClass
            .getDeclaredField("nativeLibraryDirectories").apply { isAccessible = true }
        val elementsField = listClass
            .getDeclaredField("nativeLibraryPathElements").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val dirs = dirsField.get(pathList) as MutableList<File>
        val existing = elementsField.get(pathList) as Array<*>

        // An in-APK library is addressed by this exact syntax; the linker splits on '!' and mmaps
        // the entry out of the archive. It is a path, not a directory, which is why File() is the
        // right type despite nothing existing at that name on disk.
        val inApk = File("$apkPath!/lib/$abi")
        if (dirs.none { it.path == inApk.path }) dirs.add(inApk)

        val donor = PathClassLoader("", inApk.path, null)
        val donorElements = elementsField.get(pathListField.get(donor)) as Array<*>
        if (donorElements.isEmpty()) {
            return Result.Failed("no native path element could be built for ${inApk.path}")
        }

        val merged = java.lang.reflect.Array.newInstance(
            existing.javaClass.componentType,
            existing.size + donorElements.size,
        )
        System.arraycopy(existing, 0, merged, 0, existing.size)
        System.arraycopy(donorElements, 0, merged, existing.size, donorElements.size)
        elementsField.set(pathList, merged)

        return Result.Ok("$abi, ${existing.size} of ours + ${donorElements.size} of theirs")
    }

    /** Mark a cached result as a repeat, so a caller can tell a no-op from work done. */
    private fun Result.repeat(): Result = when (this) {
        is Result.Ok -> copy(alreadyDone = true)
        is Result.Failed -> this
    }
}
