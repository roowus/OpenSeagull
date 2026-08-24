package com.roowus.openseagull

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Verifies the runtime-host architecture against the OpenPigeon actually installed on the device.
 *
 * Unlike the exploratory probe this project grew out of, these are **real assertions**: the
 * architecture is no longer in question, so a regression should fail the build rather than print a
 * line someone has to read. The exception is [reportEnvironment], which is descriptive by design.
 *
 * All of these require OpenPigeon to be installed and are skipped (not failed) when it is not —
 * an absent OpenPigeon is a valid device state, just not one that can test anything.
 *
 * Read the descriptive output with:
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 * `Log`, not `println`: instrumentation stdout is discarded on recent Android versions and reaches
 * neither logcat nor the JUnit XML.
 */
class RuntimeHostProbe {

    private val tag = "SEAGULL"

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** `null` when OpenPigeon is not installed, which every test below treats as "skip". */
    private fun pigeonOrSkip(): InstalledOpenPigeon? {
        val p = InstalledOpenPigeon.find(ctx())
        if (p == null) Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
        return p
    }

    /** Descriptive, not assertive — a snapshot of the environment the other tests ran against. */
    @Test
    fun reportEnvironment() {
        val c = ctx()
        Log.i(tag, "self=${c.packageName}")
        val p = pigeonOrSkip() ?: return

        val sig = when (c.packageManager.checkSignatures(c.packageName, p.packageName)) {
            PackageManager.SIGNATURE_MATCH ->
                "SIGNATURE_MATCH (same signer — cross-signature behaviour NOT exercised here)"
            PackageManager.SIGNATURE_NO_MATCH -> "SIGNATURE_NO_MATCH (the real-world case)"
            else -> "other"
        }
        Log.i(tag, "target=${p.packageName} v${p.versionName} signatures=$sig")

        val catalog = ForeignGameCatalog.of(p)
        Log.i(tag, "games=${catalog.games.size} via ${catalog.strategy}")
        catalog.games.forEach {
            Log.i(tag, "  ${it.name} / ${it.displayName} v${it.version}")
        }
    }

    /** The foreign ClassLoader must produce a class that is genuinely theirs, not ours. */
    @Test
    fun foreignClassLoaderResolvesTheirCode() {
        val p = pigeonOrSkip() ?: return
        val k = p.loadClassOrNull("com.openbubbles.openpigeon.MadridExtension")
        assertNotNull(k, "MadridExtension should load from the installed OpenPigeon")
        assert(k.classLoader !== javaClass.classLoader) {
            "Their class must come from their ClassLoader, not ours"
        }
    }

    /**
     * The catalog must find games, and by their own registry rather than the fallback.
     *
     * Asserting the *strategy* and not merely the count is the point: [ForeignGameCatalog] falls
     * back to two hardcoded class names, so a count-only assertion would stay green while
     * discovery had silently broken and the catalog had shrunk from 26 games to 2.
     */
    @Test
    fun catalogReadsTheirOwnRegistry() {
        val p = pigeonOrSkip() ?: return
        val catalog = ForeignGameCatalog.of(p)
        assert(catalog.games.isNotEmpty()) { "expected at least one game" }
        assert(catalog.strategy == ForeignGameCatalog.Strategy.THEIR_REGISTRY) {
            "expected to read their registry, but fell back to ${catalog.strategy}; " +
                "discovery has broken and the catalog is now incomplete"
        }
        assert(catalog.games.size > ForeignGameCatalog.KNOWN_GAMES.size) {
            "catalog (${catalog.games.size}) should exceed the hardcoded fallback list"
        }
    }

    /**
     * Poster art must resolve against **their** resource table.
     *
     * This is the wrong-Resources trap, asserted rather than eyeballed. Their `madrid_icon` id and
     * ours differ (measured 0x7f070106 vs 0x7f070108), and resolving theirs against our table does
     * not throw — it silently returns an unrelated drawable. So the check is not "did we get a
     * drawable" but "did we get *their* drawable", which is verified by name.
     */
    @Test
    fun posterResolvesAgainstTheirResources() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull { it.poster != null }
        assertNotNull(game, "at least one game should expose poster art")

        val id = game.instance.javaClass
            .getMethod("gamePoster", Map::class.java)
            .invoke(game.instance, null) as Int

        // The authoritative check: their table must own this id under their package name.
        val theirName = p.resources.getResourceName(id)
        assert(theirName.startsWith("${p.packageName}:")) {
            "id 0x${Integer.toHexString(id)} resolved to $theirName, which is not theirs"
        }

        // And the same id against OUR table is either absent or something unrelated — the failure
        // this pairing exists to prevent. Logged rather than asserted because the id may simply
        // not exist in our much smaller table, which is equally fine.
        val ours = try {
            ctx().resources.getResourceName(id)
        } catch (_: android.content.res.Resources.NotFoundException) {
            "absent from our table"
        }
        Log.i(tag, "trap check: id 0x${Integer.toHexString(id)} theirs=$theirName ours=$ours")
    }

    /**
     * Player identity must be read from **their** Context.
     *
     * Their `getSenderUUID(context)` mints and caches a UUID into the prefs of whichever Context it
     * is handed, so passing ours would silently fork the player's identity between the two apps.
     * The two must differ; if they are equal, [ForeignGame.senderUuid] is passing the wrong one.
     */
    @Test
    fun senderIdentityComesFromTheirContext() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull() ?: return
        val theirs = game.senderUuid()
        if (theirs == null) {
            // No identity yet: OpenPigeon has never been launched. Not a failure.
            Log.i(tag, "SKIP: no sender identity in their prefs yet")
            return
        }

        val ours = game.instance.javaClass
            .getMethod("getSenderUUID", Context::class.java)
            .invoke(game.instance, ctx()) as String

        Log.i(tag, "identity theirs=$theirs ours=$ours")
        assert(theirs != ours) {
            "identity came from OUR prefs — senderUuid() is passing the wrong Context"
        }
    }

    /**
     * We ship no OpenPigeon code: their classes must not be present in our own APK.
     *
     * Checked through a throwaway loader over just our APK rather than [javaClass.classLoader],
     * and not as a nicety: `installDex` appends their dex to the process-wide loader, so any other
     * test in this class that ran first makes the shared loader answer "present" for classes our
     * APK has never carried. Through that loader this gate's verdict would depend on JUnit's
     * execution order — which is exactly how it failed once on emulator-5554 while every static
     * check (CI's release-dex grep) stayed green.
     */
    @Test
    fun weShipNoOpenPigeonCode() {
        val info = ctx().applicationInfo
        val isolated = dalvik.system.PathClassLoader(info.sourceDir, null)
        for (fqcn in listOf(
            "com.openbubbles.openpigeon.MadridExtension",
            "com.openbubbles.openpigeon.Game",
            "com.openbubbles.openpigeon.pool.Drand48",
        )) {
            val found = try {
                isolated.loadClass(fqcn); true
            } catch (_: ClassNotFoundException) {
                false
            }
            assert(!found) { "$fqcn is in OUR apk — this build is not content-free" }
        }
    }
}
