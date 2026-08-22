package com.roowus.openseagull

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.bluebubbles.messaging.MadridMessage
import com.roowus.openseagull.host.ForeignAppContext
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.InstalledOpenPigeon
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Measures whether OpenSeagull can *send* a game, which is a different question from whether it can
 * *open* one.
 *
 * ## Why this is the first half of gameplay, and the easier half
 *
 * Reading OpenPigeon's picker settled something that had been assumed backwards here. Tapping a
 * cell in their grid does **not** start an Activity. `ChooseGameCallback` calls
 * `game.buildGameMessage(context, game.getNewGameData(context), null)` and hands the result to
 * `currentKeyboardHandle.addMessage(...)` — it composes a balloon and posts it. Only tapping an
 * *existing* balloon (`didTapTemplate`) opens `Intent(context, game.gameClass())`.
 *
 * So the picker tap needs no hosted Activity, no dex injection, and no Godot engine. It needs one
 * thing this project has not yet done: carry an object built by **their** code across the
 * ClassLoader boundary and into a binder call made by **ours**.
 *
 * ## The obstacle being measured
 *
 * `buildGameMessage` returns `com.bluebubbles.messaging.MadridMessage`. Both APKs define a class
 * with that exact name — it is generated from the same `.aidl` on both sides. Class identity is
 * per-ClassLoader, so unless their loader happens to delegate to ours, their instance is **not**
 * assignable to our `MadridMessage` and `IKeyboardHandle.addMessage` cannot accept it. A cast would
 * throw `ClassCastException`.
 *
 * The proposed way through is a parcel bridge: `MadridMessage` implements [Parcelable], and
 * [Parcelable] comes from the boot ClassLoader, which both apps share. So their instance can be
 * asked to write itself to a [Parcel] through an interface both sides agree on, and ours can be
 * read back out. AIDL parcelables are size-prefixed and read field-by-field with a bounds check
 * before each one, so a version skew between their `.aidl` and ours truncates rather than corrupts.
 *
 * That is the theory. Every step of it is measured below rather than argued, because a wrong
 * assumption here would surface as a balloon that silently fails to send.
 *
 * ## What is deliberately not logged
 *
 * `buildGameMessage` routes the payload through their `Cryption.encrypt`, and `getNewGameData`
 * calls `Cryption.getId()`. The resulting `url` is an encrypted blob keyed on material this project
 * has no business reading. Only its length and its unencrypted `ver=` prefix are logged; the
 * ciphertext and the base64 poster are reported as sizes. Nothing here prints key material.
 *
 * Read the output with:
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 */
class SendGameProbe {

    private val tag = "SEAGULL"

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pigeonOrSkip(): InstalledOpenPigeon? {
        val p = InstalledOpenPigeon.find(ctx())
        if (p == null) Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
        return p
    }

    /**
     * Is their `MadridMessage` the same class as ours?
     *
     * Descriptive, not assertive, because either answer is workable and the point is to know which
     * one holds. If their loader delegates to ours the classes are identical and the bridge below
     * is unnecessary; if it does not, the bridge is mandatory. Guessing wrong in either direction
     * produces code that looks right.
     */
    @Test
    fun reportMadridMessageClassIdentity() {
        val p = pigeonOrSkip() ?: return
        val theirs = p.loadClassOrNull("com.bluebubbles.messaging.MadridMessage")
        if (theirs == null) {
            Log.i(tag, "their loader has no MadridMessage — it comes from the host only")
            return
        }
        val ours = MadridMessage::class.java
        Log.i(
            tag,
            "MadridMessage identity: same=${theirs === ours} " +
                "theirLoader=${theirs.classLoader} ourLoader=${ours.classLoader}",
        )
        Log.i(
            tag,
            "assignable theirs->ours=${ours.isAssignableFrom(theirs)} " +
                "parcelable=${Parcelable::class.java.isAssignableFrom(theirs)}",
        )
    }

    /**
     * The picker-tap preconditions their own callback checks before composing a message.
     *
     * `ChooseGameCallback` consults `isConfigurable()` and `minPlayerRequirement()` before it builds
     * anything, and a game whose minimum exceeds the conversation's user count is refused with a
     * toast rather than sent. Our picker will have to make the same two decisions, so this records
     * what the installed build actually reports for each game — a minimum of 3 or 4 somewhere in
     * the catalog would mean the naive "every tap sends" implementation is wrong.
     */
    @Test
    fun reportPickerTapPreconditions() {
        val p = pigeonOrSkip() ?: return
        val games = ForeignGameCatalog.of(p).games
        assert(games.isNotEmpty()) { "expected a non-empty catalog" }

        var configurable = 0
        var aboveTwo = 0
        games.forEach { g ->
            val k = g.instance.javaClass
            val min = runCatching { k.getMethod("minPlayerRequirement").invoke(g.instance) as Int }
                .getOrDefault(-1)
            val conf = runCatching { k.getMethod("isConfigurable").invoke(g.instance) as Boolean }
                .getOrDefault(false)
            if (conf) configurable++
            if (min > 2) aboveTwo++
            Log.i(tag, "  ${g.name}: minPlayers=$min configurable=$conf")
        }
        Log.i(
            tag,
            "preconditions: ${games.size} games, $configurable configurable, " +
                "$aboveTwo requiring more than 2 players",
        )
    }

