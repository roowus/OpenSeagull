package com.roowus.openseagull

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import com.roowus.openseagull.host.ForeignCallException
import com.roowus.openseagull.host.ForeignGame
import com.roowus.openseagull.host.ForeignGameCatalog
import com.roowus.openseagull.host.ForeignPayload
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SeagullIdentity
import com.roowus.openseagull.host.SessionRegistry
import com.roowus.openseagull.ui.GamePicker
import com.roowus.openseagull.ui.Posters
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * OpenSeagull's implementation of the OpenBubbles extension contract.
 *
 * ## Scope of this version
 *
 * The keyboard renders a real, paginated grid of the games found in the user's installed
 * OpenPigeon, drawn with their own poster art, and tapping a game **sends** it — which is what a
 * picker tap does in OpenPigeon too, rather than opening a board (see [launchGame]).
 *
 * A received balloon is drawn as a board with the same geometry OpenPigeon uses ([getLiveView]), and
 * tapping it opens the game — [didTapTemplate] decodes the payload, registers the session so their
 * game can read it, and launches their Activity in *our* process. What is still missing is the way
 * back: a move made in a game is held in [SessionRegistry] and never written to the balloon, because
 * `IMessageViewHandle.updateMessage` is unwired.
 *
 * Methods that cannot yet do their job return a status view rather than a stub or an exception, so
 * a bind from OpenBubbles always produces something legible on screen. Where a method is
 * incomplete, the view says so; it does not pretend to have succeeded.
 *
 * ## Why the extension owns an [InstalledOpenPigeon]
 *
 * OpenSeagull ships no OpenPigeon content. Everything it displays or plays is read out of the
 * user's own installed copy at runtime. That handle is resolved once, lazily, and shared: it
 * caches the foreign Context, ClassLoader, and Resources, and it is the only place the two apps
 * meet. See [InstalledOpenPigeon] for the three cross-package hazards it exists to contain.
 *
 * Resolution is lazy rather than eager because OpenPigeon can be installed or uninstalled while
 * this process is alive; a missing OpenPigeon is a first-class state, not a construction failure.
 */
class MadridExtension(val context: Context) : IMadridExtension.Stub() {

    /** Re-resolved on each access so install/uninstall while we are running is picked up. */
    private val pigeon: InstalledOpenPigeon?
        get() = InstalledOpenPigeon.find(context)

    private var callback: IViewUpdateCallback? = null

    /**
     * Kept across the whole keyboard session so paging survives a redraw.
     *
     * Rebuilt on each [keyboardOpened] rather than held for the life of the process: the user may
     * install or update OpenPigeon between openings, and a stale catalog would show a game list
     * that no longer matches what is on the device.
     */
    private var picker: GamePicker? = null

    /**
     * The host's send channel, and the only way a composed game reaches the conversation.
     *
     * Handed to us once per keyboard session and valid only for that session, so it is cleared in
     * [keyboardClosed] alongside everything else. Holding a stale handle would mean posting a
     * balloon into a conversation the user has already left.
     */
    private var keyboardHandle: IKeyboardHandle? = null

    /**
     * How many people are in this conversation, as the host reports it.
     *
     * Recorded because [ForeignGame.minPlayerRequirement] is checked against it — their own
     * `ChooseGameCallback` refuses to compose a game whose minimum exceeds this, and a picker that
     * skipped the check would post balloons OpenPigeon itself would not have sent.
     */
    private var userCount: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Where game composition runs.
     *
     * [launchGame] is reached from a `BroadcastReceiver`, i.e. on our main thread under the
     * receiver time limit, and composing is not cheap: their `buildGameMessage` runs the payload
     * through `Cryption.encrypt` and renders a JPEG poster — measured at 89172 base64 chars for
     * pool. Single-threaded so that two fast taps compose in the order they were tapped rather
     * than racing.
     */
    private val composer: ExecutorService = Executors.newSingleThreadExecutor()

    override fun keyboardOpened(
        callback: IViewUpdateCallback?,
        handle: IKeyboardHandle?,
        userCount: Int,
    ): RemoteViews {
        this.callback = callback
        this.keyboardHandle = handle
        this.userCount = userCount

        val p = pigeon ?: return statusView()
        val catalog = ForeignGameCatalog.of(p)
        if (catalog.isEmpty) return statusView()

        val picker = GamePicker(context, catalog).also { this.picker = it }
        picker.keyboardHeightDp = keyboardHeightDpForHost()
        return picker.build()
    }

