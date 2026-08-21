package com.roowus.openseagull.host

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * One game from the installed OpenPigeon, reachable only by reflection.
 *
 * A `Game` in OpenPigeon is an interface with `getName()`, `displayName()`, `getVersion()`,
 * `gamePoster(Map)` and friends. We cannot hold it as that type — see [InstalledOpenPigeon] on
 * per-ClassLoader class identity — so this wraps the opaque instance and exposes the handful of
 * members OpenSeagull actually needs, each resolved defensively.
 *
 * Every property is nullable because the user's OpenPigeon is not the build this was compiled
 * against. A game that has gained or lost a method between versions should degrade to a missing
 * label, never to a crash.
 */
class ForeignGame internal constructor(
    private val pigeon: InstalledOpenPigeon,
    /** The opaque instance, whose class comes from their ClassLoader. */
    val instance: Any,
) {
    private val klass: Class<*> get() = instance.javaClass

    /** Stable wire name, e.g. `pool`, `anagrams`. This is what routing keys off. */
    val name: String? by lazy { klass.invokeOrNull(instance, "getName") as? String }

    /** Human-facing label, e.g. `Anagrams`. */
    val displayName: String? by lazy { klass.invokeOrNull(instance, "displayName") as? String }

    /** Game protocol version, as OpenPigeon reports it. */
    val version: String? by lazy { klass.invokeOrNull(instance, "getVersion") as? String }

    /**
     * The game's poster art.
     *
     * `gamePoster()` returns an `R.drawable` id **from their dex**, so it is resolved against
     * *their* [InstalledOpenPigeon.resources]. Resolving it against ours would silently return an
     * unrelated image — the exact trap documented on [InstalledOpenPigeon].
     */
    val poster: Drawable? by lazy {
        val id = klass.invokeOrNull(
            instance,
            "gamePoster",
            arrayOf(Map::class.java),
            arrayOf<Any?>(null),
        ) as? Int
        id?.let { pigeon.drawable(it) }
    }

    /**
     * The player identity OpenPigeon would use, read with the Context deliberately chosen.
     *
     * Their `getSenderUUID(context)` mints and caches a UUID in
     * `getSharedPreferences("openpigeon")` **of whatever Context it is handed**. Passing ours
     * would create a second identity in our own prefs — measured on-device: our Context yielded
     * `efa4fbfd-…`, theirs `e754dc69-…`, for the same game object.
     *
     * We pass *their* [InstalledOpenPigeon.packageContext] so the identity is the one the user
     * already plays under. Their data directory is not writable by us, so this reads an existing
     * identity rather than minting one; a user who has never launched OpenPigeon may have none
     * yet, which is why the result is nullable.
     */
    fun senderUuid(): String? = klass.invokeOrNull(
        instance,
        "getSenderUUID",
        arrayOf(Context::class.java),
        arrayOf<Any?>(pigeon.packageContext),
    ) as? String

    override fun toString(): String = "ForeignGame(${name ?: klass.name})"
}

/**
 * The set of games the installed OpenPigeon provides.
 *
 * Two strategies, in order:
 *
 * 1. **Read their own list.** `MadridExtension`'s companion object holds
 *    `val games: List<Game>` — the authoritative registry. Reading it means OpenSeagull picks up
 *    games added by OpenPigeon versions released after this code was written, with no update.
 *
 * 2. **Fall back to known class names.** If their internals move, [KNOWN_GAMES] still finds the
 *    games that have existed for a long time. Strictly worse (it cannot discover anything new) but
 *    it degrades instead of going empty.
 *
 * The fallback is deliberately not silent — [strategy] records which path produced the result, so
 * a diagnostic screen can distinguish "OpenPigeon has no games" from "we failed to read its list".
 *
 * The count is whatever the installed build has, and is expected to differ from any list read out
 * of OpenPigeon's current source. Measured against v1.1.0 (versionCode 26071901): 25 games, where
 * upstream source at the time listed 26. The missing one was Shuffleboard, added upstream after
 * that APK was built — confirmed by finding no `openpigeon/shuffle` classes in the installed dex.
 * Reading their registry rather than a list of our own is precisely what makes that a non-event.
 */