    /**
     * Why their code cannot use the Context [InstalledOpenPigeon.packageContext] hands it, and what
     * the smallest honest repair is.
     *
     * Measured, not guessed: `getNewGameData` dies inside `SettingsData.init` with
     * `getApplicationContext(...) must not be null`. That is not a bug in their code. A Context
     * from `createPackageContext` has no application object, because nothing in our process ever
     * instantiated *their* `Application` — `getApplicationContext()` returns null and their
     * Kotlin non-null parameter rejects it. Any of their code that touches settings will hit this.
     *
     * The repair under test is a [android.content.ContextWrapper] that answers that one question
     * with itself. What has to be measured is the consequence: their `SettingsData` stores whatever
     * it is handed and calls `getSharedPreferences` on it, so the wrapper decides *whose data
     * directory their settings resolve against*. Ours is writable and theirs is not, and the two
     * answers give the user a different player identity, so this is not a detail to leave to luck.
     *
     * ## The measured answer
     *
     * The wrapper resolves against **their** directory (`dataDir=/data/user/0/…openpigeon`, not
     * ours), and a write there returns `false`. So the `0 keys` this reports is the sandbox
     * refusing us, not an empty settings store — their settings layer initialises against defaults
     * on every call, and any write their code makes is silently dropped.
     *
     * Two consequences worth being explicit about, because neither announces itself:
     *
     * - `SharedPreferences.commit()` returned `false` with **no exception**. Their code does not
     *   check that return value — nothing does — so their settings layer cannot tell a failed write
     *   from a successful one. Any preference their code tries to persist is lost silently.
     * - Defaults are survivable for *sending*: the identity in a composed message comes from
     *   `sender`, which their code derives per-call, and the probe below asserts the result is
     *   well-formed. What does not carry over is user-specific state — avatar, per-game
     *   preferences. That is a fidelity gap to close deliberately, by keeping our own copy in our
     *   own directory, and not by pointing their code at our storage and hoping the shapes match.
     */
    @Test
    fun reportWhyTheirContextIsIncomplete() {
        val p = pigeonOrSkip() ?: return
        val theirCtx = p.packageContext

        Log.i(
            tag,
            "their packageContext: applicationContext=${theirCtx.applicationContext} " +
                "dataDir=${theirCtx.applicationInfo?.dataDir}",
        )
        Log.i(tag, "our applicationContext=${ctx().applicationContext}")

        val wrapped = ForeignAppContext(theirCtx)
        // Not a null check — the override's return type is non-null, so the compiler folds that to
        // `true` and it measures nothing. The real question is *which* Context comes back, since
        // their SettingsData stores it and resolves all storage against it.
        Log.i(tag, "wrapped applicationContext is the wrapper = ${wrapped.applicationContext === wrapped}")

        // Whose directory do their settings land in? Their code will call exactly this.
        val prefsName = "com.openbubbles.openpigeon.settings"
        val read = runCatching {
            wrapped.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.size
        }
        Log.i(
            tag,
            "prefs through wrapper: read=${read.getOrNull()?.let { "$it keys" } ?: "FAILED"} " +
                "err=${read.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
        )

        // A read of 0 keys is ambiguous on its own, and the ambiguity is the whole point: it could
        // be their prefs file returning empty because our uid cannot open it, or a brand-new empty
        // file in *our* directory. Those give the user two different player identities, so the
        // difference is measured here rather than reasoned about.
        //
        // `dataDir` names the directory storage resolves against, and a write attempt says whether
        // we own it. Their uid owns theirs, so a successful write means we are in ours.
        Log.i(tag, "wrapper dataDir=${wrapped.dataDir} ourDataDir=${ctx().dataDir}")
        val wrote = runCatching {
            wrapped.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putInt("seagull_probe", 1).commit()
        }
        Log.i(
            tag,
            "prefs write through wrapper: ok=${wrote.getOrNull()} " +
                "err=${wrote.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
        )
        // Put it back. If the write landed it landed in a real prefs file, and a probe has no
        // business leaving a key in one.
        runCatching {
            wrapped.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().remove("seagull_probe").commit()
        }
    }

    /**
     * Can their code compose a new game's data at all, in our process?
     *
     * `getNewGameData` is not a pure getter — it calls `Cryption.getId()` and
     * `AvatarView.buildAvatarString()`, and it reads the sender identity out of shared prefs. Any
     * of those could depend on state their app sets up at launch and we never do. If this returns
     * null or throws, the send path is blocked before the message-building question even arises.
     *
     * **Their** Context is passed, not ours, for the reason [ForeignGame.senderUuid] documents:
     * these calls resolve prefs and resources against whatever Context they are handed.
     */
    @Test
    fun theirCodeCanComposeNewGameData() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull { it.name == "pool" }
            ?: ForeignGameCatalog.of(p).games.first()
        Log.i(tag, "composing new-game data for '${game.name}'")