    override fun keyboardClosed() {
        callback = null
        picker = null
        keyboardHandle = null
        userCount = 0
    }

    /**
     * How tall the host will make the panel, which decides whether we get two rows or three.
     *
     * OpenPigeon resolves this from the calling uid's package name and version, expanding to 380 dp
     * on newer OpenBubbles builds. That detection is worth replicating, but not worth guessing at:
     * until the mapping from host version to panel height has been *measured* on-device, assuming
     * the expanded height would render a third row that the host then clips.
     *
     * So this returns the conservative default. A wrong guess here is invisible in logs and shows
     * up only as a row the user cannot reach, which is exactly the kind of bug this project has
     * been avoiding by measuring first.
     */
    private fun keyboardHeightDpForHost(): Int = GamePicker.Metrics.DefaultKeyboardHeightDp

    /**
     * Advance the picker and push the new page to the host.
     *
     * Called from [PickerActionReceiver], i.e. from a tap that happened in the host's process.
     */
    fun changePage(delta: Int) {
        val picker = this.picker ?: run {
            Log.w(TAG, "page tap with no open picker — dropping")
            return
        }
        if (!picker.movePage(delta)) return
        pushView(picker.build())
    }

    /**
     * Send a new game into the conversation, which is what tapping a picker cell actually does.
     *
     * Reading OpenPigeon's own `ChooseGameCallback` settled something this project had backwards:
     * a picker tap does **not** open a game. It calls
     * `game.buildGameMessage(context, game.getNewGameData(context), null)` and hands the result to
     * `addMessage` — it composes a balloon and posts it. Opening a board is what happens when you
     * tap an *existing* balloon, which is [didTapTemplate] and still unwired.
     *
     * So this path needs no hosted Activity, no dex injection and no Godot engine. It needs one
     * thing nothing else here has done: carry an object built by **their** code into a binder call
     * made by **ours**, which is what [ParcelBridge] exists for.
     *
     * Everything after the lookup runs on [composer] rather than the receiver's thread, because
     * `buildGameMessage` encrypts a payload and renders a poster.
     */
    fun launchGame(name: String) {
        val handle = keyboardHandle ?: run {
            Log.w(TAG, "launch tap with no keyboard handle — dropping '$name'")
            return
        }
        val p = pigeon ?: run {
            Log.w(TAG, "launch tap but OpenPigeon is gone — dropping '$name'")
            return
        }
        val game = ForeignGameCatalog.of(p).byName(name) ?: run {
            Log.w(TAG, "launch tap for unknown game '$name' — dropping")
            return
        }

        // Their own callback refuses here rather than composing, so we do too. Measured across the
        // installed catalog only `crazy` (Crazy 8s) sets a minimum, at 3.
        val min = game.minPlayerRequirement()
        if (min > userCount) {
            Log.i(TAG, "'$name' needs $min players, conversation has $userCount — refusing")
            return
        }
        // 17 of 26 games have a configuration step we have not built. Sending the default game is
        // the honest partial behaviour — it produces a real, playable balloon — but it is not what
        // their picker would have done, so it is recorded rather than passed over in silence.
        if (game.isConfigurable()) {
            Log.i(TAG, "'$name' is configurable; sending defaults (setup UI not built)")
        }

        composer.execute { composeAndSend(game, handle, name) }
    }

    /**
     * Compose [game] and post it, off the main thread.
     *
     * [ForeignCallException] is caught by name because it is precisely the signal that *their* code
     * threw rather than ours: a failure inside `buildGameMessage` says the installed OpenPigeon
     * disagrees with what we expected of it, and that is a log line and a dropped tap, not a crash
     * in the host's keyboard.
     */
    private fun composeAndSend(game: ForeignGame, handle: IKeyboardHandle, name: String) {
        val message = try {
            val data = game.newGameData() ?: run {
                Log.w(TAG, "'$name' produced no new-game data — nothing to send")
                return
            }
            game.buildMessage(stampIdentity(data, name), session = null) ?: run {
                Log.w(TAG, "'$name' built no message — nothing to send")
                return
            }
        } catch (e: ForeignCallException) {
            Log.w(TAG, "OpenPigeon threw while composing '$name'", e)
            return
        }

        // Sizes only: `url` is ciphertext from their Cryption and the poster is a base64 JPEG.
        Log.i(
            TAG,
            "sending '$name': url=${message.url?.length ?: 0} chars, " +
                "image=${message.imageBase64?.length ?: 0} chars",
        )
        try {
            handle.addMessage(message)
        } catch (e: RemoteException) {
            // `addMessage` is oneway, so this means the host process died rather than that it
            // rejected the message — there is no error channel that could tell us the latter.
            Log.w(TAG, "host went away during addMessage for '$name'", e)
        }
    }

