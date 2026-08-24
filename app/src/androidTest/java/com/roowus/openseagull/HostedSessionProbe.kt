package com.roowus.openseagull

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.ForeignCode
import com.roowus.openseagull.host.InstalledOpenPigeon
import com.roowus.openseagull.host.SessionRegistry
import org.junit.Test

/**
 * Asks the question `HostedActivityProbe` deliberately left open: **does their game read a board
 * that came from us?**
 *
 * That probe got their `KnockoutActivity` to `RESUMED` and a first frame drawn, out of our process,
 * off their dex and their resource table. What it could not show is that any of *our* data reached
 * it — because it launched a bare `Intent` with no extras, and `KnockoutActivity.onCreate` reads
 * `intent.getStringExtra("SESSION") ?: ""` and then guards the whole read behind
 * `if (sessionId.isNotEmpty())`. With no extra, their code never calls `getCurrentMessage` at all
 * and takes its `else` branch, which synthesises a blank new game. It renders, it logs nothing
 * unusual, and it looks exactly like success.
 *
 * So a rendered board is not evidence. This probe supplies the missing half: register a session
 * *here*, name it in the extra, and let their code come back through [SessionChannel] to fetch it.
 *
 * ## The oracle is theirs, not ours
 *
 * Asserting that our own [SessionRegistry] returned what we put in it would prove nothing — that is
 * one map read. The claim worth testing is that the value crossed the ClassLoader boundary, went
 * through their `GameSessionIPC`, and was parsed by their replay code. Only their side can witness
 * that, and it already does:
 *
 * ```
 * OpenPigeonLog.i("KnockoutNative", "handleMessage replayLen=${msg["replay"]?.length ?: 0} turn=${msg["isYourTurn"]}")
 * ```
 *
 * Both fields of that line are chosen here to be **impossible** to confuse with the blank-game
 * fallback, which is why [SeededReplay] is a four-piece board rather than a copy of their eight-piece
 * `emptyDefault()`:
 *
 * | | `replayLen` | `turn` |
 * |---|---|---|
 * | their `else` branch (no session) | 419 | `true` |
 * | this probe's seed | 207 | `false` |
 *
 * A run that prints `replayLen=207 turn=false` cannot have come from anywhere but here. A run that
 * prints `replayLen=419 turn=true` means the session did not reach them and the fallback fired
 * again — the *same* pixels, the opposite result. Reading the screen could not tell those apart.
 *
 * ## What a pass does not prove
 *
 * That their code received and parsed our board. Not that the board is correct, not that a move
 * writes back to the conversation ([SessionRegistry.update] merges in memory only and says so), and
 * not that turn detection works — `senderUuid` is still empty, so their
 * `isYourTurn`/`sender` comparison is unreliable regardless of what we pass here.
 *
 * Same convention as its siblings: **nothing asserts**. The outcome is a printed verdict plus a
 * line of theirs to go and read.
 *
 * ```
 * adb logcat -d -s SEAGULL:I KnockoutNative:I
 * ```
 */
class HostedSessionProbe {

    private val tag = "SEAGULL"

    private val theirKnockout = "com.openbubbles.openpigeon.knockout.KnockoutActivity"

    /** Their extra key, read at `KnockoutActivity.onCreate`. Knockout reads only this one. */
    private val SessionExtra = "SESSION"

    /**
     * A board in their replay grammar: `board:<index>#x,y,player,rot,shootDir,power#…`.
     *
     * Four pieces, not their eight, purely so `replayLen` is 207 and cannot be mistaken for the
     * 419-character `emptyDefault()` their no-session branch synthesises. The coordinates are
     * inside the same ±100 range their own default uses, and player 2's pieces carry the same
     * `3.141593` rotation, so this is a board their parser has no reason to reject.
     */
    private val SeededReplay =
        "board:0" +
            "#-60.000000,80.000000,1,0.000000,0.000000,0.000000" +
            "#60.000000,80.000000,1,0.000000,0.000000,0.000000" +
            "#-60.000000,-80.000000,2,3.141593,0.000000,0.000000" +
            "#60.000000,-80.000000,2,3.141593,0.000000,0.000000"

    /**
     * Long enough for their `onCreate`, their bind, and their parse.
     *
     * The previous probe measured 1.2 s inside `onCreate` and a first frame at +4.4 s, and their
     * `GameSessionIPC` binds asynchronously *after* that. Their own fallback-reveal timer fires at
     * 1200 ms. Eight seconds clears all of it; too short and a slow read reads as no read, which is
     * a failure mode this probe family has produced before.
     */
    private val ObservationWindowMs = 8_000L

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theirGameReadsASessionWeRegistered() {
        val pigeon = InstalledOpenPigeon.find(ctx()) ?: run {
            Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
            return
        }

        val injected = ForeignCode.installDex(pigeon)
        Log.i(tag, "installDex -> $injected")

        val ours = HostedSessionProbe::class.java.classLoader!!
        val klass = runCatching { Class.forName(theirKnockout, false, ours) }
        if (klass.isFailure) {
            Log.i(tag, "VERDICT: dex injection did not take — nothing past this is measurable")
            return
        }

        // Registered before the launch, not after: their bind completes on their own schedule and
        // has been seen landing ~1.2s in. A session registered late would race their read and turn
        // a pass into an intermittent one.
        val id = "probe-session-0001"
        SessionRegistry.open(
            id,
            game = "knockout",
            message = mapOf(
                "replay" to SeededReplay,
                // The opposite of their fallback's "true", so the log line is unambiguous about
                // which branch produced it.
                "isYourTurn" to "false",
                "player" to "1",
            ),
        )
        Log.i(tag, "seeded session $id — replayLen=${SeededReplay.length} turn=false")

        val intent = Intent(ctx(), klass.getOrThrow())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SessionExtra, id)

        val launch = runCatching { ctx().startActivity(intent) }
        Log.i(
            tag,
            "startActivity -> " + (
                launch.exceptionOrNull()
                    ?.let { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
                    ?: "no exception"
                ),
        )
        if (launch.isFailure) {
            Log.i(tag, "VERDICT: the launch itself failed — this is a regression in HostedActivityProbe's gate")
            return
        }

        Thread.sleep(ObservationWindowMs)

        // Whether their read happened is visible from here, because a read goes through our own
        // registry. This is a necessary condition, not the proof — it says their code asked, not
        // that their code understood the answer.
        val session = SessionRegistry.find(id)
        Log.i(
            tag,
            "after ${ObservationWindowMs}ms: session present=${session != null} " +
                "locked=${session != null && SessionRegistry.ids().contains(id)}",
        )

        Log.i(
            tag,
            "VERDICT: launched with $SessionExtra=$id and a ${SeededReplay.length}-char board. " +
                "The proof is THEIR log line, not this one — look for " +
                "'KnockoutNative: handleMessage replayLen=207 turn=false'. " +
                "replayLen=419 turn=true means the session never reached them and their " +
                "no-session fallback synthesised a blank game, which renders identically. " +
                "Absent entirely means their GameSessionIPC never bound",
        )
    }
}
