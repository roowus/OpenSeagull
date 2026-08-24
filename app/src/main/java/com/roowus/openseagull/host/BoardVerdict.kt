package com.roowus.openseagull.host

/**
 * What a balloon should *say* about a board: the grey line under the picture, and the glyph over
 * it.
 *
 * ## Why this is reimplemented rather than delegated
 *
 * OpenPigeon already computes both, in `Game.getDisplaySubtitle` and `Game.getWinStateImage`, and
 * everything else on the balloon path calls straight through to them. These two do not, and the
 * reason is a fault this project already measured once.
 *
 * Both of their methods begin by calling their own `getSenderUUID(context)`, which reads
 * `getSharedPreferences("openpigeon")`. From our uid that file reports `exists=false
 * readable=false`, so their lookup misses and their `?: UUID.randomUUID().toString()` fallback
 * fires — a **different player in every process**. [SeagullIdentity] carries the measurement: four
 * runs, four ids.
 *
 * Every branch below turns on `myId`. Delegating would therefore not produce a slightly-wrong
 * answer, it would produce an arbitrary one: "Your Move." and "Opponent's Move." would alternate at
 * random between renders of the same unchanged balloon, and a game you won would show a crown or a
 * cross depending on which UUID their code happened to mint that second. Worse, it would do it
 * silently — there is no failure to log, only a wrong word.
 *
 * So the *rules* are reproduced here, from their source, and evaluated against
 * [SeagullIdentity.senderUuid] — an id that outlives the process. This is the same thing
 * [SessionRegistry] and [SessionChannel] already do: behaviour derived from reading their code, not
 * their code copied. Nothing here is their implementation; it is a four-way branch on two string
 * fields.
 *
 * ## One deliberate divergence
 *
 * Their three `winner`-reading methods do `it.split("|")` and then index `parts[1]` with no size
 * check, so a `winner` value carrying no `|` throws `ArrayIndexOutOfBoundsException` inside their
 * code. (Their own `recordWinIfApplicable` guards it with `parts.size < 2`; these do not.) The
 * guard is present here. A malformed `winner` yields "no verdict yet" rather than a crash on a
 * balloon we were only asked to draw.
 */
internal object BoardVerdict {

    /**
     * The four glyphs their `getWinStateImage` can return, by **name**.
     *
     * Names rather than ids because their `R.drawable.crown_24px` is a constant in *their* R class,
     * which is not on our compile classpath and whose value is meaningless to our resource table
     * anyway. [InstalledOpenPigeon.drawableByName] resolves against their table, which is where
     * these live. Held as strings for the same reason the class names elsewhere are strings.
     */
    const val GlyphSpectator = "game_end"
    const val GlyphDraw = "sync_alt_24px"
    const val GlyphWon = "crown_24px"
    const val GlyphLost = "close_24px"

    /**
     * The subtitle, or `null` if the board carries nothing to say.
     *
     * `null` rather than a placeholder: the caller falls back to the balloon's own `caption`, which
     * is what their `RenderLiveExtension` does, and inventing a string here would shadow it.
     */
    fun subtitle(board: Map<String, String>, myId: String): String? {
        outcome(board, myId)?.let {
            return when (it) {
                Outcome.Spectating -> "Game Over"
                Outcome.Draw -> "Draw!"
                Outcome.Won -> "You Won!"
                Outcome.Lost -> "You Lost!"
            }
        }

        // 20 Questions carries its prompt in `caption` and has no turn line of its own.
        if (board["game"] == "questions") {
            return board["caption"]?.trim()?.takeIf { it.isNotEmpty() } ?: "20 Questions"
        }

        // A fresh invite still says "Let's play …" — that is the caption, not a turn.
        board["caption"]?.let { if (it.startsWith("Let's")) return it }

        return when {
            isSpectator(board, myId) -> "Spectating Game"
            // `sender` is who moved last. If that was us, it is now the other player's turn.
            board["sender"] == myId -> "Opponent's Move."
            else -> "Your Move."
        }
    }

    /** The glyph name to draw over the board, or `null` while the game is still running. */
    fun glyph(board: Map<String, String>, myId: String): String? = when (outcome(board, myId)) {
        Outcome.Spectating -> GlyphSpectator
        Outcome.Draw -> GlyphDraw
        Outcome.Won -> GlyphWon
        Outcome.Lost -> GlyphLost
        null -> null
    }

    private enum class Outcome { Spectating, Draw, Won, Lost }

    /**
     * Read the `winner` field, or `null` if the game has not ended.
     *
     * The field is `"<claimant-uuid>|<flag>"`. The flag is the part that is easy to get wrong:
     * `"0"` means a draw, and `"-1"` means the claimant *lost* rather than won — so the sense of
     * the comparison inverts. A game that reported the winner directly would not need the flag at
     * all; it exists because some of their games can only name the player the result attaches to.
     */
    private fun outcome(board: Map<String, String>, myId: String): Outcome? {
        val winner = board["winner"] ?: return null

        // Spectator wins over everything except 20 Questions, which has no two-player shape.
        if (board["game"] != "questions" && isSpectator(board, myId)) return Outcome.Spectating

        val parts = winner.split("|")
        if (parts.size < 2) return null // Guarded here; not guarded in theirs.
        if (parts[1] == "0") return Outcome.Draw

        val claimantWon = parts[1] != "-1"
        val iAmClaimant = myId == parts[0]
        return if (iAmClaimant == claimantWon) Outcome.Won else Outcome.Lost
    }

    /**
     * True when the board names two players and we are neither.
     *
     * Both must be present: a board with only `player1` filled in is a game waiting for its second
     * player, not one we are watching from outside.
     */
    private fun isSpectator(board: Map<String, String>, myId: String): Boolean {
        val player1 = board["player1"] ?: return false
        val player2 = board["player2"] ?: return false
        return myId != player1 && myId != player2
    }
}