    /**
     * Replace the two identity keys their `getNewGameData` stamped with one that survives.
     *
     * Their default body reads `val sender = getSenderUUID(context)` once and writes it to both
     * `sender` and `player2`. `getSenderUUID` reads their `openpigeon` prefs, which from our uid
     * report `exists=false readable=false`, so its `?: UUID.randomUUID().toString()` fallback runs
     * — and it runs *again* in the next process. Measured: four runs, four ids. A game sent with
     * those keys is stamped with an identity that will not exist by the time the reply arrives, so
     * their `isYourTurn = message["sender"] != myId` compares a live id against a dead one and the
     * game we started would read as never being our turn.
     *
     * [SeagullIdentity] is the fix on the read side already
     * ([com.roowus.openseagull.host.BoardVerdict]) and on the write-back side
     * ([com.roowus.openseagull.host.SessionWriter]'s `player1` claim). This is the send-side half
     * of the same rule: the id we put on the wire when a game is created must be the id we will
     * still answer to.
     *
     * Both keys, not just `sender`: theirs sets them from the same variable, and `player2` is what
     * their `updateSession` compares against to decide whether the *other* player may claim
     * `player1`. Leaving `player2` as a throwaway would let both sides claim the same slot.
     *
     * A copy rather than a mutation: [ForeignGame.newGameData] returns their map as `Map<*, *>`,
     * whose runtime type is theirs to choose — their `getNewGameData` happens to return a
     * `mutableMapOf`, but games override it and nothing in the contract promises mutability. The
     * keys are filtered to `String → String` on the way through because that is all their
     * `encodeQuery` can carry anyway; a non-String value would be dropped at the boundary with no
     * comment, exactly as [com.roowus.openseagull.host.SessionRegistry] documents.
     *
     * Returns [data] untouched when [SeagullIdentity] has no id, which on Android does not happen
     * — `attach` runs from [SeagullApplication]. Substituting an empty string would be worse than
     * their throwaway: at least a random UUID is unique to one game.
     */
    private fun stampIdentity(data: Map<*, *>, name: String): Map<*, *> {
        val myId = SeagullIdentity.senderUuid()
        if (myId.isEmpty()) {
            Log.w(TAG, "no identity to stamp on '$name' — sending their per-process UUID")
            return data
        }
        val stamped = LinkedHashMap<String, String>(data.size + 2)
        for ((key, value) in data) {
            if (key is String && value is String) stamped[key] = value
        }
        val theirs = stamped["sender"]
        stamped["sender"] = myId
        stamped["player2"] = myId
        if (theirs != null && theirs != myId) {
            Log.i(TAG, "stamped '$name' as ${myId.take(8)}… (theirs was ${theirs.take(8)}…)")
        }
        return stamped
    }

    /**
     * Send a new view to the host.
     *
     * Posted twice: once now, once after a short delay on the main thread. This mirrors what
     * OpenPigeon does, and the reason is that [IViewUpdateCallback.updateView] is `oneway` — it
     * returns immediately with no indication of whether the host was in a state to apply the
     * update. An update that arrives while the host is still laying the panel out can be dropped
     * silently. The second post costs one extra binder call and removes a class of "the tap did
     * nothing" reports that would otherwise be unreproducible.
     *
     * A [RemoteException] here means the host went away between the tap and the push, which is
     * ordinary — the callback is cleared and the next [keyboardOpened] will supply a fresh one.
     */
    private fun pushView(views: RemoteViews) {
        val target = callback ?: run {
            Log.w(TAG, "no host callback — cannot push view")
            return
        }
        try {
            target.updateView(views)
        } catch (e: RemoteException) {
            Log.w(TAG, "host went away during updateView", e)
            callback = null
            return
        }
        mainHandler.postDelayed({
            try {
                callback?.updateView(views)
            } catch (e: RemoteException) {
                Log.w(TAG, "host went away during delayed updateView", e)
                callback = null
            }
        }, RedrawSettleMs)
    }