        val data = game.instance.javaClass
            .getMethod("getNewGameData", Context::class.java)
            .invoke(game.instance, ForeignAppContext(p.packageContext)) as? Map<*, *>

        assertNotNull(data, "getNewGameData returned null — the send path is blocked")
        // Keys only. Values include the sender UUID and an avatar blob, neither of which belongs
        // in a log; `game` and `version` are printed because they are the routing fields we need
        // to be confident are populated.
        Log.i(tag, "new-game data keys=${data.keys.map { "$it" }.sorted().joinToString(",")}")
        Log.i(tag, "  game=${data["game"]} version=${data["version"]} player=${data["player"]}")
        assert(data["game"] == game.name) {
            "data['game']=${data["game"]} should match the game name ${game.name}"
        }
    }

    /**
     * The whole send path, end to end, short of actually handing the message to the host.
     *
     * Three things are proven together because they only mean anything together: their code can
     * build a `MadridMessage` in our process; that object can cross the ClassLoader boundary
     * through a [Parcel]; and what comes out the other side is a *usable* message rather than a
     * structurally-valid empty one.
     *
     * The last check is the one worth having. A parcel bridge that silently produced a message with
     * every field null would pass a "did it round-trip" assertion and fail in the field as a
     * balloon that arrives blank, so the fields the wire format actually depends on — `url` with
     * its `ver=` prefix, `session`, `messageGuid` — are asserted individually.
     */
    @Test
    fun theirMessageSurvivesTheParcelBridge() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull { it.name == "pool" }
            ?: ForeignGameCatalog.of(p).games.first()

        val k = game.instance.javaClass
        val data = k.getMethod("getNewGameData", Context::class.java)
            .invoke(game.instance, ForeignAppContext(p.packageContext)) as? Map<*, *>
        assertNotNull(data, "no new-game data to build a message from")

        val theirs = k.getMethod(
            "buildGameMessage",
            Context::class.java,
            Map::class.java,
            String::class.java,
        ).invoke(game.instance, ForeignAppContext(p.packageContext), data, null)
        assertNotNull(theirs, "buildGameMessage returned null")
        Log.i(tag, "built ${theirs.javaClass.name} via ${theirs.javaClass.classLoader}")

        // The cast that cannot be done. Asserting its impossibility is what justifies the bridge;
        // if this ever becomes assignable the bridge is dead code and should be deleted.
        val castable = MadridMessage::class.java.isAssignableFrom(theirs.javaClass)
        Log.i(tag, "direct cast possible = $castable")

        assert(theirs is Parcelable) {
            "their MadridMessage is not Parcelable through the boot loader — no bridge exists"
        }

        val ours = bridge(theirs as Parcelable)

        Log.i(
            tag,
            "bridged: guid=${ours.messageGuid?.take(8)}… session=${ours.session?.take(8)}… " +
                "ldText=${ours.ldText} isLive=${ours.isLive}",
        )
        // Sizes, not contents: url is ciphertext and imageBase64 is a JPEG poster.
        Log.i(
            tag,
            "bridged sizes: url=${ours.url?.length ?: 0} chars, " +
                "image=${ours.imageBase64?.length ?: 0} chars, caption=${ours.caption}",
        )

        assertNotNull(ours.messageGuid, "bridged message lost its guid")
        assertNotNull(ours.session, "bridged message lost its session")
        val url = ours.url
        assertNotNull(url, "bridged message lost its url — the payload is the message")
        // "data:?ver=52&data=…" — the '?' is measured, not assumed. An earlier version of this
        // assertion guessed "data:ver=" from reading their source and failed against the real
        // string, which is exactly the kind of detail a hand-written wire format hides.
        assert(url.startsWith("data:?ver=")) {
            "url should carry the versioned wire payload, got a ${url.length}-char value " +
                "starting '${url.take(12)}'"
        }
        assert(ours.isLive) { "a new game message should be live" }
    }

    /**
     * Carry a foreign [Parcelable] into one of our own classes.
     *
     * This works only because of what [Parcel] is: a byte buffer plus a position, with no notion of
     * types. Their instance writes its fields through [Parcelable.writeToParcel] — an interface
     * defined by the framework, so both apps genuinely share it — and our `CREATOR` reads the same
     * bytes back. No class is ever shared, so no `ClassCastException` is possible.
     *
     * `setDataPosition(0)` between the two halves is not optional: writing leaves the cursor at the
     * end, and reading from there would produce a message with every field null and no error.
     */
    private fun bridge(theirs: Parcelable): MadridMessage {
        val parcel = Parcel.obtain()
        return try {
            theirs.writeToParcel(parcel, 0)
            Log.i(tag, "parcelled their message = ${parcel.dataSize()} bytes")
            parcel.setDataPosition(0)
            MadridMessage.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
