package com.roowus.openseagull.host

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

/**
 * Answers the two questions gate 4 turns on, from inside a **real, non-instrumented process**.
 *
 * Gate 4 is the last one standing between a declared Activity of theirs and a board that draws.
 * Their `onCreate` calls `setContentView(0x7f0c001d)` — an id baked into their dex by their aapt2,
 * naming an entry in *their* resource table, which our process has never loaded:
 *
 * ```
 * android.content.res.Resources$NotFoundException: Resource ID #0x7f0c001d
 *     at com.openbubbles.openpigeon.knockout.KnockoutActivity.onCreate(KnockoutActivity.kt:196)
 * ```
 *
 * ## Why this is not a test
 *
 * `GameplayFeasibilityProbe` already calls `addAssetPath` and already reports a non-zero cookie.
 * That is **not** evidence the same call works in production, and treating it as such is the
 * expensive mistake available here. `addAssetPath` is a greylisted non-SDK method, and an
 * instrumented run can be launched with `am instrument --no-hidden-api-checks`, which lifts exactly
 * the restriction in question. A measurement taken with the obstacle removed cannot tell you
 * whether the obstacle is there.
 *
 * So this lives in `src/main`, reachable from [com.roowus.openseagull.DiagnosticsActivity], which
 * is `exported="true"` and launched by `adb shell am start` like any ordinary app screen — no
 * instrumentation, no relaxation, the same enforcement a user's device applies.
 *
 * ## The two questions, and why one answer cannot stand in for the other
 *
 * 1. **Is the merge legal for us at all?** ([reflectiveMerge])
 * 2. **Can it reach an Activity's Resources?** ([frameworkMerge], [livePatch])
 *
 * These are independent, and both must be yes. The framework builds an Activity's `Resources`
 * inside `createBaseContextForActivity`, which `ActivityThread.performLaunchActivity` calls
 * **before** `newActivity` — so by the time [HostedComponentFactory] runs, that object already
 * exists, and it is not the application's: `ResourcesManager` caches per `ResourcesKey`, so an
 * Activity gets its own `AssetManager`. [sharing] measures that rather than trusting it. A merge
 * that works on the application's table can be perfectly legal and still never be seen by the
 * launch it was meant to fix.
 *
 * Question 2 has two shapes, and the difference decides the whole design. [frameworkMerge] tries to
 * influence the table the framework is *about to build*; [livePatch] tries to repair the one it
 * *already built*. Only the second matches the position our hook is actually in.
 *
 * ## The trap in the framework route, and why the report checks for it specifically
 *
 * `ApplicationInfo.sharedLibraryFiles` is a **public field**, and `ContextImpl.createActivityContext`
 * feeds it into every `AssetManager` the framework builds — which would make the framework do the
 * `addAssetPath` for us, no non-SDK call anywhere. The catch is that `ResourcesManager` loads those
 * entries as **shared libraries**, and a shared library's package id is *reassigned at load time*.
 * Their `0x7f0c001d` would then name nothing, and the failure would look identical to not having
 * merged at all.
 *
 * That is why [Finding] separates `LoadedIntact` from `LoadedRemapped`. "Their id resolves" and
 * "their APK is in the table" are different claims, and only the first one is worth anything: the
 * ids are all that survived into their compiled code, so a table that holds their entries under
 * different numbers is a table their code cannot use. `getIdentifier` is irrelevant here — nothing
 * in their dex looks an entry up by name.
 *
 * ## One route mutates, on purpose, and it runs last
 *
 * [reflectiveMerge] and [frameworkMerge] leave nothing behind — a throwaway `AssetManager` and a
 * restored field — because a report that changed what it measured would poison every later reading.
 * [livePatch] cannot honour that: whether the *live* object accepts the call is the entire question,
 * and a patched copy would not answer it. So it merges this Activity's real table and leaves it
 * merged, runs after the other two, and says so on its own output line.
 */
object ForeignResourcesReport {