    /**
     * Open a received balloon: decode it, register the session, launch their Activity.
     *
     * ## Where the game runs, and why there was never a choice
     *
     * OpenPigeon opens a game with `Intent(context, game.gameClass())`, so the obvious shortcut is
     * to aim that same Intent at *their* package and let their process do the work. It cannot be
     * done: every game activity in their manifest is `android:exported="false"`, which the
     * framework enforces across packages regardless of signature. `GameSessionService` is exported,
     * but it is the session channel, not the UI.
     *
     * So the Activity runs here. `Intent(context, target)` builds a `ComponentName` from
     * `target.getName()` — a *string* — so the component resolved is `(our package, their class
     * name)`, which our manifest declares and [com.roowus.openseagull.host.ForeignCode] makes
     * loadable. Their class object comes from their ClassLoader and is never cast to anything.
     *
     * ## Ordering that is load-bearing
     *
     * [SessionRegistry.open] happens **before** `startActivity`, not after. Their game binds
     * `GameSessionService` from its own `onCreate` and has been measured reading the session ~1.2 s
     * in; registering afterwards would race that read. Losing the race is not a crash — their
     * service answers an unknown id with an empty Bundle and throws nothing — so it would surface
     * as a game that intermittently opens blank.
     *
     * Equally, `board["game_name"]` is only available *after* the decode, which is why the payload
     * is decoded before the Intent is built rather than alongside it.
     *
     * ## What is refused, and why refusing is the right answer
     *
     * A missing session id, an undecodable payload, a `game` key naming something this build does
     * not have, a game that says it cannot play this version of the payload — each returns without
     * launching. The tempting alternative is to open the game anyway on an empty board, and that is
     * precisely the failure this project keeps finding: their `GameSessionService` answers an
     * unknown session with an empty Bundle, and their games respond to an empty board by starting a
     * **new** game over the top of the one the balloon was carrying.
     *
     * Their own version guards none of this — `getSessionFor(id: String, …)` is declared non-null
     * and fed `message.session`, which the AIDL marks `@nullable`, so a Kotlin platform type walks
     * a `null` key straight into their map.
     *
     * Run inline rather than on [composer]: this arrives on a binder thread, not the main thread,
     * the decode is an LCG pass over a few hundred characters, and queueing it behind a compose in
     * flight would delay a tap for no gain.
     */
    override fun didTapTemplate(
        message: MadridMessage?,
        handle: IMessageViewHandle?,
        userCount: Int,
    ) {
        this.userCount = userCount
        if (message == null || handle == null) {
            Log.w(TAG, "balloon tap with no message or handle — nothing to open")
            return
        }
        val sessionId = message.session
        if (sessionId.isNullOrEmpty()) {
            Log.w(TAG, "balloon tap carries no session id — refusing to open")
            return
        }
        try {
            openBalloon(sessionId, message, handle)
        } catch (e: ForeignCallException) {
            // Their decrypt or their game object threw on a payload we handed it. Distinct from
            // "we could not read it", which decode() reports as null and logs on its own terms.
            Log.w(TAG, "OpenPigeon threw while opening ${sessionId.take(8)}…", e)
        }
    }

    /**
     * What a balloon turns out to be: the installed game, its decoded board, and the name to call
     * it by.
     *
     * [name] is not `board["game"]`. The wire name and the game's own name are not always equal —
     * an 8 Ball+ balloon says `pool3` while the game answering to it reports `pool` — and their
     * code passes the *game's* name as the `GAME` extra. Resolving it once here means the extra and
     * the session id can never disagree, and [getLiveView] and [openBalloon] cannot drift apart on
     * which one they used.
     */
    private data class Balloon(
        val game: ForeignGame,
        val board: Map<String, String>,
        val name: String,
    )