class ForeignGameCatalog private constructor(
    val games: List<ForeignGame>,
    val strategy: Strategy,
) {
    enum class Strategy {
        /** Read from `MadridExtension.Companion.getGames()` — complete and version-tolerant. */
        THEIR_REGISTRY,

        /** Built from [KNOWN_GAMES] because their registry could not be read. */
        KNOWN_NAMES,

        /** Neither worked. [games] is empty. */
        NONE,
    }

    companion object {
        /**
         * Long-lived game classes, used only when their registry cannot be read.
         *
         * Not exhaustive by design: this is a floor, not a mirror of their list. Keeping it short
         * avoids implying a completeness it cannot have.
         */
        val KNOWN_GAMES = listOf(
            "com.openbubbles.openpigeon.pool.PoolGame",
            "com.openbubbles.openpigeon.anagrams.AnagramsGame",
        )

        fun of(pigeon: InstalledOpenPigeon): ForeignGameCatalog {
            fromTheirRegistry(pigeon)?.takeIf { it.isNotEmpty() }?.let {
                return ForeignGameCatalog(it, Strategy.THEIR_REGISTRY)
            }
            fromKnownNames(pigeon).takeIf { it.isNotEmpty() }?.let {
                return ForeignGameCatalog(it, Strategy.KNOWN_NAMES)
            }
            return ForeignGameCatalog(emptyList(), Strategy.NONE)
        }

        /**
         * Read `MadridExtension.Companion.getGames()`.
         *
         * A Kotlin `companion object` compiles to a static `Companion` field holding an instance
         * of a nested `…$Companion` class, with a getter per property. So the sequence is: load
         * `MadridExtension`, read its static `Companion`, call `getGames()` on that. We never
         * construct `MadridExtension` itself — it takes a Context and extends `IMadridExtension
         * .Stub()`, and instantiating a foreign Binder stub in our process is both unnecessary
         * here and a good way to acquire problems.
         *
         * Touching the class does run its static initialiser, which constructs all of their game
         * objects. That is the point — those objects are the registry — but it also means any
         * failure in any one of their constructors surfaces here, hence the broad guard.
         */
        private fun fromTheirRegistry(pigeon: InstalledOpenPigeon): List<ForeignGame>? = try {
            val ext = pigeon.loadClassOrNull("com.openbubbles.openpigeon.MadridExtension")
                ?: return null
            val companion = ext.getField("Companion").get(null) ?: return null
            val list = companion.javaClass.invokeOrNull(companion, "getGames") as? List<*>
                ?: return null
            list.filterNotNull().map { ForeignGame(pigeon, it) }
        } catch (_: NoSuchFieldException) {
            null
        } catch (_: IllegalAccessException) {
            null
        } catch (_: ForeignCallException) {
            // Their registry initialiser threw. Nothing we can do about it from out here, and the
            // fallback may still work, so treat it as "unreadable" rather than propagating.
            null
        } catch (_: LinkageError) {
            // Their class references something that will not link in our process.
            null
        } catch (_: ExceptionInInitializerError) {
            // Static initialiser (the games list itself) blew up.
            null
        }

        private fun fromKnownNames(pigeon: InstalledOpenPigeon): List<ForeignGame> =
            KNOWN_GAMES.mapNotNull { fqcn ->
                try {
                    pigeon.loadClassOrNull(fqcn)
                        ?.newInstanceOrNull()
                        ?.let { ForeignGame(pigeon, it) }
                } catch (_: ForeignCallException) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            }
    }

    val isEmpty: Boolean get() = games.isEmpty()

    fun byName(name: String): ForeignGame? = games.firstOrNull { it.name == name }
}
