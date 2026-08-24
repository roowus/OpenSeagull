package com.roowus.openseagull

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.bluebubbles.messaging.MadridMessage
import com.roowus.openseagull.host.ForeignAppContext
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.ForeignPayload
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.ParcelBridge
import com.roowus.openseagull.host.SeagullIdentity
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
     * Carry a foreign [Parcelable] into one of our own classes, via the **shipped** bridge.
     *
     * This deliberately delegates rather than reimplementing. An earlier version of this file
     * carried its own copy of the parcel dance, which meant a green test proved only that *the
     * test's* copy worked — the shipped [ParcelBridge] could have drifted (a missing
     * `setDataPosition(0)`, say, whose failure mode is a blank balloon and no error) with nothing
     * here to catch it. Calling the real one is what makes these assertions a gate.
     *
     * The size log is kept because it is genuinely useful — 180152 bytes for pool, almost all of it
     * poster — and measuring it needs a parcel of our own, which costs one extra write.
     */
    private fun bridge(theirs: Parcelable): MadridMessage {
        val parcel = Parcel.obtain()
        try {
            theirs.writeToParcel(parcel, 0)
            Log.i(tag, "parcelled their message = ${parcel.dataSize()} bytes")
        } finally {
            parcel.recycle()
        }
        return ParcelBridge.toOurs(theirs)
    }

    /**
     * The accessors [MadridExtension.launchGame] actually calls, exercised as a unit.
     *
     * The tests above prove the *technique* works by driving reflection inline. This one proves
     * that [ForeignGame]'s wrappers around that technique work — that the method names, parameter
     * arrays and Context choice baked into production match the installed OpenPigeon. Those are
     * separate claims: every inline `getMethod` above could be right while a typo'd name in
     * `ForeignGame` returns null and the send path silently drops every tap.
     */
    @Test
    fun theShippedAccessorsComposeAMessage() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull { it.name == "pool" }
            ?: ForeignGameCatalog.of(p).games.first()

        Log.i(
            tag,
            "accessors for '${game.name}': minPlayers=${game.minPlayerRequirement()} " +
                "configurable=${game.isConfigurable()}",
        )

        val data = game.newGameData()
        assertNotNull(data, "ForeignGame.newGameData returned null — the send path is dead")

        val message = game.buildMessage(data, session = null)
        assertNotNull(message, "ForeignGame.buildMessage returned null — nothing would be sent")

        // The same three fields the raw-reflection test asserts. If these hold here too, the
        // wrappers are faithful; if only the raw test holds, the wrappers are the bug.
        assertNotNull(message.messageGuid, "shipped path lost the guid")
        assertNotNull(message.session, "shipped path lost the session")
        val url = message.url
        assertNotNull(url, "shipped path lost the url")
        assert(url.startsWith("data:?ver=")) {
            "shipped path produced a malformed url starting '${url.take(12)}'"
        }
        Log.i(tag, "shipped path OK: url=${url.length} chars, isLive=${message.isLive}")
    }

    /**
     * Read our own balloon back, closing the one shipped path that has never executed.
     *
     * [ForeignPayload.decode] is what turns a received balloon's `url` into a board — it is on the
     * inbound path for every game anyone sends us, and until now nothing had ever run it against
     * the installed dex. Its three failure modes are all silent (`Cryption` class absent, no
     * `INSTANCE` field, no `decrypt` method), each returning `null` and logging, so a broken
     * reflection path would present as "received games do not open" with no exception anywhere.
     *
     * The round trip is the test that needs no host: their `buildGameMessage` encrypts, ours
     * decrypts, and the board that comes back must be the board that went in. A decode built on
     * the wrong field or the wrong receiver cannot accidentally produce the right keys.
     *
     * It also checks the send-side identity stamp, which is only observable here — `sender` and
     * `player2` go in through [ForeignGame.newGameData]'s successor and come out the far side of
     * their `Cryption`, so this is the only place the two can be compared. Both must equal
     * [SeagullIdentity], not the per-process UUID their `getSenderUUID` mints.
     */
    @Test
    fun ourOwnBalloonDecodesBackToItsBoard() {
        val p = pigeonOrSkip() ?: return
        val game = ForeignGameCatalog.of(p).games.firstOrNull { it.name == "pool" }
            ?: ForeignGameCatalog.of(p).games.first()

        val sent = game.newGameData()
        assertNotNull(sent, "no new-game data — nothing to round-trip")

        val myId = SeagullIdentity.senderUuid()
        val stamped = LinkedHashMap<String, String>()
        for ((k, v) in sent) if (k is String && v is String) stamped[k] = v
        if (myId.isNotEmpty()) {
            stamped["sender"] = myId
            stamped["player2"] = myId
        }

        val message = game.buildMessage(stamped, session = null)
        assertNotNull(message, "buildMessage returned null — nothing to decode")

        val board = ForeignPayload.decode(p, message.url)
        assertNotNull(
            board,
            "ForeignPayload.decode returned null for a balloon their own code just built — " +
                "the inbound path is dead and every received game would open blank",
        )

        // Sizes and key names only: the values are game state, and `sender` is an identity.
        Log.i(
            tag,
            "round trip '${game.name}': sent ${stamped.size} keys, decoded ${board.size} " +
                "(missing=${(stamped.keys - board.keys).sorted()})",
        )

        for ((key, value) in stamped) {
            val got = board[key]
            assert(got == value) {
                "key '$key' did not survive the round trip: " +
                    "sent ${value.length} chars, decoded ${got?.length ?: -1}"
            }
        }

        if (myId.isNotEmpty()) {
            assert(board["sender"] == myId) {
                "decoded sender is not our identity — the stamp did not reach the wire"
            }
            assert(board["player2"] == myId) {
                "decoded player2 is not our identity — the stamp did not reach the wire"
            }
            Log.i(tag, "identity survived the wire as ${myId.take(8)}…")
        } else {
            Log.w(tag, "SeagullIdentity is empty — identity stamp not exercised")
        }
    }
}