    /**
     * Decode [message] and find the installed game that owns it, or `null` with a reason logged.
     *
     * Shared by [openBalloon] and [getLiveView] because a tap and a render must agree about what a
     * balloon *is*. Two copies of this prefix would be two chances to resolve the same payload to
     * different games — a board drawn from one game's art and opened into another's activity.
     *
     * Raises [ForeignCallException] rather than swallowing it: `decode`, the catalog initialiser
     * and `byName` all run their code, and both callers already have a handler that says which
     * caller was in flight. Only the "we could not read it" cases are `null` here, and each logs
     * what it could not read.
     */
    private fun readBalloon(
        p: InstalledOpenPigeon,
        message: MadridMessage,
        sessionId: String,
    ): Balloon? {
        // decode() logs which of its four ways it failed, so there is nothing to add here.
        val board = ForeignPayload.decode(p, message.url) ?: return null

        val wanted = board["game"] ?: run {
            Log.w(TAG, "decoded board names no game — ignoring ${sessionId.take(8)}…")
            return null
        }
        val game = ForeignGameCatalog.of(p).byName(wanted) ?: run {
            Log.w(TAG, "no installed game answers to '$wanted'")
            return null
        }
        return Balloon(game, board, game.name ?: wanted)
    }

    /**
     * The body of [didTapTemplate], minus the argument guards.
     *
     * Split out so the single `catch (ForeignCallException)` above covers every foreign call in one
     * place — decode, `isSupported`, `gameClass` and the catalog's own initialiser can all raise it,
     * and a `try` per call site would say the same thing five times.
     *
     * [handle] is stored, not used. It is the host's address for this balloon, and it is what
     * write-back will need; taking it here is why a tap is the moment a session becomes writable.
     * See [SessionRegistry.Session.handle].
     */
    private fun openBalloon(
        sessionId: String,
        message: MadridMessage,
        handle: IMessageViewHandle,
    ) {
        val p = pigeon ?: run {
            Log.w(TAG, "balloon tap but OpenPigeon is gone — cannot open ${sessionId.take(8)}…")
            return
        }
        val (game, board, name) = readBalloon(p, message, sessionId) ?: return
        if (!game.isSupported(board)) {
            // Their own default is `true`; a game that overrides it to refuse is telling us the
            // payload was written by a protocol version the installed code does not understand.
            Log.i(TAG, "'$name' refuses this payload as unsupported — not opening")
            return
        }
        val target = game.gameClass() ?: run {
            Log.w(TAG, "'$name' reports no gameClass — nothing to launch")
            return
        }

        SessionRegistry.open(sessionId, name, board)
        // After open, not before: open replaces the entry, and this handle is the newest one the
        // host has given us for this balloon — newer than whatever a previous render left behind.
        SessionRegistry.attachHandle(sessionId, handle)

        val intent = Intent(context, target).apply {
            putExtra("SESSION", sessionId)
            putExtra("GAME", name)
            putExtra("DISPLAY_GAME", board["game_name"])
            // A distinct `data` per tap, as theirs does: without it a second tap on the same
            // balloon is an Intent equal to the first, which the framework delivers to the
            // existing task rather than re-running the open path.
            data = "data://${System.currentTimeMillis()}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        Log.i(
            TAG,
            "opening '$name' session=${sessionId.take(8)}… keys=${board.size} " +
                "activity=${target.name}",
        )
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Their class loaded but our manifest does not declare it — a game family we have not
            // added an <activity> for. The session is dropped again so a later, working tap starts
            // from the balloon rather than from a stale board.
            Log.w(TAG, "no <activity> declared for ${target.name} — dropping the session", e)
            SessionRegistry.close(sessionId)
        }
    }

    /**
     * Draw a received game as a balloon: the board, the win glyph over it, and the two grey lines.
     *
     * ## Why the geometry is computed here rather than left to the layout
     *
     * The host gives a live view a slot roughly 60% of the screen wide and 250 dp tall, and their
     * renderer divides that itself: 58 dp of caption area when the payload carries a subcaption and
     * 46 dp when it does not, with the board taking whatever is left. Those numbers are theirs, and
     * a balloon that used different ones would sit visibly wrong next to a real GamePigeon balloon
     * in the same conversation.
     *
     * The board's height reaches the layout through the *bitmap*, not through a setter:
     * `RemoteViews.setViewLayoutHeight` is API 31 and this app runs from 26. So `balloon_board` is
     * `wrap_content` with `adjustViewBounds`, and it is handed a bitmap already at the computed
     * aspect — see the comment at the top of `balloon.xml`.
     *
     * ## Why nothing here is clickable
     *
     * Their `RenderLiveExtension` wraps the whole column in `actionStartActivity`, because Glance
     * composes the balloon and a tap on a composed tree has to carry its own `PendingIntent`. We do
     * not need one: the host calls [didTapTemplate] when a balloon is tapped, which is already
     * wired and already opens the game. Attaching a second path would risk opening it twice.
     *
     * ## Failure is a status view, not an exception
     *
     * This runs on a binder thread inside OpenBubbles' call. Throwing would surface in *their*
     * process as a dead balloon with our name on it, so every foreign call is guarded and any miss
     * falls back to [statusView] — legible, and it says what went wrong.
     */
    override fun getLiveView(
        callback: IViewUpdateCallback?,
        message: MadridMessage?,
        handle: IMessageViewHandle?,
        userCount: Int,
    ): RemoteViews {
        this.userCount = userCount
        if (message == null) return statusView()
        val p = pigeon ?: return statusView()

        val sessionId = message.session.orEmpty()
        // Before the render, and unconditionally: a render is the other occasion the host hands out
        // a handle, and OpenBubbles issues a *fresh* one on rebind. Theirs takes it in the same
        // place — `getSessionFor` calls `updateHandle` on every render of a session it knows. This
        // is a no-op until a tap has opened the session, which is the ordinary case: the balloon is
        // drawn long before anyone touches it.
        SessionRegistry.attachHandle(sessionId, handle)

        val balloon = try {
            readBalloon(p, message, sessionId)
        } catch (e: ForeignCallException) {
            Log.w(TAG, "OpenPigeon threw rendering ${sessionId.take(8)}…", e)
            null
        } ?: return statusView()

        return try {
            balloonView(balloon, message)
        } catch (e: ForeignCallException) {
            Log.w(TAG, "OpenPigeon threw drawing '${balloon.name}'", e)
            statusView()
        } catch (e: RuntimeException) {
            // One bad drawable should cost one balloon its art, not throw inside their process.
            // Same reasoning as GamePicker.addCell's guard around a foreign poster.
            Log.w(TAG, "failed to draw '${balloon.name}'", e)
            statusView()
        }
    }

    /** [getLiveView]'s body once the payload has been read; separated so its guard stays one block. */
    private fun balloonView(balloon: Balloon, message: MadridMessage): RemoteViews {
        val (game, board, name) = balloon
        val views = RemoteViews(context.packageName, R.layout.balloon)

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val dpWidth = metrics.widthPixels / density
        // Their arithmetic, kept verbatim: 60% of the screen, less 10 dp of balloon chrome.
        val widthDp = ((dpWidth * 0.60f).roundToInt() - 10).coerceAtLeast(1)
        val heightDp = BalloonHeightDp

        val subcaption = game.displaySubcaption(board)
        val captionAreaDp = if (subcaption != null) CaptionAreaWithSubcaptionDp else CaptionAreaDp
        val boardHeightDp = (heightDp - captionAreaDp).coerceAtLeast(1)

        val boardWidthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
        val boardHeightPx = (boardHeightDp * density).roundToInt().coerceAtLeast(1)

        // A game that can draw its own board draws it; the rest show their poster, cropped to the
        // same box. Their renderer makes exactly this choice, and for the same reason: a poster is
        // what a game looks like, a preview is what *this* game looks like right now.
        val boardBitmap = game.previewBitmap(board, boardWidthPx, boardHeightPx)
            ?: game.posterFor(board)?.let { Posters.fill(it, boardWidthPx, boardHeightPx) }
        if (boardBitmap != null) {
            views.setImageViewBitmap(R.id.balloon_board, boardBitmap)
        }

        // Their glyph is drawn over the board at the same size with a 32dp inset, which the layout
        // already carries; here it only needs to be the same shape as the board it covers.
        game.winStateImage(board)
            ?.let { Posters.fill(it, boardWidthPx, boardHeightPx) }
            ?.let {
                views.setImageViewBitmap(R.id.balloon_win, it)
                views.setViewVisibility(R.id.balloon_win, View.VISIBLE)
            }

        // `caption` last, as theirs does: the turn line is the better answer when there is one, and
        // "Game Name" is the placeholder their own renderer falls back to.
        val subtitle = game.displaySubtitle(board) ?: message.caption ?: "Game Name"
        views.setTextViewText(R.id.balloon_subtitle, subtitle.uppercase())

        if (subcaption != null) {
            views.setTextViewText(R.id.balloon_subcaption, subcaption.uppercase())
            views.setViewVisibility(R.id.balloon_subcaption, View.VISIBLE)
        }

        Log.i(
            TAG,
            "balloon '$name' session=${message.session.orEmpty().take(8)}… " +
                "board=${boardWidthPx}×$boardHeightPx " +
                "art=${if (boardBitmap != null) "yes" else "none"} " +
                "sub=${if (subcaption != null) "yes" else "no"}",
        )
        return views
    }

    /**
     * The other side sent a new turn on a conversation we already have open.
     *
     * ## Why an unknown session is ignored rather than opened
     *
     * [SessionRegistry.find] before [SessionRegistry.update], and **never** `open`. Theirs makes the
     * same choice with a safe call — `activeSessions[message.session]?.handleNewMessage(message)` —
     * and it is the right one for a reason worth stating: this is called for every balloon the host
     * decides to refresh, including ones the user has never tapped. Creating a session here would
     * fill the registry with boards nobody asked for, and — worse — the *next* tap on such a balloon
     * would find a session already present and reuse a board decoded at refresh time instead of the
     * one the tap carried.
     *
     * The update is a **replace**, not a merge: this payload is the whole new board, not a delta,
     * which is what distinguishes it from the `updateSession` path where a game sends only the keys
     * it changed. [SessionRegistry.update] merges, so it would leave keys the other player removed,
     * and so the board is written through [SessionRegistry.open] on a session that already exists —
     * the one case where re-opening is correct rather than a leak.
     */
    override fun messageUpdated(message: MadridMessage?) {
        if (message == null) return
        val sessionId = message.session
        if (sessionId.isNullOrEmpty()) return
        val existing = SessionRegistry.find(sessionId) ?: return
        val p = pigeon ?: return
        val board = try {
            ForeignPayload.decode(p, message.url)
        } catch (e: ForeignCallException) {
            Log.w(TAG, "OpenPigeon threw decoding an update for ${sessionId.take(8)}…", e)
            null
        } ?: return
        Log.i(TAG, "update on '${existing.game}' session=${sessionId.take(8)}… keys=${board.size}")
        SessionRegistry.open(sessionId, existing.game, board)
    }

    /**
     * Release the compose thread. Called when the service holding this extension goes away.
     *
     * `shutdown()` rather than `shutdownNow()`: a compose already in flight has either sent its
     * balloon or is about to, and interrupting it mid-`buildGameMessage` would abandon a tap the
     * user made. There is at most one, and it finishes in well under a second.
     */
    fun release() {
        composer.shutdown()
    }

    /**
     * A [RemoteViews] reporting what OpenSeagull can actually see of the installed OpenPigeon.
     *
     * [RemoteViews] rather than Glance/Compose because this must inflate in **OpenBubbles'**
     * process, not ours — that is the whole point of the contract. Keeping it to a plain layout
     * keeps the dependency surface at zero for now.
     */
    private fun statusView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.extension_status)
        val p = pigeon

        if (p == null) {
            views.setTextViewText(R.id.status_title, "OpenPigeon not found")
            views.setTextViewText(R.id.status_detail, InstalledOpenPigeon.NotInstalledMessage)
            return views
        }

        val catalog = ForeignGameCatalog.of(p)
        views.setTextViewText(
            R.id.status_title,
            "OpenSeagull — ${catalog.games.size} games loaded",
        )
        views.setTextViewText(
            R.id.status_detail,
            buildString {
                append("from ${p.packageName} ${p.versionName ?: "?"}")
                append(" via ${catalog.strategy}")
                val sample = catalog.games.mapNotNull { it.displayName }.take(4)
                if (sample.isNotEmpty()) append("\n${sample.joinToString(", ")}…")
            },
        )
        return views
    }

    private companion object {
        const val TAG = "SEAGULL"

        /** Delay before the second `updateView`, matching OpenPigeon's workaround. */
        const val RedrawSettleMs = 50L

        /**
         * Height of a balloon, in dp, and how much of it the two caption lines take.
         *
         * All three are OpenPigeon's, read from their `getLiveView` and `RenderLiveExtension`. They
         * are not free parameters: our balloons sit in the same conversation as balloons drawn by
         * real GamePigeon on iOS, and a board a few dp taller than theirs reads as a bug rather
         * than as a choice.
         */
        const val BalloonHeightDp = 250
        const val CaptionAreaDp = 46
        const val CaptionAreaWithSubcaptionDp = 58
    }
}