    /**
     * What happened to *their* id in a table built by one of the two routes.
     *
     * Ordered by how much they buy us, and deliberately finer-grained than success/failure: three
     * of these five are ways of failing that look like each other from the outside.
     */
    sealed interface Finding {

        /** The route worked and their baked id names their entry. The only outcome worth having. */
        data class LoadedIntact(val resolvedTo: String) : Finding

        /**
         * Their table is present but under a different package id, so their baked ids are dead.
         *
         * The expected outcome of the `sharedLibraryFiles` route, and the reason it is measured
         * rather than assumed.
         */
        data class LoadedRemapped(val theirIdResolvesTo: String) : Finding

        /** The call was refused — the answer this whole file exists to obtain honestly. */
        data class Blocked(val how: String) : Finding

        /** The route ran and their id still is not there. */
        data object NotLoaded : Finding

        /** The route could not be attempted; [why] says what was missing. */
        data class Unavailable(val why: String) : Finding
    }

    /**
     * The id every route is asked about.
     *
     * Owned by [ForeignCode] rather than declared here, because the merge this report measures is
     * now performed in production and verified against the same id. Two copies of a magic number
     * that must agree is one copy too many.
     */
    const val TheirKnockoutLayout = ForeignCode.KnownTheirLayout

    /**
     * Both routes, run against the installed OpenPigeon, as printable lines.
     *
     * Returns text rather than throwing or asserting, because the caller is a diagnostics screen
     * and because a blocked call is a *result* here, not an error.
     */
    fun of(host: Context, pigeon: InstalledOpenPigeon): List<String> {
        val theirApk = pigeon.sourceDir
            ?: return listOf("resources: their APK path is unknown — neither route can be tried")

        val truth = try {
            pigeon.resources.getResourceName(TheirKnockoutLayout)
        } catch (e: Resources.NotFoundException) {
            // Their own Resources not knowing their own id means the id has moved in a newer build
            // and everything below would be measuring the wrong thing.
            return listOf(
                "resources: 0x%08x is not in their table (%s) — their build has moved"
                    .format(TheirKnockoutLayout, e.javaClass.simpleName),
            )
        }

        val reflective = reflectiveMerge(host, theirApk, truth)
        val framework = frameworkMerge(host, theirApk, truth)
        // Last, because unlike the other two it does not undo itself. Anything measured after this
        // point would be reading a table that this line changed.
        val live = livePatch(host, theirApk, truth)

        return buildList {
            add("their layout id: 0x%08x = %s".format(TheirKnockoutLayout, truth))
            add("  reflective addAssetPath -> ${describe(reflective)}")
            add("  sharedLibraryFiles      -> ${describe(framework)}")
            add("  ${sharing(host)}")
            add("  live in-place patch    -> ${describe(live)}")
            add("  ${reachability(host)}")
            add("  ${verdict(reflective, framework, live)}")
        }
    }

    /**
     * Route 3: call `addAssetPath` on the **already-built** `AssetManager` of a live `Resources`.
     *
     * Routes 1 and 2 both ask "can a table be *built* with their APK in it", and the answer to that
     * turned out to be yes and no respectively. Neither answers the question the launch actually
     * poses, because the framework builds the Activity's `Resources` in
     * `createBaseContextForActivity` — before `newActivity`, and therefore before
     * [HostedComponentFactory] exists to influence it. [sharing] confirms that object is not the
     * application's, so there is nothing to pre-merge into.
     *
     * What that rules out is *preventing* the problem. It does not rule out repairing it: the
     * `AssetManager` is an ordinary object that outlives its construction, and `addAssetPath` is a
     * mutator. If it is accepted on a live instance, our factory hook is early enough after all —
     * it runs before `attach`, so before `onCreate`, so before the `setContentView` that fails.
     *
     * ## This one really does mutate the process, and that is not incidental
     *
     * Every other route in this file builds something disposable. This one cannot: patching a copy
     * would prove nothing, because the whole question is whether the *live* object accepts it. So
     * [host]'s own `Resources` is merged for real and stays merged for the life of this Activity.
     *
     * That is safe here for the same reason the hosting design works at all — this APK is built at
     * package id `0x80`, so appending a `0x7f` table can only add names that previously resolved to
     * nothing. It cannot change the meaning of any id of ours. It is still a real mutation, and the
     * output line says so rather than leaving a later reader to discover it.
     *
     * Reading back through `host.resources` rather than through the `AssetManager` is deliberate:
     * `Resources` is what their code holds, `ResourcesImpl` sits in between with its own caches, and
     * a patch the `AssetManager` accepted but `Resources` could not see would be a false pass.
     */
    private fun livePatch(host: Context, theirApk: String, truth: String): Finding = try {
        val assets = host.resources.assets
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, theirApk) as? Int

