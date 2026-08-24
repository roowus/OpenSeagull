package com.roowus.openseagull.host

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable

/**
 * The single point of contact with the user's installed OpenPigeon.
 *
 * OpenSeagull ships no OpenPigeon code and no OpenPigeon assets. Everything it needs is read out
 * of the copy the user installed themselves, at runtime, through the two public APIs Android
 * offers for it: [Context.createPackageContext] with `CONTEXT_INCLUDE_CODE` for classes, and
 * [PackageManager.getResourcesForApplication] for resources.
 *
 * ## Why a dedicated class instead of ad-hoc calls
 *
 * Three properties of cross-package loading are easy to get wrong and two of them fail *silently*.
 * Funnelling every access through one object is what makes them enforceable rather than
 * remembered. Each was measured on-device by `RuntimeHostProbe` before this class existed; the
 * numbers quoted below are that probe's output, not assumptions.
 *
 * ### 1. Class identity is per-ClassLoader — there is no casting, ever
 *
 * Their classes arrive from a different [ClassLoader] than ours. Even when the fully-qualified
 * name is identical, the [Class] objects are not, and the JVM will refuse the cast with a message
 * that reads like a compiler bug:
 *
 * ```
 * ClassCastException: com.openbubbles.openpigeon.pool.Drand48
 *     cannot be cast to com.openbubbles.openpigeon.pool.Drand48
 * ```
 *
 * That is not a namespace collision to be tidied away — it is how [ClassLoader] isolation works,
 * and it holds no matter what either side is named. Consequently *all* interop is reflective, by
 * construction. That is a design constraint on OpenSeagull, not a temporary state: no interface of
 * ours can be implemented by their class, and no instance of theirs can be assigned to a typed
 * field. Cross-loader calls go through [invoke] and its siblings or they do not happen.
 *
 * ### 2. A resource id is meaningless without the table it came from
 *
 * `R` constants are compiled into each APK's dex, and the same symbol gets different ids in
 * different packages — measured `theirs=0x7f070106` vs `ours=0x7f070108` for `madrid_icon`. An id
 * of theirs resolved against *our* [Resources] does not throw. It returns whatever unrelated
 * resource happens to occupy that slot; in the probe, their `madrid_icon` came back as our
 * `m3_tabs_rounded_line_indicator`. A wrong picture with no stack trace is the worst failure mode
 * available, so [resources] exists to make the correct pairing the only convenient one.
 *
 * ### 3. Their code writes to whichever data directory the Context points at
 *
 * OpenPigeon's `getSenderUUID(context)` lazily mints a player identity into
 * `getSharedPreferences("openpigeon")`. Handed our [Context] it returned a *different* UUID than
 * when handed theirs, because it read and wrote our prefs file. Identity therefore follows the
 * Context, and passing one by reflex silently forks the player's identity between the two apps.
 * See [packageContext] for which to pass and why.
 *
 * Passing theirs is necessary and, for this one method, still not sufficient: their prefs file is
 * not readable across uids either, so their code sees an empty map and mints a throwaway that is
 * never written — a fresh identity every process. Measured in `ForeignIdentityProbe`; the
 * consequences are on [ForeignGameCatalog.senderUuid]. Reading *code* out of their APK works;
 * reading their *private data* does not, and the two failures look alike from the call site.
 *
 * ## What this class does not do
 *
 * Nothing here copies, extracts, or redistributes any part of OpenPigeon. The code stays in the
 * user's APK and executes from it; this is the same mechanism a launcher uses to draw another
 * app's icon, applied to a class rather than a bitmap.
 */
