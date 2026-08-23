package com.roowus.openseagull.host

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Parcelable
import com.bluebubbles.messaging.MadridMessage

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

    /**
     * The Context handed to their game-logic calls.
     *
     * [senderUuid] gets away with the bare [InstalledOpenPigeon.packageContext] because it only
     * reads prefs. Anything that reaches their settings layer does not: `SettingsData.init` does
     * `appContext = context.applicationContext` into a non-null Kotlin type, and a Context from
     * `createPackageContext` has no application object, so the framework's null is rejected at the
     * call boundary. [ForeignAppContext] answers that one question and delegates everything else,
     * so their resource ids and package name still resolve against their own APK.
     *
     * One instance, held for the life of this game object, because their settings layer stores
     * whatever it is handed and a fresh wrapper per call would leave stale references behind.
     */
    private val appContext: Context by lazy { ForeignAppContext(pigeon.packageContext) }

    /**
     * How many people must be in the conversation before this game can be sent.
     *
     * Their own `ChooseGameCallback` checks this before composing anything and refuses with a toast
     * rather than sending, so our picker has to make the same decision or it will post balloons
     * their app would not have.
     *
     * Measured across the installed catalog: 25 of 26 games report `0`, and only `crazy`
     * (Crazy 8s) reports `3`. A default of `0` on a missing method is therefore the permissive
     * answer that matches every game we know about.
     */
    fun minPlayerRequirement(): Int =
        klass.invokeOrNull(instance, "minPlayerRequirement") as? Int ?: 0

    /**
     * Whether tapping this game opens a setup step instead of sending immediately.
     *
     * Measured: 17 of 26 games report `true`. We do not yet have their configuration UI, so this is
     * read to *record* the divergence rather than to act on it — see [MadridExtension.launchGame],
     * which sends the default game and says so.
     */
    fun isConfigurable(): Boolean =
        klass.invokeOrNull(instance, "isConfigurable") as? Boolean ?: false

    /**
     * The `Activity` class that plays this game, from *their* dex.
     *
     * `Class<*>` rather than a name because that is what their interface returns and what an
     * `Intent` constructor takes. It is theirs in the strong sense — loaded by their ClassLoader —
     * so an `Intent(ourContext, thatClass)` names a component our process cannot resolve until
     * their dex is added to our loader. That is a later step; this accessor only makes the class
     * reachable.
     *
     * Null means the method is gone. It is never null in a build we know of, but a caller that
     * assumed otherwise would crash at the launch site rather than declining to launch.
     */
    fun gameClass(): Class<*>? = klass.invokeOrNull(instance, "gameClass") as? Class<*>

    /**
     * Whether this build of the game can play [message] at all.
     *
     * Their interface defaults to `true` and only a few games override it — typically to refuse a
     * payload written by a newer protocol version than the installed code understands. Defaulting
     * to `true` on a missing method therefore matches their own default rather than inventing a
     * stricter one: being wrong in the permissive direction opens a game that may misbehave, being
     * wrong in the strict direction refuses one that would have worked.
     */
    fun isSupported(message: Map<String, String>): Boolean = klass.invokeOrNull(
        instance,
        "isSupported",
        arrayOf(Map::class.java),
        arrayOf<Any?>(message),
    ) as? Boolean ?: true

    /**
     * The one-line status their balloon shows, e.g. `Your Move.` or `I won!`.
     *
     * Called with [appContext] for the same reason [newGameData] is — their overrides reach the
     * settings layer, and a raw `createPackageContext` Context has no application object.
     *
     * Worth knowing before calling: their default body does `message["sender"]!!` inside the
     * `winner` branch, so a payload that carries `winner` **without** `sender` throws inside their
     * code. That surfaces here as [ForeignCallException] rather than as a crash, which is the
     * distinction [Reflect] exists to preserve.
     */
    fun subtitle(message: Map<String, String>): String? = klass.invokeOrNull(
        instance,
        "getSubtitle",
        arrayOf(Context::class.java, Map::class.java),
        arrayOf(appContext, message),
    ) as? String

    /**
     * Compose the payload for a brand-new game.
     *
     * Not a pure getter: their implementation calls `Cryption.getId()`, builds an avatar string,
     * and reads the sender identity out of prefs. Their interface default builds 14 keys; games
     * override it and add their own, which is why `pool` measured 19 on-device — `PoolGame` adds
     * `mode` and `v2`–`v5`, and rewrites `game` to `pool3` in 8 Ball+ mode. That rewrite is exactly
     * what makes [ForeignGameCatalog.byName]'s alias load-bearing.
     *
     * Null means their code declined or the method is gone — either way there is nothing to send,
     * and the caller must not fabricate a substitute.
     */
    fun newGameData(): Map<*, *>? = klass.invokeOrNull(
        instance,
        "getNewGameData",
        arrayOf(Context::class.java),
        arrayOf<Any?>(appContext),
    ) as? Map<*, *>

    /**
     * Build the balloon for [data], returning **our** `MadridMessage`.
     *
     * Their `buildGameMessage` returns a `MadridMessage` loaded by *their* ClassLoader, which is an
     * unrelated type to ours and cannot be cast — measured, `assignable theirs->ours = false`. So
     * the result is copied through [ParcelBridge], which is possible only because `Parcelable`
     * comes from the boot loader both apps share.
     *
     * [session] is their existing-session id, or `null` for a new game.
     *
     * The return is deliberately not sanity-checked here beyond the type: what a well-formed
     * message looks like (`url` carrying a `data:?ver=` payload, a non-null `session` and
     * `messageGuid`) is asserted in `SendGameProbe`, where a failure is a test result rather than a
     * silently blank balloon in a user's conversation.
     */
    fun buildMessage(data: Map<*, *>, session: String?): MadridMessage? {
        val theirs = klass.invokeOrNull(
            instance,
            "buildGameMessage",
            arrayOf(Context::class.java, Map::class.java, String::class.java),
            arrayOf(appContext, data, session),
        ) as? Parcelable ?: return null
        return ParcelBridge.toOurs(theirs)
    }

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
 * of OpenPigeon's current source. Measured against v1.1.0 (versionCode 26081901): 25 games, where
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

    /**
     * Find the game a wire payload's `game` key names.
     *
     * Not a plain lookup, because **the wire name is not always a game's name**. Their
     * `MadridExtension.findByName` carries an alias map, and it is reproduced here rather than
     * approximated:
     *
     * ```
     * "pool3" -> games.find { it.getName() == "pool" }
     * "pool2" -> games.find { it.getName() == "pool2" }
     * else    -> games.find { it.getName() == name }
     * ```
     *
     * `pool3` is the load-bearing one and it is not hypothetical — their own send path produces it.
     * `PoolGame.getNewGameData` writes `put("game", if (plusMode == "8 Ball+") "pool3" else "pool")`,
     * so every 8 Ball+ balloon carries `game=pool3`, while no game reports `getName() == "pool3"`.
     * A bare exact match returns null for those, and their `findByName(gname)!!` turns that null
     * into a NullPointerException inside their code.
     *
     * `pool2` looks redundant against the `else` branch and is kept anyway: it is theirs, it costs
     * nothing, and a future build that renames the underlying game would diverge from us silently
     * if we had "simplified" it away.
     */
    fun byName(name: String): ForeignGame? = when (name) {
        "pool3" -> games.firstOrNull { it.name == "pool" }
        "pool2" -> games.firstOrNull { it.name == "pool2" }
        else -> games.firstOrNull { it.name == name }
    }
}