        if (cookie == null || cookie == 0) {
            Finding.Blocked("addAssetPath on the live AssetManager returned $cookie")
        } else {
            classify(host.resources, truth)
        }
    } catch (e: ReflectiveOperationException) {
        Finding.Blocked("${e.javaClass.simpleName}: ${(e.cause ?: e).message?.take(120)}")
    } catch (e: LinkageError) {
        Finding.Blocked("${e.javaClass.simpleName}: ${e.message?.take(120)}")
    }

    /**
     * Whether an Activity's `AssetManager` is the *same object* as the Application's.
     *
     * The reason this line exists is that "an Activity gets its own `AssetManager`" is a claim, not
     * an observation, and the whole hook-point design turns on it. `ResourcesManager` caches
     * `ResourcesImpl` by `ResourcesKey`, and an Activity with no override config has a key equal to
     * the Application's — so the cache may well hand back the *same* `ResourcesImpl`, and with it
     * the same `AssetManager`. If it does, a single in-place merge on the application's table is
     * seen by every hosted Activity, and the ordering problem disappears entirely.
     *
     * [host] must be an Activity for this to mean anything; called with an application Context it
     * would compare an object with itself and always say "shared".
     */
    private fun sharing(host: Context): String {
        val ours = host.resources.assets
        val app = host.applicationContext.resources.assets
        return if (ours === app) {
            "activity AssetManager === application's (@${id(ours)}) — one merge reaches both"
        } else {
            "activity AssetManager @${id(ours)} != application's @${id(app)} — separate tables"
        }
    }

    private fun id(any: Any) = Integer.toHexString(System.identityHashCode(any))

    /**
     * Can code holding no `Context` still find this Activity's `AssetManager`?
     *
     * [livePatch] proves the *mutation* works, but it cheats on reach: it is handed `host`, an
     * Activity, and simply asks it for its `Resources`. [HostedComponentFactory.instantiateActivity]
     * has no such thing — its parameters are a `ClassLoader`, a class name and an `Intent`. The
     * table it needs to patch exists at that moment and is not addressable from anything it holds.
     *
     * `ResourcesManager` is the one object that knows about all of them. It is a process singleton
     * (`getInstance()`) and it keeps every `ResourcesImpl` it has built in `mResourceImpls`, a
     * `Map<ResourcesKey, WeakReference<ResourcesImpl>>`. If this Activity's `AssetManager` is
     * reachable by walking that map, then the factory can reach the next Activity's the same way,
     * and the hook point is a real hook point rather than a demo that only works when someone hands
     * you the answer.
     *
     * Read-only on purpose: this walks and compares object identities, patching nothing. What it
     * reports is whether a *route* exists — [livePatch] already settled that arriving is enough.
     *
     * The failure mode worth naming is finding the map but not this Activity's entry, which would
     * mean the factory can reach *some* tables and not the one that matters. So the comparison is
     * against `host.resources.assets` by identity, not a count of what was found.
     */
    private fun reachability(host: Context): String = try {
        val target = host.resources.assets

        val rm = Class.forName("android.app.ResourcesManager")
        val instance = rm.getMethod("getInstance").invoke(null)
        val implsField = rm.getDeclaredField("mResourceImpls").apply { isAccessible = true }
        val impls = implsField.get(instance) as? Map<*, *>
            ?: return "ResourcesManager.mResourceImpls is not a Map — no blind route to the table"

        val assetsField = Class.forName("android.content.res.ResourcesImpl")
            .getDeclaredField("mAssets").apply { isAccessible = true }

        var total = 0
        var found = false
        for (ref in impls.values) {
            val impl = (ref as? java.lang.ref.Reference<*>)?.get() ?: continue
            total++
            if (assetsField.get(impl) === target) found = true
        }

        if (found) {
            "ResourcesManager holds $total live ResourcesImpl and THIS activity's AssetManager " +
                "is among them — the factory can reach it with no Context"
        } else {
            "ResourcesManager holds $total live ResourcesImpl but NOT this activity's " +
                "AssetManager — walking the cache is not a route to it"
        }
    } catch (e: ReflectiveOperationException) {
        "ResourcesManager is not reachable (${e.javaClass.simpleName}) — the factory would need " +
            "a Context from somewhere else"
    } catch (e: LinkageError) {
        "ResourcesManager is not reachable (${e.javaClass.simpleName})"
    }

    private fun describe(f: Finding): String = when (f) {
        is Finding.LoadedIntact -> "INTACT (${f.resolvedTo})"
        is Finding.LoadedRemapped -> "REMAPPED — their id now names ${f.theirIdResolvesTo}"
        is Finding.Blocked -> "BLOCKED (${f.how})"
        Finding.NotLoaded -> "not loaded"
        is Finding.Unavailable -> "unavailable (${f.why})"
    }

    /**
     * What the pair of findings means for the hook that has to be written next.
     *
     * Spelled out because the two routes fail in ways that call for opposite work, and the point of
     * measuring both was to not have to guess which.
     */
    private fun verdict(reflective: Finding, framework: Finding, live: Finding): String = when {
        // Leads because it is the only one of the three that answers the launch. Routes 1 and 2 ask
        // whether a table CAN be built with their APK in it; this asks whether the table the
        // framework ALREADY built for an Activity can be repaired, which is the situation the hook
        // is actually in.
        live is Finding.LoadedIntact ->
            "VERDICT: the live Activity AssetManager accepts addAssetPath in place and their ids " +
                "resolve through it. The hook point is settled: HostedComponentFactory runs " +
                "before attach and therefore before onCreate, so patching the Activity's own " +
                "Resources there is early enough. Next: ForeignCode.installResources, per-object " +
                "and idempotent, NOT process-wide"

        live is Finding.LoadedRemapped ->
            "VERDICT: the in-place patch landed but their id now names something else — do not " +
                "ship it. An id that resolves to the wrong entry draws a wrong layout instead of " +
                "throwing, which is the failure mode that costs days"

        live is Finding.Blocked && reflective is Finding.LoadedIntact ->
            "VERDICT: a table can be BUILT with their APK (route 1) but an existing one cannot be " +
                "patched (route 3). So the merge has to happen at construction, which our factory " +
                "hook is too late for — the remaining routes are ResourcesLoader (API 30+) and " +
                "replacing the Activity's Resources object wholesale, both needing an API 26-29 answer"

        framework is Finding.LoadedIntact ->
            "VERDICT: sharedLibraryFiles carries their ids intact — the framework builds the " +
                "Activity's table itself, so no non-SDK call is needed and the hook can set the " +
                "field before the launch"

        reflective is Finding.Blocked ->
            "VERDICT: addAssetPath is BLOCKED in a real process — the probe's cookie was an " +
                "artefact of relaxed hidden-API enforcement. Do not port that code. ResourcesLoader " +
                "(API 30+) is the remaining route and needs an answer for API 26-29"

        else ->
            "VERDICT: no route produced their id — read the three lines above before writing " +
                "any merge code"
    }

    /**
     * Route 1: build a table by calling the greylisted `AssetManager.addAssetPath` ourselves.
     *
     * Ours is added first, matching [ForeignCode]'s ordering rule — appending can only add names
     * that were previously unresolvable, so code of ours that already worked cannot change meaning.
     * Their ids survive that ordering only because this APK is built at package id `0x80`
     * (`--package-id 0x80 --allow-reserved-package-id`), leaving `0x7f` entirely to them.
     *
     * The distinction this function exists to draw is between a `Blocked` and a `NotLoaded`: a
     * refused reflective call and a merge that silently did nothing look the same from the outside,
     * and only the first one means "stop, use ResourcesLoader instead".
     */
    private fun reflectiveMerge(host: Context, theirApk: String, truth: String): Finding = try {
        val assets = AssetManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)

        val ourCookie = addAssetPath.invoke(assets, host.applicationInfo.sourceDir) as? Int
        val theirCookie = addAssetPath.invoke(assets, theirApk) as? Int

        if (theirCookie == null || theirCookie == 0) {
            Finding.Blocked("addAssetPath returned $theirCookie for their APK (ours=$ourCookie)")
        } else {
            @Suppress("DEPRECATION")
            val merged = Resources(assets, host.resources.displayMetrics, host.resources.configuration)
            classify(merged, truth)
        }
    } catch (e: ReflectiveOperationException) {
        // The one outcome that changes the plan. A NoSuchMethodException means the method is hidden
        // from us outright; an InvocationTargetException wrapping a SecurityException or
        // NoSuchMethodError is the runtime denying a greylisted call.
        Finding.Blocked("${e.javaClass.simpleName}: ${(e.cause ?: e).message?.take(120)}")
    } catch (e: LinkageError) {
        Finding.Blocked("${e.javaClass.simpleName}: ${e.message?.take(120)}")
    }

    /**
     * Route 2: append their APK to `ApplicationInfo.sharedLibraryFiles` and let the framework do it.
     *
     * Attractive because the field is public API and because the framework then performs the merge
     * on every `AssetManager` it builds — including the per-Activity one that route 1 cannot reach.
     *
     * `createConfigurationContext` is the honest stand-in for that: it goes through
     * `ResourcesManager.getResources` with the same `ApplicationInfo`, so it exercises the framework's
     * own builder rather than a reimplementation of it. If their ids come back remapped here, they
     * would be remapped in an Activity too.
     *
     * The field is restored in a `finally` regardless of outcome. This object promises not to mutate
     * the process, and leaving their APK on a live `ApplicationInfo` would do exactly that — every
     * `Resources` built afterwards would silently differ from one built before.
     */
    private fun frameworkMerge(host: Context, theirApk: String, truth: String): Finding {
        val info = host.applicationInfo
        val saved = info.sharedLibraryFiles
        if (saved != null && theirApk in saved) {
            return Finding.Unavailable("their APK is already on sharedLibraryFiles")
        }
        return try {
            info.sharedLibraryFiles = (saved ?: emptyArray()) + theirApk
            val rebuilt = host.createConfigurationContext(host.resources.configuration)
            classify(rebuilt.resources, truth)
        } catch (e: RuntimeException) {
            Finding.Unavailable("${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            info.sharedLibraryFiles = saved
        }
    }

    /**
     * Ask [res] what [TheirKnockoutLayout] names, and grade the answer against [truth].
     *
     * The three-way split is the whole point. A resolvable-but-different name is the shared-library
     * remap, and it is strictly worse than a clean miss because it would inflate *something* — their
     * code would draw the wrong layout instead of throwing, which is the failure mode that costs
     * days.
     */
    private fun classify(res: Resources, truth: String): Finding = try {
        val got = res.getResourceName(TheirKnockoutLayout)
        if (got == truth) Finding.LoadedIntact(got) else Finding.LoadedRemapped(got)
    } catch (e: Resources.NotFoundException) {
        Finding.NotLoaded
    }
}