class InstalledOpenPigeon private constructor(
    private val host: Context,
    /** The package id that was actually found, e.g. `com.openbubbles.openpigeon`. */
    val packageName: String,
) {

    companion object {
        /**
         * Package ids to look for, in order of preference.
         *
         * More than one entry because a user may have a rebuilt or repackaged copy installed. The
         * first that resolves wins. Package-visibility filtering (targetSdk 30+) means every id
         * here must also appear in `<queries>` in the manifest or [PackageManager] will report it
         * missing even when it is installed.
         */
        val CANDIDATES = listOf(
            "com.openbubbles.openpigeon",
        )

        /**
         * Locate an installed OpenPigeon, or return `null` if the user has none.
         *
         * `null` is an ordinary, expected outcome — OpenSeagull is useless without OpenPigeon and
         * must say so plainly rather than crash. Callers should surface [NotInstalledMessage]
         * rather than treating this as an error condition.
         */
        fun find(host: Context): InstalledOpenPigeon? {
            for (candidate in CANDIDATES) {
                try {
                    host.packageManager.getPackageInfo(candidate, 0)
                    return InstalledOpenPigeon(host, candidate)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Not installed, or filtered out of view by <queries>. Try the next.
                }
            }
            return null
        }

        /** What to show the user when [find] returns `null`. */
        const val NotInstalledMessage: String =
            "OpenSeagull needs OpenPigeon installed. It adds features to OpenPigeon; " +
                "it does not replace it."
    }

    /**
     * A [Context] for the installed OpenPigeon, carrying both its code and its resources.
     *
     * `CONTEXT_IGNORE_SECURITY` is required because the two apps are signed by different keys in
     * any real install. It does not grant us anything the user has not already installed: the
     * classes still run in *our* process, under *our* uid, with *our* permissions. The probe
     * confirmed this path works across `SIGNATURE_NO_MATCH`.
     *
     * Prefer this Context whenever their code will touch storage, because their data belongs in
     * their directory. `prefsWrite` against it measured `false` — we can read their world but not
     * write into it, which is the correct and expected sandbox boundary, not a bug to work around.
     * Where a write is genuinely needed, it belongs in *our* data directory under a key we own.
     */
    val packageContext: Context by lazy {
        host.createPackageContext(
            packageName,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
        )
    }

    /** The foreign [ClassLoader]. Every class it returns is reflection-only — see the class KDoc. */
    val classLoader: ClassLoader get() = packageContext.classLoader

    /**
     * Their resource table.
     *
     * Any id that came out of their dex must be resolved through this and never through
     * `host.resources`, which would silently return an unrelated resource.
     */
    val resources: Resources by lazy {
        host.packageManager.getResourcesForApplication(packageName)
    }

    /** Load one of their classes, or `null` if this build of OpenPigeon does not have it. */
    fun loadClassOrNull(fqcn: String): Class<*>? = try {
        classLoader.loadClass(fqcn)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        // A class that exists but cannot be linked here (missing dependency, verifier rejection).
        // Indistinguishable from absent for our purposes, and equally not worth crashing over.
        null
    }

    /**
     * Resolve one of *their* resource ids to a [Drawable], against their table.
     *
     * Returns `null` rather than throwing: an id from an OpenPigeon version we were not built
     * against is a routine mismatch, not an exceptional one.
     */
    fun drawable(theirId: Int): Drawable? = try {
        resources.getDrawable(theirId, null)
    } catch (_: Resources.NotFoundException) {
        null
    }

    /** Look a resource up by name instead of by id — version-tolerant, unlike a baked-in constant. */
    fun drawableByName(name: String): Drawable? {
        val id = resources.getIdentifier(name, "drawable", packageName)
        return if (id == 0) null else drawable(id)
    }

    /**
     * The absolute path to their installed `base.apk`.
     *
     * Needed because two things [packageContext] cannot do require the archive *by name*: appending
     * their dex to our own ClassLoader, and addressing a library inside it as
     * `<sourceDir>!/lib/<abi>/<soname>`. Both live in [ForeignCode]; nothing else should need this.
     *
     * Readable by our uid — measured, `head -c 4` on it returns the `PK` zip magic — so
     * the path is useful rather than merely informative. Nothing is copied out of it: the linker
     * mmaps entries in place, which is what makes hosting their code cost no disk.
     *
     * `null` means the package vanished between [find] and here, which a background uninstall can
     * genuinely cause.
     */
    val sourceDir: String?
        get() = try {
            host.packageManager.getApplicationInfo(packageName, 0).sourceDir
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** Version of the installed OpenPigeon, for diagnostics and compatibility gates. */
    val versionName: String?
        get() = try {
            host.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
